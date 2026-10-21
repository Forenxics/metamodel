# AGENTS.md

Apache MetaModel — a Java data-access framework that exposes one unified query API over many
datastores (JDBC, CSV, Excel, JSON, XML, ARFF, fixed-width, POJO, OpenOffice, MongoDB,
CouchDB, Elasticsearch, Cassandra, HBase, DynamoDB, Kafka, Hadoop, Neo4j, Salesforce,
SugarCRM). 30 Maven modules.

## Setup commands

- **JDK**: Temurin 21 is the verified toolchain (`C:\Users\patri\AppData\Local\Programs\Temurin\jdk-21.0.12+8`).
  The source level is still **Java 8** — do not raise it without a deliberate decision.
- **Maven**: 3.9.9, vendored at `.tools/apache-maven-3.9.9`. Use the wrapper at the workspace
  root: `E:\MINIMAX\metamodel-master\mvnw.cmd` (it pins `JAVA_HOME` and `PATH`).
- Install deps: `& 'E:\MINIMAX\metamodel-master\mvnw.cmd' -B clean install`

## Build & test commands

```powershell
cd 'E:\MINIMAX\metamodel-master\metamodel-master'

# full reactor
& 'E:\MINIMAX\metamodel-master\mvnw.cmd' -B clean install

# one module (fast loop — deps are already installed in ~/.m2)
& 'E:\MINIMAX\metamodel-master\mvnw.cmd' -B -pl <module> test

# one module plus its reactor dependencies
& 'E:\MINIMAX\metamodel-master\mvnw.cmd' -B -pl <module> -am test

# one test class
& 'E:\MINIMAX\metamodel-master\mvnw.cmd' -B -pl <module> test '-Dtest=SomeTest'
```

> **PowerShell**: always quote `-D...` arguments (`'-Dmaven.test.failure.ignore=true'`).
> Unquoted, PowerShell mangles them into a bogus lifecycle phase.

## Project layout

- `core/` — the engine: query model, schema model, update builders, utilities. Everything depends on it.
  - `core/.../query/` — query clauses, filters, aggregates, SQL rendering
  - `core/.../schema/` — table/column/relationship model (mutable + immutable variants)
  - `core/.../data/` — rows and data sets
  - `core/.../util/` — comparators, refs, resources, date helpers
  - `core/.../factory/` — `DataContext` discovery and instantiation
- `<adapter>/` — one directory per datastore: `jdbc`, `csv`, `excel`, `json`, `xml`, `arff`,
  `fixedwidth`, `pojo`, `openoffice`, `couchdb`, `mongodb` (+`mongo2`/`mongo3`), `elasticsearch`
  (+`common`/`native`/`rest`), `hbase`, `cassandra`, `dynamodb`, `kafka`, `hadoop`, `neo4j`,
  `salesforce`, `sugarcrm`, `spring`, `full` (aggregate artifact).
- `docs/` — long-form documentation, including the audit brief and defect register.

## Architecture in one paragraph

A `DataContext` exposes tables and executes queries. `Query` is a six-clause model (select,
from, where, group by, having, order by) built through `QueryPostprocessDataContext` and
`QueryPostprocessDelegate`. Filters are `FilterItem` trees evaluated either in memory by
`FilterItem.evaluate(Row)` or translated to a datastore-native query by an
`AbstractQueryRewriter` (one per dialect). `UpdateableDataContext` handles writes through
`InsertBuilder` / `UpdateBuilder` / `DeleteBuilder` / `CreateTableBuilder` / `DropTableBuilder`.
Adapters implement `DataContext`, `DataSet` and the builders; the `intercept/` package lets
callers post-process or filter results.

**Where to change what:** query semantics → `core/query`; SQL generation for one database →
`jdbc/dialects/`; a new datastore → copy the shape of an existing adapter module; schema model
changes ripple into every adapter, so treat them as public API.

## Code style

- 4-space indent, no tabs in Java files (POM files use tabs).
- Apache license header on **every** new `.java` file — the build runs `apache-rat` and fails without it.
  Copy it verbatim from a neighbouring file in the same module. `.md` files are exempt.
- Line width ~120 is the norm but not enforced; do not reformat untouched lines.
- Public API is a compatibility promise — this is a widely used Apache library. Never change
  a public signature or observable behaviour without saying so explicitly in your report.

## Testing instructions

- Unit tests: `& 'E:\MINIMAX\metamodel-master\mvnw.cmd' -B -pl <module> test` (JUnit 4, some
  legacy JUnit 3 `TestCase` classes still exist).
- Integration tests live in `src/test/java/**/integrationtests/` and need live databases or
  Docker. They are skipped by default; enable them with the `integration-test` profile and a
  `metamodel-integrationtest-configuration.properties` in your home directory.
- **Every fix needs a regression test that fails before the fix and passes after it.** Prove it:
  revert the production change, confirm the test fails for the stated reason, then re-apply.
  A green suite and a *proving* suite are different claims.
- Never weaken, disable or delete an existing test to make the build pass.

## Security

- This library parses untrusted files and builds SQL. Treat parsing (XML, CSV, XLSX, JSON) and
  SQL construction as untrusted-input boundaries.
- Never log credentials, tokens, connection URIs or authorization headers.
- XML parsing must disable DOCTYPE and external entity resolution (CWE-611).
- Identifiers (schema/table/column names) rendered into SQL must be quoted **and** have
  embedded quote characters escaped by doubling them.
- `ObjectInputStream` subclasses must not deserialize untrusted input without a class allow-list.
- Never commit secrets.

## Known landmines

- **JDK-internal reflection.** `core/.../util/LegacyDeserializationObjectInputStream.java` used
  to reflect into `java.io.ObjectStreamClass` private members. Since Java 9 (JEP 403) that
  throws `InaccessibleObjectException`, and the class silently degraded. Use the public
  `ObjectStreamClass` API only — `getSerialVersionUID()` and `getFields()` are enough to
  recognise an enum descriptor (`suid == 0 && fields.length == 0`).
- **Java 8 source level.** No `List.of`/`Map.of`, no `var`, no `Files.readString`, no
  `Optional.isEmpty`, no text blocks, no `Stream.toList()`.
- **Do not run `mvn install` concurrently** from more than one process — it races on `~/.m2`.
- **Maven Central cannot serve every legacy coordinate.** Some 2018-era JAX-WS artifacts only
  ever existed on now-dead plain-HTTP repositories; they are pinned in the POMs.
- **`sugarcrm`, `neo4j`, `full` and `spring`** are at the end of the reactor and depend on the
  JAX-WS code generation plugin; a failure there blocks the tail of the build.

## PR & commit conventions

- Branch from `master`; never push to it directly.
- Commit messages: `fix:` / `feat:` / `docs:` / `refactor:` / `chore:` / `test:`.
- Keep the build green: `mvn clean install` must pass end to end before opening a PR.
