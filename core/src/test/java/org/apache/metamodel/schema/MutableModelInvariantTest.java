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
package org.apache.metamodel.schema;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import org.junit.Test;

/**
 * Tests that the mutable schema model does not leave dangling back-references
 * behind when columns and tables are removed.
 */
public class MutableModelInvariantTest {

    @Test
    public void testRemovedColumnNoLongerReferencesItsTable() throws Exception {
        final MutableTable table = new MutableTable("t1");
        final MutableColumn column = new MutableColumn("c1", ColumnType.STRING, table, 0, true);
        table.addColumn(column);
        assertSame(table, column.getTable());

        table.removeColumn(column);

        assertNull(column.getTable());
        assertEquals("c1", column.getQualifiedLabel());
        assertEquals(0, table.getColumnCount());
    }

    @Test
    public void testRemovingColumnOfAnotherTableDoesNotDetachIt() throws Exception {
        final MutableTable table1 = new MutableTable("t1");
        final MutableTable table2 = new MutableTable("t2");
        final MutableColumn column = new MutableColumn("c1", ColumnType.STRING, table2, 0, true);

        table1.removeColumn(column);

        assertSame(table2, column.getTable());
    }

    @Test
    public void testRemovedTableNoLongerReferencesItsSchema() throws Exception {
        final MutableSchema schema = new MutableSchema("s1");
        final MutableTable table = new MutableTable("t1");
        table.setSchema(schema);
        schema.addTable(table);
        assertSame(schema, table.getSchema());

        schema.removeTable(table);

        assertNull(table.getSchema());
        assertEquals(0, schema.getTableCount());
    }

    @Test
    public void testClearedTablesNoLongerReferenceTheirSchema() throws Exception {
        final MutableSchema schema = new MutableSchema("s1");
        final MutableTable table = new MutableTable("t1");
        table.setSchema(schema);
        schema.addTable(table);

        schema.clearTables();

        assertNull(table.getSchema());
        assertEquals(0, schema.getTableCount());
    }

    @Test
    public void testRemovingTableOfAnotherSchemaDoesNotDetachIt() throws Exception {
        final MutableSchema schema1 = new MutableSchema("s1");
        final MutableSchema schema2 = new MutableSchema("s2");
        final MutableTable table = new MutableTable("t1");
        table.setSchema(schema2);

        schema1.removeTable(table);

        assertSame(schema2, table.getSchema());
    }

    @Test
    public void testUnnamedTableHashCodeDoesNotThrow() throws Exception {
        // a table without a name is legal (the name is only set later), so
        // hashCode() must not throw a NullPointerException for it.
        final MutableTable table = new MutableTable();

        assertEquals(0, table.hashCode());
        assertEquals("t1".hashCode(), new MutableTable("t1").hashCode());
    }
}
