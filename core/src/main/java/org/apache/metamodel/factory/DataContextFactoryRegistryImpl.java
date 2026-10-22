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
package org.apache.metamodel.factory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;

import org.apache.metamodel.DataContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DataContextFactoryRegistryImpl implements DataContextFactoryRegistry, Cloneable {

    private static final Logger logger = LoggerFactory.getLogger(DataContextFactoryRegistryImpl.class);

    /**
     * The resource that the {@link ServiceLoader} looks for when it discovers
     * the {@link DataContextFactory} implementations.
     */
    private static final String SERVICE_RESOURCE = "META-INF/services/" + DataContextFactory.class.getName();

    private static final DataContextFactoryRegistry DEFAULT_INSTANCE;

    static {
        DEFAULT_INSTANCE = createFromClasspath();
    }
    
    public static DataContextFactoryRegistry createFromClasspath() {
        final ResourceFactoryRegistry resourceFactoryRegistry = ResourceFactoryRegistryImpl.getDefaultInstance();
        final DataContextFactoryRegistryImpl registry = new DataContextFactoryRegistryImpl(resourceFactoryRegistry);
        registry.discoverFromClasspath();
        return registry;
    }

    public static DataContextFactoryRegistry getDefaultInstance() {
        return DEFAULT_INSTANCE;
    }

    private final List<DataContextFactory> factories;
    private final ResourceFactoryRegistry resourceFactoryRegistry;

    public DataContextFactoryRegistryImpl(ResourceFactoryRegistry resourceFactoryRegistry) {
        this.factories = new ArrayList<>();
        this.resourceFactoryRegistry = resourceFactoryRegistry;
    }

    public DataContextFactoryRegistryImpl(Collection<DataContextFactory> factories,
            ResourceFactoryRegistry resourceFactoryRegistry) {
        this.factories = new ArrayList<>(factories);
        this.resourceFactoryRegistry = resourceFactoryRegistry;
    }

    @Override
    public DataContextFactoryRegistryImpl clone() {
        return new DataContextFactoryRegistryImpl(factories, resourceFactoryRegistry);
    }

    @Override
    public void addFactory(DataContextFactory factory) {
        factories.add(factory);
    }

    @Override
    public void clearFactories() {
        factories.clear();
    }

    @Override
    public Collection<DataContextFactory> getFactories() {
        return Collections.unmodifiableList(factories);
    }

    @Override
    public DataContext createDataContext(DataContextProperties properties)
            throws UnsupportedDataContextPropertiesException {
        for (DataContextFactory factory : factories) {
            if (factory.accepts(properties, resourceFactoryRegistry)) {
                return factory.create(properties, resourceFactoryRegistry);
            }
        }
        throw new UnsupportedDataContextPropertiesException(createNoFactoryFoundMessage());
    }

    /**
     * Discovers the {@link DataContextFactory} implementations that are
     * available on the classpath.
     * <p>
     * The lookup is performed with the thread context classloader as well as
     * with the classloader of the {@link DataContextFactory} class itself. The
     * thread context classloader is the one that a servlet container - or a
     * shaded fat jar - typically controls, and it does not necessarily
     * contain the {@value #SERVICE_RESOURCE} resources. In that case the
     * factories would silently not be found, and the failure would surface
     * much later as a "no data context found" error without any explanation.
     */
    public void discoverFromClasspath() {
        final Map<String, DataContextFactory> discovered = new LinkedHashMap<>();
        for (ClassLoader classLoader : getDiscoveryClassLoaders()) {
            for (DataContextFactory factory : loadFactories(classLoader)) {
                // the same factory may be visible through more than one
                // classloader - it must only be registered once.
                final String className = factory.getClass().getName();
                if (!discovered.containsKey(className)) {
                    discovered.put(className, factory);
                }
            }
        }

        if (discovered.isEmpty()) {
            logger.warn("No DataContextFactory implementations were found on the classpath. The service lookup "
                    + "searched for '{}' in the thread context classloader ({}) and in the classloader of the "
                    + "DataContextFactory class ({}).", SERVICE_RESOURCE,
                    Thread.currentThread().getContextClassLoader(), DataContextFactory.class.getClassLoader());
        }

        for (DataContextFactory factory : discovered.values()) {
            addFactory(factory);
        }
    }

    /**
     * @return the classloaders that the service lookup is performed with
     */
    private List<ClassLoader> getDiscoveryClassLoaders() {
        final List<ClassLoader> classLoaders = new ArrayList<>(2);
        final ClassLoader contextClassLoader = Thread.currentThread().getContextClassLoader();
        if (contextClassLoader != null) {
            classLoaders.add(contextClassLoader);
        }
        final ClassLoader ownClassLoader = DataContextFactory.class.getClassLoader();
        if (ownClassLoader != null && !classLoaders.contains(ownClassLoader)) {
            classLoaders.add(ownClassLoader);
        }
        return classLoaders;
    }

    /**
     * Loads the {@link DataContextFactory} implementations that are visible
     * through a given classloader.
     *
     * @param classLoader the classloader to search
     * @return the factories that were found
     */
    private List<DataContextFactory> loadFactories(ClassLoader classLoader) {
        final List<DataContextFactory> factories = new ArrayList<>();
        final ServiceLoader<DataContextFactory> serviceLoader = ServiceLoader.load(DataContextFactory.class,
                classLoader);
        try {
            for (DataContextFactory factory : serviceLoader) {
                factories.add(factory);
            }
        } catch (ServiceConfigurationError e) {
            // a single broken service declaration must not prevent the other
            // factories from being discovered - but the cause has to be named.
            logger.warn("Failed to load a DataContextFactory implementation through the classloader {}: {}",
                    classLoader, e.getMessage(), e);
        }
        return factories;
    }

    /**
     * Creates the message of the exception that is thrown when no data context
     * could be created. The message names the cause, so that a missing
     * factory can be told apart from properties that no factory accepts.
     *
     * @return the message
     */
    private String createNoFactoryFoundMessage() {
        final StringBuilder sb = new StringBuilder("No data context could be created for the given properties.");
        if (factories.isEmpty()) {
            sb.append(" No DataContextFactory implementations were discovered on the classpath. The service lookup "
                    + "searched for '").append(SERVICE_RESOURCE)
                    .append("' in the thread context classloader (")
                    .append(Thread.currentThread().getContextClassLoader())
                    .append(") and in the classloader of the DataContextFactory class (")
                    .append(DataContextFactory.class.getClassLoader()).append(").");
        } else {
            sb.append(" The ").append(factories.size())
                    .append(" discovered DataContextFactory implementation(s) did not accept the given properties: ");
            for (int i = 0; i < factories.size(); i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(factories.get(i).getClass().getName());
            }
            sb.append('.');
        }
        return sb.toString();
    }
}
