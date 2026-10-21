# Security Policy

## Reporting a vulnerability

Please **do not** open a public GitHub issue for a security problem.

Report vulnerabilities privately through the Apache Software Foundation's
vulnerability reporting process at <https://www.apache.org/security/>, or by
contacting the MetaModel project directly at `dev@metamodel.apache.org`.

Include: the affected module and version, a description of the issue, the
input that triggers it, and the impact. Please do not include exploit code in
public channels.

## Supported versions

MetaModel is a library, not a hosted service. Fixes land on `master` and are
released in the next minor version. Security fixes are backported only to the
most recent release.

## Threat model — what a MetaModel user should be aware of

MetaModel reads data from datastores and runs queries against them. Its
security boundary is therefore **the data and the metadata it is pointed at**,
not a network perimeter.

### 1. Untrusted files

The file-based adapters — `csv`, `excel`, `json`, `xml`, `arff`, `fixedwidth`,
`openoffice` — parse files that a user may have received from an untrusted
source. Treat them as hostile input:

- **XML (`xml` module)** — the parser must reject DTDs and external entities.
  External entity resolution in the XML adapters is a remote file disclosure and
  SSRF primitive. See the XXE entries in `docs/DEFECT-REGISTER.md`.
- **Excel (`excel` module)** — `.xlsx` and `.ods` files are ZIP archives. They
  must be read with the archive expansion bounded, and the XML parts inside
  them are subject to the same XML rules.
- **CSV (`csv` module)** — CSV values beginning with `=`, `+`, `-` or `@` are
  interpreted as formulas by spreadsheet applications. If you write CSV that
  will be opened in a spreadsheet, quote or prefix such values.
- **POJO (`pojo` module)** — the POJO adapter instantiates classes
  reflectively. Only point it at classes you control.

### 2. Untrusted identifiers

MetaModel's JDBC adapter renders schema, table and column names into SQL
text. Identifier names must be quoted, and embedded quote characters escaped
by doubling them. If you build a `DataContext` from metadata that came from an
untrusted source, untrusted identifiers will reach the database.

### 3. Deserialization

`org.apache.metamodel.util.LegacyDeserializationObjectInputStream` exists to
read legacy MetaModel objects. Java deserialization is dangerous on untrusted
input; the class therefore applies a class allow-list. Do not deserialize a
stream you did not produce.

### 4. Credentials

Connection strings, passwords and tokens may appear in datastore
configuration. They must never be written to logs. If you configure a
datastore with credentials, keep them out of source control and out of any
log aggregation pipeline.

### 5. Network adapters

The `couchdb`, `mongodb`, `elasticsearch`, `hbase`, `cassandra`, `dynamodb`,
`kafka`, `hadoop`, `neo4j`, `salesforce` and `sugarcrm` adapters connect to
remote services. Use TLS, and prefer client libraries that verify certificates
and hostnames. Endpoint URLs supplied by configuration are fetched by the
library — do not derive them from untrusted input.
