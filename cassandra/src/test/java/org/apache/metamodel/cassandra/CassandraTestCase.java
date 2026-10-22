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

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Base class for the Cassandra module's tests.
 * <p>
 * The tests are integration tests: they connect to a <em>real</em> Cassandra
 * server. The connection details are read from the same configuration file that
 * the other MetaModel integration tests use, namely
 * <code>~/metamodel-integrationtest-configuration.properties</code>:
 * </p>
 * <pre>
 * cassandra.hostname=localhost
 * cassandra.port=9042
 * cassandra.keyspace=metamodel_test
 * cassandra.localDatacenter=datacenter1
 * </pre>
 * <p>
 * When no <code>cassandra.hostname</code> is configured the tests are skipped.
 * </p>
 */
public abstract class CassandraTestCase {

    private static final int DEFAULT_CASSANDRA_PORT = 9042;
    private static final String DEFAULT_TEST_KEYSPACE_NAME = "metamodel_test";
    private static final String DEFAULT_LOCAL_DATACENTER = "datacenter1";

    private String _hostname;
    private int _port = DEFAULT_CASSANDRA_PORT;
    private String _keyspaceName = DEFAULT_TEST_KEYSPACE_NAME;
    private String _localDatacenter = DEFAULT_LOCAL_DATACENTER;
    private boolean _configured;

    /**
     * Reads the integration test configuration, if it is available.
     */
    protected void loadConfiguration() throws IOException {
        final File file = new File(getPropertyFilePath());
        if (file.exists()) {
            loadPropertyFile(file);
            return;
        }
        // Continuous integration case
        if (System.getenv("CONTINUOUS_INTEGRATION") != null) {
            final File travisFile = new File("../travis-metamodel-integrationtest-configuration.properties");
            if (travisFile.exists()) {
                loadPropertyFile(travisFile);
            }
        }
    }

    private void loadPropertyFile(File file) throws FileNotFoundException, IOException {
        final Properties properties = new Properties();
        final InputStream inputStream = new FileInputStream(file);
        try {
            properties.load(inputStream);
        } finally {
            inputStream.close();
        }

        _hostname = properties.getProperty("cassandra.hostname");

        final String port = properties.getProperty("cassandra.port");
        if (port != null && !port.isEmpty()) {
            _port = Integer.parseInt(port.trim());
        }

        final String keyspace = properties.getProperty("cassandra.keyspace");
        if (keyspace != null && !keyspace.isEmpty()) {
            _keyspaceName = keyspace;
        }

        final String localDatacenter = properties.getProperty("cassandra.localDatacenter");
        if (localDatacenter != null && !localDatacenter.isEmpty()) {
            _localDatacenter = localDatacenter;
        }

        _configured = (_hostname != null && !_hostname.isEmpty());

        if (_configured) {
            System.out.println("Loaded Cassandra configuration. Hostname=" + _hostname + ", Port=" + _port
                    + ", Keyspace=" + _keyspaceName + ", LocalDatacenter=" + _localDatacenter);
        }
    }

    private String getPropertyFilePath() {
        final String userHome = System.getProperty("user.home");
        return userHome + "/metamodel-integrationtest-configuration.properties";
    }

    protected String getInvalidConfigurationMessage() {
        return "!!! WARN !!! Cassandra module ignored\r\n" + "Please configure cassandra connection locally ("
                + getPropertyFilePath() + "), to run integration tests";
    }

    public String getHostname() {
        return _hostname;
    }

    public int getPort() {
        return _port;
    }

    public String getKeyspaceName() {
        return _keyspaceName;
    }

    public String getLocalDatacenter() {
        return _localDatacenter;
    }

    public boolean isConfigured() {
        return _configured;
    }
}
