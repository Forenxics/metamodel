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
package org.apache.metamodel.mongodb.common;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.metamodel.data.DataSetHeader;
import org.apache.metamodel.data.Row;
import org.apache.metamodel.data.SimpleDataSetHeader;
import org.apache.metamodel.query.SelectItem;
import org.apache.metamodel.schema.ColumnType;
import org.apache.metamodel.schema.Table;
import org.apache.metamodel.util.SimpleTableDef;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * Tests the driver agnostic row conversion of {@link MongoDBUtils}.
 * <p>
 * This test intentionally uses no type of the MongoDB Java driver at all. The module
 * {@code MetaModel-mongodb-common} is shared by the MongoDB 2.x module (2.x driver) and
 * the MongoDB 3.x/4.x module (4.x driver), so it must be compilable - and testable -
 * without any driver on the classpath.
 */
public class MongoDBUtilsTest {

    private static final Table TABLE = new SimpleTableDef("my_table", new String[] { "name.first", "name.last", "gender",
            "addresses[0].city" }, new ColumnType[] { ColumnType.STRING, ColumnType.STRING, ColumnType.STRING,
            ColumnType.STRING }).toTable();

    private static DataSetHeader createHeader() {
        final List<SelectItem> selectItems = new ArrayList<SelectItem>();
        for (String columnName : TABLE.getColumnNames()) {
            selectItems.add(new SelectItem(TABLE.getColumnByName(columnName)));
        }
        return new SimpleDataSetHeader(selectItems);
    }

    @Test
    public void testToRowOfNullMapIsNull() {
        assertNull(MongoDBUtils.toRow(null, createHeader()));
    }

    @Test
    public void testToRowResolvesNestedKeys() {
        final Map<String, Object> name = new LinkedHashMap<String, Object>();
        name.put("first", "John");
        name.put("last", "Doe");

        final Map<String, Object> address = new LinkedHashMap<String, Object>();
        address.put("city", "Copenhagen");

        final Map<String, Object> document = new LinkedHashMap<String, Object>();
        document.put("name", name);
        document.put("gender", "MALE");
        document.put("addresses", Arrays.asList(address));

        final Row row = MongoDBUtils.toRow(document, createHeader());

        assertEquals("John", row.getValue(0));
        assertEquals("Doe", row.getValue(1));
        assertEquals("MALE", row.getValue(2));
        assertEquals("Copenhagen", row.getValue(3));
    }

    @Test
    public void testMissingKeyIsNull() {
        final Map<String, Object> document = new LinkedHashMap<String, Object>();
        document.put("gender", "MALE");

        final Row row = MongoDBUtils.toRow(document, createHeader());

        assertNull(row.getValue(0));
        assertEquals("MALE", row.getValue(2));
    }

    /**
     * Nested documents are passed through to the row as they are delivered by the
     * driver. The 2.x driver delivers a {@code com.mongodb.BasicDBObject} and the 4.x
     * driver delivers an {@code org.bson.Document}, so this module must neither
     * unwrap nor copy them - that is the responsibility of the driver specific module.
     */
    @Test
    public void testNestedDocumentIsPassedThroughUnchanged() {
        final Map<String, Object> name = new LinkedHashMap<String, Object>();
        name.put("first", "John");

        final Map<String, Object> document = new LinkedHashMap<String, Object>();
        document.put("name", name);

        final List<SelectItem> selectItems = new ArrayList<SelectItem>();
        selectItems.add(new SelectItem(TABLE.getColumnByName("name.first")));
        selectItems.add(new SelectItem(TABLE.getColumnByName("name.last")));

        final Row row = MongoDBUtils.toRow(document, new SimpleDataSetHeader(selectItems));

        assertEquals("John", row.getValue(0));
        assertNull(row.getValue(1));
        assertTrue(document.get("name") instanceof Map);
        assertSame(name, document.get("name"));
    }
}
