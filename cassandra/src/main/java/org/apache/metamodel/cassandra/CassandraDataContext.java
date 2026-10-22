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

import java.io.Closeable;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.apache.metamodel.DataContext;
import org.apache.metamodel.MetaModelException;
import org.apache.metamodel.QueryPostprocessDataContext;
import org.apache.metamodel.data.DataSet;
import org.apache.metamodel.data.SimpleDataSetHeader;
import org.apache.metamodel.query.FilterItem;
import org.apache.metamodel.query.SelectItem;
import org.apache.metamodel.schema.Column;
import org.apache.metamodel.schema.ColumnType;
import org.apache.metamodel.schema.MutableColumn;
import org.apache.metamodel.schema.MutableSchema;
import org.apache.metamodel.schema.MutableTable;
import org.apache.metamodel.schema.Schema;
import org.apache.metamodel.schema.Table;
import org.apache.metamodel.util.SimpleTableDef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.CqlSessionBuilder;
import com.datastax.oss.driver.api.core.cql.ResultSet;
import com.datastax.oss.driver.api.core.cql.Row;
import com.datastax.oss.driver.api.core.cql.Statement;
import com.datastax.oss.driver.api.core.metadata.Metadata;
import com.datastax.oss.driver.api.core.metadata.schema.ColumnMetadata;
import com.datastax.oss.driver.api.core.metadata.schema.KeyspaceMetadata;
import com.datastax.oss.driver.api.core.metadata.schema.TableMetadata;
import com.datastax.oss.driver.api.core.type.CustomType;
import com.datastax.oss.driver.api.core.type.DataType;
import com.datastax.oss.driver.api.core.type.UserDefinedType;
import com.datastax.oss.driver.api.querybuilder.QueryBuilder;
import com.datastax.oss.driver.api.querybuilder.relation.Relation;
import com.datastax.oss.driver.api.querybuilder.select.Select;
import com.datastax.oss.driver.api.querybuilder.select.SelectFrom;
import com.datastax.oss.driver.api.querybuilder.term.Term;

/**
 * DataContext implementation for Apache Cassandra database.
 *
 * When instantiating this DataContext, a keyspace name is provided. In
 * Cassandra, the keyspace is the container for your application data, similar
 * to a schema in a relational database. Keyspaces are used to group column
 * families together.
 *
 * This implementation supports either automatic discovery of a schema or manual
 * specification of a schema, through the {@link SimpleTableDef} class.
 *
 * <p>
 * This implementation is built on the DataStax Java driver 4.x
 * (<code>com.datastax.oss:java-driver-core</code>). In driver 4.x the
 * {@link CqlSession} replaces the driver 3.x <code>Cluster</code> + <code>Session</code>
 * pair: the session <em>is</em> the connection to the cluster, and it carries its own
 * connection pool. A session can therefore be created and owned by this context
 * (see {@link #CassandraDataContext(String, int, String, String, SimpleTableDef...)}),
 * or supplied - and owned - by the caller (see
 * {@link #CassandraDataContext(CqlSession, String, SimpleTableDef...)}).
 * </p>
 */
public class CassandraDataContext extends QueryPostprocessDataContext implements DataContext, Closeable {

    private static final Logger logger = LoggerFactory.getLogger(CassandraDataContext.class);

    /**
     * The default Cassandra port.
     */
    public static final int DEFAULT_PORT = 9042;

    /**
     * The default local datacenter name. The 4.x driver refuses to start a session
     * without being told which datacenter is local to the client, and
     * <code>datacenter1</code> is the name that a stock Cassandra installation
     * (including the official Docker image) uses.
     */
    public static final String DEFAULT_LOCAL_DATACENTER = "datacenter1";

    /**
     * The single session that this context uses to talk to Cassandra. It is
     * created lazily (on first use) and reused for every subsequent query.
     */
    private volatile CqlSession session;

    /**
     * Whether the session in {@link #session} was created by this context. If it
     * was supplied by the caller then this context must not close it.
     */
    private final boolean sessionOwnedByContext;

    private SimpleTableDef[] tableDefs;
    private final String keySpaceName;

    /**
     * The contact points used to create a session owned by this context.
     */
    private final String contactPoints;

    private final int port;

    private final String localDatacenter;

    /**
     * Constructs a {@link CassandraDataContext} that uses a session owned by the
     * caller. This constructor accepts a custom array of {@link SimpleTableDef}s
     * which allows the user to define his own view on the indexes in the engine.
     * <p>
     * The session is <em>not</em> closed by {@link #close()} - it remains owned by
     * whoever created it.
     * </p>
     *
     * @param session
     *            the Cassandra session to use
     * @param keySpace
     *            the name of the Cassandra keyspace
     * @param tableDefs
     *            an array of {@link SimpleTableDef}s, which define the table
     *            and column model of the keyspace.
     */
    public CassandraDataContext(CqlSession session, String keySpace, SimpleTableDef... tableDefs) {
        super(false);
        this.session = session;
        this.sessionOwnedByContext = false;
        this.keySpaceName = keySpace;
        this.tableDefs = tableDefs;
        this.contactPoints = null;
        this.port = DEFAULT_PORT;
        this.localDatacenter = DEFAULT_LOCAL_DATACENTER;
    }

    /**
     * Constructs a {@link CassandraDataContext} that uses a session owned by the
     * caller and automatically detects the schema structure/view on the keyspace
     * (see {@link #detectSchema(CqlSession, String)}).
     *
     * @param session
     *            the Cassandra session to use
     * @param keySpace
     *            the name of the Cassandra keyspace to represent
     */
    public CassandraDataContext(CqlSession session, String keySpace) {
        this(session, keySpace, detectSchema(session, keySpace));
    }

    /**
     * Constructs a {@link CassandraDataContext} that creates - and owns - its own
     * session. The session is created lazily, upon the first query, and is reused
     * for every subsequent query. It is closed by {@link #close()}.
     * <p>
     * This constructor accepts a custom array of {@link SimpleTableDef}s which
     * allows the user to define his own view on the indexes in the engine.
     * </p>
     *
     * @param contactPoints
     *            a comma separated list of host names of the Cassandra nodes
     * @param port
     *            the port of the Cassandra nodes
     * @param localDatacenter
     *            the name of the datacenter that is local to the client. The 4.x
     *            driver requires this to be set.
     * @param keySpace
     *            the name of the Cassandra keyspace
     * @param tableDefs
     *            an array of {@link SimpleTableDef}s, which define the table
     *            and column model of the keyspace.
     */
    public CassandraDataContext(String contactPoints, int port, String localDatacenter, String keySpace,
            SimpleTableDef... tableDefs) {
        super(false);
        this.session = null;
        this.sessionOwnedByContext = true;
        this.keySpaceName = keySpace;
        this.tableDefs = tableDefs;
        this.contactPoints = contactPoints;
        this.port = port;
        this.localDatacenter = localDatacenter;
    }

    /**
     * Constructs a {@link CassandraDataContext} that creates - and owns - its own
     * session and automatically detects the schema structure/view on the keyspace.
     * <p>
     * Unlike {@link #CassandraDataContext(String, int, String, String, SimpleTableDef...)}
     * the session cannot be created lazily here: schema detection needs a live
     * session, so it is created eagerly by this constructor.
     * </p>
     *
     * @param contactPoints
     *            a comma separated list of host names of the Cassandra nodes
     * @param port
     *            the port of the Cassandra nodes
     * @param localDatacenter
     *            the name of the datacenter that is local to the client. The 4.x
     *            driver requires this to be set.
     * @param keySpace
     *            the name of the Cassandra keyspace to represent
     */
    public CassandraDataContext(String contactPoints, int port, String localDatacenter, String keySpace) {
        this(contactPoints, port, localDatacenter, keySpace, (SimpleTableDef[]) null);
        // Schema detection requires a live session, so it is created eagerly here.
        this.tableDefs = detectSchema(getSession(), keySpace);
    }

    /**
     * Constructor used by {@link CassandraDataContextFactory}, which creates the
     * session itself and therefore hands the ownership of that session over to
     * the context, so that it is released when the context is closed.
     */
    CassandraDataContext(CqlSession session, String keySpace, boolean sessionOwnedByContext,
            SimpleTableDef... tableDefs) {
        super(false);
        this.session = session;
        this.sessionOwnedByContext = sessionOwnedByContext;
        this.keySpaceName = keySpace;
        this.tableDefs = tableDefs;
        this.contactPoints = null;
        this.port = DEFAULT_PORT;
        this.localDatacenter = DEFAULT_LOCAL_DATACENTER;
    }

    /**
     * Returns the {@link CqlSession} that this context uses to communicate with
     * Cassandra.
     * <p>
     * When the session is owned by this context it is created lazily, upon first
     * use, and is then reused for every query that this context executes. A
     * 4.x session owns its connection pool, so creating one per query would leak
     * one session - and one connection pool - per query.
     * </p>
     *
     * @return the session of this context (never <code>null</code>)
     */
    CqlSession getSession() {
        CqlSession result = session;
        if (result == null) {
            synchronized (this) {
                result = session;
                if (result == null) {
                    result = createSession();
                    session = result;
                }
            }
        }
        return result;
    }

    /**
     * Creates a new session against the contact points that this context was
     * configured with.
     * <p>
     * This method is protected so that subclasses can supply their own session -
     * for instance in tests.
     * </p>
     */
    protected CqlSession createSession() {
        final CqlSessionBuilder builder = CqlSession.builder();
        final List<InetSocketAddress> addresses = new ArrayList<InetSocketAddress>();
        if (contactPoints != null) {
            for (String contactPoint : contactPoints.split(",")) {
                final String host = contactPoint.trim();
                if (!host.isEmpty()) {
                    addresses.add(new InetSocketAddress(host, port));
                }
            }
        }
        builder.addContactPoints(addresses);
        builder.withLocalDatacenter(localDatacenter);
        return builder.build();
    }

    /**
     * Closes the {@link CqlSession} that this context created.
     * <p>
     * Only a session that this context created is closed - a session that was
     * supplied by the caller remains owned by the caller.
     * </p>
     */
    @Override
    public void close() {
        final CqlSession result = session;
        if (result != null && sessionOwnedByContext) {
            session = null;
            try {
                result.close();
            } catch (Exception e) {
                logger.warn("Failed to close Cassandra session", e);
            }
        }
    }

    /**
     * Performs an analysis of the given keyspace in a Cassandra session and
     * detects the cassandra types structure based on the metadata provided by the
     * datastax cassandra java client.
     *
     * @see #detectTable(TableMetadata)
     *
     * @param session
     *            the session to inspect
     * @param keyspaceName
     *            the name of the keyspace to inspect
     * @return a mutable schema instance, useful for further fine tuning by the
     *         user.
     */
    public static SimpleTableDef[] detectSchema(CqlSession session, String keyspaceName) {
        final Metadata metadata = session.getMetadata();
        KeyspaceMetadata keyspace = metadata.getKeyspace(keyspaceName).orElse(null);
        if (keyspace == null) {
            // The 4.x driver maintains its schema metadata asynchronously, so a
            // keyspace that was created after this session was started may not be
            // known yet. Refresh the schema before declaring it missing.
            keyspace = session.refreshSchema().getKeyspace(keyspaceName).orElse(null);
        }
        if (keyspace == null) {
            throw new IllegalArgumentException("Keyspace '" + keyspaceName + "' does not exist in the database");
        }
        final Collection<TableMetadata> tables = keyspace.getTables().values();
        final SimpleTableDef[] result = new SimpleTableDef[tables.size()];
        int i = 0;
        for (final TableMetadata tableMetaData : tables) {
            final SimpleTableDef table = detectTable(tableMetaData);
            result[i] = table;
            i++;
        }
        return result;
    }

    /**
     * Performs an analysis of the given keyspace and returns the detected schema.
     * A temporary session is created for the inspection and closed afterwards.
     *
     * @param contactPoints
     *            a comma separated list of host names of the Cassandra nodes
     * @param port
     *            the port of the Cassandra nodes
     * @param localDatacenter
     *            the name of the datacenter that is local to the client
     * @param keyspaceName
     *            the name of the keyspace to inspect
     * @return the detected schema of the keyspace
     */
    public static SimpleTableDef[] detectSchema(String contactPoints, int port, String localDatacenter,
            String keyspaceName) {
        final CqlSession session = CqlSession.builder()
                .addContactPoints(parseContactPoints(contactPoints, port))
                .withLocalDatacenter(localDatacenter)
                .build();
        try {
            return detectSchema(session, keyspaceName);
        } finally {
            session.close();
        }
    }

    /**
     * Performs an analysis of an available table in Cassandra.
     *
     * @param tableMetaData
     *            the table meta data
     * @return a table definition for cassandra.
     */
    public static SimpleTableDef detectTable(TableMetadata tableMetaData) {
        final Collection<ColumnMetadata> columns = tableMetaData.getColumns().values();
        final String[] columnNames = new String[columns.size()];
        final ColumnType[] columnTypes = new ColumnType[columns.size()];
        int i = 0;
        for (final ColumnMetadata column : columns) {
            columnNames[i] = column.getName().asInternal();
            columnTypes[i] = getColumnTypeFromMetaDataField(column.getType());
            i++;
        }

        return new SimpleTableDef(tableMetaData.getName().asInternal(), columnNames, columnTypes);
    }

    @Override
    protected Schema getMainSchema() throws MetaModelException {
        final MutableSchema theSchema = new MutableSchema(getMainSchemaName());
        for (final SimpleTableDef tableDef : tableDefs) {
            final MutableTable table = tableDef.toTable().setSchema(theSchema);

            final TableMetadata cassandraTable = getSession().getMetadata().getKeyspace(keySpaceName)
                    .flatMap(keyspace -> keyspace.getTable(table.getName())).orElse(null);
            if (cassandraTable != null) {
                final List<ColumnMetadata> primaryKeys = cassandraTable.getPrimaryKey();
                for (ColumnMetadata primaryKey : primaryKeys) {
                    final MutableColumn column = (MutableColumn) table.getColumnByName(primaryKey.getName()
                            .asInternal());
                    if (column != null) {
                        column.setPrimaryKey(true);
                    }
                    column.setNativeType(getNativeTypeName(primaryKey.getType()));
                }
            }

            theSchema.addTable(table);
        }
        return theSchema;
    }

    @Override
    protected String getMainSchemaName() throws MetaModelException {
        return keySpaceName;
    }

    @Override
    protected DataSet materializeMainSchemaTable(Table table, List<Column> columns, int maxRows) {
        Select query = QueryBuilder.selectFrom(keySpaceName, table.getName()).all();
        if (limitMaxRowsIsSet(maxRows)) {
            query = query.limit(maxRows);
        }
        final ResultSet resultSet = getSession().execute(query.build());

        final Iterator<Row> response = resultSet.iterator();
        return new CassandraDataSet(response, columns);
    }

    private boolean limitMaxRowsIsSet(int maxRows) {
        return (maxRows != -1);
    }

    @Override
    protected org.apache.metamodel.data.Row executePrimaryKeyLookupQuery(Table table, List<SelectItem> selectItems,
            Column primaryKeyColumn, Object keyValue) {

        if (primaryKeyColumn.getType() == ColumnType.UUID && keyValue instanceof String) {
            keyValue = UUID.fromString(keyValue.toString());
        }

        final SelectFrom selectFrom = QueryBuilder.selectFrom(keySpaceName, table.getName());
        Select select = null;
        for (SelectItem selectItem : selectItems) {
            final Column column = selectItem.getColumn();
            assert column != null;
            select = select == null ? selectFrom.column(column.getName()) : select.column(column.getName());
        }
        if (select == null) {
            select = selectFrom.all();
        }

        final Statement<?> statement = select
                .where(Relation.column(primaryKeyColumn.getName()).isEqualTo(toTerm(keyValue))).build();

        final Row row = getSession().execute(statement).one();

        return CassandraUtils.toRow(row, new SimpleDataSetHeader(selectItems));
    }

    @Override
    protected Number executeCountQuery(Table table, List<FilterItem> whereItems, boolean functionApproximationAllowed) {
        if (!whereItems.isEmpty()) {
            // not supported - will have to be done by counting client-side
            logger.debug(
                    "Not able to execute count query natively - resorting to query post-processing, which may be expensive");
            return null;
        }
        final Statement<?> statement = QueryBuilder.selectFrom(keySpaceName, table.getName()).countAll().build();
        final Row response = getSession().execute(statement).one();
        return response.getLong(0);
    }

    /**
     * Converts a key value into a CQL term that can be used in a where clause.
     */
    private static Term toTerm(Object keyValue) {
        if (keyValue == null) {
            return QueryBuilder.raw("null");
        }
        return QueryBuilder.literal(keyValue);
    }

    /**
     * Parses a comma separated list of host names into a list of contact points.
     */
    static List<InetSocketAddress> parseContactPoints(String contactPoints, int port) {
        final List<InetSocketAddress> addresses = new ArrayList<InetSocketAddress>();
        if (contactPoints != null) {
            for (String contactPoint : contactPoints.split(",")) {
                final String host = contactPoint.trim();
                if (!host.isEmpty()) {
                    addresses.add(new InetSocketAddress(host, port));
                }
            }
        }
        return addresses;
    }

    /**
     * Returns the native (CQL) type name of a column, as it was reported by the
     * driver 3.x metadata (for instance <code>UUID</code> or <code>TIMESTAMP</code>).
     */
    private static String getNativeTypeName(DataType dataType) {
        return getBaseTypeName(dataType).toUpperCase(Locale.ROOT);
    }

    /**
     * Returns the base CQL type name of a data type, for instance <code>text</code>
     * for a <code>list&lt;text&gt;</code> column.
     */
    private static String getBaseTypeName(DataType dataType) {
        return CassandraUtils.getBaseTypeName(dataType);
    }

    private static ColumnType getColumnTypeFromMetaDataField(DataType dataType) {
        if (dataType instanceof UserDefinedType || dataType instanceof CustomType) {
            return ColumnType.OTHER;
        }
        switch (getBaseTypeName(dataType)) {
        case "bigint":
            return ColumnType.BIGINT;
        case "counter":
            return ColumnType.BIGINT;
        case "blob":
            return ColumnType.BLOB;
        case "boolean":
            return ColumnType.BOOLEAN;
        case "decimal":
            return ColumnType.DECIMAL;
        case "double":
            return ColumnType.DOUBLE;
        case "float":
            return ColumnType.FLOAT;
        case "int":
            return ColumnType.INTEGER;
        case "text":
            return ColumnType.STRING;
        case "timestamp":
            return ColumnType.TIMESTAMP;
        case "uuid":
            return ColumnType.UUID;
        case "timeuuid":
            return ColumnType.UUID;
        case "varchar":
            return ColumnType.VARCHAR;
        case "varint":
            return ColumnType.BIGINT;
        case "list":
            return ColumnType.LIST;
        case "map":
            return ColumnType.MAP;
        case "set":
            return ColumnType.SET;
        case "inet":
            return ColumnType.INET;
        case "ascii":
            return ColumnType.STRING;
        case "date":
            return ColumnType.DATE;
        case "time":
            return ColumnType.TIME;
        case "smallint":
            return ColumnType.SMALLINT;
        case "tinyint":
            return ColumnType.TINYINT;
        default:
            return ColumnType.STRING;
        }
    }
}
