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

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.apache.metamodel.ConnectionException;
import org.apache.metamodel.DataContext;
import org.apache.metamodel.factory.DataContextFactory;
import org.apache.metamodel.factory.DataContextProperties;
import org.apache.metamodel.factory.ResourceFactoryRegistry;
import org.apache.metamodel.factory.UnsupportedDataContextPropertiesException;
import org.apache.metamodel.util.SimpleTableDef;
import org.elasticsearch.client.Client;
import org.elasticsearch.client.transport.TransportClient;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.transport.TransportAddress;
import org.elasticsearch.transport.client.PreBuiltTransportClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Factory for ElasticSearch data context of native type.
 * 
 * The factory will activate when DataContext type is specified as
 * "elasticsearch", "es-node", "elasticsearch-node", "es-transport",
 * "elasticsearch-transport".
 * 
 * This factory is configured with the following properties:
 * 
 * <ul>
 * <li>clientType (needed if datacontext type is just "elasticsearch" - must be
 * either "transport" or "node")</li>
 * <li>hostname (if clientType is "transport")</li>
 * <li>port (if clientType is "transport")</li>
 * <li>database (index name)</li>
 * <li>cluster</li>
 * </ul>
 * 
 * <p>
 * <b>Security note:</b> the Elasticsearch 7.x transport client supports
 * <em>neither authentication nor TLS</em>. It has no supported user name /
 * password authentication, and it contains no code that reads the
 * <code>xpack.security.*</code> or <code>ssl.*</code> settings - the transport
 * connection is therefore always plain text and unauthenticated. The
 * <code>username</code>, <code>password</code>, <code>ssl</code>,
 * <code>keystorePath</code> and <code>keystorePassword</code> properties are
 * consequently <em>not</em> supported: they are rejected with an
 * {@link UnsupportedDataContextPropertiesException} instead of being silently
 * ignored, because a user who sets them would otherwise wrongly believe that
 * the connection is protected. If you need credentials or an encrypted
 * connection, use the REST based data context instead (data context types
 * "es-rest" / "elasticsearch-rest", implemented by
 * <code>ElasticSearchRestDataContextFactory</code> in the elasticsearch-rest
 * module), which supports the "username" and "password" properties and
 * "https" URLs.
 * 
 * @deprecated {@link TransportClient} on which this implementation is based is deprecated in Elasticsearch 7.x and will
 *             be removed in Elasticsearch 8. Please use ElasticSearchRestDataContext instead.
 */
@Deprecated
public class ElasticSearchDataContextFactory implements DataContextFactory {
    private static final Logger logger = LoggerFactory.getLogger(ElasticSearchDataContextFactory.class);

    /**
     * Properties that the Elasticsearch 7.x transport client cannot honour. See the class javadoc
     * for the details.
     */
    private static final List<String> UNSUPPORTED_TRANSPORT_PROPERTIES = Arrays.asList("username", "password", "ssl",
            "keystorePath", "keystorePassword");

    @Override
    public boolean accepts(DataContextProperties properties, ResourceFactoryRegistry resourceFactoryRegistry) {
        switch (properties.getDataContextType()) {
        case "elasticsearch":
        case "es-node":
        case "elasticsearch-node":
        case "es-transport":
        case "elasticsearch-transport":
            return acceptsInternal(properties);
        }
        return false;
    }

    private boolean acceptsInternal(DataContextProperties properties) {
        final String clientType = getClientType(properties);
        if (clientType == null) {
            return false;
        }
        if (!"node".equals(clientType)) {
            if (properties.getHostname() == null || properties.getPort() == null) {
                return false;
            }
        }
        if (getIndex(properties) == null) {
            return false;
        }
        if (getCluster(properties) == null) {
            return false;
        }
        return true;
    }

    private String getClientType(DataContextProperties properties) {
        switch (properties.getDataContextType()) {
        case "elasticsearch-node":
        case "es-node":
            return "node";
        case "elasticsearch-transport":
        case "es-transport":
            return "transport";
        }
        final String clientType = (String) properties.toMap().get("clientType");
        return clientType;
    }

    private String getIndex(DataContextProperties properties) {
        final String databaseName = properties.getDatabaseName();
        if (databaseName == null) {
            return (String) properties.toMap().get("index");
        }
        return databaseName;
    }

    private String getCluster(DataContextProperties properties) {
        return (String) properties.toMap().get("cluster");
    }

    @Override
    public DataContext create(DataContextProperties properties, ResourceFactoryRegistry resourceFactoryRegistry)
            throws UnsupportedDataContextPropertiesException, ConnectionException {
        rejectUnsupportedTransportProperties(properties);

        final Client client = createTransportClient(properties);
        final String indexName = getIndex(properties);
        final SimpleTableDef[] tableDefinitions = properties.getTableDefs();
        return new ElasticSearchDataContext(client, indexName, tableDefinitions);
    }

    /**
     * Rejects the properties that the transport client cannot honour. Silently ignoring them would
     * leave the user with an unprotected connection while they believe that the connection is
     * protected.
     * 
     * @param properties the data context properties
     */
    private void rejectUnsupportedTransportProperties(DataContextProperties properties) {
        final List<String> present = new ArrayList<>();
        for (final String propertyName : UNSUPPORTED_TRANSPORT_PROPERTIES) {
            if (isPropertyPresent(properties, propertyName)) {
                present.add(propertyName);
            }
        }
        if (present.isEmpty()) {
            return;
        }
        throw new UnsupportedDataContextPropertiesException("The Elasticsearch transport client does not support the "
                + "propert" + (present.size() == 1 ? "y" : "ies") + " " + present
                + ". The Elasticsearch transport client cannot authenticate a connection and cannot use TLS, so these "
                + "properties would be silently ignored and the transport connection would remain plain text and "
                + "unauthenticated. Use the REST based data context (\"es-rest\" / \"elasticsearch-rest\") instead, "
                + "which supports the \"username\" and \"password\" properties and \"https\" URLs.");
    }

    /**
     * Checks whether a property was configured. Both the raw property map and the typed accessors are
     * consulted, so that a custom {@link DataContextProperties} implementation is covered as well.
     * 
     * @param properties the data context properties
     * @param propertyName the name of the property
     * @return true if the property was given a value
     */
    private boolean isPropertyPresent(DataContextProperties properties, String propertyName) {
        if (properties.toMap().get(propertyName) != null) {
            return true;
        }
        if ("username".equals(propertyName)) {
            return properties.getUsername() != null;
        }
        if ("password".equals(propertyName)) {
            return properties.getPassword() != null;
        }
        return false;
    }

    /**
     * Creates the {@link Settings} used by the transport client. These are the only settings that the
     * Elasticsearch 7.x transport client honours - any other setting is rejected by the client with
     * an "unknown setting [x]" error.
     *
     * @param properties the data context properties
     * @return the settings for the transport client
     */
    Settings createTransportSettings(DataContextProperties properties) {
        return Settings
                .builder()
                .put("cluster.name", getCluster(properties))
                .build();
    }

    private Client createTransportClient(DataContextProperties properties) {
        final Settings settings = createTransportSettings(properties);
        final TransportClient client = new PreBuiltTransportClient(settings);
        try {
            client
                    .addTransportAddress(new TransportAddress(InetAddress.getByName(properties.getHostname()),
                            properties.getPort()));
        } catch (UnknownHostException e) {
            logger.warn("no IP address for the host with name \"{}\" could be found.", properties.getHostname());
        }
        return client;
    }

}
