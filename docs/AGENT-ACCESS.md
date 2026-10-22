# Agent-driven data access — the policy proxy and the MCP surface

**Status:** determination, not an implementation. Nothing in this document is
built yet. It records *what* has to be true before an AI agent may be allowed
to reach a datastore through MetaModel, and *why* the shape is a proxy with an
MCP server in front of it.

This document complements `SECURITY.md` (the threat model of the library) and
`docs/DEFECT-REGISTER.md` (what was found and fixed in the library itself).

## 1. The two concerns are different, and only one needs a proxy

### 1.1 The parser-configuration risk (library-level, deployment-fixed)

`sugarcrm` depends on `jaxws-rt` with `provided` scope, so the JAX-WS runtime
that parses inbound SOAP messages is supplied by the consuming application. The
hardening that stops an XXE (`supportDTD=false`,
`isSupportingExternalEntities=false`) is a property of **that runtime**, not of
the sugarcrm module. See `docs/DEFECT-REGISTER.md` §5.1, NW-18.

A proxy does **not** fix this by being a proxy — the risk lives inside the
process that parses the response. What a proxy does is **own the classpath**:
a deployed service pins `jaxws-rt` (and every other adapter dependency) in its
own artifact, so there is no "whatever the consumer happens to have" left.
That is a complete fix for the residual risk, and it needs nothing from the
MCP layer.

There is no clean library-level fix. The JAX-WS RI creates the `XMLInputFactory`
itself; the only way for a library to influence it would be to shadow the
`javax.xml.stream.XMLInputFactory` service on the classpath — a JVM-wide
change that would fight the consuming application for control of StAX. That
is the same objection the register raised against the system-property toggle,
and it stands.

### 1.2 The agent-facing risk (needs a policy point)

MetaModel is a library. It cannot know *which* of its caller's tables an agent
should see, whether the agent may write, or what a row limit should be. The
agent-facing risk is therefore not a library defect at all — it is a **missing
component**: a place where policy is expressed and enforced, between the agent
and the datastore.

That component is a proxy.

## 2. What `apache/metamodel-membrane` is, and what it is not

`apache/metamodel-membrane` (GitHub mirror of the ASF repository) is
**"Data Federation as a RESTful service"**, a separate project — it is *not* a
module of the main reactor any more; upstream `apache/metamodel` master has no
`membrane` directory.

Verified from the source (master, last commit **2019-06-04**, METAMODEL-1212):

| | |
|---|---|
| Version | `0.2-SNAPSHOT` (only `0.1` was ever released) |
| Built against | `org.apache.metamodel:MetaModel:5.2.0` — **not** the fixed 5.3.4-SNAPSHOT in this tree |
| Stack | Spring MVC + Undertow, WAR packaging, `swagger.yaml` as the API contract, `swagger-codegen` for the model classes |
| Domain model | `TenantRegistry` → `TenantContext` → `DataSourceRegistry` → `DataContext`; file-based and in-memory registries with caching wrappers; `FederatedDataContextFactory` federates across sources |
| Endpoints | `/{tenant}/{dataContext}/…` — datasources, schema, tables, columns, table data, **query**, root information |

**The abstractions are exactly right.** Tenant-scoped, registry-driven, an
OpenAPI contract, one surface over many datastores. That is the shape a policy
proxy needs, and it is why Membrane is worth studying.

**The implementation must not be deployed as it stands.** Five concrete reasons,
all read out of the source:

1. **No authentication and no authorization.** `dispatcher-servlet.xml` declares
   the Spring Security namespace but contains no security element, and
   `core/pom.xml` has no `spring-security` dependency. Every endpoint —
   including `TableDataController` — is anonymous.
2. **It exposes more than an agent should have.** `TableDataController` supports
   **update and delete** (METAMODEL-1154), and `QueryController` accepts a
   **raw query string**: `GET /{tenant}/{dataContext}/query?sql=…` handed
   straight to `DataContext.parseQuery(...)` and logged at INFO. A free-text
   SQL endpoint is precisely the surface an LLM must not be given.
3. **It would run against the vulnerable library.** Parent POM
   `MetaModel:5.2.0` predates every fix in `docs/DEFECT-REGISTER.md` —
   including the S1 identifier injection (JDBC-10) and the XXE hardening.
   Reviving Membrane unchanged would put the *unfixed* MetaModel behind the
   agent and undo the value of the audit.
4. **Its dependency set is 2019-era and carries advisories** —
   `logback-classic 1.1.7`, `hibernate-validator 5.2.4.Final`,
   `postgresql 42.1.4`, `jtds 1.3.1`.
5. **It has been dormant for seven years** and is no longer part of the main
   build.

**Conclusion: adopt the design, not the code.** Re-implement the membrane's
abstractions against the fixed MetaModel, with the policy layer and
authentication it never had.

## 3. The recommended architecture

```
┌──────────────────────────────────────────────────────────────┐
│ AI agent  (MCP host)                                        │
└───────────────────────────┬──────────────────────────────────┘
                            │ MCP: tools over stdio or Streamable HTTP
                            ▼
┌──────────────────────────────────────────────────────────────┐
│ MCP server — thin adapter                                   │
│   holds NO datastore credentials, enforces NO policy         │
│   tools: list_data_sources · list_tables · describe_table ·  │
│          select(table, columns, filters, limit)              │
└───────────────────────────┬──────────────────────────────────┘
                            │ authenticated HTTP (token / mTLS)
                            ▼
┌──────────────────────────────────────────────────────────────┐
│ Policy proxy ("the membrane")  ◀── ALL POLICY LIVES HERE    │
│   · authentication, tenant resolution                        │
│   · datasource allow-list                                    │
│   · table + column allow-list, column masking                │
│   · read-only enforcement                                    │
│   · row / offset / wall-clock limits                         │
│   · query shape validation                                  │
│   · audit log of every tool call                            │
│   · owns the classpath: pins MetaModel and the adapters      │
└───────────────────────────┬──────────────────────────────────┘
                            │ credentials never leave this process
                            ▼
       Apache MetaModel (fixed 5.3.4-SNAPSHOT) ──▶ datastores
```

### The rules that make this safe

1. **Policy lives in the proxy, never in the MCP server.** The MCP server is the
   component the agent can influence; if it also decided what was allowed, the
   agent would eventually reach the proxy with a different tenant or a
   different tool argument. The proxy is the trust boundary; the MCP server is
   an adapter inside it.
2. **No free-text query tool.** The tool surface is structured — table,
   columns, typed filters, limit — and the proxy translates it into a
   MetaModel `Query` object. `MetaModel`'s `parseQuery` gives no guarantee
   about what an LLM would write into it.
3. **The agent never holds datastore credentials.** The MCP server authenticates
   to the proxy; the proxy owns the connection strings.
4. **The proxy must use the fixed MetaModel.** Otherwise none of the fixes in
   `docs/DEFECT-REGISTER.md` apply to the traffic the agent causes.
5. **Read-only by default.** Write and delete are separate capabilities that
   are not granted by default, and should require a human approval step rather
   than an agent's judgement.
6. **The proxy is where the untrusted-response risk is contained.** It is the
   only component that parses datastore responses, and it pins the versions.

## 5. Repository topology — the trade-off, and what it turns on

The proxy is a **service**; MetaModel is a **library**. Putting them in one
repository is not a packaging detail — it couples two release cadences, and it
decides what the project is publicly seen to be.

### 5.1 The three things that can be tied, and are not the same thing

| | Tied to MetaModel? | Why it matters |
|---|---|---|
| **The code** | **Always.** The proxy calls `DataContext`, builds `Query` objects, reads `DataSet`s. There is no version of this that is not coupled. | Nothing to decide. |
| **The release** | **This is the actual decision.** | A library releases conservatively (semantic versioning, a vote, a backport policy for security fixes). A service and an MCP tool surface must iterate fast. |
| **The identity** | **Also a decision, and it should be made on purpose.** | Is this "an AI gateway" — or "a policy-enforced data-access service, of which the MCP server is one client"? |

Getting the identity wrong is the expensive mistake. If the module is called
*agent access*, then a BI tool, a partner integration, or a compliance gateway
cannot use it, and the project has publicly repositioned itself as an AI
product because of a directory layout.

### 5.2 Pros and cons

**Service in a separate repository**

| Pros | Cons |
|---|---|
| Release cadence is independent — the MCP tool surface and the policy rules move at service speed, not at library speed | Version drift: the service pins a **released** MetaModel, so a library fix is only available after a release |
| The identity can be the general one: "data access as a service"; the MCP server is one client alongside a REST API, a JDBC-facing proxy, a partner SDK | Needs its own PMC, its own CI, its own vulnerability triage — and a project with no volunteers loses it, which is exactly how `metamodel-membrane` died |
| The dependency direction is explicit and enforceable: the service depends on MetaModel, never the reverse | Two repositories to scan, patch and release |
| The service's operational profile (credentials, attack surface, container images, on-call) is isolated from the library's | The published artifact set of the library stays clean, but so does the review of the service's code |
| A released MetaModel is a **stronger** safety gate than "we built it from HEAD on a Tuesday": the service can only use a MetaModel that has been through a release | — |

**Service as a module in this repository**

| Pros | Cons |
|---|---|
| No version drift — the service always builds against HEAD, so a library fix is available immediately | The release cadence is coupled: every tool-surface change waits for a MetaModel release, or ships unreleased in the repository — which is what "half done" looks like |
| Shared CI, shared dependency management (the 23 upgrades), shared RAT and licence policy, shared security review | The service's dependency churn (Spring Boot, the MCP SDK, auth libraries) lands in the library's dependency set and release notes |
| One place to look: the threat model, the defect register and the policy documentation together | The Java 8 bytecode floor becomes awkward — a modern service wants Java 17/21. It is possible per module (`maven.compiler.release`), but the CI matrix then has to build that module only on the newer JDKs |
| Atomic changes when MetaModel's API changes | MetaModel would be publishing a server in its distribution, and its security posture would become the project's security posture |

### 5.3 What it actually turns on

Two things, and they can be answered independently of the repository layout:

1. **Governance.** Is there a community and a PMC able to own a service
   repository? `metamodel-membrane` was dormant from 2019-06-04 to its end
   because a subproject with no owner does not survive. If the answer is no,
   then a separate repository will also die, and the honest choice is to keep
   the service in this repository where at least the CI runs.
2. **Release the library first.** The service must run against a *fixed*
   MetaModel. Membrane's failure was precisely that it ran against 5.2.0,
   before every fix in `docs/DEFECT-REGISTER.md` — including the S1
   identifier injection. In a separate repository the protection is "pin a
   released version"; in this repository it is "build against HEAD". The
   release-gated rule is the stronger of the two, and it is only available
   once 5.3.4 is released.

### 5.4 The recommendation

**Separate repository for the service, with the policy engine implemented
exactly once, inside it.** The reasoning:

- The release-cadence mismatch is decisive. A policy proxy and an MCP server
  change at service speed; a library changes at library speed. Coupling them
  makes the *fast* thing slow, which is how the fast thing stops being done.
- The identity should be the general one. The machinery — tenant scoping,
  table and column allow-lists, column masking, read-only enforcement, row
  limits, audit — is identical for an agent, a BI tool and a partner
  integration. The MCP server is then *one client* of the service, which is
  what makes the service worth having at all.
- The dependency direction is the safety property: the service depends on
  MetaModel; MetaModel never depends on the service. That is enforceable in
  either topology, but it is only *visible* in separate repositories.

**But keep the service in this repository if there is no one to own it
there.** The governance test is the real one. A separate repository with no
maintainer is worse than a module with CI: it fails silently, which is the
failure mode this project is trying to avoid. In that case, add it as a
**module that is not part of the published library distribution** — it gets
the shared CI and the shared dependency management, and it does not pollute
the artifacts or the Java 8 floor of the library.

Either way, the *design* is identical: §3's architecture and §3's six rules do
not depend on where the code lives.

### 5.5 The sequencing, which is the same in both topologies

1. **Release the fixed MetaModel (5.3.4).** The service must not run against
   an unfixed library, and "unfixed" includes the S1 identifier injection.
   This tree is ready for it: green on JDK 21 and 25, 1049 tests.
2. **Then** the service repository, or the service module.
3. **Then** phase 1 of §7 — the registry and the policy model, read-only,
   over one datasource type. The policy model is the part that must not be
   built twice, so it is written once and reused by every later phase.

## 6. Where this sits — the module layout of the service

The proxy is a **new component**, not a change to the library. Whether it
lives in this repository or in its own (§5), the internal layout is the same:

| Module | Responsibility |
|---|---|
| `policy` | tenant + datasource registry, policy evaluation, the `Query` construction from a structured request, audit log — **the part that must not be built twice** |
| `rest` | the REST surface (the membrane's contract), authentication, OpenAPI spec |
| `mcp` | the MCP server: tool schemas, argument validation, result shaping — **no policy, no credentials** |

Two notes on the build:

- The `policy` module needs MetaModel's types (`Query`, `Column`, `Table`,
  `DataContext`), so it is Java and it depends on `core`. The `mcp` module
  does **not** need them: it talks to the `rest` module over HTTP. That is
  what keeps the tool surface free to change without touching the policy.
- The Java MCP SDK is first-class (`io.modelcontextprotocol.sdk:mcp`, with
  `mcp-core`, `mcp-json-jackson2`/`3` and the `mcp-bom`; the Spring MVC and
  WebFlux transports live in Spring AI 2.0+ under `org.springframework.ai`).
  There is no need to write the service in another language to get a good
  MCP implementation.

The data-context layer needs `DataContextFactory`, which lives in `full`.
A proxy that only needs JDBC should not pull in `sugarcrm` — depend on
`core` plus the adapters it actually serves.

## 7. Sizing — what is actually required

| Phase | Content | Note |
|---|---|---|
| 0 | Decide the deployment: is the proxy a service in this repository, or a separate repository? | The only decision that changes everything downstream |
| 1 | `proxy/core` with the registry and the policy model, **read-only**, over one datasource type (JDBC) | Proves the model; the policy engine is the part that must not be redone |
| 2 | `proxy/rest` with authentication (mTLS or bearer token) and the OpenAPI contract | The contract is what the MCP server is generated from |
| 3 | `proxy/mcp` — the MCP tools, generated from the OpenAPI spec | Keep the tool surface and the REST contract in sync by generation, not by hand |
| 4 | Write capability, behind a separate grant | Only after the read path has proven itself in production |

### What is deliberately *not* proposed

- **Reviving `metamodel-membrane` as a repository.** If the ASF wants that
  project back, the correct revival is against the fixed MetaModel with an
  authentication and policy layer added — which is phase 1 and 2 above, in a
  different repository and a different implementation. Committing to the 2019
  code is not an option.
- **A JVM-wide StAX override in the library.** See §1.1.
- **An MCP server that talks to datastores directly.** It would have to hold
  the credentials and would have no policy point, which is the entire thing
  the proxy exists to provide.
