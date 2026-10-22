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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

import org.apache.metamodel.data.DataSet;
import org.apache.metamodel.query.FilterItem;
import org.apache.metamodel.query.SelectItem;
import org.apache.metamodel.schema.Column;
import org.apache.metamodel.schema.ColumnType;
import org.apache.metamodel.schema.Table;
import org.apache.metamodel.util.SimpleTableDef;
import org.junit.Test;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.ResultSet;
import com.datastax.oss.driver.api.core.cql.Row;
import com.datastax.oss.driver.api.core.cql.SimpleStatement;

/**
 * Tests that a {@link CassandraDataContext} uses a <em>single</em>
 * {@link CqlSession} for all of its queries, and that it closes that session when
 * the context itself is closed.
 * <p>
 * A 4.x session owns its connection pool, so creating one per query would leak
 * one session (and one connection pool) per query.
 * </p>
 * <p>
 * These tests use a fake session, so that they can run without a live Cassandra
 * instance.
 * </p>
 */
public class CassandraSessionReuseTest {

    private static final String KEYSPACE = "my_keyspace";

    private static final SimpleTableDef TABLE_DEF = new SimpleTableDef("songs", new String[] { "id", "title" },
            new ColumnType[] { ColumnType.VARCHAR, ColumnType.VARCHAR });

    @Test
    public void testASingleSessionIsUsedForAllQueries() throws Exception {
        final FakeSession fakeSession = new FakeSession();
        final CassandraDataContext context = new CassandraDataContext(fakeSession.getSession(), KEYSPACE, TABLE_DEF);

        final Table table = TABLE_DEF.toTable();
        final List<Column> columns = table.getColumns();

        // 1) a 'select all' query
        final DataSet dataSet = context.materializeMainSchemaTable(table, columns, 10);
        assertFalse(dataSet.next());

        // 2) a count query
        final Number count = context.executeCountQuery(table, Collections.<FilterItem> emptyList(), false);
        assertEquals(42L, count.longValue());

        // 3) a primary key lookup
        final Column idColumn = table.getColumnByName("id");
        final List<SelectItem> selectItems = Collections.singletonList(new SelectItem(idColumn));
        assertNull(context.executePrimaryKeyLookupQuery(table, selectItems, idColumn, "id-1"));

        // All three queries must have been executed through that one session.
        assertEquals("All queries must use the same session", 3, fakeSession.executeCount);
    }

    @Test
    public void testTheSessionIsCreatedLazilyAndOnlyOnce() throws Exception {
        final FakeSession fakeSession = new FakeSession();
        final FakeSessionContext context = new FakeSessionContext(fakeSession.getSession());

        assertEquals("No session should be created before the first query", 0, context.createCount);

        final Table table = TABLE_DEF.toTable();
        context.materializeMainSchemaTable(table, table.getColumns(), 10);
        context.materializeMainSchemaTable(table, table.getColumns(), 10);

        assertEquals(1, context.createCount);
        assertEquals(2, fakeSession.executeCount);
    }

    @Test
    public void testCloseClosesTheSessionThatTheContextCreated() throws Exception {
        final FakeSession fakeSession = new FakeSession();
        final FakeSessionContext context = new FakeSessionContext(fakeSession.getSession());

        final Table table = TABLE_DEF.toTable();
        context.materializeMainSchemaTable(table, table.getColumns(), 10);
        assertFalse("The session should still be open", fakeSession.isClosed());

        context.close();

        assertTrue("The session must be closed when the context is closed", fakeSession.isClosed());
    }

    @Test
    public void testCloseDoesNotCloseACallerOwnedSession() throws Exception {
        final FakeSession fakeSession = new FakeSession();
        final CassandraDataContext context = new CassandraDataContext(fakeSession.getSession(), KEYSPACE, TABLE_DEF);

        final Table table = TABLE_DEF.toTable();
        context.materializeMainSchemaTable(table, table.getColumns(), 10);
        assertFalse("The session should still be open", fakeSession.isClosed());

        context.close();

        assertFalse("A session that the caller owns must not be closed by the context", fakeSession.isClosed());
    }

    /**
     * A {@link CassandraDataContext} that creates its own session, but hands out a
     * fake one instead of connecting to a real Cassandra instance.
     */
    private static final class FakeSessionContext extends CassandraDataContext {

        private final CqlSession fakeSession;
        private int createCount;

        FakeSessionContext(CqlSession fakeSession) {
            super("localhost", 9042, "datacenter1", KEYSPACE, TABLE_DEF);
            this.fakeSession = fakeSession;
        }

        @Override
        protected CqlSession createSession() {
            createCount++;
            return fakeSession;
        }
    }

    /**
     * A fake {@link CqlSession} that records the statements it is asked to
     * execute.
     */
    private static final class FakeSession implements InvocationHandler {

        private final CqlSession session;
        private final List<String> queries = new ArrayList<String>();
        private int executeCount;
        private boolean closed;

        FakeSession() {
            session = (CqlSession) Proxy.newProxyInstance(CqlSession.class.getClassLoader(),
                    new Class<?>[] { CqlSession.class }, this);
        }

        CqlSession getSession() {
            return session;
        }

        public Object invoke(Object proxy, Method method, Object[] args) {
            final String name = method.getName();
            if ("execute".equals(name) && args != null && args.length >= 1) {
                executeCount++;
                final String query = queryString(args[0]);
                queries.add(query);
                return newResultSet(isCountQuery(query));
            }
            if ("close".equals(name)) {
                closed = true;
                return null;
            }
            if ("isClosed".equals(name)) {
                return closed;
            }
            if ("toString".equals(name)) {
                return "FakeSession";
            }
            if ("hashCode".equals(name)) {
                return System.identityHashCode(proxy);
            }
            if ("equals".equals(name)) {
                return proxy == args[0];
            }
            return defaultValue(method.getReturnType());
        }

        boolean isClosed() {
            return closed;
        }

        private static String queryString(Object statement) {
            if (statement instanceof SimpleStatement) {
                final String query = ((SimpleStatement) statement).getQuery();
                if (query != null) {
                    return query;
                }
            }
            return String.valueOf(statement);
        }

        private static boolean isCountQuery(String query) {
            return query != null && query.toLowerCase().contains("count(");
        }

        /**
         * Creates a result set that either holds a single (fake) row - for count
         * queries - or no rows at all. A primary key lookup that yields no row
         * lets the context skip the conversion of a row into a MetaModel row.
         */
        private ResultSet newResultSet(boolean withRow) {
            final Row row = withRow ? newRow() : null;
            return (ResultSet) Proxy.newProxyInstance(ResultSet.class.getClassLoader(),
                    new Class<?>[] { ResultSet.class }, new InvocationHandler() {
                        public Object invoke(Object proxy, Method method, Object[] args) {
                            final String name = method.getName();
                            if ("one".equals(name)) {
                                return row;
                            }
                            if ("iterator".equals(name)) {
                                return (Iterator<Row>) Collections.<Row> emptyList().iterator();
                            }
                            if ("all".equals(name)) {
                                return Collections.emptyList();
                            }
                            if ("isExhausted".equals(name)) {
                                return Boolean.TRUE;
                            }
                            if ("isFullyFetched".equals(name)) {
                                return Boolean.TRUE;
                            }
                            if ("getAvailableWithoutFetching".equals(name)) {
                                return Integer.valueOf(0);
                            }
                            if ("wasApplied".equals(name)) {
                                return Boolean.TRUE;
                            }
                            if ("toString".equals(name)) {
                                return "FakeResultSet";
                            }
                            if ("hashCode".equals(name)) {
                                return System.identityHashCode(proxy);
                            }
                            if ("equals".equals(name)) {
                                return proxy == args[0];
                            }
                            return defaultValue(method.getReturnType());
                        }
                    });
        }

        private Row newRow() {
            return (Row) Proxy.newProxyInstance(Row.class.getClassLoader(), new Class<?>[] { Row.class },
                    new InvocationHandler() {
                        public Object invoke(Object proxy, Method method, Object[] args) {
                            final String name = method.getName();
                            if ("getLong".equals(name)) {
                                return Long.valueOf(42L);
                            }
                            if ("getInt".equals(name)) {
                                return Integer.valueOf(42);
                            }
                            if ("getObject".equals(name)) {
                                return Long.valueOf(42L);
                            }
                            if ("isNull".equals(name)) {
                                return Boolean.FALSE;
                            }
                            if ("toString".equals(name)) {
                                return "FakeRow";
                            }
                            if ("hashCode".equals(name)) {
                                return System.identityHashCode(proxy);
                            }
                            if ("equals".equals(name)) {
                                return proxy == args[0];
                            }
                            return defaultValue(method.getReturnType());
                        }
                    });
        }
    }

    private static Object defaultValue(Class<?> returnType) {
        if (!returnType.isPrimitive()) {
            return null;
        }
        if (Boolean.TYPE.equals(returnType)) {
            return Boolean.FALSE;
        }
        if (Byte.TYPE.equals(returnType)) {
            return Byte.valueOf((byte) 0);
        }
        if (Short.TYPE.equals(returnType)) {
            return Short.valueOf((short) 0);
        }
        if (Character.TYPE.equals(returnType)) {
            return Character.valueOf(' ');
        }
        if (Integer.TYPE.equals(returnType)) {
            return Integer.valueOf(0);
        }
        if (Long.TYPE.equals(returnType)) {
            return Long.valueOf(0L);
        }
        if (Float.TYPE.equals(returnType)) {
            return Float.valueOf(0f);
        }
        if (Double.TYPE.equals(returnType)) {
            return Double.valueOf(0d);
        }
        return null;
    }
}
