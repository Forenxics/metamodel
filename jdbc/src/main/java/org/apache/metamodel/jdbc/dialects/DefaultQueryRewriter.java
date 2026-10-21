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
package org.apache.metamodel.jdbc.dialects;

import java.sql.Timestamp;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.ListIterator;
import java.util.Set;

import org.apache.metamodel.jdbc.JdbcDataContext;
import org.apache.metamodel.query.AggregateFunction;
import org.apache.metamodel.query.AverageAggregateFunction;
import org.apache.metamodel.query.CountAggregateFunction;
import org.apache.metamodel.query.FilterItem;
import org.apache.metamodel.query.FromItem;
import org.apache.metamodel.query.FunctionType;
import org.apache.metamodel.query.GroupByItem;
import org.apache.metamodel.query.MaxAggregateFunction;
import org.apache.metamodel.query.MinAggregateFunction;
import org.apache.metamodel.query.OperatorType;
import org.apache.metamodel.query.OrderByItem;
import org.apache.metamodel.query.Query;
import org.apache.metamodel.query.ScalarFunction;
import org.apache.metamodel.query.SelectItem;
import org.apache.metamodel.query.SumAggregateFunction;
import org.apache.metamodel.schema.ColumnType;
import org.apache.metamodel.schema.Schema;
import org.apache.metamodel.schema.Table;
import org.apache.metamodel.util.CollectionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Generic query rewriter that adds syntax enhancements that are only possible to resolve just before execution time.
 */
public class DefaultQueryRewriter extends AbstractQueryRewriter {

    private static final Logger logger = LoggerFactory.getLogger(DefaultQueryRewriter.class);

    private static final String SPECIAL_ALIAS_CHARACTERS = "- ,.|*%()!#¤/\\=?;:~";
    private static final Set<Class<? extends FunctionType>> SUPPORTED_FUNCTION_CLASSES = new HashSet<>(
            Arrays.<Class<? extends FunctionType>> asList(CountAggregateFunction.class, SumAggregateFunction.class,
                    MaxAggregateFunction.class, MinAggregateFunction.class, AverageAggregateFunction.class));

    public DefaultQueryRewriter(JdbcDataContext dataContext) {
        super(dataContext);
    }

    @Override
    protected Query beforeRewrite(Query query) {
        query = query.clone();

        JdbcDataContext dataContext = getDataContext();
        if (dataContext != null) {
            String identifierQuoteString = dataContext.getIdentifierQuoteString();
            if (identifierQuoteString != null) {
                List<SelectItem> selectItems = query.getSelectClause().getItems();
                for (SelectItem item : selectItems) {
                    String alias = item.getAlias();
                    if (needsQuoting(alias, identifierQuoteString)) {
                        item.setAlias(identifierQuoteString + escapeIdentifier(alias, identifierQuoteString)
                                + identifierQuoteString);
                    }
                }
                List<FromItem> fromItems = query.getFromClause().getItems();
                for (FromItem item : fromItems) {
                    String alias = item.getAlias();
                    if (needsQuoting(alias, identifierQuoteString)) {
                        item.setAlias(identifierQuoteString + escapeIdentifier(alias, identifierQuoteString)
                                + identifierQuoteString);
                    }
                }
            }
        }
        return query;
    }

    @Override
    public String rewriteColumnType(ColumnType columnType, Integer columnSize) {
        if (columnType == ColumnType.STRING) {
            // convert STRING to VARCHAR as the default SQL type for strings
            return rewriteColumnType(ColumnType.VARCHAR, columnSize);
        }
        if (columnType == ColumnType.NUMBER) {
            // convert NUMBER to FLOAT as the default SQL type for numbers
            return rewriteColumnType(ColumnType.FLOAT, columnSize);
        }
        return super.rewriteColumnType(columnType, columnSize);
    }

    protected boolean needsQuoting(String alias, String identifierQuoteString) {
        boolean result = false;
        if (alias != null && identifierQuoteString != null) {
            if (alias.indexOf(identifierQuoteString) != -1) {
                // the alias contains the identifier quote character. It has to be
                // quoted (and escaped) in order not to terminate the identifier.
                result = true;
            } else {
                for (int i = 0; i < SPECIAL_ALIAS_CHARACTERS.length(); i++) {
                    char specialCharacter = SPECIAL_ALIAS_CHARACTERS.charAt(i);
                    if (alias.indexOf(specialCharacter) != -1) {
                        result = true;
                        break;
                    }
                }
            }
        }
        if (logger.isDebugEnabled()) {
            logger.debug("needsQuoting(" + alias + "," + identifierQuoteString + ") = " + result);
        }
        return result;
    }

    /**
     * Escapes an identifier by doubling every occurrence of the identifier quote
     * character, so that it cannot terminate the quoted identifier.
     *
     * @param identifier the identifier to escape
     * @param identifierQuoteString the identifier quote character of the database
     * @return the escaped identifier
     */
    protected static String escapeIdentifier(String identifier, String identifierQuoteString) {
        return identifier.replace(identifierQuoteString, identifierQuoteString + identifierQuoteString);
    }

    /**
     * Determines whether an identifier has to be quoted in order for it to be
     * rendered as a single, valid SQL identifier.
     *
     * @param identifier the identifier to inspect
     * @param identifierQuoteString the identifier quote character of the database
     * @return true if the identifier must be quoted
     */
    protected static boolean needsIdentifierQuoting(String identifier, String identifierQuoteString) {
        if (identifierQuoteString != null && identifier.indexOf(identifierQuoteString) != -1) {
            // the identifier contains the identifier quote character and would
            // therefore terminate the quoted identifier
            return true;
        }
        for (int i = 0; i < identifier.length(); i++) {
            final char c = identifier.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '_' || c == '$') {
                continue;
            }
            // anything that is not a "plain" identifier character (a space, a
            // dash, a dot, a semicolon, a quote character etc.) would either
            // change the meaning of the statement or break it apart into
            // multiple statements - so it must be quoted.
            return true;
        }
        return false;
    }

    /**
     * Returns the identifier quote character of the database, or null if it is
     * not known.
     *
     * @return the identifier quote character
     */
    protected String getIdentifierQuoteString() {
        final JdbcDataContext dataContext = getDataContext();
        return dataContext == null ? null : dataContext.getIdentifierQuoteString();
    }

    @Override
    protected String rewriteFromItem(Query query, FromItem item) {
        final String result = super.rewriteFromItem(query, item);
        return rewriteSchemaNames(item, result, false);
    }

    /**
     * Protects the schema name of a table reference. The core module renders
     * the schema name without any quoting or escaping (see
     * FromItem#toStringNoAlias()), so a schema name that contains the
     * identifier quote character - or any other character that is not valid in
     * an unquoted identifier - could otherwise break out of the statement.
     *
     * @param item the from item that was rendered
     * @param result the rendered from item
     * @param alwaysQuote if true the schema name is quoted even when it does not
     *        require protection
     * @return the rendered from item with a protected schema name
     */
    protected String rewriteSchemaNames(FromItem item, String result, boolean alwaysQuote) {
        return rewriteSchemaNames(item, result, alwaysQuote, getIdentifierQuoteString());
    }

    /**
     * @param item the from item that was rendered
     * @param result the rendered from item
     * @param alwaysQuote if true the schema name is quoted even when it does not
     *        require protection
     * @param identifierQuoteString the identifier quote character to use, or null
     *        if it is unknown
     * @return the rendered from item with a protected schema name
     */
    protected String rewriteSchemaNames(FromItem item, String result, boolean alwaysQuote,
            String identifierQuoteString) {
        final Table table = item.getTable();
        if (table != null) {
            return rewriteSchemaName(table.getSchema(), result, alwaysQuote, identifierQuoteString);
        }
        // A join is rendered by the core module, which renders the schema names
        // of the joined tables as well - so they have to be protected one by one.
        String newResult = result;
        final FromItem leftSide = item.getLeftSide();
        if (leftSide != null) {
            newResult = rewriteJoinedSide(newResult, leftSide, alwaysQuote, identifierQuoteString);
        }
        final FromItem rightSide = item.getRightSide();
        if (rightSide != null) {
            newResult = rewriteJoinedSide(newResult, rightSide, alwaysQuote, identifierQuoteString);
        }
        return newResult;
    }

    private String rewriteJoinedSide(String result, FromItem side, boolean alwaysQuote,
            String identifierQuoteString) {
        final Table table = side.getTable();
        if (table == null) {
            return result;
        }
        final String sideSql = side.toSql(isSchemaIncludedInColumnPaths());
        final String protectedSide = rewriteSchemaName(table.getSchema(), sideSql, alwaysQuote,
                identifierQuoteString);
        if (protectedSide.equals(sideSql)) {
            return result;
        }
        return result.replace(sideSql, protectedSide);
    }

    private String rewriteSchemaName(Schema schema, String result, boolean alwaysQuote,
            String identifierQuoteString) {
        if (schema == null) {
            return result;
        }
        final String schemaName = schema.getName();
        if (schemaName == null || schemaName.isEmpty()) {
            return result;
        }
        if (identifierQuoteString == null) {
            return result;
        }
        if (!alwaysQuote && !needsIdentifierQuoting(schemaName, identifierQuoteString)) {
            // ordinary schema names are rendered exactly as they always were
            return result;
        }
        // the schema name is the first part of the qualified table reference
        final String qualifiedPrefix = schemaName + ".";
        final int index = result.indexOf(qualifiedPrefix);
        if (index != 0) {
            return result;
        }
        final String quotedSchemaName = identifierQuoteString
                + escapeIdentifier(schemaName, identifierQuoteString) + identifierQuoteString;
        return result.substring(0, index) + quotedSchemaName + result.substring(index + schemaName.length());
    }

    /**
     * Protects the schema name of a rendered column reference. Dialects that
     * include the schema in the column paths (DB2 and SQL Server) render the
     * schema with AbstractSchema#getQuotedName(), which quotes the name but
     * does not escape an embedded quote character.
     *
     * @param sql the rendered column reference
     * @param selectItem the select item that the reference belongs to
     * @return the protected column reference
     */
    protected String protectColumnPath(String sql, SelectItem selectItem) {
        if (!isSchemaIncludedInColumnPaths()) {
            return sql;
        }
        if (selectItem == null || selectItem.getColumn() == null) {
            return sql;
        }
        final Table table = selectItem.getColumn().getTable();
        if (table == null) {
            return sql;
        }
        return protectSchemaQualifier(sql, table.getSchema());
    }

    private String protectSchemaQualifier(String sql, Schema schema) {
        if (schema == null) {
            return sql;
        }
        final String schemaName = schema.getName();
        if (schemaName == null || schemaName.isEmpty()) {
            return sql;
        }
        final String identifierQuoteString = getIdentifierQuoteString();
        if (identifierQuoteString == null) {
            return sql;
        }
        if (!needsIdentifierQuoting(schemaName, identifierQuoteString)) {
            // an ordinary schema name is rendered exactly as it always was
            return sql;
        }
        final String protectedSchemaName = identifierQuoteString
                + escapeIdentifier(schemaName, identifierQuoteString) + identifierQuoteString;
        final String quotedSchemaName = identifierQuoteString + schemaName + identifierQuoteString;
        if (sql.startsWith(quotedSchemaName + ".")) {
            return protectedSchemaName + sql.substring(quotedSchemaName.length());
        }
        if (sql.startsWith(schemaName + ".")) {
            return protectedSchemaName + sql.substring(schemaName.length());
        }
        return sql;
    }

    @Override
    protected String rewriteSelectItem(Query query, SelectItem item) {
        return protectColumnPath(super.rewriteSelectItem(query, item), item);
    }

    @Override
    protected String rewriteOrderByItem(Query query, OrderByItem item) {
        return protectColumnPath(super.rewriteOrderByItem(query, item), item.getSelectItem());
    }

    @Override
    protected String rewriteGroupByItem(Query query, GroupByItem item) {
        return protectColumnPath(super.rewriteGroupByItem(query, item), item.getSelectItem());
    }

    @Override
    public String rewriteFilterItem(FilterItem item) {
        return protectColumnPath(rewriteFilterItemValues(item), item.getSelectItem());
    }

    private String rewriteFilterItemValues(FilterItem item) {
        Object operand = item.getOperand();
        if (operand != null) {
            if (operand instanceof String) {
                String str = (String) operand;
                // escape single quotes
                if (str.indexOf('\'') != -1) {
                    str = escapeQuotes(str);
                    FilterItem replacementFilterItem = new FilterItem(item.getSelectItem(), item.getOperator(), str);
                    return super.rewriteFilterItem(replacementFilterItem);
                }
            } else if (operand instanceof Timestamp) {
                final String timestampLiteral = rewriteTimestamp((Timestamp) operand);
                return rewriteFilterItemWithOperandLiteral(item, timestampLiteral);
            } else if (operand instanceof Iterable || operand.getClass().isArray()) {
                // operand is a set of values (typically in combination with an
                // IN or NOT IN operator). Each individual element must be escaped.

                assert OperatorType.IN.equals(item.getOperator()) || OperatorType.NOT_IN.equals(item.getOperator());

                @SuppressWarnings("unchecked") final List<Object> elements =
                        (List<Object>) CollectionUtils.toList(operand);

                for (ListIterator<Object> it = elements.listIterator(); it.hasNext();) {
                    Object next = it.next();
                    if (next == null) {
                        logger.warn(
                                "element in IN list is NULL, which isn't supported by SQL. Stripping the element from the list: {}",
                                item);
                        it.remove();
                    } else if (next instanceof String) {
                        String str = (String) next;
                        if (str.indexOf('\'') != -1) {
                            str = escapeQuotes(str);
                            it.set(str);
                        }
                    }
                }

                FilterItem replacementFilterItem = new FilterItem(item.getSelectItem(), item.getOperator(), elements);
                return super.rewriteFilterItem(replacementFilterItem);
            }
        }
        return super.rewriteFilterItem(item);
    }

    /**
     * Rewrites a (non-compound) {@link FilterItem} when it's operand has already been rewritten into a SQL literal.
     * 
     * @param item
     * @param operandLiteral
     * @return
     */
    protected String rewriteFilterItemWithOperandLiteral(FilterItem item, String operandLiteral) {
        final OperatorType operator = item.getOperator();
        final SelectItem selectItem = item.getSelectItem();
        final StringBuilder sb = new StringBuilder();
        sb.append(selectItem.getSameQueryAlias(false));
        FilterItem.appendOperator(sb, item.getOperand(), operator);
        sb.append(operandLiteral);
        return sb.toString();
    }

    /**
     * Rewrites a {@link Timestamp} into it's literal representation as known by this SQL dialect.
     * 
     * This default implementation returns the JDBC spec's escape syntax for a timestamp: {ts 'yyyy-mm-dd hh:mm:ss.f . .
     * .'}
     * 
     * @param ts
     * @return
     */
    protected String rewriteTimestamp(Timestamp ts) {
        return "{ts '" + ts.toString() + "'}";
    }

    @Override
    public boolean isScalarFunctionSupported(ScalarFunction function) {
        return SUPPORTED_FUNCTION_CLASSES.contains(function.getClass());
    }

    @Override
    public boolean isAggregateFunctionSupported(AggregateFunction function) {
        return SUPPORTED_FUNCTION_CLASSES.contains(function.getClass());
    }

    @Override
    public boolean isFirstRowSupported(final Query query) {
        return false;
    }

    @Override
    public boolean isMaxRowsSupported() {
        return false;
    }

    @Override
    public String escapeQuotes(String item) {
        return item.replaceAll("\\'", "\\'\\'");
    }

	@Override
	public boolean isPrimaryKeySupported() {
		return true;
	}
}