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
package org.apache.metamodel.query;

import junit.framework.TestCase;

/**
 * Documents the behaviour of the aggregate functions when they are evaluated
 * over an empty input set or over a set that only contains NULL values.
 */
public class AggregateFunctionTest extends TestCase {

    public void testCountOverEmptyAndNullValues() throws Exception {
        assertEquals(Long.valueOf(0), FunctionType.COUNT.evaluate());
        assertEquals(Long.valueOf(0), FunctionType.COUNT.evaluate((Object) null));
        assertEquals(Long.valueOf(0), FunctionType.COUNT.evaluate(null, null));
        assertEquals(Long.valueOf(2), FunctionType.COUNT.evaluate("a", null, "b"));
    }

    /**
     * The sum of nothing must be a number (and not null), because the rest of
     * the framework expects a numeric result.
     */
    public void testSumOverEmptyAndNullValues() throws Exception {
        assertEquals(Double.valueOf(0d), FunctionType.SUM.evaluate());
        assertEquals(Double.valueOf(0d), FunctionType.SUM.evaluate((Object) null));
        assertEquals(Double.valueOf(0d), FunctionType.SUM.evaluate(null, null));
        assertEquals(Double.valueOf(6d), FunctionType.SUM.evaluate(1, 2, 3));
    }

    /**
     * The average of nothing must not result in a division by zero.
     */
    public void testAverageOverEmptyAndNullValues() throws Exception {
        assertEquals(Double.valueOf(0d), FunctionType.AVG.evaluate());
        assertEquals(Double.valueOf(0d), FunctionType.AVG.evaluate((Object) null));
        assertEquals(Double.valueOf(0d), FunctionType.AVG.evaluate(null, null));
        assertEquals(Double.valueOf(2d), FunctionType.AVG.evaluate(1, 2, 3));
    }

    public void testMinMaxOverEmptyAndNullValues() throws Exception {
        assertNull(FunctionType.MIN.evaluate());
        assertNull(FunctionType.MAX.evaluate());
        assertNull(FunctionType.MIN.evaluate((Object) null));
        assertNull(FunctionType.MAX.evaluate((Object) null));
        assertNull(FunctionType.MIN.evaluate(null, null));
        assertNull(FunctionType.MAX.evaluate(null, null));
        assertEquals(Integer.valueOf(1), FunctionType.MIN.evaluate(3, 1, 2));
        assertEquals(Integer.valueOf(3), FunctionType.MAX.evaluate(3, 1, 2));
    }

    public void testExpectedColumnTypes() throws Exception {
        assertEquals(org.apache.metamodel.schema.ColumnType.BIGINT,
                FunctionType.COUNT.getExpectedColumnType(org.apache.metamodel.schema.ColumnType.INTEGER));
        assertEquals(org.apache.metamodel.schema.ColumnType.DOUBLE,
                FunctionType.SUM.getExpectedColumnType(org.apache.metamodel.schema.ColumnType.INTEGER));
    }
}
