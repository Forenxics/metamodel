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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.apache.http.HttpEntity;
import org.apache.http.HttpHost;
import org.apache.http.HttpRequest;
import org.apache.http.HttpVersion;
import org.apache.http.ProtocolVersion;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.conn.ClientConnectionManager;
import org.apache.http.entity.BasicHttpEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.message.BasicHttpResponse;
import org.apache.http.params.HttpParams;
import org.apache.http.protocol.HttpContext;
import org.apache.metamodel.data.DataSet;
import org.apache.metamodel.query.CompiledQuery;
import org.apache.metamodel.schema.ColumnType;
import org.apache.metamodel.util.SimpleTableDef;
import org.junit.Test;

/**
 * Tests that the {@code serviceRoot} that is handed to a {@link Neo4jDataContext} constructor is
 * actually used for the requests that the context sends to Neo4j.
 * <p>
 * Before the fix the request wrapper was created <em>before</em> the caller's service root was
 * assigned to the context, so the wrapper was always built with the default service root
 * ({@code /db/data}) and the caller's argument was silently ignored.
 */
public class Neo4jDataContextServiceRootTest {

    private static final String DEFAULT_SERVICE_ROOT = "/db/data";
    private static final String CUSTOM_SERVICE_ROOT = "/custom/neo4j/root";

    private static final SimpleTableDef[] TABLE_DEFS = new SimpleTableDef[] {
            new SimpleTableDef("JUnitLabel", new String[] { "property1" }, new ColumnType[] { ColumnType.INTEGER }) };

    /**
     * The service root that is passed to the constructor must be used for the Cypher transaction
     * endpoint.
     */
    @Test
    public void testCustomServiceRootIsUsedForCypherTransactions() {
        final RecordingHttpClient httpClient = new RecordingHttpClient();
        final Neo4jDataContext context =
                new Neo4jDataContext("localhost", 7474, httpClient, CUSTOM_SERVICE_ROOT, TABLE_DEFS);

        executeSelectQuery(context);

        assertEquals("Expected the Cypher transaction to be sent to the configured service root, but the requests "
                + "were: " + httpClient.getRequestUris(),
                Arrays.asList(CUSTOM_SERVICE_ROOT + "/transaction/commit"), httpClient.getRequestUris());
    }

    /**
     * The service root that is passed to the constructor must also be used when the context detects
     * the labels of the database.
     */
    @Test
    public void testCustomServiceRootIsUsedForLabelDetection() {
        final RecordingHttpClient httpClient = new RecordingHttpClient();
        new Neo4jDataContext("localhost", 7474, httpClient, CUSTOM_SERVICE_ROOT);

        assertEquals("Expected the label detection to use the configured service root, but the requests were: "
                + httpClient.getRequestUris(), Arrays.asList(CUSTOM_SERVICE_ROOT + "/labels"),
                httpClient.getRequestUris());
    }

    /**
     * The same must hold for the constructors that take a username and a password.
     */
    @Test
    public void testCustomServiceRootIsUsedByTheUsernameAndPasswordConstructor() {
        final RecordingHttpClient httpClient = new RecordingHttpClient();
        TestableNeo4jDataContext.setRecordingHttpClient(httpClient);

        final Neo4jDataContext context = new TestableNeo4jDataContext("localhost", 7474, "neo4j", "password",
                CUSTOM_SERVICE_ROOT, TABLE_DEFS);
        executeSelectQuery(context);

        assertEquals("Expected the Cypher transaction to be sent to the configured service root, but the requests "
                + "were: " + httpClient.getRequestUris(),
                Arrays.asList(CUSTOM_SERVICE_ROOT + "/transaction/commit"), httpClient.getRequestUris());
    }

    /**
     * ...also when that constructor detects the labels of the database.
     */
    @Test
    public void testCustomServiceRootIsUsedForLabelDetectionByTheUsernameAndPasswordConstructor() {
        final RecordingHttpClient httpClient = new RecordingHttpClient();
        TestableNeo4jDataContext.setRecordingHttpClient(httpClient);

        new TestableNeo4jDataContext("localhost", 7474, "neo4j", "password", CUSTOM_SERVICE_ROOT);

        assertEquals("Expected the label detection to use the configured service root, but the requests were: "
                + httpClient.getRequestUris(), Arrays.asList(CUSTOM_SERVICE_ROOT + "/labels"),
                httpClient.getRequestUris());
    }

    /**
     * The constructors that do <em>not</em> take a service root must keep using the default one.
     */
    @Test
    public void testDefaultServiceRootIsUsedWhenNoServiceRootIsGiven() {
        final RecordingHttpClient httpClient = new RecordingHttpClient();
        final Neo4jDataContext context = new Neo4jDataContext("localhost", 7474, httpClient, TABLE_DEFS);

        executeSelectQuery(context);

        assertEquals("Expected the default service root to be used, but the requests were: "
                + httpClient.getRequestUris(), Arrays.asList(DEFAULT_SERVICE_ROOT + "/transaction/commit"),
                httpClient.getRequestUris());
    }

    /**
     * ...also for the label detection.
     */
    @Test
    public void testDefaultServiceRootIsUsedForLabelDetectionWhenNoServiceRootIsGiven() {
        final RecordingHttpClient httpClient = new RecordingHttpClient();
        new Neo4jDataContext("localhost", 7474, httpClient);

        assertEquals("Expected the default service root to be used, but the requests were: "
                + httpClient.getRequestUris(), Arrays.asList(DEFAULT_SERVICE_ROOT + "/labels"),
                httpClient.getRequestUris());
    }

    /**
     * ...and for the constructors that take a username and a password.
     */
    @Test
    public void testDefaultServiceRootIsUsedByTheUsernameAndPasswordConstructor() {
        final RecordingHttpClient httpClient = new RecordingHttpClient();
        TestableNeo4jDataContext.setRecordingHttpClient(httpClient);

        final Neo4jDataContext context =
                new TestableNeo4jDataContext("localhost", 7474, "neo4j", "password", TABLE_DEFS);
        executeSelectQuery(context);

        assertEquals("Expected the default service root to be used, but the requests were: "
                + httpClient.getRequestUris(), Arrays.asList(DEFAULT_SERVICE_ROOT + "/transaction/commit"),
                httpClient.getRequestUris());
    }

    /**
     * Executes a query that makes the context send a Cypher query to Neo4j. The rows of the
     * response are irrelevant - the request that is sent is what is being asserted upon.
     *
     * @param context the context to query
     */
    private void executeSelectQuery(final Neo4jDataContext context) {
        final CompiledQuery query = context.query().from("JUnitLabel").select("property1").compile();
        try (final DataSet dataSet = context.executeQuery(query)) {
            // intentionally empty - the request is what matters here
        }
    }

    /**
     * A data context that uses a recording HTTP client instead of a real one. The client is held in
     * a static field, because {@link Neo4jDataContext#createHttpClient()} is called from the
     * constructor of the superclass - before the fields of this subclass are initialized.
     */
    private static class TestableNeo4jDataContext extends Neo4jDataContext {

        private static RecordingHttpClient _recordingHttpClient;

        static void setRecordingHttpClient(final RecordingHttpClient httpClient) {
            _recordingHttpClient = httpClient;
        }

        TestableNeo4jDataContext(final String hostname, final int port, final String username, final String password,
                final String serviceRoot, final SimpleTableDef... tableDefs) {
            super(hostname, port, username, password, serviceRoot, tableDefs);
        }

        TestableNeo4jDataContext(final String hostname, final int port, final String username, final String password,
                final String serviceRoot) {
            super(hostname, port, username, password, serviceRoot);
        }

        TestableNeo4jDataContext(final String hostname, final int port, final String username, final String password,
                final SimpleTableDef... tableDefs) {
            super(hostname, port, username, password, tableDefs);
        }

        @Override
        CloseableHttpClient createHttpClient() {
            return _recordingHttpClient;
        }
    }

    /**
     * An HTTP client that records the URI of every request it is asked to execute and that answers
     * with a canned Neo4j-like response.
     */
    private static class RecordingHttpClient extends CloseableHttpClient {

        private final List<String> _requestUris = new ArrayList<>();

        @Override
        protected CloseableHttpResponse doExecute(final HttpHost target, final HttpRequest request,
                final HttpContext context) throws IOException, ClientProtocolException {
            _requestUris.add(request.getRequestLine().getUri());
            final String responseBody = "GET".equals(request.getRequestLine().getMethod()) ? "[]"
                    : "{\"results\":[],\"errors\":[]}";
            return new MockClosableHttpResponse(HttpVersion.HTTP_1_1, 200, "OK", responseBody);
        }

        @Override
        public HttpParams getParams() {
            return null;
        }

        @Override
        public ClientConnectionManager getConnectionManager() {
            return null;
        }

        @Override
        public void close() {
            // Do nothing
        }

        List<String> getRequestUris() {
            return _requestUris;
        }
    }

    private static class MockClosableHttpResponse extends BasicHttpResponse implements CloseableHttpResponse {

        private final String _content;

        public MockClosableHttpResponse(final ProtocolVersion ver, final int code, final String reason,
                final String content) {
            super(ver, code, reason);
            _content = content;
        }

        @Override
        public HttpEntity getEntity() {
            if (_content == null) {
                return null;
            }
            final BasicHttpEntity basicHttpEntity = new BasicHttpEntity();
            basicHttpEntity.setContent(new ByteArrayInputStream(_content.getBytes(StandardCharsets.UTF_8)));
            return basicHttpEntity;
        }

        @Override
        public void close() throws IOException {
            // Do nothing
        }
    }
}
