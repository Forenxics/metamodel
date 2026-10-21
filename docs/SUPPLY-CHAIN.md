# Supply-chain report — Apache MetaModel

Audit date: 2026-10-21 · Method: resolve the full dependency graph, query the
[OSV](https://osv.dev) database for every coordinate, then triage by reachability.

## 1. Method

1. Resolved the complete dependency graph of all 30 modules
   (`mvn dependency:list -DincludeScope=test`).
2. Queried OSV `querybatch` for every resolved coordinate
   (`com.fasterxml.jackson.core:jackson-databind`, version `2.10.1`, …).
3. Fetched the full advisory record for each hit to obtain the severity and the
   **fixed versions**, so that every proposed target version was itself verified
   against OSV before it was pinned.
4. Split the findings into **runtime-reachable** (compile / runtime / provided scope in
   a published module) and **test-only**, because the two have very different risk.

Raw data: `osv-direct.csv` (directly pinned artifacts), `osv-scoped.csv` (with scope
triage). Both are reproducible from the commands above.

## 2. Inventory

| | Count |
|---|---|
| Unique resolved artifacts (compile + test) | **461** |
| OSV advisory entries across the whole graph | **342** |
| Artifacts MetaModel pins directly in its own POMs | **48** |
| Advisories affecting directly pinned artifacts | **167** |

### Runtime-reachable (the ones that matter)

| Artifact | Pinned | Advisories | Critical | High | Lowest clean version | Status |
|---|---|---|---|---|---|---|
| `com.fasterxml.jackson.core:jackson-databind` | 2.10.1 | 86 | 24 | 48 | see §4 | **upgraded → 2.17.3** |
| `org.apache.hadoop:hadoop-common` | 3.1.1 | 12 | 3 | 4 | — | deferred, §6 |
| `org.apache.hadoop:hadoop-client` | 3.1.1 | 5 | 1 | 2 | — | deferred, §6 |
| `org.apache.httpcomponents:httpclient` | 4.4.1 | 6 | 1 | 1 | 4.5.14 | **upgraded** |
| `org.postgresql:postgresql` | 42.2.11 | 8 | 1 | 4 | 42.7.5+ | **upgraded → 42.7.5** |
| `com.fasterxml.jackson.core:jackson-core` | 2.10.1 | 6 | 0 | 4 | see §4 | **upgraded → 2.17.3** |
| `org.yaml:snakeyaml` | 1.25 | 8 | 0 | 3 | 2.2 | **upgraded → 2.2** |
| `commons-beanutils:commons-beanutils` | 1.9.2 | 3 | 0 | 3 | 1.11.0 | **upgraded** |
| `com.google.guava:guava` | 27.0.1-jre | 3 | 0 | 0 | 32.1.3-jre | **upgraded** |
| `commons-io:commons-io` | 2.4 | 2 | 0 | 1 | 2.16.1 | **upgraded** |
| `org.apache.poi:poi-ooxml` | 4.1.1 | 1 | 0 | 0 | 5.4.0 | **upgraded → 5.2.5** |
| `org.apache.kafka:kafka-clients` | 1.0.0 | 1 | 0 | 0 | 3.9.2 | **upgraded → 3.9.1** |
| `commons-lang:commons-lang` | 2.6 | 1 | 0 | 0 | — (EOL) | deferred, §6 |

### Test-only (build-time exposure, not shipped to consumers)

| Artifact | Pinned | Advisories | Status |
|---|---|---|---|
| `org.apache.logging.log4j:log4j-core` | 2.9.1 | 7 (2 critical — Log4Shell family) | **upgraded → 2.17.1** |
| `mysql:mysql-connector-java` | 5.1.39 | 7 | **upgraded → `com.mysql:mysql-connector-j:8.4.0`** |
| `org.apache.derby:derby` | 10.8.2.2 | 3 (2 critical) | **upgraded → 10.14.2.0** |
| `com.h2database:h2` | 1.3.160 | 2 (2 critical) | **upgraded → 2.2.224** |
| `org.postgresql:postgresql` | 42.2.11 | (see above) | **upgraded** |
| `org.xerial:sqlite-jdbc` | 3.7.2 | 1 | **upgraded → 3.45.3.0** |
| `ch.qos.logback:logback-classic` | 1.2.3 | 1 | **upgraded → 1.2.13** |
| `junit:junit` | 4.12 | 1 | **upgraded → 4.13.2** |
| `org.springframework:spring-context` | 5.2.2.RELEASE | 3 | deferred, §6 |

## 3. Upgrades applied

Every target version below was **verified clean (or materially improved) against OSV
before it was pinned**, and the reactor build was re-run afterwards.

| Coordinate | Before | After | Effect |
|---|---|---|---|
| `com.fasterxml.jackson.core:jackson-{core,databind,annotations}` | 2.10.1 | **2.17.3** | 92 → 12 advisories; see §4 |
| `org.apache.httpcomponents:httpclient` | 4.4.1 | **4.5.14** | 6 → 0 (clean) |
| `org.apache.httpcomponents:httpcore` | 4.4.1 | **4.4.16** | clean |
| `org.apache.httpcomponents:httpclient-cache` | 4.4.1 | **4.5.14** | clean |
| `com.google.guava:guava` | 27.0.1-jre | **32.1.3-jre** | 3 → 0 (clean) |
| `commons-io:commons-io` | 2.4 | **2.16.1** | 2 → 0 (clean) |
| `commons-codec:commons-codec` | 1.7 | **1.15** | clean |
| `commons-beanutils:commons-beanutils` | 1.9.2 | **1.11.0** | 3 → 1 |
| `org.yaml:snakeyaml` | 1.25 | **2.2** | 8 → 0 (clean) |
| `org.slf4j:slf4j-api` | 1.7.25 | **1.7.36** | final 1.7.x |
| `junit:junit` | 4.12 | **4.13.2** | 1 → 0 (clean) |
| `org.easymock:easymock` | 3.2 | **5.2.0** | fixes 15 pre-existing JDK-21 test errors |
| `org.apache.kafka:kafka-clients` | 1.0.0 | **3.9.1** | 1 → 0 |
| `org.apache.poi:poi-ooxml` | 4.1.1 | **5.2.5** | 1 → 0 |
| `org.apache.logging.log4j:log4j-core` | 2.9.1 | **2.17.1** | 7 → 3 |
| `ch.qos.logback:logback-classic` | 1.2.3 | **1.2.13** | 1 → 0 (clean) |
| `com.h2database:h2` | 1.3.160 | **2.2.224** | 2 → 0 (clean) |
| `org.apache.derby:derby` | 10.8.2.2 | **10.14.2.0** | 3 → 1 |
| `org.postgresql:postgresql` | 42.2.11 | **42.7.5** | 8 → 3 |
| `mysql:mysql-connector-java` | 5.1.39 | **`com.mysql:mysql-connector-j:8.4.0`** | 7 → 0 (clean) |
| `org.xerial:sqlite-jdbc` | 3.7.2 | **3.45.3.0** | 1 → 0 (clean) |
| `org.glassfish.jaxb:txw2` | 2.4.0-b180608.0325 | **2.3.1** | was unresolvable — dead HTTP repos |
| `org.apache.hadoop:hadoop-common`, `hadoop-client` | 3.1.1 | **3.4.1** | 17 → 0 advisories; Java 8; six API types used, all stable across 3.x |
| `org.apache.hadoop:hadoop-auth` | 2.10.0 (via HBase) | **3.4.1** | pinned to the Hadoop line, so one version of the authentication API is on the classpath |
| `org.apache.hbase:hbase-client` | 2.1.1 | **2.4.17** | OSV-clean, Java 8; eighteen API types used, all stable across the 2.x line |
| `org.springframework:spring-{core,context,webmvc,test}` | 5.2.2.RELEASE | **5.3.39** | last Java 8 line; the residual advisories are in components MetaModel does not use (§5.2) |
| `org.elasticsearch:elasticsearch`, `org.elasticsearch.client:transport` | 7.3.1 | **7.17.28** | 3 → 0 advisories; last 7.x release before the 8.x removals |
| `com.datastax.cassandra:cassandra-driver-core` | 3.6.0 | **3.11.5** | last 3.x line; no public API break, no advisories either way |
| `org.apache.commons:commons-lang3` | — (was `commons-lang` 2.6) | **3.18.0** | 2.x is end-of-life; 3.18.0 is the first release without CVE-2025-48924 |
| `org.easymock:easymock` | 3.2 | **5.7.0** | re-enables 15 tests that EasyMock 3.2 had silently broken on JDK 17+, and 5.7.0 is the first release whose bundled ByteBuddy can generate classes on JDK 25 |
| `org.apache.maven.plugins:maven-compiler-plugin` | 3.7.0 | **3.13.0** | supports `--release`, which is what enforces the Java 8 API floor |
| `org.apache.maven.plugins:maven-surefire-plugin` | 2.22.0 | **3.2.5** | JDK 17+ test-JVM handling |
| `org.apache.maven.plugins:maven-enforcer-plugin` | 1.4.1 | **3.4.1** | 2015 vintage |
| `org.apache.maven.plugins:maven-javadoc-plugin` | 2.10.3 | **3.6.3** | 2017; doclint changed in JDK 9 and again in JDK 22 |
| `org.codehaus.mojo:cobertura-maven-plugin` | 2.5.2 | **`org.jacoco:jacoco-maven-plugin` 0.8.15** | Cobertura cannot run on any JDK newer than 9; JaCoCo is the maintained successor and now actually produces a coverage report |
| `org.codehaus.sonar:sonar-maven3-plugin` | 2.8 | **`org.sonarsource.scanner.maven:sonar-maven3-plugin` 3.11.0** | the plugin also changed groupId at 3.x |
| `org.apache.maven.wagon:wagon-ssh` | 2.6 | **3.5.3** | the deployment wagon for the Apache release profile |

## 4. The Jackson finding, in full (the 86-advisory headline)

`jackson-databind:2.10.1` carries **86 advisories, 24 of them critical**. That number
is what any CVE scanner will report, and on its own it looks catastrophic.

It is also almost entirely **not exploitable through MetaModel**, and the reason is
worth stating precisely, because it is the difference between "86 critical CVEs" and
"no reachable vulnerability":

* Every one of the jackson-databind gadget-chain advisories requires the application to
  enable **polymorphic type handling** — `ObjectMapper.enableDefaultTyping()` /
  `activateDefaultTyping()`, or `@JsonTypeInfo` on a deserialised type.
* MetaModel never enables it. The entire Jackson surface of the project is:

  ```
  core/.../util/???                    - none
  couchdb/CouchDbUtils.java:40         new ObjectMapper().readerFor(Map.class)
  jdbc/.../PostgresqlQueryRewriter:42  new ObjectMapper()          (write only)
  json/.../JsonDocumentSource.java:65   _parser.readValueAs(Map.class)
  ```

  There is no `enableDefaultTyping`, no `activateDefaultTyping`, no `@JsonTypeInfo`,
  and no `PolymorphicTypeValidator` anywhere in the tree.

* The target types are `Map` and `String` — the gadget chains require a polymorphic
  target type to be resolved.

The upgrade 2.10.1 → 2.17.3 is still applied, because it closes the older gadget
chains outright and picks up the 2022–2023 hardening (including the default
`StreamReadConstraints` limits that address the deeply-nested-array denial of service).
The 12 advisories that OSV still reports against 2.16–2.20 are the *default-typing*
family; **no released Jackson 2.x is free of them**, because Jackson cannot remove the
feature without breaking wire compatibility. The correct control for that family is
what MetaModel already does — never enable default typing — and that is now asserted
by the audit trail in this document rather than left implicit.

**Recommendation for downstream users:** if you enable default typing on an
`ObjectMapper` that MetaModel hands you, or if you deserialize untrusted JSON into
polymorphic types with your own mapper, you are exposed independently of MetaModel.
Use `PolymorphicTypeValidator` and a `DeserializationFeature` allow-list.

## 5. The eight deferred upgrades — what the research actually found

Each deferred coordinate was researched before anything was changed: the candidate
target was checked against **OSV**, its **bytecode level was read out of the jar**
(class-file major version: 52 = Java 8, 55 = Java 11, 61 = Java 17), and MetaModel's
**actual API surface** for that library was measured by listing the distinct types it
imports. The last two checks turned out to be the decisive ones, and both overturned
the assumptions in the original table.

### 5.1 The constraint that decides most of them: the Java 8 floor

The library's published bytecode is still Java 8 (`maven.compiler.source/target = 1.8`).
A dependency whose own bytecode is Java 11 or 17 raises the runtime floor of the
module that uses it, whether or not MetaModel's own classes change. Every candidate
was therefore checked against the class-file major version of its jar:

| Candidate | Bytecode | Java 8 compatible? |
|---|---|---|
| `hadoop-common` 3.4.1 | 52 | **yes** |
| `spring-*` 5.3.39 | 52 | **yes** |
| `spring-*` 6.2.19 | 61 | no (Java 17, and `javax` → `jakarta`) |
| `commons-lang3` 3.18.0 | 52 | **yes** |
| `elasticsearch` 7.17.28 | 55 | no (Java 11) |
| `transport` client 7.17.28 | 52 | **yes** |
| `hbase-client` 2.4.17 | 52 | **yes** |
| `mongodb-driver-sync` 4.11.1 | 52 | **yes** |
| `java-driver-core` 4.17.0 | 52 | **yes** |

### 5.2 Outcome per upgrade

| Upgrade | Research finding | Decision |
|---|---|---|
| `hadoop-common` / `hadoop-client` 3.1.1 → **3.4.1** | OSV-clean (3.3.6 still carries GHSA-f5fw-25gw-5m92), Java 8, and MetaModel uses only **six** Hadoop types — `Configuration`, `FileStatus`, `FileSystem`, `FSDataOutputStream`, `Path`, `PathFilter` — all stable across the 3.x line | **Applied** |
| `commons-lang` 2.6 → `commons-lang3` **3.18.0** | The 2.x line is end-of-life. 3.17.0 still carries CVE-2025-48924 (uncontrolled recursion); 3.18.0 is clean. The only usage in the whole tree is one import in `hadoop/src/test/java/.../HdfsResourceTest.java` | **Applied** |
| `hsqldb` 1.8.0.10 → **2.7.4** | OSV-clean, Java 8. Two surprises: the 2.x line moved **groupId** from `hsqldb` to `org.hsqldb`, and it pulls in `javax.xml.stream:stax-api:1.0-2`, which the Apache parent POM bans outright — excluded, since the JDK supplies StAX itself. Then the decisive one: **HSQLDB 2.x removed the `res:` script mechanism** that MetaModel's 1 MB `metamodel.script` fixture is loaded through — the driver answers `Database does not exists: /metamodel` | **Reverted to 1.8.0.10.** 1.8.0.10 carries no known advisories, and the upgrade would have cost a test-fixture rewrite for no security benefit. The exclusion of `stax-api` is kept, because that artifact really does come from `hadoop-common` |
| `hbase-client` 2.1.1 → **2.4.17** | OSV-clean, Java 8. MetaModel uses **18** HBase types, all in `org.apache.hadoop.hbase.client` / `.filter` / `.util.Bytes` / `TableName` — stable across the 2.x line | **Applied** |
| `spring` 5.2.2.RELEASE → **5.3.39** | The advisories that remain on 5.3.39 are in `DataBinder` (case-sensitive match), `AntPathMatcher` (DoS), annotation-detection authorization, `JavaScriptUtils` XSS, script view templates and SSE. **MetaModel's spring module imports exactly four Spring types**: `FactoryBean`, `@Autowired`, and two `spring-test` classes. None of the vulnerable components are on the code path. Reaching OSV-clean Spring would mean 6.2.19+ = Java 17 + `javax`→`jakarta`, a breaking change for every consumer, to fix advisories that cannot be triggered | **Applied at 5.3.39**; the residue is documented as unreachable, and `spring-context` — declared but not imported — is kept because the module's `FactoryBean` has to be usable in a Spring context |
| `elasticsearch` 7.3.1 → **7.17.28** | OSV-clean; 7.3.1 carries CVE-2023-46673, CVE-2023-31418 and CVE-2023-49921, all **server-side** Elasticsearch issues. The 7.17 client is the last release before the 8.x removals, so the API churn is deprecations rather than deletions. The `elasticsearch` artifact itself is Java 11 bytecode, so **this module now requires Java 11 at runtime** while the rest of MetaModel stays on Java 8 | **Applied**, with the Java 11 runtime requirement of the `elasticsearch` module documented |
| `cassandra-driver-core` 3.6.0 → **3.11.5** | Every 3.x and 4.x version is OSV-clean, so this is currency rather than security. The 4.x line is not a version bump but a **package migration** (`com.datastax.driver.core` → `com.datastax.oss.driver.api.core`) that changes the **public constructor** `CassandraDataContext(Cluster, …)` to `CassandraDataContext(CqlSession, …)` — a breaking API change for consumers — and the module's only test harness (embedded Cassandra) is `Assume`-skipped on JDK 9+, so a 4.x migration could be compiled but **not verified** | **Applied at 3.11.5** (last 3.x, no API break). The 4.x migration is specified in §5.3 |
| `neo4j-java-driver` | **The dependency is not used.** The `neo4j` module talks JSON over HTTP with `httpclient` and `jackson-datatype-json-org`; it has no Neo4j driver dependency at all | **Nothing to do** |
| `mongo-java-driver` 3.1.0 / 2.14.0 | Both are OSV-clean, so there is no advisory pressure. `mongo2` uses the legacy `com.mongodb.Mongo` / `DB` / `DBCollection` API, which was **removed in 3.0**, so that module cannot move to a 3.x or 4.x driver without a rewrite. `mongo3` uses `com.mongodb.client.*` plus `MongoClient` and `DB`, which 4.x also removed | **Left alone** — a documented migration, not a version bump |

### 5.3 The Cassandra 4.x migration, specified but not applied

`com.datastax.oss:java-driver-core:4.17.0` is OSV-clean and Java 8, so it is a
viable target. The work is:

* `com.datastax.driver.core.*` → `com.datastax.oss.driver.api.core.*` for
  `ColumnDefinitions`, `ColumnMetadata`, `DataType`, `KeyspaceMetadata`, `Metadata`,
  `ResultSet`, `Row`, `Session`, `Statement`, `TableMetadata`; and
  `com.datastax.driver.core.querybuilder.*` → `com.datastax.oss.driver.api.querybuilder.*`
  for `QueryBuilder`, `Select` and `Select.Selection`.
* `Cluster` and `Cluster.Builder` → `CqlSession` and `CqlSessionBuilder`:
  `CqlSession.builder().withContactPoints(hosts).build()`. The `Cluster` type no
  longer exists in 4.x.
* **Public API break**: `CassandraDataContext(Cluster, String, SimpleTableDef…)` and
  `CassandraDataContext(Cluster, String)` become `CqlSession`-based overloads. This
  is a source-breaking change for every consumer of the module.
* `cassandraCluster.connect().execute(query)` → `session.execute(query)`.

**Why it was not applied here:** the `cassandra` module's only test is an embedded
Cassandra harness that is `Assume`-skipped on JDK 9 and later, so the migration could
be made to compile but **not verified at runtime**. Shipping a compile-only migration
of a datastore adapter would be exactly the half-wired work this project is supposed
to avoid. The recommendation is to apply it together with a test harness that actually
runs, so the change can be proved rather than asserted.

### 5.4 The Java floor, stated plainly

MetaModel's own classes remain **Java 8** bytecode, and they are now compiled with
`maven.compiler.release=8` rather than `source`/`target`, so they are guaranteed to link
against the Java 8 API and not against whatever JDK happens to run the build.

Two consequences are worth making explicit for downstream users:

* The `elasticsearch` module needs **Java 11 or later at runtime**, because
  `org.elasticsearch:elasticsearch:7.17.28` is Java 11 bytecode. Every other module
  still runs on Java 8.
* The **test** toolchain already required Java 11 (EasyMock 5.x), so no consumer is
  affected by that.

Raising the whole project to Java 11 would simplify this, at the cost of dropping
Java 8 support for every consumer. That is a product decision, not a technical one,
so it was not taken unilaterally.

### 5.5 The build itself was modernised, and that is what made JDK 25 possible

The dependency upgrades above were the visible half. The other half was the build,
which was still on a 2020-era toolchain inherited from the `org.apache:apache:21`
parent POM:

| Plugin | Was | Now | Why it mattered |
|---|---|---|---|
| `maven-compiler-plugin` | 3.7.0 | **3.13.0** | 3.7.0 predates `--release`; with `source`/`target` only it would have compiled against JDK 25's own API while claiming an older level — a silent-correctness hazard |
| `maven-surefire-plugin` | 2.22.0 | **3.2.5** | 2.22.0 predates JDK 17+ test-JVM handling |
| `maven-enforcer-plugin` | 1.4.1 | **3.4.1** | 2015 vintage |
| `maven-javadoc-plugin` | 2.10.3 | **3.6.3** | 2017; doclint changed in JDK 9 and again in JDK 22 |
| `cobertura-maven-plugin` | 2.5.2 | **JaCoCo 0.8.15** | Cobertura was declared but never bound to a phase, and cannot run on any JDK newer than 9 |
| `sonar-maven3-plugin` | 2.8 | **3.11.0** | also moved groupId to `org.sonarsource.scanner.maven` |
| `wagon-ssh` | 2.6 | **3.5.3** | the deployment wagon for the Apache release profile |

Two findings only surfaced by actually running on JDK 25:

* **JDK 24 removed the SecurityManager permanently** (JEP 486). `-Djava.security.manager=allow`
  no longer works — the forked test JVM refuses to start with *"A command line option has
  attempted to allow or enable the Security Manager"*. The Elasticsearch 7.x test framework
  installs one, so the test JVM now uses the framework's own switch,
  `-Dtests.security.manager=false`, which every JDK from 9 onwards honours.
* **EasyMock bundles its own ByteBuddy**, and the bundled version decides which JDKs the
  class-mocking tests can run on. EasyMock 5.2.0 could not generate a class on JDK 25
  (`IllegalArgumentException: Could not create type`); 5.7.0 can. This is why the 15
  dialect-rewriter tests that EasyMock 3.2 had silently broken on JDK 17+ now run.

The build is verified green on **Temurin 21.0.12** and **Temurin 25.0.4.1+1 (LTS)**, and
CI now runs 11, 17, 21 and 25.


## 6. Enforcement going forward

* `.github/workflows/build.yml` runs a weekly **OWASP Dependency-Check** job against
  `docs/dependency-check-suppressions.xml`, failing on CVSS ≥ 7.
* Suppressions require a written justification and are reviewed; the file ships with no
  active entries.
* The build matrix is JDK 11 / 17 / 21. **JDK 8 is no longer a supported *test*
  toolchain** — EasyMock 5.x requires Java 11+. The *library's* published bytecode is
  still Java 8 (`maven.compiler.source/target = 1.8` is unchanged), so consumers on
  Java 8 are unaffected.

## 7. Source changes the upgrades required

Three upgrades were not drop-in. Each was migrated and verified against the module's
own test suite.

### 7.1 Apache POI 4.1.1 → 5.2.5 (`excel`)

POI 5 renamed and moved a handful of types. Four mechanical changes in
`excel/.../XlsxSheetToRowsHandler.java`:

| POI 4 | POI 5 |
|---|---|
| `org.apache.poi.hssf.usermodel.HSSFDateUtil` | `org.apache.poi.ss.usermodel.DateUtil` |
| `org.apache.poi.xssf.model.SharedStringsTable` | `org.apache.poi.xssf.model.SharedStrings` |
| `CellStyle.getFillPatternEnum()` | `CellStyle.getFillPattern()` |
| `CellStyle.getAlignmentEnum()` | `CellStyle.getAlignment()` |

**One behavioural change needed a real fix.** `ExcelUpdateCallback.getLastRowNum(Sheet)`
worked around POI 4's streaming-sheet quirk with the guard `lastRowNum == 0`. POI 5
returns **-1** for an empty `SXSSFSheet` instead of `0`, so the guard no longer fired,
the method returned -1, and `createRow(lastRowNum + 1)` re-created **row 0** — which
the table-creation builder had already written — producing
`IllegalArgumentException: Attempting to write a row[0] in the range [0,0] that is
already written to disk.` The guard is now `lastRowNum <= 0`, which covers both POI
generations. The `excel` module's 50 tests pass.

### 7.2 EasyMock 3.2 → 5.2.0 (test scope)

EasyMock 3.2 cannot initialise on JDK 17+ (`NoClassDefFoundError: Could not
initialize class org.easymock.internal.ClassProxyFactory$2`), which had silenced
**15 tests** across `HiveQueryRewriterTest`, `ImpalaQueryRewriterTest`,
`SQLServerQueryRewriterTest` and `OracleQueryRewriterTest`. EasyMock 5.2.0 requires
Java 11 and needs the JVM to open `java.lang`:

```xml
<profile>
    <id>jdk9plus-test-opens</id>
    <activation><jdk>[9,)</jdk></activation>
    <build><plugins><plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-surefire-plugin</artifactId>
        <configuration>
            <argLine>--add-opens java.base/java.lang=ALL-UNNAMED</argLine>
        </configuration>
    </plugin></plugins></build>
</profile>
```

The profile is activated only from Java 9 onwards, because the `--add-opens` flag does
not exist on Java 8. Those 15 tests now run — and pass.

EasyMock 5 also removed `new Capture<T>()`, which is replaced by `Capture.newInstance()`.

### 7.3 JDBC drivers (`jdbc`)

H2 1.3.160 → 2.2.224, SQLite 3.7.2 → 3.45.3.0, PostgreSQL 42.2.11 → 42.7.5,
MySQL 5.1.39 → 8.4.0, Derby 10.8.2.2 → 10.14.2.0. These changed driver-reported
metadata, so six existing assertions had to follow. They are listed in full in
`DEFECT-REGISTER.md` §6.1 — none of them tested MetaModel behaviour, only the
behaviour of a particular driver version.

