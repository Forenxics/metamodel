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
package org.apache.metamodel.couchdb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.List;

import org.apache.metamodel.UpdateCallback;
import org.apache.metamodel.UpdateScript;
import org.apache.metamodel.data.DataSet;
import org.apache.metamodel.schema.ColumnType;
import org.apache.metamodel.schema.Table;
import org.apache.metamodel.util.SimpleTableDef;
import org.ektorp.CouchDbConnector;
import org.ektorp.DbInfo;
import org.ektorp.http.HttpClient;
import org.ektorp.http.StdHttpClient;
import org.ektorp.impl.ObjectMapperFactory;
import org.ektorp.impl.StdCouchDbInstance;
import org.junit.Assume;
import org.junit.Test;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Tests that the CouchDB adapter can read the database info ({@code GET /db}) that CouchDB 2.3
 * and newer returns.
 *
 * <p>
 * CouchDB 2.3 turned the {@code purge_seq} of a database into an opaque sequence id of the shape
 * {@code "0-g1AAAAEzeJzLYWBg..."}, while ektorp 1.5.0 still declares
 * {@link DbInfo#getPurgeSeq()} as an {@code int}. Reading such a database info therefore failed
 * with {@code InvalidFormatException}, which broke the row count of the CouchDB adapter
 * ({@code CouchDbDataContext#executeCountQuery(...)}) - and with it any insert that was followed
 * by a row count.
 * </p>
 *
 * <p>
 * These tests do not require the metamodel integration test configuration file - they only need a
 * CouchDB server for the live test, and that one detects the server by itself.
 * </p>
 */
public class CouchDbObjectMapperFactoryTest {

    private static final String HOST = "localhost";

    private static final int PORT = 5986;

    private static final String DATABASE_NAME = "metamodel_probe";

    /**
     * The verbatim body of {@code GET /metamodel_probe} as returned by a CouchDB 2.3.1 server.
     */
    private static final String COUCHDB_23_DB_INFO_JSON = "{"
            + "\"db_name\":\"metamodel_probe\","
            + "\"purge_seq\":\"0-g1AAAAEzeJzLYWBg4MhgTmHgzcvPy09JdcjLz8gvLskBCjMlMiTJ____PyuRAYeCPBYgyfAASP3Hqy5JAUgm2RNh1gGIWfcJqVsAUbcfj50JIDvribCzAWLWfDxmOYDMioeYlQUAojJi9g\","
            + "\"update_seq\":\"0-g1AAAAEzeJzLYWBg4MhgTmHgzcvPy09JdcjLz8gvLskBCjMlMiTJ____PyuRAYeCJAUgmWSPX40DSE08fjUJIDX1eNXksQBJhgYgBVQ2n5C6BRB1-wmpOwBRd5-QugcQdSD3ZQEAiJJi9g\","
            + "\"sizes\":{\"file\":33960,\"external\":0,\"active\":0},"
            + "\"other\":{\"data_size\":0},"
            + "\"doc_del_count\":0,"
            + "\"doc_count\":0,"
            + "\"disk_size\":33960,"
            + "\"disk_format_version\":7,"
            + "\"data_size\":0,"
            + "\"compact_running\":false,"
            + "\"cluster\":{\"q\":8,\"n\":1,\"w\":1,\"r\":1},"
            + "\"instance_start_time\":\"0\"}";

    /**
     * A POJO with a couple of int properties, used to verify that the tolerance for the opaque
     * {@code purge_seq} value does not spill over to other properties.
     */
    public static class IntFieldsPojo {
        @JsonProperty("purge_seq")
        public int purgeSeq;
        @JsonProperty("doc_del_count")
        public int docDelCount;
        @JsonProperty("other_count")
        public int otherCount;
    }

    /**
     * The database info of a CouchDB 2.3.1 server must be deserializable, even though its
     * {@code purge_seq} is an opaque string.
     */
    @Test
    public void testDbInfoWithOpaquePurgeSeqIsDeserialized() throws Exception {
        final ObjectMapper objectMapper = createMetaModelObjectMapper();

        final DbInfo dbInfo = objectMapper.readValue(COUCHDB_23_DB_INFO_JSON, DbInfo.class);

        assertEquals(0, dbInfo.getPurgeSeq());
        assertEquals(0, dbInfo.getDocCount());
        assertEquals(0, dbInfo.getDocDelCount());
    }

    /**
     * The tolerance must be restricted to the {@code purge_seq} property - every other int
     * property must still reject a non-numeric string.
     */
    @Test
    public void testToleranceIsRestrictedToPurgeSeq() throws Exception {
        final ObjectMapper objectMapper = createMetaModelObjectMapper();

        // the tolerance does apply to purge_seq ...
        final IntFieldsPojo pojo = objectMapper.readValue(
                "{\"purge_seq\":\"0-g1AAAAEzeJzLYWBg4MhgTmHgzcvPy09JdcjLz8gvLsk\",\"doc_del_count\":7,"
                        + "\"other_count\":9}",
                IntFieldsPojo.class);
        assertEquals(0, pojo.purgeSeq);
        assertEquals(7, pojo.docDelCount);
        assertEquals(9, pojo.otherCount);

        // ... but not to another int property
        assertRejected(objectMapper, "{\"purge_seq\":\"0-g1AAAAEzeJzLYWBg4MhgTmHgzcvPy09JdcjLz8gvLsk\","
                + "\"doc_del_count\":\"not-a-number\"}", IntFieldsPojo.class);
        assertRejected(objectMapper, "{\"other_count\":\"not-a-number\"}", IntFieldsPojo.class);

        // ... and not to a purge_seq that is not shaped like an opaque sequence id
        assertRejected(objectMapper, "{\"purge_seq\":\"not-a-number\"}", IntFieldsPojo.class);

        // the very same applies to the real database info: doc_del_count must still be rejected
        assertRejected(objectMapper, COUCHDB_23_DB_INFO_JSON.replace("\"doc_del_count\":0",
                "\"doc_del_count\":\"not-a-number\""), DbInfo.class);
    }

    /**
     * The ektorp instance that MetaModel builds must use the tolerant object mapper factory.
     */
    @Test
    public void testMetaModelUsesTolerantObjectMapperFactory() throws Exception {
        final HttpClient httpClient = new StdHttpClient.Builder().host(HOST).port(PORT).build();
        try {
            final CouchDbDataContext dataContext = new CouchDbDataContext(httpClient);

            final ObjectMapperFactory objectMapperFactory =
                    ((StdCouchDbInstance) dataContext.getCouchDbInstance()).getObjectMapperFactory();
            assertTrue("Expected a CouchDbObjectMapperFactory, but got: " + objectMapperFactory.getClass(),
                    objectMapperFactory instanceof CouchDbObjectMapperFactory);

            // the mapper of the connector - the one that reads the database info - must tolerate
            // the opaque purge_seq
            final CouchDbConnector connector =
                    ((StdCouchDbInstance) dataContext.getCouchDbInstance()).createConnector(DATABASE_NAME, false);
            final ObjectMapper objectMapper = objectMapperFactory.createObjectMapper(connector);
            assertEquals(0, objectMapper.readValue(COUCHDB_23_DB_INFO_JSON, DbInfo.class).getPurgeSeq());
        } finally {
            httpClient.shutdown();
        }
    }

    /**
     * A live test against a CouchDB 2.3 server: inserting a row and counting the rows of the
     * table must both work.
     */
    @Test
    public void testLiveInsertAndRowCount() throws Exception {
        final HttpClient httpClient = new StdHttpClient.Builder().host(HOST).port(PORT).socketTimeout(8000).build();
        try {
            final StdCouchDbInstance instance = new StdCouchDbInstance(httpClient,
                    new CouchDbObjectMapperFactory());
            final List<String> databases;
            try {
                databases = instance.getAllDatabases();
            } catch (Exception e) {
                Assume.assumeTrue("No CouchDB server available at " + HOST + ":" + PORT + " (" + e.getMessage()
                        + ")", false);
                return;
            }

            final String databaseName = "metamodel_purge_seq_test";
            if (databases.contains(databaseName)) {
                instance.deleteDatabase(databaseName);
            }
            instance.createDatabase(databaseName);
            try {
                final SimpleTableDef tableDef = new SimpleTableDef(databaseName, new String[] { "name" },
                        new ColumnType[] { ColumnType.STRING });
                final CouchDbDataContext dataContext = new CouchDbDataContext(httpClient, tableDef);
                final Table table = dataContext.getTableByQualifiedLabel(databaseName);

                dataContext.executeUpdate(new UpdateScript() {
                    @Override
                    public void run(UpdateCallback callback) {
                        callback.insertInto(table).value("name", "foo").execute();
                    }
                });

                // this is the row count that used to fail with an InvalidFormatException
                final DataSet dataSet = dataContext.query().from(table).selectCount().execute();
                try {
                    assertTrue(dataSet.next());
                    assertEquals(1, ((Number) dataSet.getRow().getValue(0)).intValue());
                    assertFalse(dataSet.next());
                } finally {
                    dataSet.close();
                }
            } finally {
                instance.deleteDatabase(databaseName);
            }
        } finally {
            httpClient.shutdown();
        }
    }

    /**
     * Asserts that the given JSON is rejected by the given mapper.
     */
    private void assertRejected(ObjectMapper objectMapper, String json, Class<?> type) throws Exception {
        try {
            objectMapper.readValue(json, type);
            fail("Expected a JsonMappingException for: " + json);
        } catch (JsonMappingException e) {
            // expected
        }
    }

    /**
     * Creates the object mapper that the CouchDB adapter uses to read the database info, through
     * the public API of {@link CouchDbDataContext}.
     */
    private ObjectMapper createMetaModelObjectMapper() {
        final HttpClient httpClient = new StdHttpClient.Builder().host(HOST).port(PORT).build();
        try {
            final CouchDbDataContext dataContext = new CouchDbDataContext(httpClient);
            final StdCouchDbInstance instance = (StdCouchDbInstance) dataContext.getCouchDbInstance();
            final CouchDbConnector connector = instance.createConnector(DATABASE_NAME, false);
            return instance.getObjectMapperFactory().createObjectMapper(connector);
        } finally {
            httpClient.shutdown();
        }
    }
}
