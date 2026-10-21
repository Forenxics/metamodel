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
package org.apache.metamodel.elasticsearch.rest;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.apache.http.HttpHost;
import org.apache.metamodel.factory.DataContextProperties;
import org.apache.metamodel.factory.DataContextPropertiesImpl;
import org.elasticsearch.client.RestHighLevelClient;
import org.junit.Test;

/**
 * Tests the configuration handling of {@link ElasticSearchRestDataContextFactory} that
 * can be verified without a running ElasticSearch instance.
 */
public class ElasticSearchRestDataContextFactoryTest {

    private DataContextProperties properties(String url, String index, String database) {
        final Map<String, Object> map = new HashMap<String, Object>();
        map.put("type", "elasticsearch");
        map.put("url", url);
        if (index != null) {
            map.put("index", index);
        }
        if (database != null) {
            map.put("database", database);
        }
        return new DataContextPropertiesImpl(map);
    }

    /**
     * The scheme of the configured URL must be carried over to the HTTP host. Before the
     * fix the scheme was dropped, which silently downgraded https:// connections to
     * plain http:// - sending the Basic Authentication credentials in clear text.
     */
    @Test
    public void testHttpsSchemeIsPreserved() throws Exception {
        assertScheme("https", "https://localhost:9200");
    }

    @Test
    public void testHttpSchemeIsPreserved() throws Exception {
        assertScheme("http", "http://localhost:9200");
    }

    private void assertScheme(String expectedScheme, String url) throws Exception {
        final ElasticSearchRestDataContextFactory factory = new ElasticSearchRestDataContextFactory();
        final RestHighLevelClient client = factory.createClient(properties(url, "myindex", null));
        try {
            final HttpHost host = client.getLowLevelClient().getNodes().get(0).getHost();
            assertEquals(expectedScheme, host.getSchemeName());
            assertEquals("localhost", host.getHostName());
            assertEquals(9200, host.getPort());
        } finally {
            client.close();
        }
    }

    /**
     * The 'index' property is a documented alternative to the 'database' property.
     */
    @Test
    public void testIndexPropertyIsResolved() {
        final ElasticSearchRestDataContextFactory factory = new ElasticSearchRestDataContextFactory();
        assertEquals("myindex", factory.getIndex(properties("http://localhost:9200", "myindex", null)));
    }

    @Test
    public void testDatabasePropertyTakesPrecedenceOverIndexProperty() {
        final ElasticSearchRestDataContextFactory factory = new ElasticSearchRestDataContextFactory();
        assertEquals("mydatabase", factory.getIndex(properties("http://localhost:9200", "myindex", "mydatabase")));
    }

    @Test
    public void testNoIndexPropertyResolvesToNull() {
        final ElasticSearchRestDataContextFactory factory = new ElasticSearchRestDataContextFactory();
        assertNull(factory.getIndex(properties("http://localhost:9200", null, null)));
    }

    /**
     * A configuration that only specifies an 'index' (and no 'database') must be accepted
     * by the factory.
     */
    @Test
    public void testAcceptsIndexPropertyOnly() {
        final ElasticSearchRestDataContextFactory factory = new ElasticSearchRestDataContextFactory();
        assertTrue(factory.accepts(properties("http://localhost:9200", "myindex", null), null));
    }

    @Test
    public void testDoesNotAcceptWithoutIndexNorDatabase() {
        final ElasticSearchRestDataContextFactory factory = new ElasticSearchRestDataContextFactory();
        assertFalse(factory.accepts(properties("http://localhost:9200", null, null), null));
    }
}
