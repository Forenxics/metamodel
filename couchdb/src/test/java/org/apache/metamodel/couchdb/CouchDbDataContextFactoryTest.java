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
import static org.junit.Assert.assertNull;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;

import org.apache.metamodel.factory.DataContextPropertiesImpl;
import org.ektorp.http.StdHttpClient;
import org.junit.Test;

/**
 * Tests the credential handling of {@link CouchDbDataContextFactory}.
 *
 * <p>
 * ektorp's {@link StdHttpClient.Builder#url(String)} parses the user info part of the URL into the
 * very same "username" and "password" fields that
 * {@link StdHttpClient.Builder#username(String)} and
 * {@link StdHttpClient.Builder#password(String)} write to. Calling those unconditionally - with a
 * null value - therefore wiped out the credentials of a URL such as
 * "http://user:password@localhost:5984", and the request ended up being made without any
 * credentials (HTTP 401).
 */
public class CouchDbDataContextFactoryTest {

    private DataContextPropertiesImpl createProperties() {
        final Map<String, Object> map = new HashMap<>();
        map.put("type", "couchdb");
        return new DataContextPropertiesImpl(map);
    }

    /**
     * Reads one of the (protected) credential fields of ektorp's builder. ektorp does not expose
     * them, and it is the builder - not the built client - that holds them.
     */
    private String getBuilderCredential(StdHttpClient.Builder builder, String fieldName) throws Exception {
        final Field field = StdHttpClient.Builder.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        return (String) field.get(builder);
    }

    /**
     * The credentials embedded in the URL must survive.
     */
    @Test
    public void testUrlEmbeddedCredentialsAreNotOverwritten() throws Exception {
        final DataContextPropertiesImpl properties = createProperties();
        properties.put("url", "http://alice:s3cret@localhost:5984");

        final StdHttpClient.Builder builder =
                new CouchDbDataContextFactory().createHttpClientBuilder(properties);

        assertEquals("alice", getBuilderCredential(builder, "username"));
        assertEquals("s3cret", getBuilderCredential(builder, "password"));
    }

    /**
     * Credentials given as properties must be applied.
     */
    @Test
    public void testConfiguredCredentialsAreApplied() throws Exception {
        final DataContextPropertiesImpl properties = createProperties();
        properties.put("url", "http://localhost:5984");
        properties.put("username", "alice");
        properties.put("password", "s3cret");

        final StdHttpClient.Builder builder =
                new CouchDbDataContextFactory().createHttpClientBuilder(properties);

        assertEquals("alice", getBuilderCredential(builder, "username"));
        assertEquals("s3cret", getBuilderCredential(builder, "password"));
    }

    /**
     * An explicitly configured credential takes precedence over the one embedded in the URL.
     */
    @Test
    public void testConfiguredCredentialsOverrideUrlCredentials() throws Exception {
        final DataContextPropertiesImpl properties = createProperties();
        properties.put("url", "http://alice:s3cret@localhost:5984");
        properties.put("username", "bob");
        properties.put("password", "hunter2");

        final StdHttpClient.Builder builder =
                new CouchDbDataContextFactory().createHttpClientBuilder(properties);

        assertEquals("bob", getBuilderCredential(builder, "username"));
        assertEquals("hunter2", getBuilderCredential(builder, "password"));
    }

    /**
     * Without any credentials at all, none must be set on the builder.
     */
    @Test
    public void testNoCredentialsAreSetWhenNoneAreConfigured() throws Exception {
        final DataContextPropertiesImpl properties = createProperties();
        properties.put("url", "http://localhost:5984");

        final StdHttpClient.Builder builder =
                new CouchDbDataContextFactory().createHttpClientBuilder(properties);

        assertNull(getBuilderCredential(builder, "username"));
        assertNull(getBuilderCredential(builder, "password"));
    }
}
