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
package org.apache.metamodel.cassandra;

import com.datastax.oss.driver.api.core.cql.ColumnDefinition;
import com.datastax.oss.driver.api.core.cql.ColumnDefinitions;
import com.datastax.oss.driver.api.core.cql.Row;
import com.datastax.oss.driver.api.core.type.DataType;
import org.apache.metamodel.data.DataSetHeader;
import org.apache.metamodel.data.DefaultRow;
import org.apache.metamodel.query.SelectItem;

import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A utility class for Cassandra module.
 */
public class CassandraUtils {

    /**
     * Converts a Cassandra Row data object {@link com.datastax.oss.driver.api.core.cql.Row}
     * into MetaModel {@link org.apache.metamodel.data.Row}.
     *
     * @param dbObject
     *            a Cassandra object storing data.
     * @param header
     *            a header describing the columns of the data stored.
     * @return the MetaModel {@link org.apache.metamodel.data.Row} result
     *         object.
     */
    public static org.apache.metamodel.data.Row toRow(Row dbObject, DataSetHeader header) {
        if (dbObject == null) {
            return null;
        }

        final int size = header.size();

        final Object[] values = new Object[size];
        for (int i = 0; i < values.length; i++) {
            final SelectItem selectItem = header.getSelectItem(i);
            final String key = selectItem.getColumn().getName();
            values[i] = getColumnValue(key, dbObject);
        }
        return new DefaultRow(header, values);
    }

    /**
     * Returns the base CQL type name of a data type, for instance <code>text</code>
     * for a <code>list&lt;text&gt;</code> column.
     * <p>
     * The driver 4.x no longer exposes a <code>DataType.Name</code> enumeration, so
     * the CQL representation of the type is used instead.
     * </p>
     *
     * @param dataType
     *            the type to inspect
     * @return the base CQL type name, in lower case
     */
    static String getBaseTypeName(DataType dataType) {
        final String cql = dataType.asCql(false, false);
        final int index = cql.indexOf('<');
        return (index >= 0 ? cql.substring(0, index) : cql).trim();
    }

    private static Object getColumnValue(String columnName, Row row) {
        final ColumnDefinitions columns = row.getColumnDefinitions();
        final ColumnDefinition columnDefinition = columns.get(columnName);
        if (columnDefinition == null) {
            throw new IllegalArgumentException("Column '" + columnName + "' was not found in the result set");
        }
        final DataType columnType = columnDefinition.getType();
        switch (getBaseTypeName(columnType)) {
        case "bigint":
            return row.getLong(columnName);
        case "counter":
            return row.getLong(columnName);
        case "blob":
            return toByteArray(row.getByteBuffer(columnName));
        case "boolean":
            return row.getBool(columnName);
        case "decimal":
            return row.getBigDecimal(columnName);
        case "double":
            return row.getDouble(columnName);
        case "float":
            return row.getFloat(columnName);
        case "int":
            return row.getInt(columnName);
        case "text":
            return row.getString(columnName);
        case "timestamp":
            return toDate(row.getInstant(columnName));
        case "uuid":
            return row.getUuid(columnName);
        case "timeuuid":
            return row.getUuid(columnName);
        case "varchar":
            return row.getString(columnName);
        case "varint":
            return row.getBigInteger(columnName);
        case "list":
            return row.getList(columnName, List.class);
        case "map":
            return row.getMap(columnName, Map.class, String.class);
        case "set":
            return row.getSet(columnName, Set.class);
        case "inet":
            return row.getInetAddress(columnName);
        default:
            return row.getString(columnName);
        }
    }

    /**
     * Converts a {@link ByteBuffer} into a byte array, without consuming the
     * position of the buffer that was handed in.
     */
    private static byte[] toByteArray(ByteBuffer buffer) {
        if (buffer == null) {
            return null;
        }
        final byte[] bytes = new byte[buffer.remaining()];
        buffer.duplicate().get(bytes);
        return bytes;
    }

    /**
     * Converts an {@link Instant} into a {@link Date}, which is the java type that
     * MetaModel uses for timestamp columns.
     */
    private static Date toDate(Instant instant) {
        if (instant == null) {
            return null;
        }
        return Date.from(instant);
    }

}
