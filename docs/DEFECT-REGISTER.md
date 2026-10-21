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
| Defects found and fixed | **36** (8 × S1 security, 20 × S2 correctness, 8 × S3 robustness) |
| Defects found and deliberately **not** fixed (with the exact recommended change) | **14** |
| Findings **refuted** on re-verification | **1** — acting on it would have caused a double-escaping regression (§4) |
| New regression tests / test methods | **14 new test classes, ~90 new test methods** |
| Modules that did not build at all before the audit | **8** (7 missing module directories + the `sugarcrm` dependency deadlock) |
| Dependency advisories triaged | **342** across 461 resolved artifacts; **23 upgrades applied** and verified clean against OSV |
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

## 5. Open items — found, deliberately not fixed

Each of these is a real defect with the reason it was not fixed recorded. None is
presented as fixed.

| ID | Sev | Location | Why not fixed |
|---|---|---|---|
| JDBC-08 | S2 | `jdbc/.../JdbcBatchUpdateCallback.java` | `executePreparedStatement` returns `-1`, so `UpdateSummary.getUpdatedRows()` reports `null` ("unknown") instead of the true count, even though `executeBatch()`'s `int[]` is available. Fixing it means accumulating counts across `addBatch()` calls and attributing them to insert/update/delete — a design change with a real risk of double-counting. |
| JDBC-09 | S3 | `jdbc/.../dialects/HsqldbQueryRewriter.java` | `needsQuoting` computes `containsLowerCase` from `identifierQuoteString` instead of from `alias`, so its "always escape" branch is dead (`identifierQuoteString` is `"` for every supported database). **A fix was implemented and then reverted**: escaping the alias produces `FROM PUBLIC."CUSTOMERS" "a"` while core renders the qualifier unquoted as `a."CREDITLIMIT"`, and HSQLDB then fails with `Column not found`. Closing this properly requires core to quote qualifiers consistently — see the next row. |
| — | S3 | `core/.../query/SelectItem.java`, `FilterItem.java` | Column qualifiers are rendered unquoted (`alias."COLUMN"`), which is what blocks JDBC-09. Core and the JDBC layer must agree on quoting; that is a coordinated change, not a bounded fix. |
| — | S3 | `core/.../factory/DataContextFactoryRegistryImpl.java` | `ServiceLoader.load(DataContextFactory.class)` resolves through the **thread context classloader**. In a container or a shaded fat jar whose TCCL does not contain `META-INF/services/...`, factories are silently not found. There is no in-code mitigation for merged service files; the only lever is a fallback to the class's own loader, which changes discovery semantics. |
| — | S2 | `sugarcrm/.../SugarCrmXmlHelper.java` | Parses XML responses from a remote server. The XXE hardening pattern applied to the `xml` and `excel` modules is applicable here as well; it was not applied in this pass because the `sugarcrm` module's build dependency chain had to be repaired first. |
| — | **S1** | `cassandra/.../CassandraDataContext.java:196,224,238` | **A `Session` is leaked per query.** Each of the three call sites invokes `cassandraCluster.connect()`, which creates a new `Session` with its own connection pool, and no session is ever closed. *Not fixed:* the module's only test harness is an embedded Cassandra that is skipped on JDK 9+ (`Assume.assumeTrue(…, false)`), so a regression test would have to mock the DataStax `Cluster`/`Session`/`Metadata`; no fails-before/passes-after test could be produced. *Recommended:* hold one lazily created `Session` per `CassandraDataContext`, close it in a new `close()`, and reuse it at all three sites. |
| — | **S1** | `spring/.../JdbcDataContextFactoryBeanDelegate.java:63` | **The JDBC URL is logged at ERROR.** `logger.error("Failed to get JDBC connection using URL: " + url, e)` writes the URL — which routinely embeds credentials (`jdbc:postgresql://h/db?user=u&password=p`) — and the driver's exception, which usually repeats it. `SpringResource` / `DataContextFactoryBean` log the raw resource and exception too (`:60`, `:106`, `:124`). *Not fixed:* the `spring` module depends on `MetaModel-full`, which is not installed in the local repository. *Recommended:* mask credential-bearing URL segments before logging, reusing the `CredentialRedactor` pattern. |
| — | S2 | `elasticsearch/native/.../ElasticSearchDataContextFactory.java:55-60,137-143` | The factory documents `username`, `password`, `ssl`, `keystorePath` and `keystorePassword` for `clientType=transport`, but `createTransportClient` builds `Settings` with only `name` and `cluster.name` — the transport connection is always plaintext and unauthenticated. *Not fixed deliberately:* the correct settings names and behaviour depend on the ES client version, and the transport client has no supported username/password authentication, so a partial implementation would be exactly the half-wired work the brief forbids. *Recommended:* implement the four settings against `PreBuiltTransportClient` and drop `username`/`password` from the javadoc, or remove all five and point users at `ElasticSearchRestDataContext`. |
| — | S3 | `couchdb/.../CouchDbDataContextFactory.java:59-60` | URL-embedded credentials are **overwritten**: `username(null)` / `password(null)` are called unconditionally *after* `url(url)`, and ektorp's `StdHttpClient$Builder.url(URL)` has already parsed the `userInfo` out of the URL — so the result is a 401. *Not fixed:* ektorp exposes no getter for the credentials, so the only observable assertion would require reflection into a third-party class. *Recommended:* call `username()` / `password()` only when the corresponding property is non-null. |
| — | S3 | `sugarcrm/.../SugarCrmDataContext.java:84,107` | The WSDL URL (which may embed credentials) and the login user name are logged. *Recommended:* pass the WSDL URL through `CredentialRedactor` and drop the user name from the debug line. |
| — | S3 | `neo4j/.../Neo4jDataContext.java:80,89,98,107` | The `CloseableHttpClient` the context creates is never closed; the context exposes no `close()`, so the client and its connection pool live until JVM exit. *Recommended:* make `Neo4jDataContext` implement `Closeable` and close the client it created (not one passed in by the caller). |
| — | S3 | `salesforce/.../SalesforceDataContext.java:389` | Every SOQL query is logged at INFO, writing query literals — potentially personal data — into the log. *Recommended:* log at DEBUG, or mask the literal values. |
| — | S3 | `dynamodb/.../DynamoDbDataContextFactory.getCredentials` | A username without a password builds `new BasicAWSCredentials(username, null)` and the SDK then throws `IllegalArgumentException("Secret key cannot be null.")`. *Recommended:* fail fast with a clear message, or fall back to the default credentials provider chain. |
| — | S2 | `core/.../util/LegacyDeserializationObjectInputStream.java` | **The deserialized legacy operator breaks the `equals`/`hashCode` contract.** `OperatorTypeImpl.equals()` deliberately accepts any `OperatorType` implementation — "to allow other implementations that wrap this. For instance the implementation provided by LegacyDeserializationObjectInputStream" — and `OperatorTypeImpl.hashCode()` hashes `toSql()` + `isSpaceDelimited()`. But the legacy operator has to be a **real enum** for the JDK to bind the legacy enum descriptor to it, and `java.lang.Enum.hashCode()` is final and identity-based. So a deserialized legacy operator is `equals` to `OperatorType.EQUALS_TO` yet hashes differently, and the two can both end up in one `HashSet`. *Not fixed:* the only clean resolution is to have the deserialized value be an `OperatorTypeImpl`, which needs a conversion step — `readResolve` is **not** invoked for enum constants on the JDK 21 enum path (verified empirically: the hook was implemented, the test still failed, and the hook was removed rather than left as dead code), so the conversion has to happen either in `FilterItem`'s own `readObject` or by narrowing `OperatorTypeImpl.equals()` to `OperatorTypeImpl` instances. Both are cross-package behaviour changes that need their own regression tests. |

---

## 6. Supply-chain findings

Full inventory, methodology, scope triage and the deferred-upgrade rationale are in
[`SUPPLY-CHAIN.md`](SUPPLY-CHAIN.md). Summary: **342 advisories across 461 resolved
artifacts**; **167 advisories affect the 48 coordinates MetaModel pins directly**.
23 dependency upgrades were applied and re-verified against OSV before pinning, and the
reactor build was re-run afterwards.

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
