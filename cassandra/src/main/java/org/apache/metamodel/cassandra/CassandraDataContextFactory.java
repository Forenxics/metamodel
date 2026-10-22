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

import java.util.Map;

import org.apache.metamodel.ConnectionException;
import org.apache.metamodel.DataContext;
import org.apache.metamodel.factory.AbstractDataContextFactory;
import org.apache.metamodel.factory.DataContextProperties;
import org.apache.metamodel.factory.ResourceFactoryRegistry;
import org.apache.metamodel.factory.UnsupportedDataContextPropertiesException;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.CqlSessionBuilder;

public class CassandraDataContextFactory extends AbstractDataContextFactory {

    /**
     * The name of the property that holds the name of the datacenter that is
     * local to the client. The 4.x driver refuses to start a session without it.
     */
    public static final String LOCAL_DATACENTER_PROPERTY = "localDatacenter";

    @Override
    protected String getType() {
        return "cassandra";
    }

    @Override
    public DataContext create(DataContextProperties properties, ResourceFactoryRegistry resourceFactoryRegistry)
            throws UnsupportedDataContextPropertiesException, ConnectionException {

        final Map<String, Object> map = properties.toMap();
        final CqlSessionBuilder sessionBuilder = CqlSession.builder();

        final String hostname = properties.getHostname();
        if (hostname != null && !hostname.trim().isEmpty()) {
            final int port = properties.getPort() == null ? CassandraDataContext.DEFAULT_PORT
                    : properties.getPort().intValue();
            sessionBuilder.addContactPoints(CassandraDataContext.parseContactPoints(hostname, port));
        }

        // The 4.x driver will not start a session without being told which
        // datacenter is local to the client.
        final String localDatacenter = getString(map.get(LOCAL_DATACENTER_PROPERTY),
                CassandraDataContext.DEFAULT_LOCAL_DATACENTER);
        sessionBuilder.withLocalDatacenter(localDatacenter);

        if (map.containsKey("cluster-name")) {
            // The 4.x driver has no notion of a cluster name - the name of the
            // cluster is read-only metadata - so it is used as the name that this
            // client announces to the server.
            sessionBuilder.withApplicationName((String) map.get("cluster-name"));
        }

        if (properties.getUsername() != null && properties.getPassword() != null) {
            sessionBuilder.withAuthCredentials(properties.getUsername(), properties.getPassword());
        }

        final CqlSession session = sessionBuilder.build();

        final String keySpace = getString(map.get("keyspace"), properties.getDatabaseName());

        // The session was created here, so the context takes ownership of it and
        // releases it when the context is closed.
        return new CassandraDataContext(session, keySpace, true, properties.getTableDefs());
    }

}
