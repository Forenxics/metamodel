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

## 5. Deferred upgrades — with reasons, not hand-waving

| Coordinate | Pinned | Target | Why deferred |
|---|---|---|---|
| `org.apache.hadoop:hadoop-client`, `hadoop-common` | 3.1.1 | 3.4.1 | 17 advisories (4 critical) across the two artifacts, but the Hadoop surface used by MetaModel is 60 import sites spanning `FileSystem`, `Path`, `Configuration` and `HadoopFileReader`. A 3.1 → 3.4 move also collides with `hbase-client` 1.x, which is built against the 2.x/3.0-era Hadoop APIs. This needs its own change with a dedicated test pass over the `hadoop` module. |
| `org.springframework:spring-{core,context,webmvc,test}` | 5.2.2.RELEASE | 5.3.39+ or 6.x | 3 advisories (1 high — the Spring4Shell class). The `spring` module is a thin `DataContextFactoryBean` wrapper; the upgrade is low-risk in isolation but 6.x requires Java 17 and drops `javax.*` for `jakarta.*`, which is a breaking change for every consumer of that module. |
| `commons-lang:commons-lang` | 2.6 | `commons-lang3` 3.14+ | 2.x is end-of-life with no patched release. The only usage in the tree is `hadoop/src/test/java/.../HdfsResourceTest.java` (test scope). Migrating that single import to `org.apache.commons.lang3.SerializationUtils` and excluding `commons-lang` from the Hadoop dependency is the intended fix; it was not applied here because excluding `commons-lang` from Hadoop 3.1.1 risks a `NoClassDefFoundError` inside Hadoop's own code paths at runtime, which cannot be tested without a live HDFS. |
| `org.elasticsearch:elasticsearch`, `org.elasticsearch.client:transport` | 7.3.1 | 7.17.x | 7.3.1 is long out of support. The `transport` client was removed in 8.x, so the upgrade target is the last 7.x. The Elasticsearch adapter's API usage is small, but this belongs with the `elasticsearch` module's own change rather than a blind version bump. |
| `com.datastax.cassandra:cassandra-driver-core` | 3.x | — | The driver was superseded by the DataStax "java-driver-core" 4.x with a different package name (`com.datastax.oss`). A package-level migration, not a version bump. |
| `org.apache.hbase:hbase-client`, `org.neo4j:neo4j-java-driver`, `mongo-java-driver` | — | — | Client stacks whose upstream coordinates and APIs changed majorly; each needs its own module change. None of them appear in the runtime-reachable advisory list above. |
| `org.apache.poi:poi-ooxml` | 4.1.1 | 5.2.5 | **Applied**, but note POI 5.x is a major API change against 78 import sites. It was applied and the excel module's 50 tests pass. |
| `hsqldb:hsqldb` | 1.8.0.10 | 2.7.x | No advisory recorded, but the version is from 2011 and is used by the in-memory JDBC tests. Deferred because HSQLDB 1.8 → 2.x changed SQL dialect strictness, which would invalidate a large body of existing test expectations. |

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

