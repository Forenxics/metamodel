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

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.util.concurrent.atomic.AtomicInteger;

import junit.framework.TestCase;

import org.apache.commons.dbcp.BasicDataSource;
import org.apache.metamodel.jdbc.dialects.DefaultQueryRewriter;

/**
 * Tests that the JDBC update callback releases the resources it acquires, also
 * when the update fails.
 */
public class JdbcUpdateCallbackResourceTest extends TestCase {

    /**
     * A non-transactional query rewriter (Hive, Hive2, Impala) used to leave
     * the connection open, because the connection was only released inside the
     * "isTransactional()" branch.
     */
    public void testConnectionIsReleasedForNonTransactionalRewriter() throws Exception {
        Class.forName("org.hsqldb.jdbcDriver");
        final BasicDataSource dataSource = new BasicDataSource();
        dataSource.setDriverClassName("org.hsqldb.jdbcDriver");
        dataSource.setUrl("jdbc:hsqldb:mem:resource_leak_test");
        dataSource.setUsername("sa");
        dataSource.setPassword("");
        try {
            final JdbcDataContext dataContext = new JdbcDataContext(dataSource);
            dataContext.setQueryRewriter(new DefaultQueryRewriter(dataContext) {
                @Override
                public boolean isTransactional() {
                    return false;
                }
            });

            final JdbcSimpleUpdateCallback callback = new JdbcSimpleUpdateCallback(dataContext);
            callback.getConnection();
            assertEquals(1, dataSource.getNumActive());

            callback.close(false);

            assertEquals(0, dataSource.getNumActive());
        } finally {
            dataSource.close();
        }
    }

    /**
     * When an update fails the transaction is rolled back and the prepared
     * statement must be closed - it used to be left open.
     */
    public void testPreparedStatementIsClosedWhenUpdateFails() throws Exception {
        Class.forName("org.hsqldb.jdbcDriver");
        final Connection connection = DriverManager.getConnection("jdbc:hsqldb:mem:statement_leak_test", "sa", "");

        final AtomicInteger closedStatements = new AtomicInteger();
        final Connection proxy = (Connection) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] { Connection.class }, new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                        final Object result;
                        try {
                            result = method.invoke(connection, args == null ? new Object[0] : args);
                        } catch (InvocationTargetException e) {
                            throw e.getCause();
                        }
                        if ("prepareStatement".equals(method.getName()) && result instanceof PreparedStatement) {
                            return wrapStatement((PreparedStatement) result, closedStatements);
                        }
                        return result;
                    }
                });

        try {
            final java.sql.Statement createStatement = connection.createStatement();
            try {
                createStatement.execute("CREATE TABLE metamodel_stmt_test (id INTEGER)");
            } finally {
                createStatement.close();
            }

            final JdbcDataContext dataContext = new JdbcDataContext(proxy);
            final JdbcSimpleUpdateCallback callback = new JdbcSimpleUpdateCallback(dataContext);

            final PreparedStatement statement = callback.getPreparedStatement("SELECT id FROM metamodel_stmt_test",
                    true, false);
            assertNotNull(statement);
            assertEquals(0, closedStatements.get());

            callback.close(false);

            assertEquals(1, closedStatements.get());
        } finally {
            connection.close();
        }
    }

    private static Object wrapStatement(final PreparedStatement statement, final AtomicInteger closedStatements) {
        return Proxy.newProxyInstance(JdbcUpdateCallbackResourceTest.class.getClassLoader(),
                new Class<?>[] { PreparedStatement.class }, new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                        if ("close".equals(method.getName())) {
                            closedStatements.incrementAndGet();
                        }
                        try {
                            return method.invoke(statement, args == null ? new Object[0] : args);
                        } catch (InvocationTargetException e) {
                            throw e.getCause();
                        }
                    }
                });
    }
}
