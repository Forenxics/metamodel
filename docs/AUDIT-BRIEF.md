# MetaModel Audit — Shared Brief

> Read this file completely before doing anything. It is the shared context for every
> investigator on this engagement. Your own scope and claims are appended by the coordinator.

## 1. What this project is

**Apache MetaModel 5.3.4-SNAPSHOT** — a Java data-access framework. It provides one unified
query API over many datastores: JDBC, CSV, Excel, JSON, XML, ARFF, fixed-width, POJO,
OpenOffice, MongoDB, CouchDB, Elasticsearch, Cassandra, HBase, DynamoDB, Kafka, Hadoop,
Neo4j, Salesforce and SugarCRM.

30 Maven modules, 523 main source files (~51k LOC), 198 test files (~25.5k LOC).

## 2. Environment — already set up, do not reinstall

| Thing | Value |
|---|---|
| Repo root | `E:\MINIMAX\metamodel-master\metamodel-master` |
| JDK | Temurin **21.0.12** — `C:\Users\patri\AppData\Local\Programs\Temurin\jdk-21.0.12+8` |
| Maven | **3.9.9**, project-local at `E:\MINIMAX\metamodel-master\.tools\apache-maven-3.9.9` |
| Launcher | `E:\MINIMAX\metamodel-master\mvnw.cmd` (pins JAVA_HOME + PATH) |
| Local repo | `%USERPROFILE%\.m2\repository` (warm) |
| VCS | git repo initialised at the repo root; baseline commit `6a8e2d2` = pristine upstream |

### Commands (PowerShell — always quote `-D` args)

```powershell
cd 'E:\MINIMAX\metamodel-master\metamodel-master'

# whole reactor (expect the two known blockers in §3)
& 'E:\MINIMAX\metamodel-master\mvnw.cmd' -B clean install

# one module, fast loop (deps already installed in ~/.m2)
& 'E:\MINIMAX\metamodel-master\mvnw.cmd' -B -pl <module> test

# one module plus its reactor dependencies
& 'E:\MINIMAX\metamodel-master\mvnw.cmd' -B -pl <module> -am test '-Dmaven.test.failure.ignore=true'

# one test class
& 'E:\MINIMAX\metamodel-master\mvnw.cmd' -B -pl <module> test '-Dtest=SomeTest'
```

**PowerShell gotcha:** `-Dmaven.test.failure.ignore=true` unquoted gets mangled into a
lifecycle phase. Always wrap `-D...` in single quotes.

**Do not run `mvn install` from more than one place at a time** — concurrent writes to
`~/.m2` race. Use `test`, not `install`, in your own loops.

## 3. Known baseline state — measured, do not re-investigate

The tree was verified **byte-identical (SHA-256, all 1002 files)** to `apache/metamodel`
upstream `master`. Git baseline commit: `6a8e2d2`.

A full reactor build (`mvn clean install '-Dmaven.test.failure.ignore=true'`) was run.
Result: **28 of 30 modules SUCCESS.** Two defects block the build:

**Blocker A — `core`, 1 test error (the coordinator is fixing this; assume it is fixed)**
`LegacyDeserializationObjectInputStreamTest.testCreateSerializeAndDeserializeOldSchemaAndQuery`
→ `java.io.InvalidClassException: cannot bind enum descriptor to a non-enum class`.

**Blocker B — `sugarcrm`, dependency resolution failure (supply-chain workstream)**
`sugarcrm/pom.xml` declares dead **plain-HTTP** repositories (`http://maven.java.net/content/repositories/releases/`,
`http://maven.java.net/content/repositories/staging/`, `http://download.eclipse.org/rt/eclipselink/maven.repo`,
`http://maven.java.net/content/repositories/snapshots/`). Maven 3.8+ blocks these via the
`maven-default-http-blocker` mirror, so `org.glassfish.jaxb:txw2:2.4.0-b180608.0325` cannot
resolve. This blocks `sugarcrm` → `neo4j` → `full` → `spring`.

While Blocker B is open, `sugarcrm` cannot be built. `neo4j`, `full` and `spring` are also
unbuildable. Investigate those modules by **reading code**; report findings without
executing their tests, and say so in your report.

Everything else is green.

## 4. Ground rules — these are not optional

1. **Exclusive file ownership.** You may ONLY create or modify files under the paths listed
   in *your* scope below. Every path outside it belongs to another investigator. Do not
   edit, reformat, "tidy", or "just fix" anything outside your scope — a lost race on a
   shared file means one agent's work silently disappears. If you find a defect outside
   your scope, **report it** with `file:line` and evidence; do not fix it.

2. **Never edit any `pom.xml`.** All POMs (root and module) belong to the supply-chain
   workstream. If you need a dependency, plugin or configuration change, describe it
   exactly in your report (groupId:artifactId:old→new, plus why) instead of making it.

3. **Never edit** `.travis.yml`, anything under `.github/`, `docs/`, or any root-level file.

4. **Apache RAT license check.** Every new `.java` file must carry the standard ASF 2.0
   header — copy it verbatim from a neighbouring file in the same module. `.md` files are
   exempt from RAT. The build fails without this.

5. **No half-wired work.** If a fix cannot be implemented properly in-house, leave the code
   untouched and record it as an open item with the blocker and the exact recommended
   change. A half-finished mechanism looks finished and is worse than an untouched defect.
   Do not add a stub, a TODO, or a no-op that reads as if the problem were solved.

6. **Every fix needs a regression test** that fails before the fix and passes after. If you
   genuinely cannot write one, say so explicitly and explain why.

7. **Preserve the public API and observable behaviour** unless the behaviour is provably the
   bug. List every intentional behaviour change in your report. This is a widely-used Apache
   library — a silent behaviour change is a regression for downstream users.

8. **Java 8 source level is enforced** (`maven.compiler.source/target = 1.8`). Do **not** use
   Java 9+ APIs: no `List.of`/`Map.of`, no `var`, no `Files.readString`/`Files.writeString`,
   no `Optional.isEmpty`, no `Optional.or`, no text blocks, no `Stream.toList()`.
   No new runtime dependencies either (test-only deps also need a POM change → report it).

9. **Never weaken, disable or delete an existing test to make the build pass.** If an existing
   test asserts buggy behaviour, fix the production code and keep the assertion. If the
   assertion itself is wrong, report it — do not silently change it.

10. **Do not commit.** The coordinator owns git. Leave changes in the working tree.

## 5. Defect classes to hunt

*Correctness*
- Wrong results: filters matching the wrong rows; NULL / sort-order / comparison errors;
  off-by-one; wrong aggregation; incorrect type coercion; lost or duplicated rows.
- Broken API contracts; interface methods that silently no-op.
- Resource leaks: unclosed streams, connections, sessions; missing try-with-resources.
- Thread safety: shared mutable state without synchronisation; unsafe publication.
- Copy-paste bugs: duplicated blocks where one copy was changed and the other was not.

*Security (CWE)*
| CWE | What to look for |
|---|---|
| 611 XXE | XML parsing without disabling DTDs / external entities (`SAXParserFactory`, `DocumentBuilderFactory`, `XMLInputFactory`, `SAXReader`) |
| 502 Insecure deserialization | `ObjectInputStream` without a class allow-list; native deserialization of untrusted data |
| 89 SQL injection | untrusted identifiers/values concatenated into SQL instead of parameterised — every `AbstractQueryRewriter`, `JdbcCreateTableBuilder`, `JdbcInsertBuilder`, `JdbcDeleteBuilder`, `JdbcUpdateBuilder` |
| 78 Command injection | `Runtime.exec` / `ProcessBuilder` with untrusted arguments |
| 22 Path traversal | file paths from untrusted input without normalisation |
| 798 Hardcoded credentials | and credentials/connection strings leaking into logs at INFO/DEBUG/WARN |
| 295 Missing TLS verification | disabled or unverified certificate/hostname checks; plain HTTP for credentialed protocols |
| 918 SSRF | server-side fetches of user-supplied URLs |
| 400 Resource exhaustion | unbounded reads, ignored `maxRows`, zip bombs (xlsx/ods are ZIP), catastrophic regex backtracking |
| 190 Integer overflow | size/length arithmetic; unchecked `NumberFormatException` on untrusted input |

## 6. Deliverable — return a markdown report with exactly these sections

1. **Summary** — counts (defects fixed, defects reported-not-fixed, new test classes, files
   changed) and one paragraph on what was most broken.
2. **Defect register** — a markdown table, one row per defect, most severe first.
   Columns: `#` | `Severity` | `CWE` | `File:line` | `Defect` | `Concrete failing scenario` |
   `Fix` | `Regression test`.
   Severity: `S1` (security / RCE / data loss / wrong results returned to users),
   `S2` (crash, resource leak, wrong behaviour in an edge case),
   `S3` (robustness, minor).
3. **Files changed** — every file created or modified, one line of reason each.
4. **Tests added** — every new test class and method, plus proof the test fails without the fix.
5. **Open items / not fixed** — each with the blocker and the exact recommended change.
6. **Findings outside my scope** — `file:line` + evidence. Report only; do not fix.
7. **Verification** — the exact commands you ran and their results; paste the final
   `Tests run: N, Failures: 0, Errors: 0` line for every module you touched.

## 7. Quality bar

**"Would this test fail if the bug came back?"** Prove it. For each fix: revert the
production change locally, confirm the new test fails for the stated reason, then re-apply.
State which fixes you proved this way. A green suite and a *proving* suite are different
claims, and only the second one is worth anything.

Equally: **enumerate every defect you can find, ranked — not just the one you found first.**
"Find the defect" returns one bug; "enumerate every defect you can find" returns the ones
that matter and tells the coordinator which one was misdiagnosed.

## 8. Response format

The register may be long. The prose around it must be tight. No preamble, no explanation of
what MetaModel is, no "I will now begin". Lead with findings.
