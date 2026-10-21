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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

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
import org.junit.Test;

/**
 * Tests that the HTTP resources that are used to talk to Neo4j are released.
 * <p>
 * A {@link CloseableHttpResponse} that is not closed keeps its connection checked out
 * of the connection pool. Once the pool is exhausted, the client blocks instead of
 * returning a result.
 */
public class Neo4jRequestWrapperResourceTest {

    private static final String HOSTNAME = "localhost";
    private static final int PORT = 7474;
    private static final String SERVICE_ROOT = "/db/data";

    /**
     * The response of a Cypher query must be closed once it has been consumed.
     */
    @Test
    public void testResponseIsClosedAfterCypherQuery() {
        final AtomicBoolean closed = new AtomicBoolean(false);
        final Neo4jRequestWrapper wrapper = createWrapper(closed, "MockContent for BasicHttpEntity");

        final String result = wrapper.executeCypherQuery("MATCH (n) RETURN n;");

        assertEquals("MockContent for BasicHttpEntity", result);
        assertTrue("The HTTP response must be closed so that the connection is released", closed.get());
    }

    /**
     * The response must also be closed when it does not carry an entity.
     */
    @Test
    public void testResponseIsClosedWhenEntityIsNull() {
        final AtomicBoolean closed = new AtomicBoolean(false);
        final Neo4jRequestWrapper wrapper = createWrapper(closed, null);

        final String result = wrapper.executeCypherQuery("MATCH (n) RETURN n;");

        assertNull(result);
        assertTrue("The HTTP response must be closed so that the connection is released", closed.get());
    }

    private Neo4jRequestWrapper createWrapper(final AtomicBoolean closed, final String content) {
        final CloseableHttpClient mockHttpClient = new CloseableHttpClient() {

            @Override
            public void close() throws IOException {
                // Do nothing
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
            protected CloseableHttpResponse doExecute(HttpHost target, HttpRequest request, HttpContext context)
                    throws IOException, ClientProtocolException {
                return new MockClosableHttpResponse(HttpVersion.HTTP_1_1, 200, "OK", content, closed);
            }
        };

        return new Neo4jRequestWrapper(mockHttpClient, new HttpHost(HOSTNAME, PORT), "testUsername", "testPassword",
                SERVICE_ROOT);
    }

    private static class MockClosableHttpResponse extends BasicHttpResponse implements CloseableHttpResponse {

        private final String _content;
        private final AtomicBoolean _closed;

        public MockClosableHttpResponse(ProtocolVersion ver, int code, String reason, String content,
                AtomicBoolean closed) {
            super(ver, code, reason);
            _content = content;
            _closed = closed;
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
            _closed.set(true);
        }
    }
}
