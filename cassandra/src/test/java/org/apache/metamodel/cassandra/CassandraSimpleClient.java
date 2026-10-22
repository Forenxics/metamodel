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

import java.time.Duration;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.CqlSessionBuilder;
import com.datastax.oss.driver.api.core.config.DefaultDriverOption;
import com.datastax.oss.driver.api.core.config.DriverConfigLoader;

/**
 * Utility test class that provides a handy way to
 * connect to a Cassandra cluster through the
 * {@link CqlSession} class.
 *
 * To get a connected instance of the session you
 * should call {@link #connect(String, int, String)}
 * providing the node, port and local datacenter of your
 * Cassandra cluster, then you can get the connected instance
 * by calling {@link #getSession()}.
 *
 */
public class CassandraSimpleClient {

    /**
     * The driver 4.x defaults to a request timeout of 2 seconds, which is too
     * short for the schema changing statements that the tests use to set up
     * their fixtures.
     */
    public static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(60);

    private CqlSession session;

    public void connect(String node, int port, String localDatacenter) {
        connect(node, port, localDatacenter, DEFAULT_REQUEST_TIMEOUT);
    }

    public void connect(String node, int port, String localDatacenter, Duration requestTimeout) {
        final CqlSessionBuilder builder = CqlSession.builder()
                .addContactPoint(new java.net.InetSocketAddress(node, port))
                .withConfigLoader(DriverConfigLoader.programmaticBuilder()
                        .withDuration(DefaultDriverOption.REQUEST_TIMEOUT, requestTimeout).build())
                .withLocalDatacenter(localDatacenter);
        session = builder.build();
    }

    public CqlSession getSession() {
        return session;
    }

    public void close() {
        if (session != null) {
            session.close();
            session = null;
        }
    }
}
