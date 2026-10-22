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
package org.apache.metamodel.jdbc;

import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.net.URL;
import java.util.Collections;
import java.util.Enumeration;

import org.apache.metamodel.factory.DataContextFactory;
import org.apache.metamodel.factory.DataContextFactoryRegistry;
import org.apache.metamodel.factory.DataContextFactoryRegistryImpl;
import org.junit.Test;

/**
 * Tests that the {@link DataContextFactory} implementations are discovered
 * even when the thread context classloader does not contain the service
 * resources - which is the case in a container or in a shaded fat jar.
 */
public class JdbcDataContextFactoryDiscoveryTest {

    private static final String SERVICE_RESOURCE = "META-INF/services/" + DataContextFactory.class.getName();

    @Test
    public void testDiscoveryFallsBackToTheClassLoaderOfTheFactoryClass() {
        final ClassLoader original = Thread.currentThread().getContextClassLoader();
        try {
            Thread.currentThread().setContextClassLoader(new ServiceHidingClassLoader());

            final DataContextFactoryRegistry registry = DataContextFactoryRegistryImpl.createFromClasspath();

            boolean found = false;
            for (DataContextFactory factory : registry.getFactories()) {
                if (factory instanceof JdbcDataContextFactory) {
                    found = true;
                    break;
                }
            }
            assertTrue("the JdbcDataContextFactory must be discovered through the classloader of the "
                    + "DataContextFactory class", found);
        } finally {
            Thread.currentThread().setContextClassLoader(original);
        }
    }

    /**
     * A classloader that behaves like the thread context classloader of a
     * container or of a shaded fat jar: it can load the classes, but it does
     * not contain the service resources.
     */
    private static class ServiceHidingClassLoader extends ClassLoader {

        public ServiceHidingClassLoader() {
            super(DataContextFactory.class.getClassLoader());
        }

        @Override
        public Enumeration<URL> getResources(String name) throws IOException {
            if (SERVICE_RESOURCE.equals(name)) {
                return Collections.emptyEnumeration();
            }
            return super.getResources(name);
        }
    }
}
