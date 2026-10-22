> **This is an unofficial fork.**
>
> Apache MetaModel was retired by the Apache Software Foundation in **June 2021**
> and moved to [the Attic](https://attic.apache.org/projects/metamodel.html) in
> July 2021. The ASF does not release retired projects, so this repository
> carries the fixes and the modernisation on top of the last upstream commit -
> they will never be released by the ASF.
>
> This fork is **not** an ASF project and is not endorsed by the ASF. It is
> maintained independently, and it exists so that the security fixes below are
> actually usable.
>
> What was found and changed:
>
> - [`docs/DEFECT-REGISTER.md`](docs/DEFECT-REGISTER.md) - 64 defects, with
>   the evidence for each, and the two findings that were **refuted** on
>   re-verification.
> - [`docs/AUDIT-BRIEF.md`](docs/AUDIT-BRIEF.md) - the rules the work was
>   done under.
> - [`SECURITY.md`](SECURITY.md) - the threat model.
> - [`AGENTS.md`](AGENTS.md) - notes for agents working in this repository.
>
> The most serious fix is an **S1 SQL injection** in the JDBC layer: a FROM
> item alias was rendered unquoted and unescaped in the WHERE, GROUP BY, HAVING
> and ORDER BY clauses, while the FROM clause rendered it escaped. An alias
> containing a quote character could therefore terminate the identifier and the
> remainder of it became SQL. It is fixed at the root, in
> `org.apache.metamodel.query.Query#clone()`.
>
> The build was restored and modernised as well: it did not build at all when
> the audit started, and it is now green on **Temurin 21 and 25** with 1049
> tests, against live MongoDB, Cassandra and CouchDB.
>
> The ASF asks forks of Attic projects to let them know so that they can link
> to them - see the note on the
> [MetaModel page in the Attic](https://attic.apache.org/projects/metamodel.html).
>
> **Publishing.** The version in this repository is still `5.3.4-SNAPSHOT`,
> because that is what the upstream line was called and the ASF never released
> it. When the fork is published to Maven Central, it will be published under
> a group of its own - not `org.apache.metamodel`, which the ASF owns - and
> under the version **`5.3.4-forenxics.1`**. The qualifier is what makes the
> version say what it is: a fork of the 5.3.4 line, carrying the security
> fixes, and not a 5.3.4 that the ASF released.

## Apache MetaModel

MetaModel is a data access framework, providing a common interface for exploration and querying of different types of datastores.

<div>
<img src="http://metamodel.apache.org/img/logo.png" style="float: right; margin-left: 20px;" alt="MetaModel logo" />
</div>

### Mailing lists

 * Developer list:  dev@metamodel.apache.org
 * User list:  user@metamodel.apache.org
 * Commits list:    commits@metamodel.apache.org

### Website

http://metamodel.apache.org/

### Documentation

Please check out our [wiki for user documentation](https://cwiki.apache.org/confluence/display/METAMODEL).

### Building the code

MetaModel uses maven as it's build tool. Code can be built with:

```
mvn clean install
```

### Running the integration tests

 1. Copy the file 'example-metamodel-integrationtest-configuration.properties' to your user home.
 2. Remove the 'example-' prefix from its filename
 3. Modify the file to enable properties of the integration tests that you're interested in.
 4. Re-run "mvn clean install".

### Contributing

Please see [CONTRIBUTE.md](CONTRIBUTE.md)
