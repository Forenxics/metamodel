/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.metamodel.jdbc;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import junit.framework.TestCase;

import org.apache.metamodel.data.DataSet;
import org.apache.metamodel.jdbc.dialects.DB2QueryRewriter;
import org.apache.metamodel.jdbc.dialects.DefaultQueryRewriter;
import org.apache.metamodel.jdbc.dialects.H2QueryRewriter;
import org.apache.metamodel.jdbc.dialects.Hive2QueryRewriter;
import org.apache.metamodel.jdbc.dialects.HiveQueryRewriter;
import org.apache.metamodel.jdbc.dialects.HsqldbQueryRewriter;
import org.apache.metamodel.jdbc.dialects.IQueryRewriter;
import org.apache.metamodel.jdbc.dialects.ImpalaQueryRewriter;
import org.apache.metamodel.jdbc.dialects.MysqlQueryRewriter;
import org.apache.metamodel.jdbc.dialects.OracleQueryRewriter;
import org.apache.metamodel.jdbc.dialects.PostgresqlQueryRewriter;
import org.apache.metamodel.jdbc.dialects.SQLServerQueryRewriter;
import org.apache.metamodel.jdbc.dialects.SQLiteQueryRewriter;
import org.apache.metamodel.query.OperatorType;
import org.apache.metamodel.query.Query;
import org.apache.metamodel.query.SelectItem;
import org.apache.metamodel.schema.Column;
import org.apache.metamodel.schema.MutableColumn;
import org.apache.metamodel.schema.MutableSchema;
import org.apache.metamodel.schema.MutableTable;
import org.apache.metamodel.schema.Schema;
import org.apache.metamodel.schema.Table;
import org.apache.metamodel.schema.TableType;

/**
 * Tests for dialect specific rewriting defects.
 */
public class JdbcDialectRewriteDefectTest extends TestCase {

    private Connection _connection;
    private JdbcDataContext _dataContext;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        Class.forName("org.hsqldb.jdbcDriver");
        _connection = DriverManager.getConnection("jdbc:hsqldb:mem:dialect_defect", "sa", "");
        _dataContext = new JdbcDataContext(_connection);
    }

    @Override
    protected void tearDown() throws Exception {
        if (_connection != null) {
            _connection.close();
            _connection = null;
        }
        super.tearDown();
    }

    /**
     * The HSQLDB rewriter used to remove the last occurrence of the text
     * "TIMESTAMP" from the rendered filter - which also removed it from a
     * column name such as "UPDATED_TIMESTAMP", producing a statement that
     * refers to a column that does not exist.
     */
    public void testHsqldbDoesNotStripTimestampFromColumnName() throws Exception {
        final Statement st = _connection.createStatement();
        try {
            st.execute("CREATE TABLE metamodel_timestamp_test (UPDATED_TIMESTAMP TIMESTAMP)");
        } finally {
            st.close();
        }
        _dataContext.refreshSchemas();

        final Table table = _dataContext.getDefaultSchema().getTableByName("METAMODEL_TIMESTAMP_TEST");
        assertNotNull(table);
        final Column column = table.getColumnByName("UPDATED_TIMESTAMP");
        assertNotNull(column);

        final Query query = new Query().from(table).select(column)
                .where(column, OperatorType.EQUALS_TO, new Timestamp(1000000000000L));

        final String sql = _dataContext.getQueryRewriter().rewriteQuery(query);
        assertTrue(sql, sql.contains("\"UPDATED_TIMESTAMP\""));

        // and the statement must actually be executable
        final DataSet dataSet = _dataContext.executeQuery(query);
        assertNotNull(dataSet);
        dataSet.close();
    }

    /**
     * The PostgreSQL rewriter used to quote the schema name with
     * {@link String#replaceFirst(String, String)}, which treats the schema name
     * as a regular expression. A schema name containing a regular expression
     * metacharacter was therefore not quoted at all - or made the rewriter
     * throw a {@link java.util.regex.PatternSyntaxException}.
     */
    public void testPostgresqlQuotesSchemaNameContainingRegexCharacters() {
        final MutableSchema schema = new MutableSchema("my+schema");
        final MutableTable table = new MutableTable("t", TableType.TABLE, schema);
        final MutableColumn column = new MutableColumn("c");
        column.setTable(table);

        final Query query = new Query().from(table).select(column);

        final String sql = new PostgresqlQueryRewriter(_dataContext).rewriteQuery(query);

        assertTrue(sql, sql.contains("\"my+schema\""));
    }

    /**
     * A schema name that is not a valid regular expression must not make the
     * rewriter fail.
     */
    public void testPostgresqlDoesNotFailOnSchemaNameThatIsNotAValidRegex() {
        final MutableSchema schema = new MutableSchema("foo[bar");
        final MutableTable table = new MutableTable("t", TableType.TABLE, schema);
        final MutableColumn column = new MutableColumn("c");
        column.setTable(table);

        final Query query = new Query().from(table).select(column);

        final String sql = new PostgresqlQueryRewriter(_dataContext).rewriteQuery(query);

        assertTrue(sql, sql.contains("\"foo[bar\""));
    }

    /**
     * A schema name that also occurs inside the table name must not confuse the
     * schema quoting.
     */
    public void testPostgresqlQuotesSchemaNameThatIsPrefixOfTableName() {
        final MutableSchema schema = new MutableSchema("public");
        final MutableTable table = new MutableTable("publications", TableType.TABLE, schema);
        final MutableColumn column = new MutableColumn("c");
        column.setTable(table);

        final Query query = new Query().from(table).select(column);

        final String sql = new PostgresqlQueryRewriter(_dataContext).rewriteQuery(query);

        assertTrue(sql, sql.contains("\"public\".publications"));
    }

    /**
     * A schema name that contains the identifier quote character must not be
     * able to break out of the table reference it is rendered into. The schema
     * name is rendered by the core module without any quoting or escaping
     * (see FromItem#toStringNoAlias()), so every dialect has to protect it.
     */
    public void testSchemaNameCannotBreakOutInAnyDialect() {
        final String maliciousSchemaName = "x\" , (SELECT 1) AS \"y\"";
        final String escapedSchemaName = "\"x\"\" , (SELECT 1) AS \"\"y\"\"\"";
        for (IQueryRewriter rewriter : allDialects()) {
            final String name = rewriter.getClass().getSimpleName();
            final String sql = rewriteWithSchema(rewriter, maliciousSchemaName);
            assertFalse(name, sql.contains(maliciousSchemaName + "."));
            assertTrue(name, sql.contains(escapedSchemaName + "."));
        }
    }

    /**
     * An ordinary schema name must be rendered exactly as it was before the
     * protection was added - i.e. without being quoted - so that the generated
     * statements do not change.
     */
    public void testOrdinarySchemaNameIsNotQuoted() {
        for (IQueryRewriter rewriter : allDialects()) {
            if (rewriter instanceof PostgresqlQueryRewriter) {
                // PostgreSQL has always quoted the schema name
                continue;
            }
            final String name = rewriter.getClass().getSimpleName();
            final String sql = rewriteWithSchema(rewriter, "myschema");
            assertTrue(name, sql.contains("FROM myschema.\"t\""));
            assertFalse(name, sql.contains("\"myschema\""));
        }
    }

    /**
     * PostgreSQL has always quoted the schema name - that behaviour is
     * preserved, but an embedded quote character is now escaped as well.
     */
    public void testOrdinarySchemaNameIsQuotedInPostgresql() {
        final String sql = rewriteWithSchema(new PostgresqlQueryRewriter(_dataContext), "myschema");
        assertTrue(sql, sql.contains("FROM \"myschema\".\"t\""));
    }

    /**
     * The elements of an IN list are escaped by the JDBC layer. The core layer
     * must not escape them a second time - that would turn "O'Brien" into
     * "O''''Brien" and corrupt every query that contains an apostrophe.
     */
    public void testInListValuesAreEscapedExactlyOnce() {
        final MutableTable table = new MutableTable("t");
        table.setQuote("\"");
        final MutableColumn column = new MutableColumn("c");
        column.setTable(table);

        final Query query = new Query().from(table).select(column)
                .where(column, OperatorType.IN, Arrays.asList("O'Brien", "plain"));

        final String sql = new DefaultQueryRewriter(_dataContext).rewriteQuery(query);

        assertTrue(sql, sql.contains("'O''Brien'"));
        assertFalse(sql, sql.contains("'O'Brien'"));
        assertFalse(sql, sql.contains("O''''Brien"));
    }

    /**
     * A scalar value is escaped exactly once as well.
     */
    public void testScalarValueIsEscapedExactlyOnce() {
        final MutableTable table = new MutableTable("t");
        table.setQuote("\"");
        final MutableColumn column = new MutableColumn("c");
        column.setTable(table);

        final Query query = new Query().from(table).select(column)
                .where(column, OperatorType.EQUALS_TO, "O'Brien");

        final String sql = new DefaultQueryRewriter(_dataContext).rewriteQuery(query);

        assertTrue(sql, sql.contains("'O''Brien'"));
        assertFalse(sql, sql.contains("'O'Brien'"));
        assertFalse(sql, sql.contains("O''''Brien"));
    }

    private List<IQueryRewriter> allDialects() {
        final List<IQueryRewriter> rewriters = new ArrayList<IQueryRewriter>();
        rewriters.add(new DefaultQueryRewriter(_dataContext));
        rewriters.add(new HsqldbQueryRewriter(_dataContext));
        rewriters.add(new SQLiteQueryRewriter(_dataContext));
        rewriters.add(new MysqlQueryRewriter(_dataContext));
        rewriters.add(new H2QueryRewriter(_dataContext));
        rewriters.add(new Hive2QueryRewriter(_dataContext));
        rewriters.add(new ImpalaQueryRewriter(_dataContext));
        rewriters.add(new OracleQueryRewriter(_dataContext));
        rewriters.add(new SQLServerQueryRewriter(_dataContext));
        rewriters.add(new DB2QueryRewriter(_dataContext));
        rewriters.add(new HiveQueryRewriter(_dataContext));
        rewriters.add(new PostgresqlQueryRewriter(_dataContext));
        return rewriters;
    }

    private String rewriteWithSchema(IQueryRewriter rewriter, String schemaName) {
        final MutableSchema schema = new MutableSchema(schemaName);
        final MutableTable table = new MutableTable("t", TableType.TABLE, schema);
        table.setQuote("\"");
        final MutableColumn column = new MutableColumn("c");
        column.setTable(table);

        final Query query = new Query().from(table).select(column);
        return rewriter.rewriteQuery(query);
    }
}
