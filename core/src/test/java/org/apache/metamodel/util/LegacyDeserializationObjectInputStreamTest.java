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
package org.apache.metamodel.util;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InvalidClassException;
import java.io.ObjectOutputStream;

import org.apache.metamodel.query.OperatorType;
import org.apache.metamodel.query.Query;
import org.apache.metamodel.schema.Table;

import junit.framework.TestCase;

public class LegacyDeserializationObjectInputStreamTest extends TestCase {

    private static final String LEGACY_QUERY_RESOURCE = "src/test/resources/metamodel-3.4-query-and-schema.ser";

    private Object readLegacyQuery() throws Exception {
        final FileInputStream in = new FileInputStream(LEGACY_QUERY_RESOURCE);
        try {
            final LegacyDeserializationObjectInputStream ois = new LegacyDeserializationObjectInputStream(in);
            try {
                return ois.readObject();
            } finally {
                ois.close();
            }
        } finally {
            in.close();
        }
    }

    /**
     * Method used to generate the input file (requires org.eobjects.metamodel
     * available)
     * 
     * <pre>
     * final org.apache.metamodel.schema.MutableSchema schema = new org.apache.metamodel.schema.MutableSchema(&quot;myschema&quot;);
     * final org.apache.metamodel.schema.MutableTable table = new org.apache.metamodel.schema.MutableTable(&quot;mytable&quot;,
     *         org.apache.metamodel.schema.TableType.TABLE, schema);
     * schema.addTable(table);
     * 
     * table.addColumn(new org.apache.metamodel.schema.MutableColumn(&quot;mycol1&quot;, org.apache.metamodel.schema.ColumnType.INTEGER,
     *         table, 0, 16, &quot;int&quot;, false, &quot;my remark 1&quot;, false, &quot;\&quot;&quot;));
     * table.addColumn(new org.apache.metamodel.schema.MutableColumn(&quot;mycol1&quot;, org.apache.metamodel.schema.ColumnType.VARCHAR,
     *         table, 1, 255, &quot;text&quot;, true, &quot;my remark 2&quot;, true, null));
     * 
     * final org.apache.metamodel.query.Query q = new org.apache.metamodel.query.Query();
     * q.from(table);
     * q.select(table.getColumn(0));
     * q.where(table.getColumn(1), org.apache.metamodel.query.OperatorType.EQUALS_TO, &quot;foo&quot;);
     * 
     * final FileOutputStream out = new FileOutputStream(filename);
     * try {
     *     new ObjectOutputStream(out).writeObject(q);
     * } finally {
     *     out.close();
     * }
     * </pre>
     */
    public void testCreateSerializeAndDeserializeOldSchemaAndQuery() throws Exception {
        final Object obj = readLegacyQuery();

        assertTrue(obj instanceof Query);

        final Query q = (Query) obj;
        final Table table = q.getFromClause().getItem(0).getTable();

        assertEquals("Table[name=mytable,type=TABLE,remarks=null]", table.toString());
        assertEquals("Column[name=mycol1,columnNumber=0,type=INTEGER,nullable=false,nativeType=int,columnSize=16]",
                table.getColumn(0).toString());
        assertEquals("Column[name=mycol1,columnNumber=1,type=VARCHAR,nullable=true,nativeType=text,columnSize=255]",
                table.getColumn(1).toString());

        assertEquals("SELECT mytable.\"mycol1\" FROM myschema.mytable WHERE mytable.mycol1 = 'foo'", q.toSql());
    }

    /**
     * The legacy stream describes {@link OperatorType} as an enum, while
     * {@link OperatorType} is an interface nowadays. Binding the enum
     * descriptor to the interface makes the JDK fail with "cannot bind enum
     * descriptor to a non-enum class", so the operator has to be bound to the
     * legacy enum implementation - which still has to satisfy the modern
     * {@link OperatorType} contract.
     */
    public void testLegacyEnumOperatorIsBoundToTheModernInterface() throws Exception {
        final Object obj = readLegacyQuery();
        assertTrue(obj instanceof Query);

        final Query q = (Query) obj;
        final OperatorType operator = q.getWhereClause().getItem(0).getOperator();

        assertNotNull("operator of the legacy filter", operator);
        assertTrue("operator must implement the modern OperatorType interface",
                operator instanceof OperatorType);
        assertEquals(OperatorType.EQUALS_TO, operator);
        assertEquals("=", operator.toSql());
    }

    /**
     * A stream that was written by a current MetaModel release must still be
     * readable through this stream - the legacy handling must not interfere
     * with descriptors that are not legacy enums.
     */
    public void testCurrentStreamIsStillReadable() throws Exception {
        final ByteArrayOutputStream bos = new ByteArrayOutputStream();
        final ObjectOutputStream oos = new ObjectOutputStream(bos);
        try {
            oos.writeObject("a plain value");
        } finally {
            oos.close();
        }

        final LegacyDeserializationObjectInputStream ois = new LegacyDeserializationObjectInputStream(
                new ByteArrayInputStream(bos.toByteArray()));
        try {
            assertEquals("a plain value", ois.readObject());
        } finally {
            ois.close();
        }
    }

    /**
     * The default policy only lets MetaModel and JDK classes through, so a
     * hostile stream cannot make this class instantiate arbitrary types.
     */
    public void testDefaultAllowList() throws Exception {
        final LegacyDeserializationObjectInputStream ois = new LegacyDeserializationObjectInputStream(
                new ByteArrayInputStream(new byte[] { (byte) 0xAC, (byte) 0xED, 0x00, 0x05 }));
        try {
            assertTrue(ois.isAllowedClass("org.apache.metamodel.query.Query"));
            assertTrue(ois.isAllowedClass("org.eobjects.metamodel.query.Query"));
            assertTrue(ois.isAllowedClass("java.util.ArrayList"));
            assertTrue(ois.isAllowedClass("javax.sql.rowset.CachedRowSet"));
            assertTrue(ois.isAllowedClass("[Ljava.lang.String;"));
            assertFalse(ois.isAllowedClass("com.example.gadget.Exploit"));
            assertFalse(ois.isAllowedClass("[Lcom.example.gadget.Exploit;"));
        } finally {
            ois.close();
        }
    }

    /**
     * A class outside the allowed namespaces must be rejected instead of being
     * instantiated.
     */
    public void testDisallowedClassIsRejected() throws Exception {
        final ByteArrayOutputStream bos = new ByteArrayOutputStream();
        final ObjectOutputStream oos = new ObjectOutputStream(bos);
        try {
            oos.writeObject(new java.util.Date(0));
        } finally {
            oos.close();
        }

        final LegacyDeserializationObjectInputStream ois = new LegacyDeserializationObjectInputStream(
                new ByteArrayInputStream(bos.toByteArray())) {
            @Override
            protected boolean isAllowedClass(String className) {
                return false;
            }
        };
        try {
            ois.readObject();
            fail("Expected the disallowed class to be rejected");
        } catch (InvalidClassException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("not allowed"));
        } finally {
            ois.close();
        }
    }
}