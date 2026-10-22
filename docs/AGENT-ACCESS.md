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

## 4. Where this sits in this repository

The proxy is a **new component**, not a change to the library. Proposed layout:

| Module | Responsibility |
|---|---|
| `proxy/core` | tenant + datasource registry, policy evaluation, the `Query` construction from a structured request, audit log |
| `proxy/rest` | the REST surface (the membrane's contract), authentication, OpenAPI spec |
| `proxy/mcp` | the MCP server: tool schemas, argument validation, result shaping — **no policy, no credentials** |

`full` already contains the `DataContextFactory` that the proxy's data-context
layer needs, so the proxy would depend on `full` (or on a narrower subset of
the adapters — a proxy that only needs JDBC should not pull in `sugarcrm`).

## 5. Sizing — what is actually required

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
