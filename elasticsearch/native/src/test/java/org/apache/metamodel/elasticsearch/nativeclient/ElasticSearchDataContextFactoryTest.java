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
package org.apache.metamodel.elasticsearch.nativeclient;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.HashMap;
import java.util.Map;

import org.apache.metamodel.factory.DataContextPropertiesImpl;
import org.apache.metamodel.factory.UnsupportedDataContextPropertiesException;
import org.elasticsearch.common.settings.Settings;
import org.junit.Test;

/**
 * Tests that the Elasticsearch native (transport) data context factory only advertises the
 * configuration options that the Elasticsearch 7.x transport client actually honours.
 */
@SuppressWarnings("deprecation")
public class ElasticSearchDataContextFactoryTest {

    private static final String CLUSTER_NAME = "metamodel-test-cluster";

    private DataContextPropertiesImpl createTransportProperties() {
        final Map<String, Object> map = new HashMap<>();
        map.put("type", "es-transport");
        map.put("cluster", CLUSTER_NAME);
        map.put("hostname", "localhost");
        map.put("port", 9300);
        map.put("database", "my-index");
        return new DataContextPropertiesImpl(map);
    }

    /**
     * The settings that the transport client is built with must at least carry the cluster name -
     * that is the setting that the Elasticsearch 7.x transport client reads.
     */
    @Test
    public void testTransportSettingsCarryClusterName() {
        final Settings settings = new ElasticSearchDataContextFactory().createTransportSettings(createTransportProperties());
        assertEquals(CLUSTER_NAME, settings.get("cluster.name"));
    }

    /**
     * The Elasticsearch 7.x transport client rejects any setting that it does not know, so the
     * settings that the factory produces must only contain the ones that it accepts. Before the fix
     * the factory also put a "name" setting into the settings, which made every attempt to create a
     * transport data context fail with "unknown setting [name]".
     */
    @Test
    public void testTransportSettingsOnlyContainSettingsThatTheClientAccepts() {
        final Settings settings = new ElasticSearchDataContextFactory().createTransportSettings(createTransportProperties());

        assertEquals(1, settings.size());
        assertEquals(CLUSTER_NAME, settings.get("cluster.name"));
        assertNull(settings.get("name"));
    }

    /**
     * The transport client cannot authenticate a connection, so the "username" property must not be
     * accepted. Before the fix it was silently ignored, which left the user with a plain text,
     * unauthenticated connection while they believed that it was protected.
     */
    @Test
    public void testUsernamePropertyIsRejected() {
        final DataContextPropertiesImpl properties = createTransportProperties();
        properties.put("username", "elastic");

        try {
            new ElasticSearchDataContextFactory().create(properties, null);
            fail("Exception expected");
        } catch (UnsupportedDataContextPropertiesException e) {
            final String message = e.getMessage();
            assertTrue(message, message.contains("username"));
            assertTrue(message, message.contains("es-rest"));
        }
    }

    /**
     * Same as {@link #testUsernamePropertyIsRejected()} for the password.
     */
    @Test
    public void testPasswordPropertyIsRejected() {
        final DataContextPropertiesImpl properties = createTransportProperties();
        properties.put("password", "s3cret");

        try {
            new ElasticSearchDataContextFactory().create(properties, null);
            fail("Exception expected");
        } catch (UnsupportedDataContextPropertiesException e) {
            final String message = e.getMessage();
            assertTrue(message, message.contains("password"));
        }
    }

    /**
     * The transport client contains no code that reads the "ssl" / "keystorePath" /
     * "keystorePassword" settings, so those must be rejected rather than silently ignored.
     */
    @Test
    public void testSslAndKeystorePropertiesAreRejected() {
        for (final String propertyName : new String[] { "ssl", "keystorePath", "keystorePassword" }) {
            final DataContextPropertiesImpl properties = createTransportProperties();
            properties.put(propertyName, propertyName.equals("ssl") ? Boolean.TRUE : "/tmp/whatever");

            try {
                new ElasticSearchDataContextFactory().create(properties, null);
                fail("Exception expected for property " + propertyName);
            } catch (UnsupportedDataContextPropertiesException e) {
                final String message = e.getMessage();
                assertTrue(message, message.contains(propertyName));
            }
        }
    }

    /**
     * A configuration that only uses the supported properties must still be accepted.
     */
    @Test
    public void testSupportedPropertiesAreNotRejected() {
        final DataContextPropertiesImpl properties = createTransportProperties();

        final Settings settings = new ElasticSearchDataContextFactory().createTransportSettings(properties);

        assertEquals(CLUSTER_NAME, settings.get("cluster.name"));
        assertNull(settings.get("username"));
        assertNull(settings.get("password"));
        assertNull(settings.get("xpack.security.transport.ssl.enabled"));
    }

    /**
     * Creates the properties of a configuration that asks for the Elasticsearch "node" client.
     *
     * @param dataContextType the data context type to configure
     * @return the data context properties
     */
    private DataContextPropertiesImpl createNodeProperties(String dataContextType) {
        final Map<String, Object> map = new HashMap<>();
        map.put("type", dataContextType);
        map.put("clientType", "node");
        map.put("cluster", CLUSTER_NAME);
        map.put("database", "my-index");
        // no "hostname" and no "port" - a node client does not connect to a remote host
        return new DataContextPropertiesImpl(map);
    }

    /**
     * The Elasticsearch "node" client cannot be created in Elasticsearch 7.x - it was an
     * in-process client, which a remote client cannot obtain. Before the fix this accepted
     * configuration failed with a raw NullPointerException, because no port was configured for
     * the node client type and the port was auto-unboxed. It must now fail with an
     * explanatory error that names the transport client and the REST based data context as the
     * alternatives.
     */
    @Test
    public void testNodeClientTypeIsRejectedWithAnExplanatoryError() {
        for (final String dataContextType : new String[] { "elasticsearch", "es-node", "elasticsearch-node" }) {
            final DataContextPropertiesImpl properties = createNodeProperties(dataContextType);
            final ElasticSearchDataContextFactory factory = new ElasticSearchDataContextFactory();

            // the factory still claims these data context types, so that the user gets the
            // explanatory error instead of a generic "no factory found" error
            assertTrue("Expected the factory to accept the data context type " + dataContextType,
                    factory.accepts(properties, null));

            try {
                factory.create(properties, null);
                fail("Exception expected for the data context type " + dataContextType);
            } catch (UnsupportedDataContextPropertiesException e) {
                final String message = e.getMessage();
                assertTrue(message, message.contains("node"));
                assertTrue(message, message.contains("es-transport"));
                assertTrue(message, message.contains("elasticsearch-transport"));
                assertTrue(message, message.contains("es-rest"));
                assertTrue(message, message.contains("elasticsearch-rest"));
                // the NullPointerException that the user would otherwise get is only a symptom
                // of the unsupported client type, and the message says so
                assertTrue(message, message.contains("NullPointerException"));
            } catch (NullPointerException e) {
                fail("Expected an UnsupportedDataContextPropertiesException, but got a NullPointerException: " + e);
            }
        }
    }

    /**
     * The transport client needs a port to connect to. Before the fix the missing port was
     * auto-unboxed into a NullPointerException; the missing property must now be named.
     */
    @Test
    public void testTransportClientTypeWithoutAPortIsRejected() {
        final Map<String, Object> map = new HashMap<>();
        map.put("type", "elasticsearch");
        map.put("clientType", "transport");
        map.put("cluster", CLUSTER_NAME);
        map.put("database", "my-index");
        map.put("hostname", "localhost");
        // no "port"

        final DataContextPropertiesImpl properties = new DataContextPropertiesImpl(map);

        try {
            new ElasticSearchDataContextFactory().create(properties, null);
            fail("Exception expected");
        } catch (UnsupportedDataContextPropertiesException e) {
            final String message = e.getMessage();
            assertTrue(message, message.contains("\"port\""));
        } catch (NullPointerException e) {
            fail("Expected an UnsupportedDataContextPropertiesException, but got a NullPointerException: " + e);
        }
    }

    /**
     * The transport client needs a hostname to connect to. Before the fix the missing hostname
     * was not detected at all - the client silently connected to the loopback address instead -
     * and the missing property must now be named.
     */
    @Test
    public void testTransportClientTypeWithoutAHostnameIsRejected() {
        final Map<String, Object> map = new HashMap<>();
        map.put("type", "elasticsearch");
        map.put("clientType", "transport");
        map.put("cluster", CLUSTER_NAME);
        map.put("database", "my-index");
        map.put("port", 9300);
        // no "hostname"

        final DataContextPropertiesImpl properties = new DataContextPropertiesImpl(map);

        try {
            new ElasticSearchDataContextFactory().create(properties, null);
            fail("Exception expected");
        } catch (UnsupportedDataContextPropertiesException e) {
            final String message = e.getMessage();
            assertTrue(message, message.contains("\"hostname\""));
        } catch (NullPointerException e) {
            fail("Expected an UnsupportedDataContextPropertiesException, but got a NullPointerException: " + e);
        }
    }
}
