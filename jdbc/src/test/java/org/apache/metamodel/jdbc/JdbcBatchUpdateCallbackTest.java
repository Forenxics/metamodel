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

import org.apache.metamodel.BatchUpdateScript;
import org.apache.metamodel.UpdateCallback;
import org.apache.metamodel.UpdateSummary;
import org.apache.metamodel.schema.ColumnType;
import org.apache.metamodel.schema.Schema;
import org.apache.metamodel.schema.Table;

import junit.framework.TestCase;

/**
 * Tests that the batch update callback reports the number of rows that were
 * actually affected. The counts of a batch are only known once the batch has
 * been executed, so they cannot be derived from the statements that are added
 * to the batch.
 */
public class JdbcBatchUpdateCallbackTest extends TestCase {

    private Connection _connection;
    private JdbcDataContext _dataContext;
    private Schema _schema;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        Class.forName("org.hsqldb.jdbcDriver");
        _connection = DriverManager.getConnection("jdbc:hsqldb:mem:" + getName(), "SA", "");
        _dataContext = new JdbcDataContext(_connection);
        _schema = _dataContext.getDefaultSchema();
    }

    @Override
    protected void tearDown() throws Exception {
        super.tearDown();
        _connection.close();
    }

    private Table createTestTable() {
        _dataContext.executeUpdate(new BatchUpdateScript() {
            @Override
            public void run(UpdateCallback callback) {
                callback.createTable(_schema, "test_table").withColumn("id").ofType(ColumnType.INTEGER).execute();
            }
        });
        return _schema.getTableByName("test_table");
    }

    /**
     * The number of inserted rows has to be reported - and it must not be
     * counted twice.
     */
    public void testBatchInsertReportsTheNumberOfInsertedRows() throws Exception {
        final Table table = createTestTable();

        final UpdateSummary summary = _dataContext.executeUpdate(new BatchUpdateScript() {
            @Override
            public void run(UpdateCallback callback) {
                for (int i = 0; i < 3; i++) {
                    callback.insertInto(table).value("id", i).execute();
                }
            }
        });

        assertNotNull("the number of inserted rows must be known", summary.getInsertedRows());
        assertEquals(3, summary.getInsertedRows().get().intValue());
    }

    /**
     * The number of updated rows is only known once the batch has been
     * executed - before the fix it was reported as "unknown" (null).
     */
    public void testBatchUpdateReportsTheNumberOfUpdatedRows() throws Exception {
        final Table table = createTestTable();

        _dataContext.executeUpdate(new BatchUpdateScript() {
            @Override
            public void run(UpdateCallback callback) {
                for (int i = 0; i < 3; i++) {
                    callback.insertInto(table).value("id", i).execute();
                }
            }
        });

        final UpdateSummary summary = _dataContext.executeUpdate(new BatchUpdateScript() {
            @Override
            public void run(UpdateCallback callback) {
                callback.update(table).where("id").eq(0).value("id", 100).execute();
                callback.update(table).where("id").eq(1).value("id", 101).execute();
            }
        });

        assertNotNull("the number of updated rows must be known", summary.getUpdatedRows());
        assertEquals(2, summary.getUpdatedRows().get().intValue());
        assertEquals(0, summary.getInsertedRows().get().intValue());
    }

    /**
     * The number of deleted rows is only known once the batch has been
     * executed - before the fix it was reported as "unknown" (null).
     */
    public void testBatchDeleteReportsTheNumberOfDeletedRows() throws Exception {
        final Table table = createTestTable();

        _dataContext.executeUpdate(new BatchUpdateScript() {
            @Override
            public void run(UpdateCallback callback) {
                for (int i = 0; i < 3; i++) {
                    callback.insertInto(table).value("id", i).execute();
                }
            }
        });

        final UpdateSummary summary = _dataContext.executeUpdate(new BatchUpdateScript() {
            @Override
            public void run(UpdateCallback callback) {
                callback.deleteFrom(table).where("id").eq(2).execute();
            }
        });

        assertNotNull("the number of deleted rows must be known", summary.getDeletedRows());
        assertEquals(1, summary.getDeletedRows().get().intValue());
    }
}
