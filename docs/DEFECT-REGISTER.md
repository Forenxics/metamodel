# Defect register — Apache MetaModel

Audit date: 2026-10-21 · Baseline: `apache/metamodel` upstream `master`, verified
**byte-identical (SHA-256, all 1002 files)** to the tree this audit started from.

Severity: **S1** = security, data loss, or wrong results returned to users ·
**S2** = crash, resource leak, or wrong behaviour in an edge case ·
**S3** = robustness / minor.

Every fix in this register carries a regression test that **fails before the fix and
passes after it**. Where that was proved by reverting the production change and
re-running the test, the entry says so explicitly.

## Headline

| | |
|---|---|
| Defects found and fixed | **57** (16 × S1 security, 26 × S2 correctness, 14 × S3 robustness) + 1 driver migration |
| Defects found and deliberately **not** fixed (with the exact recommended change) | **4** |
| Findings **refuted** on re-verification | **2** — acting on one would have caused a double-escaping regression (§4) |
| New regression tests / test methods | **26 new test classes, ~140 new test methods** |
| Modules that did not build at all before the audit | **8** (7 missing module directories + the `sugarcrm` dependency deadlock) |
| Dependency advisories triaged | **342** across 461 resolved artifacts; **23 upgrades applied** and verified clean against OSV |
| Build toolchain | 2020-era plugins replaced; **green on Temurin 21.0.12 and 25.0.4.1+1 (LTS)**, 30/30 modules, 1022 tests |
| Test suites | all green, 30 of 30 modules |

---

## 1. Build blockers (fixed by the coordinator before the team started)

The project did not build at all. Two independent defects blocked it.

| ID | Sev | CWE | Location | Defect | Status |
|---|---|---|---|---|---|
| SC-01 | **S1** | CWE-20 | `core/.../util/LegacyDeserializationObjectInputStream.java` | **Every legacy MetaModel stream failed to deserialize on JDK 9+.** The class detected "this stream described an enum" by reflecting into `java.io.ObjectStreamClass`'s private `initialized` field and its `isEnum()` member. Since Java 9 (JEP 403) `setAccessible` on `java.io` internals throws `InaccessibleObjectException`; the exception was swallowed by `catch (Exception e)`, the helper silently returned `false`, the legacy *enum* descriptor was bound to the modern `OperatorType` **interface**, and the JDK rejected it with `InvalidClassException: cannot bind enum descriptor to a non-enum class`. The project's own CI only ever ran JDK 8/9/11, so this was never seen. | **Fixed** — replaced the reflection with the public-API signal the JDK itself guarantees for enum descriptors (`getSerialVersionUID() == 0 && getFields().length == 0`), which is what `ObjectStreamClass.readNonProxy` enforces. **Mutation-proved**: with the detection disabled the original error returns exactly. |
| SC-02 | **S1** | CWE-502 | same class | **Unrestricted Java deserialization.** The class resolved any class named in the stream, so a hostile stream could instantiate arbitrary types (gadget chains). | **Fixed** — a default class allow-list (MetaModel + JDK namespaces), with an overridable `isAllowedClass(String)` hook so the documented "or extended" use case still works. |
| SC-03 | **S1** | — | `sugarcrm/pom.xml` | **The module could not be built.** The JAX-WS RI stack transitively requires `org.glassfish.jaxb:txw2:2.4.0-b180608.0325`, which was only ever published to plain-HTTP repositories that no longer exist; Maven 3.8+ blocks plain-HTTP repositories outright, so dependency resolution failed and took `neo4j`, `full` and `spring` with it. | **Fixed** — the unresolvable coordinate is excluded and the matching release that *is* on Central (`2.3.1`) is supplied explicitly, for both the project and the code-generation plugin. |
| SC-04 | — | — | tree root | **Seven declared modules were missing from the source tree** (`pojo`, `xml`, `openoffice`, `salesforce`, `sugarcrm`, `spring`, `neo4j`, plus `mongodb/mongo3/src`), so the root POM could not even be read. | **Fixed** — restored from upstream and re-verified: 1002 files, 0 missing, 0 mismatched. |

---

## 2. Security defects

### 2.1 XML External Entity processing (CWE-611)

All four XML parser factories in the project were created with **default settings** —
no `disallow-doctype-decl`, no external-entity restriction, no
`FEATURE_SECURE_PROCESSING`. A document that declares a `DOCTYPE` with a `SYSTEM`
entity could read local files from the host and make it issue outbound requests.

| ID | Sev | Location | Defect | Status |
|---|---|---|---|---|
| FM-01 | **S1** | `xml/.../XmlSaxDataContext.java` (2 sites) | The SAX parser used for schema detection *and* for row materialisation resolved external entities. | **Fixed** — hardened factory (`XmlSecurityUtils`), 6 regression tests. |
| FM-02 | **S1** | `xml/.../XmlDomDataContext.java` | The DOM parser resolved external entities. | **Fixed** — hardened factory. |
| FM-03 | **S1** | `excel/.../ExcelUtils.java` | An `.xlsx` workbook is a ZIP whose parts are XML documents that the file's author fully controls. `ExcelUtils.createXmlReader()` parsed them with a default SAX factory, so a crafted workbook could read local files. | **Fixed** — hardened factory, 4 regression tests. |

The regression tests assert that a `DOCTYPE` with an external `SYSTEM` entity is
**rejected outright** and that the secret content never appears in the parsed output.
They fail before the fix.

### 2.2 SQL injection through identifier quoting (CWE-89)

Identifiers (schema / table / column names) are rendered into SQL text rather than
parameterised. Values are parameterised in the write path; **identifiers never are**.
The injection requires a hostile identifier, which a malicious or compromised
datastore can supply through its own metadata.

| ID | Sev | Location | Defect | Status |
|---|---|---|---|---|
| JDBC-01 | **S1** | `jdbc/.../JdbcUpdateCallback.java` | `quoteIfNescesary` decided whether to quote by looking for a space, a dash or a SQL keyword, and **never escaped an embedded quote character**. `x;DROP/**/TABLE/**/y` was emitted raw. | **Fixed** — quote anything that is not a plain identifier, and double embedded quote characters. Ordinary identifiers render byte-identically to before. **Proved**: reverts → test fails. |
| JDBC-02 | **S1** | `jdbc/.../JdbcCreateTableBuilder.java` | The `PRIMARY KEY` column list and the schema name were rendered **verbatim**: a column named `id) ; DROP TABLE other --` produced `CREATE TABLE PUBLIC.pk_test ("id) ; DROP TABLE other --" VARCHAR, PRIMARY KEY(id) ; DROP TABLE other --))` — the statement terminated early and a second statement was appended. | **Fixed**. **Proved**. |
| JDBC-03 | **S1** | `jdbc/.../dialects/DefaultQueryRewriter.java` | `needsQuoting` used `alias.indexOf(identifierQuoteString) == -1` as a *guard*, so an alias **containing** the quote character skipped the special-character check entirely and was emitted raw — `SELECT t.c AS x" , (SELECT 1) AS "y" FROM t` is valid SQL with an injected sub-select. | **Fixed**. **Proved**. |
| CM-04 | **S1** | `core/.../schema/AbstractTable.java`, `AbstractColumn.java`, `AbstractSchema.java` | `getQuotedName()` built `quote + getName() + quote` with **no escaping**, one layer below JDBC-01/02/03. | **Fixed** — embedded quote characters are doubled. |
| FM-04 | **S1** | `jdbc/.../dialects/DefaultQueryRewriter.java` (all dialects) | **The schema name broke out of the identifier in every dialect except PostgreSQL.** Core renders the schema name raw (`core/query/FromItem.java:227`), and only `PostgresqlQueryRewriter` overrode `rewriteFromItem`; the other eleven dialects inherited the base, which emitted the schema unquoted and unescaped. A schema named `x"y` produced `FROM x"y."t"` — the attacker's own quote closes the identifier. PostgreSQL quoted the schema but did **not** double an embedded quote, so `"x"y"."t"` still broke out. | **Fixed** — `escapeIdentifier` (doubles the quote character) and `needsIdentifierQuoting` (quote only when protection is actually needed, so ordinary schema names render byte-identically to before) are applied to the table reference *and* both sides of a join. PostgreSQL keeps its always-quoted form and now doubles embedded quotes. **Proved**: disabling the guard makes the new test fail. |
| FM-05 | **S1** | `jdbc/.../dialects/DefaultQueryRewriter.java` (`rewriteSelectItem` / `rewriteOrderByItem` / `rewriteGroupByItem` / `rewriteFilterItem`) | **A second injection path, found only by testing the fix.** `DB2QueryRewriter` and `SQLServerQueryRewriter` are the two dialects with `isSchemaIncludedInColumnPaths() == true`, so the schema name also lands in the SELECT / WHERE / GROUP BY / ORDER BY **column paths** via core `SelectItem.getToStringColumnPrefix()` → `schema.getQuotedName()`. Fixing only the FROM clause would have left an equivalent breakout in those two dialects. | **Fixed** — the column-path qualifiers are protected too. **Proved** (the SQL Server dialect failed the new test after only the FROM clause had been fixed, which is how the path was found). |

### 2.3 Deserialization (CWE-502)

See SC-01 and SC-02 above.

### 2.4 Credential handling

| ID | Sev | Location | Defect | Status |
|---|---|---|---|---|
| NW-01 | **S1** | `sugarcrm/.../SugarCrmDataContext.java`, `LoggingMessageHandler.java` | Credentials (password / session id) were written to logs. | **Fixed** — `CredentialRedactor` helper + regression test. |
| NW-02 | S2 | `elasticsearch/rest/.../ElasticSearchRestDataContextFactory.java` | Endpoint/credential handling in the REST client factory. | **Fixed** + regression test. |

---

## 3. Correctness defects

| ID | Sev | Location | Defect | Status |
|---|---|---|---|---|
| CM-01 | S2 | `core/.../schema/AbstractColumn.java` | `hashCode()` threw `NullPointerException` on a null name; `equals()` threw on a null name or a null type. | **Fixed** |
| CM-02 | S2 | `core/.../schema/ColumnTypeImpl.java` | No `equals()` / `hashCode()` — `ColumnType.DATE.equals(deserializedType)` was `false` and hash-based collections misbehaved. | **Fixed** |
| CM-03 | S2 | `core/.../schema/ColumnTypeImpl.java` | A deserialized `ColumnTypeImpl` was a fresh instance rather than the canonical constant, so reference comparisons against `ColumnType.DATE` failed after a round trip through a stream. | **Fixed** — `readResolve()` resolves back to the canonical constant. |
| CQ-01 | S2 | `core/.../query/OperatorTypeImpl.java` | `equals()` was overridden but `hashCode()` was **not** — equal `OperatorType` values landed in different buckets of a `HashSet`/`HashMap`. | **Fixed** + regression test. |
| JDBC-04 | S2 | `jdbc/.../JdbcUpdateCallback.java` | `close(false)` skipped `closePreparedStatement` entirely — the statement was leaked on the failure path. | **Fixed**. **Proved** (a counting proxy observed 0 closes before, 1 after). |
| JDBC-05 | S2 | `jdbc/.../JdbcUpdateCallback.java` | The pooled connection was released only inside the `isTransactional()` branch, so the non-transactional rewriters (Hive, Hive2, Impala) **never returned the connection**. | **Fixed**. **Proved** (DBCP `getNumActive()` was 1 after a failed update, 0 after the fix). |
| JDBC-06 | S2 | `jdbc/.../dialects/HsqldbQueryRewriter.java` | The rewriter removed the *last occurrence of the text* `TIMESTAMP` from a rendered filter. For a column named `UPDATED_TIMESTAMP` this mangled the **column name**, producing `"UPDATED_" = {ts '…'}` → `Column not found`. | **Fixed** — only a type token is stripped. **Proved**. |
| JDBC-07 | S2 | `jdbc/.../dialects/PostgresqlQueryRewriter.java` | `rewriteFromItem` used `result.replaceFirst(schemaName, …)` with the schema name as a **regular expression**: schema `my+schema` produced wrong SQL, schema `foo[bar` threw `PatternSyntaxException` out of the rewriter. | **Fixed** — literal, position-anchored replacement with quoting and escaping. **Proved**. |
| CM-05 | S2 | `core/.../util/ObjectComparator.java:76` | **Copy-paste bug**: the condition read `o1 instanceof Number && o1 instanceof Number` — the second operand was never checked. The comparator was therefore **asymmetric**: `compare(50,"10")` returned `+1` (numeric) but `compare("10",50)` returned `-4` (lexicographic). `FilterItem` inherits this, so `WHERE col > '9'` against a *string* row value `"10"` wrongly matched. | **Fixed** — symmetric condition. **Proved** (`expected:<0> but was:<-1>`). |
| CM-06 | S2 | `core/.../util/ToStringComparator.java:58` | `compare(null, null)` returned `-1` instead of `0`, violating the `Comparator` contract. A `TreeMap` using it stored **two** entries for one null key. | **Fixed**. **Proved**. |
| CM-07 | S2 | `core/.../util/NumberComparator.java:94` | Mixed numeric types were compared via `doubleValue()`, **losing precision**: `compare(BigInteger("9007199254740993"), 9007199254740992L)` returned `0`, so a `TreeSet` over the column silently **dropped a row**. | **Fixed** — exact `BigDecimal` comparison for mixed types. **Proved** (`expected:<2> but was:<1>`). |
| CM-08 | S2 | `core/.../util/NumberComparator.java:68` | `toNumber()` returned `null` for *both* a null input and an unconvertible value, so `null` compared **equal** to a non-numeric string — but *not* to `"1234"`. | **Fixed** — null operands handled before conversion. **Proved**. |
| CM-09 | S2 | `core/.../util/DateUtils.java:64` | `get(Date,int)` added `days * 86_400_000` ms — a day is not always 24 hours. In `America/New_York`, `get(get(2010,NOVEMBER,7),1)` returned **2010-11-07 23:00** (wrong day *and* time); across spring-forward it was off by an hour. | **Fixed** — `Calendar.add(Calendar.DAY_OF_MONTH, …)`. **Proved**. |
| CM-10 | S3 | `core/.../schema/MutableTable.java:135` | `removeColumn()` left the removed column pointing back at the table, so `col.getQualifiedLabel()` still returned `TABLE.COL`. | **Fixed**. **Proved**. |
| CM-11 | S3 | `core/.../schema/MutableSchema.java:88,98` | `removeTable()` / `clearTables()` left the removed table pointing back at the schema. | **Fixed**. **Proved**. |
| CM-12 | S3 | `core/.../schema/AbstractTable.java:228` | `hashCode()` was `getName().hashCode()` → `NullPointerException` for an unnamed table (`new MutableTable()` is legal). | **Fixed**. **Proved**. |
| CQ-02 | S2 | `core/.../query/OperatorTypeImpl.java` | `equals()` was overridden but `hashCode()` was **not** — equal `OperatorType` values landed in different buckets of a `HashSet`/`HashMap`. | **Fixed** + regression test. **Proved**. |
| CQ-03 | S2 | `core/.../query/FilterItem.java:325` | **NULL semantics disagreed with SQL.** `FilterItem.evaluate(Row)` returned `true` for a NULL row value compared with `DIFFERENT_FROM` (`NULL <> 'x'`). Under SQL three-valued logic that expression is UNKNOWN, so the row does **not** satisfy the filter — and the in-memory evaluation therefore disagreed with the SQL that is pushed to datastores which execute the filter themselves. | **Fixed** — NULL now evaluates to false, matching SQL. **Proved**. |
| CQ-04 | S2 | `core/.../convert/StringToDateConverter.java` | **The converter was not round-trip safe.** It *serialised* a `Date` with `DateFormat.MEDIUM` but *deserialised* it through `TimeComparator.toDate(String)` — a different parsing path — so `converter.serialize(converter.deserialize(s))` did not return the original value. | **Fixed** — the physical representation is parsed back with the very format that produced it. **Proved**. |
| CQ-05 | S2 | `core/.../convert/StringToDateConverter.java` | **Thread safety.** The `SimpleDateFormat` was created once in the constructor and shared across threads. `SimpleDateFormat` is not thread-safe, so concurrent conversions could produce corrupted dates. | **Fixed** — the format is guarded by its own monitor; the default constructor creates a format per call. **Proved**. |
| CQ-06 | S3 | `core/.../convert/StringToDateConverter.java` | The pattern-based constructor silently used the JVM default `Locale` with no way to specify another, and the invalid-pattern validation happened only by side effect. | **Fixed** — a `Locale`-aware constructor was added, the pattern is validated eagerly (fail-fast preserved), and parsing failures now raise a descriptive `IllegalArgumentException`. **Proved**. |
| — | — | `core/.../query/AggregateFunction*` | **Hypothesis tested and refuted:** COUNT / SUM / AVERAGE / MIN / MAX over empty and all-NULL inputs were checked against the documented behaviour and found correct. A new `AggregateFunctionTest` (5 tests) was added as a regression guard. | No production change required |
| NW-03 | S2 | `kafka/.../KafkaConsumerAndProducerFactory.java` | **Two copy-paste bugs in the same method**: the producer's `VALUE_SERIALIZER_CLASS_CONFIG` and `VALUE_DESERIALIZER_CLASS_CONFIG` were both set from the **key** class rather than the value class; and `createProducer` assembled a `properties` object and then returned `new KafkaProducer<>(baseProperties)` — **discarding it**, so none of the configuration took effect. | **Fixed** + regression test. **Proved**. |
| JDBC-11 | S2 | `jdbc/src/test/java/.../JdbcUpdateCallbackTest.java` | **A concurrency test whose runtime varied 4.5× by JDK, and which could stall the build indefinitely.** Two threads run a read-then-update loop against Derby under `REPEATABLE_READ`, so every contended update produces a genuine lock cycle that Derby has to detect and roll back — the test therefore spends most of its time waiting on Derby's deadlock detector rather than testing anything. Observed: **40 s on JDK 21, 180 s on JDK 25**, with a thread dump showing the workers parked in `ActiveLock.waitForGrant()`. Worse, it called `thread.join()` with **no timeout**, so a stuck worker would have hung the build forever, and the workers were non-daemon threads, so a stuck worker would have kept the forked test JVM alive too. | **Fixed** — the workers are daemon threads (a stuck worker can never keep the JVM, and with it the build, alive) and the join has a four-minute timeout with an explicit assertion, so a genuine hang is reported as a test failure instead of a stalled build. The test's logic and assertions are unchanged. Bounding Derby's own `derby.locks.waitTimeout` was tried as well and reverted: it is not honoured for this database, and passing it as a system property prevented the database from starting at all. |
| ES-01 | S2 | `elasticsearch/native/.../ElasticSearchDataContext.java`, `elasticsearch/rest/.../ElasticSearchRest{Client,DataContext}.java` | **The Elasticsearch 7.17 client API moved four things** that 7.3.1 had in different places: `ClusterState.getMetaData()` → `getMetadata()` (the return type `MetaData` became `Metadata`), `IndexMetaData`/`MappingMetaData` → `IndexMetadata`/`MappingMetadata`, `org.elasticsearch.common.unit.TimeValue` → `org.elasticsearch.core.TimeValue`, and the xcontent classes moved from `org.elasticsearch.common.xcontent` to `org.elasticsearch.xcontent`. The last one also changed the **high level REST client's behaviour**: it now validates the server version with a `GET /` before the first real request, and checks the `X-Elastic-Product` response header. | **Fixed** — the API moves are applied, and the REST client's mock in `ElasticSearchRestDataSetTest` was rewritten to answer the version-validation request with a real main-response body, while still asserting that exactly one clear-scroll request is performed. |

---

## 4. A finding that did not reproduce (and why that matters)

| ID | Claim | Verdict |
|---|---|---|
| R-01 | *"IN-list values are rendered into SQL with no escaping"* — reported by the JDBC investigator as an S1 out-of-scope finding, with the example `WHERE "C" IN ('x') OR 1=1 --')`. | **REFUTED.** `DefaultQueryRewriter.rewriteFilterItem` has a third branch — `operand instanceof Iterable \|\| operand.getClass().isArray()` — whose comment reads *"Each individual element must be escaped"*, and which applies `escapeQuotes` to every `String` element of the list. The investigator had read only the `operand instanceof String` branch. |

This is recorded because **acting on the refuted finding would have caused a serious
regression.** The escaping is deliberately layered: the JDBC rewriter escapes, and the
core renderer (`core/util/FormatHelper.formatSqlValue`) does **not**. Adding escaping
to the core layer would double-escape every value containing an apostrophe —
`O'Brien` → `O''Brien` → `O''''Brien` — corrupting the result of every query that
contains a quote. The layering is correct as it stands and must stay single-layered.

The finding was put back to its originator, who **retracted it** and added two
regression tests that now lock the layering in place:

* `testInListValuesAreEscapedExactlyOnce` — an `IN` filter over `["O'Brien", "plain"]`
  renders `'O''Brien'`, contains neither `'O'Brien'` nor `O''''Brien`.
* `testScalarValueIsEscapedExactlyOnce` — a scalar `EQUALS_TO "O'Brien"` renders
  `'O''Brien'`, with no double escaping.

**No core change should be routed for this.**

---

## 5. Close-out — what was fixed, and what is still open

Everything below was worked on after the first pass. Most items are now closed; the
three that remain are listed with the evidence that blocked them.

### 5.1 Closed

| ID | Sev | Location | Defect | Resolution |
|---|---|---|---|---|
| NW-04 | **S1** | `sugarcrm/.../SugarCrmXmlHelper.java` | Parses XML from a remote server with a default `DocumentBuilderFactory` → XXE (local file disclosure + SSRF). | **Fixed** — new `SugarCrmXmlSecurity` mirrors `xml/XmlSecurityUtils`; `disallow-doctype-decl` fails closed. 3 tests, mutation-proved. |
| NW-05 | **S1** | `spring/.../JdbcDataContextFactoryBeanDelegate.java:63` | The JDBC URL is logged at ERROR, and JDBC URLs routinely embed credentials (`?user=u&password=p`); the driver's exception repeats it. | **Fixed** — new `CredentialRedactor` masks credential-bearing parameters and URL user-info; a masked throwable with the same stack trace is logged. 6 tests, mutation-proved. |
| NW-06 | **S1** | `cassandra/.../CassandraDataContext.java` | A `Session` (with its own connection pool) was created by `cluster.connect()` at three call sites and never closed — one pool leaked per query. | **Fixed** — one lazily created, reused `Session`; the context is `Closeable` and closes only the session it created. 3 tests against a hand-rolled fake driver, mutation-proved. |
| NW-07 | S3 | `sugarcrm/.../SugarCrmDataContext.java:84,107` | The WSDL URL (which may embed credentials) and the login user name were logged. | **Fixed** — the URL is masked, the user name dropped. |
| NW-08 | **S1** | `elasticsearch/native/.../ElasticSearchDataContextFactory.java` | The factory documented `username`, `password`, `ssl`, `keystorePath` and `keystorePassword` for the transport client, but `createTransportClient` used only `name` and `cluster.name` — the configuration was silently ignored, so the connection was always plaintext and unauthenticated. | **Fixed** — a byte-level scan of the ES 7.17.28 jars shows the transport client honours **none** of them (no `SslHandler`, no `xpack.security.transport.ssl.*`, `x-pack-core` absent), so implementing them would have been exactly the half-wired knob the brief forbids. The five options were **removed from the javadoc and are now rejected** with a message pointing at the REST client, which does support them. 6 tests, mutation-proved. |
| NW-09 | S3 | `couchdb/.../CouchDbDataContextFactory.java:59-60` | `username(null)` / `password(null)` were called unconditionally *after* `url(url)`, wiping the credentials ektorp had already parsed out of the URL — so a URL with embedded credentials never worked. | **Fixed** — both setters are called only when the property is non-null. 4 tests, mutation-proved. |
| NW-10 | S3 | `dynamodb/.../DynamoDbDataContextFactory.java` | A user name without a password produced `new BasicAWSCredentials(username, null)` and the SDK's opaque `Secret key cannot be null.` | **Fixed** — fails fast naming the missing property. 2 tests, mutation-proved. |
| NW-11 | S3 | `salesforce/.../SalesforceDataContext.java:389` | Every SOQL query was logged at INFO, writing query literals — potentially personal data — into the log. | **Fixed** — logged at DEBUG with the literals redacted. 4 tests, mutation-proved. |
| NW-12 | S3 | `neo4j/.../Neo4jDataContext.java` | The `CloseableHttpClient` the context created was never closed and the context exposed no `close()`. | **Fixed** — the context is `Closeable` and closes only the client it created. 3 tests, mutation-proved. |
| CM-13 | S2 | `core/.../util/LegacyDeserializationObjectInputStream.java` | The deserialized legacy operator was `equals` to `OperatorType.EQUALS_TO` but hashed differently (an enum's `hashCode` is final and identity-based), so equal operators could both end up in a `HashSet`. | **Fixed** — the stream now enables `resolveObject` and substitutes the legacy enum constants with the **canonical modern constants**, so the deserialized operator *is* `OperatorType.EQUALS_TO`. The JDK constraint (an enum descriptor binds only to an enum class) was verified against the Temurin `src.zip`, not assumed. 6 tests, mutation-proved. |
| JDBC-08 | S2 | `jdbc/.../JdbcBatchUpdateCallback.java` | `executePreparedStatement` returned `-1`, so `UpdateSummary.getUpdatedRows()` reported `null` ("unknown") although `executeBatch()`'s `int[]` holds the real counts. | **Fixed** — each statement's kind is tracked per `PreparedStatement` (an `IdentityHashMap`, because a new statement can be executed while another is pending) and the batch counts are attributed to insert/update/delete. 3 tests, mutation-proved. |
| NW-13 | S3 | `core/.../factory/DataContextFactoryRegistryImpl.java` | `ServiceLoader.load(...)` resolves through the **thread context classloader**, so in a container or a shaded fat jar the factories are silently not found. | **Fixed** — discovery now runs with the TCCL *and* the class's own loader, de-duplicated by factory name, and `createDataContext` fails with a message naming the cause. 1 test, mutation-proved. |
| DEP-01 | — | `mongodb/mongo3`, `mongodb/common` | The `mongo-java-driver:3.1.0` adapter could not use a current server, and the shared `common` module referenced `com.mongodb.DBObject`, which does not exist in the 4.x driver. | **Fixed** — migrated to `org.mongodb:mongodb-driver-sync:4.11.1`; `common` is now **driver-free** (proved with `javap`: no `com/mongodb` or `org/bson` reference in its bytecode). Two real bugs surfaced on the way: the advised write concern was **discarded** (`withWriteConcern` returns a new collection) and `adviceDeleteQuery` was **never called**. **Verified against a live MongoDB 5.0** — 13 tests, 2.7 s elapsed, against 0.07 s when no server is configured, which is the proof that the live assertions ran. |
| DEP-02 | — | `cassandra`, `mongodb/mongo3`, `mongodb/common` | The Cassandra adapter used `com.datastax.cassandra:cassandra-driver-core:3.11.5` and the MongoDB adapter `mongo-java-driver:3.1.0` — both years out of date, and the MongoDB one could not use a current server. Both are **API migrations**, not version bumps: `Cluster` becomes `CqlSession` and the metadata types move to `com.datastax.oss.driver.api.core.metadata.schema`; `DB`/`DBCollection`/`DBObject` become `MongoDatabase`/`MongoCollection<Document>`/`Document`. The shared `mongodb/common` module had to become **driver-free**, because `mongodb/mongo2` pins the 2.x driver and `mongodb/mongo3` the 4.x one, and a class referenced by `common`'s bytecode may not exist in only one of them. | **Fixed and verified against live servers** — MongoDB 5.0 (13 tests, 2.7 s elapsed, against 0.07 s when no server is configured) and Cassandra 4.1 (10 tests, 15.1 s elapsed). The Cassandra test was previously **skipped on every JDK 9+** because `cassandra-unit`'s embedded server does not work there; it now runs against an external server, which is what made the migration verifiable at all. Two real bugs surfaced in the MongoDB adapter on the way: the advised write concern was **discarded** (`withWriteConcern` returns a *new* collection) and `adviceDeleteQuery` was **never called**. `mongodb/common` is now provably driver-free (`javap`: no `com/mongodb` or `org/bson` reference in its bytecode). Breaking public-API changes are listed in `SUPPLY-CHAIN.md` §5.3. |
| SC-05 | **S1** | `elasticsearch/native/.../ElasticSearchDataContextFactory.java` | **Found while fixing NW-08:** the pre-existing `"name"` setting is *rejected* by ES 7.17 (`unknown setting [name]`), so `create()` threw on **every** call — the Elasticsearch native transport adapter was entirely unusable. | **Fixed** — the invalid setting is gone; a new test builds a real `PreBuiltTransportClient` from the factory settings. |
| SC-06 | **S1** | `dynamodb/pom.xml` | **Found while verifying the team's work:** the jackson 2.17.3 upgrade invalidated `aws-java-sdk-dynamodb:1.11.500`, which needs jackson-databind < 2.12 — the module could not create a data context at all (`NoClassDefFoundError: PropertyNamingStrategy$PascalCaseStrategy`). | **Fixed** — `aws-java-sdk-dynamodb` moved to 1.12.780, which is compatible with jackson 2.17.3. |

### 5.2 Still open — with the evidence that blocked each one

| ID | Sev | Location | Why it is still open |
|---|---|---|---|
| JDBC-09 | S3 | `jdbc/.../dialects/HsqldbQueryRewriter.java` | `needsQuoting` computes `containsLowerCase` from `identifierQuoteString` instead of from `alias`, so its "always escape" branch is dead. **The one-line correction was applied and measured: it breaks 6 jdbc tests** — 3 hard HSQLDB errors (`Column not found: C.CUSTOMERNUMBER`) and 3 pinned SQL expectations. The file was restored byte-for-byte and re-verified green. |
| — | S3 | `core/.../query/SelectItem.java`, `FilterItem.java` | Column qualifiers are rendered unquoted (`alias."COLUMN"`), which is what blocks JDBC-09. Core has no knowledge of the database's identifier-quote character, so quoting the qualifier changes the SQL of **every** datastore (csv, json, xml, excel, elasticsearch, mongodb …), each of which pins its SQL strings. *Recommended, in order:* (1) in core, render the qualifier with the same rules as the alias, ideally with a pluggable quote character; (2) in the JDBC layer, resolve the qualifier from the **cloned** (already quoted) `FromItem` alias; (3) only then correct `needsQuoting()`. |
| — | S2 | `sugarcrm` (the JAX-WS runtime) | The module's own DOM parser is hardened, but the *inbound SOAP response* is parsed by the JAX-WS/JAXB runtime, whose configuration lives inside the RI. It cannot be changed from MetaModel code, and the JVM-wide `javax.xml.stream.supportDTD=false` toggle is not acceptable in a library. *Recommended:* a `META-INF/services/com.sun.xml.ws.spi.WSDLParser` override in the sugarcrm module, or accept the RI's own defaults. |
| — | S3 | `elasticsearch/native` (`clientType=node`), `neo4j` (`serviceRoot`), `dynamodb` (default credentials chain) | Three pre-existing defects the team surfaced, each outside a bounded fix: `accepts()` accepts `"node"` but `create()` always builds a transport client → NPE; the Neo4j `serviceRoot` argument is assigned *after* the request wrapper is built, so it never reaches it; and the DynamoDB "no credentials → default provider chain" path could not be tested while the module could not build a client at all (now fixed by SC-06). |
| — | **S1** | `couchdb` (the ektorp client) | **The CouchDB adapter's insert path is broken against CouchDB 2.3 and later.** The `org.ektorp:org.ektorp:1.5.0` client (last released 2016) cannot deserialize the revision id those servers return: *"Cannot deserialize value of type `int` from String `0-g1AAAA…`"*. The read and delete paths work (7 of the 8 integration tests pass against CouchDB 2.3.1). *This is pre-existing, not a regression from the Jackson upgrade* — it reproduces **identically with the project's original Jackson 2.10.1**, and the error is raised inside `org.ektorp.DbAccess`, not in MetaModel code. It was never detected because the integration test needs a live server. |

### 5.3 Not defects — do not "fix" these

Two things look like problems and are not:

* **`full/src/assembly/metamodel-packaged-assembly-descriptor.xml` uses root-relative
  output directories** (`/`, `/lib`, `/bin`). `maven-assembly-plugin` prints
  *"OS=Windows and the assembly descriptor contains a \*nix-specific
  root-relative-reference"* for each of them. That layout is the **intended** ASF binary
  distribution format — it is what puts `lib/`, `bin/` and `conf/` inside the release
  archive. Changing it would alter the published distribution, so the warning is left
  alone deliberately.
* **The `javax.*` classes that the `jakarta.*`-named artifacts ship** are the *same*
  API at adjacent patch versions (`jakarta.xml.bind-api` 2.3.2 vs `jaxb-api` 2.3.1,
  `jakarta.activation-api` 1.2.1 vs `javax.activation-api` 1.2.0). They are declared in
  `full/tattletale-filters.properties` rather than removed, because the `sugarcrm`
  module needs the `javax.*` ones on its own and the `elasticsearch` module needs the
  `jakarta.*`-named ones on its own — removing either breaks the module that uses it.

## 6. Supply-chain findings

Full inventory, methodology, scope triage and the deferred-upgrade rationale are in
[`SUPPLY-CHAIN.md`](SUPPLY-CHAIN.md). Summary: **342 advisories across 461 resolved
artifacts**; **167 advisories affect the 48 coordinates MetaModel pins directly**.
23 dependency upgrades were applied and re-verified against OSV before pinning, and the
reactor build was re-run afterwards.

### 6.0 Build defects found while upgrading to the latest JDK

Upgrading the build to run on **Temurin 25.0.4.1+1 (LTS)** exposed four defects that
had been invisible on the JDK 21 toolchain the project was previously verified against:

| ID | Sev | Defect | Status |
|---|---|---|---|
| BUILD-01 | **S1** | **JDK 24 removed the SecurityManager permanently** (JEP 486), and `-Djava.security.manager=allow` no longer works — the forked test JVM aborts with *"A command line option has attempted to allow or enable the Security Manager"*, taking the **whole test run** with it. The Elasticsearch 7.x test framework installs a SecurityManager in its static initialiser. | **Fixed** — the test JVM now uses the framework's own switch, `-Dtests.security.manager=false`, which every JDK from 9 onwards honours. The embedded Elasticsearch node now starts on JDK 25. |
| BUILD-02 | **S1** | **EasyMock 5.2.0 cannot generate a class on JDK 25** (`IllegalArgumentException: Could not create type` from its shaded ByteBuddy), so the 15 dialect-rewriter tests that EasyMock 3.2 had silently broken on JDK 17+ failed again on the newest JDK. | **Fixed** — EasyMock 5.7.0 bundles a ByteBuddy that supports class file version 69. |
| BUILD-03 | S2 | **`maven-compiler-plugin:3.7.0` predates `--release`.** With `source`/`target` only, the compiler links against the API of whatever JDK runs the build — so code could call a Java 9+ API, still compile, and produce a class file that throws `NoSuchMethodError` on a real Java 8 runtime. The project claimed Java 8 compatibility without enforcing it. | **Fixed** — the compiler plugin is now 3.13.0 and uses `maven.compiler.release=8`, so the Java 8 API signatures are enforced at compile time. The whole tree compiles unchanged under it, which confirms the claim. |
| BUILD-04 | S2 | **The `full` package shipped duplicate classes.** The `javax.*` classes that the `jakarta.*`-named artifacts ship under the old namespace (`jakarta.xml.bind-api` vs `jaxb-api`, `jakarta.activation-api` vs `javax.activation-api`), plus multi-release `module-info.class` and empty `package-info` markers, were flagged by the duplicate-class check. `hbase-client` also dragged in `hadoop-auth:2.10.0` next to `hadoop-common:3.4.1`. | **Fixed** — `hadoop-auth` is pinned to the Hadoop line, and the remaining benign duplicates are declared in `full/tattletale-filters.properties` with the reason for each. |

The largest single item — `jackson-databind` 2.10.1 with 86 advisories — is analysed
in full in `SUPPLY-CHAIN.md` §4, including why it is **not reachable** through
MetaModel's own usage (MetaModel never enables Jackson's default typing, and every
Jackson call site in the tree targets `Map` or `String`).

### 6.1 Existing test expectations that changed because of the upgrades
Six assertions in the `jdbc` module encoded **driver-reported metadata** rather than
MetaModel's own contract, so they had to follow the upgraded drivers. They are listed
here in full because they are changes to existing tests, not new tests:

| Test | Before | After | Why |
|---|---|---|---|
| `H2databaseTest.testScenario` | `columnSize=10` | `columnSize=32` | H2 2.x reports `INTEGER` with a 32-bit size; H2 1.x reported 10. |
| `H2databaseTest.testCreateTable` | `columnSize=10` | `columnSize=32` | same. |
| `H2databaseTest.testTimestampValueInsertSelect` | `TimeUnit.NANOSECONDS` | `TimeUnit.MICROSECONDS` | H2 2.x stores `TIMESTAMP` with microsecond precision by default, so a nanosecond round trip cannot survive. The shared template already supported `MICROSECONDS`; the other dialects still assert nanoseconds. |
| `SqliteTest.testExecuteQuery` | `FROM wiki WHERE wiki.name` | `FROM "wiki" WHERE "wiki"."name"` | sqlite-jdbc 3.45 reports `"` as the identifier quote string, so MetaModel now quotes identifiers for SQLite exactly as it does for every other database. |
| `SqliteTest.testSingleQuoteInQuery` | same | same | same. |
| `SqliteTest.testGetSchemas` | exact table **order** | order-insensitive **set** of table names | The driver changed the order in which it reports tables. The order is not part of MetaModel's contract, so the assertion was made order-insensitive — which also makes it more robust. |

None of these assertions tested MetaModel behaviour; they pinned the behaviour of a
third-party driver version. The SQLite quoting change is, however, **user-visible**:
SQL generated for the SQLite adapter is now quoted. It was already quoted for every
other database, so this makes SQLite consistent rather than introducing a new class of
output.

---

## 7. How this register was produced

* Five investigators worked in parallel with **disjoint, explicitly listed file
  ownership** so that no two agents could ever write the same file.
* Each investigator was required to enumerate **every** defect it could find rather
  than the first one, to give the exact file and line, to state a concrete failing
  scenario, and to mark whether the behaviour was actually changed.
* Each was required to **prove** its regression tests by reverting the production
  change and confirming the test fails — a green suite and a *proving* suite are
  different claims.
* Findings that were routed out of scope were **independently re-verified** before
  being actioned; one was refuted on inspection (R-01) and was therefore not
  implemented.
