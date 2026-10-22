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
package org.apache.metamodel.neo4j;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.apache.http.HttpHost;
import org.apache.http.HttpRequest;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.conn.ClientConnectionManager;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.params.HttpParams;
import org.apache.http.protocol.HttpContext;
import org.apache.metamodel.schema.ColumnType;
import org.apache.metamodel.util.SimpleTableDef;
import org.junit.Test;

/**
 * Tests that {@link Neo4jDataContext} closes the HTTP client that it created - and only that one.
 */
public class Neo4jDataContextCloseTest {

    private static final SimpleTableDef[] TABLE_DEFS = new SimpleTableDef[] {
            new SimpleTableDef("JUnitLabel", new String[] { "property1" }, new ColumnType[] { ColumnType.INTEGER }) };

    /**
     * An HTTP client that records whether it has been closed.
     */
    private static class RecordingHttpClient extends CloseableHttpClient {

        private boolean _closed;

        @Override
        protected CloseableHttpResponse doExecute(HttpHost target, HttpRequest request, HttpContext context) {
            throw new UnsupportedOperationException("No requests are expected in this test");
        }

        @Override
        public HttpParams getParams() {
            throw new UnsupportedOperationException("No requests are expected in this test");
        }

        @Override
        public ClientConnectionManager getConnectionManager() {
            throw new UnsupportedOperationException("No requests are expected in this test");
        }

        @Override
        public void close() {
            _closed = true;
        }

        public boolean isClosed() {
            return _closed;
        }
    }

    /**
     * A data context that creates a recording HTTP client instead of a real one. The client is
     * collected in a static list, because {@link Neo4jDataContext#createHttpClient()} is called from
     * the constructor of the superclass - before the fields of this subclass are initialized.
     */
    private static class TestableNeo4jDataContext extends Neo4jDataContext {

        private static final List<RecordingHttpClient> CREATED_CLIENTS = new ArrayList<>();

        TestableNeo4jDataContext(final String hostname, final int port, final String username, final String password,
                final SimpleTableDef... tableDefs) {
            super(hostname, port, username, password, tableDefs);
        }

        @Override
        CloseableHttpClient createHttpClient() {
            final RecordingHttpClient client = new RecordingHttpClient();
            CREATED_CLIENTS.add(client);
            return client;
        }
    }

    /**
     * The HTTP client that the context creates itself must be closed by {@link Neo4jDataContext#close()}.
     * Before the fix the client - and its connection pool - was never closed.
     */
    @Test
    public void testCloseClosesTheHttpClientThatTheContextCreated() throws Exception {
        final TestableNeo4jDataContext context =
                new TestableNeo4jDataContext("localhost", 7474, "neo4j", "password", TABLE_DEFS);

        assertEquals(1, TestableNeo4jDataContext.CREATED_CLIENTS.size());
        final RecordingHttpClient client = TestableNeo4jDataContext.CREATED_CLIENTS.get(0);
        assertFalse(client.isClosed());

        context.close();

        assertTrue("Expected the HTTP client created by the data context to be closed", client.isClosed());
    }

    /**
     * An HTTP client that was handed to the data context by the caller is owned by the caller, and
     * must therefore not be closed by the data context.
     */
    @Test
    public void testCloseDoesNotCloseACallerSuppliedHttpClient() throws Exception {
        final RecordingHttpClient client = new RecordingHttpClient();

        final Neo4jDataContext context = new Neo4jDataContext("localhost", 7474, client, TABLE_DEFS);
        assertNotNull(context);

        context.close();

        assertFalse("Expected a caller supplied HTTP client not to be closed", client.isClosed());
    }

    /**
     * Closing the data context twice must not fail.
     */
    @Test
    public void testCloseIsIdempotent() throws Exception {
        final TestableNeo4jDataContext context =
                new TestableNeo4jDataContext("localhost", 7474, "neo4j", "password", TABLE_DEFS);

        context.close();
        context.close();

        assertTrue(TestableNeo4jDataContext.CREATED_CLIENTS.get(0).isClosed());
    }
}
