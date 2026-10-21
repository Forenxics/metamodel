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

import junit.framework.TestCase;

import org.apache.metamodel.jdbc.dialects.DefaultQueryRewriter;
import org.apache.metamodel.query.Query;
import org.apache.metamodel.query.SelectItem;
import org.apache.metamodel.schema.ColumnType;
import org.apache.metamodel.schema.MutableColumn;
import org.apache.metamodel.schema.MutableTable;
import org.apache.metamodel.schema.Schema;

/**
 * Tests that identifiers (table names, column names and select aliases) which
 * reach the JDBC module cannot break out of the SQL statement they are
 * rendered into.
 */
public class JdbcIdentifierInjectionTest extends TestCase {

    private Connection _connection;
    private JdbcDataContext _dataContext;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        Class.forName("org.hsqldb.jdbcDriver");
        _connection = DriverManager.getConnection("jdbc:hsqldb:mem:identifier_injection", "sa", "");
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

    private JdbcCreateTableBuilder createTableBuilder(String name) {
        final JdbcSimpleUpdateCallback callback = new JdbcSimpleUpdateCallback(_dataContext);
        final Schema schema = _dataContext.getDefaultSchema();
        return (JdbcCreateTableBuilder) callback.createTable(schema, name);
    }

    /**
     * An identifier that contains the identifier quote character must be quoted
     * <em>and</em> the embedded quote character must be escaped - otherwise it
     * terminates the quoted identifier and the remainder of the identifier is
     * interpreted as SQL.
     */
    public void testQuoteIfNecessaryEscapesEmbeddedQuoteCharacter() {
        final JdbcSimpleUpdateCallback callback = new JdbcSimpleUpdateCallback(_dataContext);

        // ordinary identifiers must be left untouched
        assertEquals("id", callback.quoteIfNescesary("id"));
        assertEquals("first_name", callback.quoteIfNescesary("first_name"));

        // keywords must be quoted
        assertEquals("\"select\"", callback.quoteIfNescesary("select"));

        // an embedded identifier quote character must be escaped by doubling it
        assertEquals("\"a\"\"b\"", callback.quoteIfNescesary("a\"b"));

        // an identifier that is not a plain identifier must be quoted - it would
        // otherwise be rendered verbatim into the statement
        assertEquals("\"x;DROP/**/TABLE/**/y\"", callback.quoteIfNescesary("x;DROP/**/TABLE/**/y"));
        assertEquals("\"my.col\"", callback.quoteIfNescesary("my.col"));
    }

    /**
     * The CREATE TABLE statement must not render the primary key column names
     * verbatim - they are attacker controlled when the table is created from
     * user supplied input.
     */
    public void testCreateTableStatementQuotesPrimaryKeyColumnNames() {
        final JdbcCreateTableBuilder builder = createTableBuilder("pk_test");
        builder.withColumn("id) ; DROP TABLE other --").ofType(ColumnType.STRING).asPrimaryKey();

        final String sql = builder.createSqlStatement();

        assertTrue(sql, sql.contains("PRIMARY KEY(\"id) ; DROP TABLE other --\")"));
        assertFalse(sql, sql.contains("PRIMARY KEY(id) ; DROP"));
    }

    /**
     * The CREATE TABLE statement must escape the table name.
     */
    public void testCreateTableStatementEscapesTableName() {
        final JdbcCreateTableBuilder builder = createTableBuilder("t\"x");
        builder.withColumn("c").ofType(ColumnType.STRING);

        final String sql = builder.createSqlStatement();

        assertTrue(sql, sql.contains("\"t\"\"x\""));
    }

    /**
     * A select alias is rendered verbatim into the statement. An alias that
     * contains the identifier quote character must be quoted and escaped,
     * otherwise the alias can inject an arbitrary expression into the SELECT
     * clause.
     */
    public void testSelectAliasIsQuotedAndEscaped() {
        final MutableTable table = new MutableTable("t");
        final MutableColumn column = new MutableColumn("c");
        column.setTable(table);

        final String payload = "x\" , (SELECT 1) AS \"y\"";
        final Query query = new Query().from(table).select(new SelectItem(column).setAlias(payload));

        final String sql = new DefaultQueryRewriter(_dataContext).rewriteQuery(query);

        assertTrue(sql, sql.contains("AS \"" + payload.replace("\"", "\"\"") + "\""));
        assertFalse(sql, sql.contains("AS " + payload));
    }

    /**
     * The same applies to the alias of a FROM item.
     */    public void testFromAliasIsQuotedAndEscaped() {
        final MutableTable table = new MutableTable("t");
        final MutableColumn column = new MutableColumn("c");
        column.setTable(table);

        final String payload = "x\" , PUBLIC.\"TABLES\" AS \"y\"";
        final Query query = new Query().from(table, payload).select(new SelectItem(column));

        final String sql = new DefaultQueryRewriter(_dataContext).rewriteQuery(query);

        final String quotedAlias = "\"" + payload.replace("\"", "\"\"") + "\"";
        assertTrue(sql, sql.contains(quotedAlias));
        assertFalse(sql, sql.contains(payload));
    }
}
