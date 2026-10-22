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

import java.util.Map;

import org.apache.metamodel.data.DataSetHeader;
import org.apache.metamodel.data.DefaultRow;
import org.apache.metamodel.data.Row;
import org.apache.metamodel.query.SelectItem;
import org.apache.metamodel.util.CollectionUtils;

/**
 * A utility class for MongoDB module.
 * <p>
 * This class is intentionally <b>driver agnostic</b>: it must not reference any type
 * of the MongoDB Java driver. The class is shared between the MongoDB 2.x module
 * (which uses the 2.x driver) and the MongoDB 3.x/4.x module (which uses the 4.x
 * driver), and a reference to a driver type that only exists in one of them would
 * cause a {@link NoClassDefFoundError} at runtime in the other.
 * <p>
 * Both the 2.x driver ({@code com.mongodb.BasicDBObject}) and the 4.x driver
 * ({@code org.bson.Document}) represent a MongoDB document as a {@link Map}, so a
 * {@link Map} is the natural currency between the driver specific modules and this
 * class.
 */
public class MongoDBUtils {

    /**
     * Converts a map into MetaModel. This map stores data of a MongoDB document.
     * <p>
     * Values are exposed as they are delivered by the driver. Driver specific
     * document objects (such as {@code com.mongodb.BasicDBObject} of the 2.x driver)
     * are converted by the driver specific module before this method is called, so
     * that this method stays independent of the driver version in use.
     *
     * @param map
     *            a map object storing data of a MongoDB document.
     * @param header
     *            a header describing the columns of the data stored.
     * @return the MetaModel {@link Row} result object.
     */
    public static Row toRow(Map<?,?> map, DataSetHeader header) {
        if (map == null) {
            return null;
        }

        final int size = header.size();
        final Object[] values = new Object[size];
        for (int i = 0; i < values.length; i++) {
            final SelectItem selectItem = header.getSelectItem(i);
            final String key = selectItem.getColumn().getName();
            values[i] = CollectionUtils.find(map, key);
        }
        return new DefaultRow(header, values);
    }

}
