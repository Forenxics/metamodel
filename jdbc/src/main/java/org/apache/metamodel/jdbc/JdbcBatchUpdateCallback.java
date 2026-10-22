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

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import org.apache.metamodel.UpdateCallback;
import org.apache.metamodel.util.FileHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Jdbc {@link UpdateCallback} for databases that support the JDBC Batch
 * features.
 */
final class JdbcBatchUpdateCallback extends JdbcUpdateCallback {

    private static final Logger logger = LoggerFactory.getLogger(JdbcBatchUpdateCallback.class);

    /**
     * The kind of a statement that is added to a batch. The kind is needed in
     * order to attribute the update counts that
     * {@link PreparedStatement#executeBatch()} returns to the right part of
     * the {@link org.apache.metamodel.UpdateSummary}.
     */
    private static final int OPERATION_OTHER = 0;
    private static final int OPERATION_INSERT = 1;
    private static final int OPERATION_UPDATE = 2;
    private static final int OPERATION_DELETE = 3;

    /**
     * The kind of the statement that is currently being added to a batch.
     */
    private int _currentOperation = OPERATION_OTHER;

    /**
     * The kinds of the statements that have been added to each of the prepared
     * statements that are currently open. The kinds have to be tracked per
     * statement, because a new statement may be opened (and executed) while
     * another statement is still waiting for its batch to be executed.
     */
    private final Map<PreparedStatement, List<Integer>> _pendingOperations = new IdentityHashMap<>();

    public JdbcBatchUpdateCallback(JdbcDataContext dataContext) {
        super(dataContext);
    }

    @Override
    public void executeInsert(PreparedStatement st, boolean reuseStatement) throws SQLException {
        _currentOperation = OPERATION_INSERT;
        try {
            // NOTE: the superclass counts one insert per statement, which is
            // not accurate for a batch - the real number of affected rows is
            // only known once the batch has been executed.
            executePreparedStatement(st, reuseStatement, true);
        } finally {
            _currentOperation = OPERATION_OTHER;
        }
    }

    @Override
    public void executeUpdate(PreparedStatement st, boolean reuseStatement) throws SQLException {
        _currentOperation = OPERATION_UPDATE;
        try {
            // NOTE: the superclass adds the return value of
            // executePreparedStatement() - which is -1 for a batch - to the
            // update summary, which would mark the number of updated rows as
            // unknown. The real counts are added when the batch is executed.
            executePreparedStatement(st, reuseStatement, false);
        } finally {
            _currentOperation = OPERATION_OTHER;
        }
    }

    @Override
    public void executeDelete(PreparedStatement st, boolean reuseStatement) throws SQLException {
        _currentOperation = OPERATION_DELETE;
        try {
            // NOTE: see the comment in executeUpdate().
            executePreparedStatement(st, reuseStatement, false);
        } finally {
            _currentOperation = OPERATION_OTHER;
        }
    }

    @Override
    protected int executePreparedStatement(PreparedStatement st) throws SQLException {
        st.addBatch();
        List<Integer> operations = _pendingOperations.get(st);
        if (operations == null) {
            operations = new ArrayList<>();
            _pendingOperations.put(st, operations);
        }
        operations.add(_currentOperation);
        return -1;
    }

    @Override
    protected void closePreparedStatement(PreparedStatement preparedStatement) {
        try {
            int[] results = preparedStatement.executeBatch();
            if (logger.isDebugEnabled()) {
                for (int i = 0; i < results.length; i++) {
                    int result = results[i];
                    final String resultString;
                    switch (result) {
                    case Statement.SUCCESS_NO_INFO:
                        resultString = "SUCCESS_NO_INFO";
                        break;
                    case Statement.EXECUTE_FAILED:
                        resultString = "EXECUTE_FAILED";
                        break;
                    default:
                        resultString = result + " rows updated";
                    }
                    logger.debug("batch execute result[" + i + "]:" + resultString);
                }
            }
            applyBatchResults(preparedStatement, results);
        } catch (SQLException e) {
            throw JdbcUtils.wrapException(e, "execute batch: " + preparedStatement, JdbcUtils.JdbcActionType.UPDATE);
        } finally {
            _pendingOperations.remove(preparedStatement);
            FileHelper.safeClose(preparedStatement);
        }
    }

    /**
     * Attributes the update counts that the batch execution returned to the
     * update summary. The counts are returned per statement, in the order in
     * which the statements were added to the batch, so they can be attributed
     * to the kind of the statement that they belong to.
     *
     * @param preparedStatement
     *            the statement whose batch was executed
     * @param results
     *            the update counts returned by
     *            {@link PreparedStatement#executeBatch()}
     */
    private void applyBatchResults(PreparedStatement preparedStatement, int[] results) {
        final List<Integer> operations = _pendingOperations.get(preparedStatement);
        if (operations == null || operations.isEmpty()) {
            return;
        }

        int inserts = 0;
        int updates = 0;
        int deletes = 0;
        boolean updatesUnknown = false;
        boolean deletesUnknown = false;

        for (int i = 0; i < operations.size(); i++) {
            final int operation = operations.get(i);
            if (operation == OPERATION_OTHER) {
                // not an insert, update or delete (a DDL statement for
                // instance) - such a statement is not counted.
                continue;
            }
            final int result = (i < results.length) ? results[i] : Statement.SUCCESS_NO_INFO;
            switch (operation) {
            case OPERATION_INSERT:
                if (result == Statement.SUCCESS_NO_INFO || result == Statement.EXECUTE_FAILED) {
                    // the driver did not report how many rows were inserted.
                    // Every batched insert statement inserts a single row.
                    inserts += 1;
                } else {
                    inserts += result;
                }
                break;
            case OPERATION_UPDATE:
                if (result == Statement.SUCCESS_NO_INFO || result == Statement.EXECUTE_FAILED) {
                    // the number of updated rows cannot be determined
                    updatesUnknown = true;
                } else {
                    updates += result;
                }
                break;
            case OPERATION_DELETE:
                if (result == Statement.SUCCESS_NO_INFO || result == Statement.EXECUTE_FAILED) {
                    // the number of deleted rows cannot be determined
                    deletesUnknown = true;
                } else {
                    deletes += result;
                }
                break;
            default:
                break;
            }
        }

        if (inserts > 0) {
            getUpdateSummaryBuilder().addInserts(inserts);
        }
        if (updates > 0) {
            getUpdateSummaryBuilder().addUpdates(updates);
        }
        if (deletes > 0) {
            getUpdateSummaryBuilder().addDeletes(deletes);
        }
        if (updatesUnknown) {
            getUpdateSummaryBuilder().makeUpdatesUnknown();
        }
        if (deletesUnknown) {
            getUpdateSummaryBuilder().makeDeletesUnknown();
        }
    }

    @Override
    protected boolean isGeneratedKeysCollectionEnabled() {
        return false;
    }
}
