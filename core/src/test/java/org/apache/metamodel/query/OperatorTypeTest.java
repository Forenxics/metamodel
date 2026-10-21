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

import java.util.HashSet;
import java.util.Set;

import junit.framework.TestCase;

public class OperatorTypeTest extends TestCase {

    public void testConvertOperatorTypeNormal() throws Exception {
        assertEquals(OperatorType.EQUALS_TO, OperatorTypeImpl.convertOperatorType("="));
        assertEquals(OperatorType.GREATER_THAN, OperatorTypeImpl.convertOperatorType(">"));
        assertEquals(OperatorType.LESS_THAN, OperatorTypeImpl.convertOperatorType("<"));
        assertEquals(OperatorType.DIFFERENT_FROM, OperatorTypeImpl.convertOperatorType("<>"));
        assertEquals(OperatorType.LIKE, OperatorTypeImpl.convertOperatorType("LIKE"));
        assertEquals(OperatorType.IN, OperatorTypeImpl.convertOperatorType("IN"));
        assertEquals(null, OperatorTypeImpl.convertOperatorType("foo"));
    }
    
    public void testConvertOperatorTypeAliases() throws Exception {
        assertEquals(OperatorType.EQUALS_TO, OperatorTypeImpl.convertOperatorType("eq"));
        assertEquals(OperatorType.EQUALS_TO, OperatorTypeImpl.convertOperatorType("EQ"));
        assertEquals(OperatorType.EQUALS_TO, OperatorTypeImpl.convertOperatorType("EQUALS_TO"));
        assertEquals(OperatorType.EQUALS_TO, OperatorTypeImpl.convertOperatorType("=="));
        assertEquals(OperatorType.DIFFERENT_FROM, OperatorTypeImpl.convertOperatorType("!="));
        assertEquals(OperatorType.DIFFERENT_FROM, OperatorTypeImpl.convertOperatorType("DIFFERENT_FROM"));
        assertEquals(OperatorType.DIFFERENT_FROM, OperatorTypeImpl.convertOperatorType("ne"));
        assertEquals(OperatorType.DIFFERENT_FROM, OperatorTypeImpl.convertOperatorType("NOT_EQUALS_TO"));
        assertEquals(OperatorType.DIFFERENT_FROM, OperatorTypeImpl.convertOperatorType("NOT_EQUAL_TO"));
        assertEquals(OperatorType.IN, OperatorTypeImpl.convertOperatorType("in"));
        assertEquals(OperatorType.IN, OperatorTypeImpl.convertOperatorType("IN"));
        assertEquals(OperatorType.GREATER_THAN, OperatorTypeImpl.convertOperatorType("GREATER_THAN"));
        assertEquals(OperatorType.GREATER_THAN, OperatorTypeImpl.convertOperatorType("GT"));
        assertEquals(OperatorType.GREATER_THAN_OR_EQUAL, OperatorTypeImpl.convertOperatorType(">="));
        assertEquals(OperatorType.GREATER_THAN_OR_EQUAL, OperatorTypeImpl.convertOperatorType("=>"));
        assertEquals(OperatorType.GREATER_THAN_OR_EQUAL, OperatorTypeImpl.convertOperatorType("GREATER_THAN_OR_EQUAL"));
        assertEquals(OperatorType.LESS_THAN, OperatorTypeImpl.convertOperatorType("lt"));
        assertEquals(OperatorType.LESS_THAN, OperatorTypeImpl.convertOperatorType("LESS_THAN"));
        assertEquals(OperatorType.LESS_THAN_OR_EQUAL, OperatorTypeImpl.convertOperatorType("<="));
        assertEquals(OperatorType.LESS_THAN_OR_EQUAL, OperatorTypeImpl.convertOperatorType("=<"));
        assertEquals(OperatorType.LESS_THAN_OR_EQUAL, OperatorTypeImpl.convertOperatorType("LESS_THAN_OR_EQUAL"));
        assertEquals(OperatorType.LIKE, OperatorTypeImpl.convertOperatorType("like"));
    }
    
    public void testConvertOperatorTypeTrimmed() throws Exception {
        assertEquals(OperatorType.EQUALS_TO, OperatorTypeImpl.convertOperatorType("  =   "));
    }

    /**
     * Verifies that {@link OperatorTypeImpl} honours the equals/hashCode
     * contract: equal operator types must produce equal hash codes, otherwise
     * they end up in different buckets of a {@link HashSet} or
     * {@link java.util.HashMap}.
     */
    public void testEqualsAndHashCodeContract() throws Exception {
        OperatorType op1 = new OperatorTypeImpl("=", false);
        OperatorType op2 = new OperatorTypeImpl("=", false);

        assertEquals(op1, op2);
        assertEquals(op1.hashCode(), op2.hashCode());

        Set<OperatorType> set = new HashSet<OperatorType>();
        set.add(op1);
        set.add(op2);
        assertEquals(1, set.size());
        assertTrue(set.contains(op1));
        assertTrue(set.contains(op2));
    }

    /**
     * All built-in operators must collapse to a single entry per operator when
     * both the built-in constant and an equal-but-distinct instance are added
     * to the same set.
     */
    public void testBuiltInOperatorsHashCodeConsistent() throws Exception {
        Set<OperatorType> set = new HashSet<OperatorType>();
        for (OperatorType operator : OperatorType.BUILT_IN_OPERATORS) {
            set.add(operator);
            set.add(new OperatorTypeImpl(operator.toSql(), operator.isSpaceDelimited()));
        }
        assertEquals(OperatorType.BUILT_IN_OPERATORS.length, set.size());
    }

    public void testNonEqualOperatorsAreNotCollapsed() throws Exception {
        OperatorType op1 = new OperatorTypeImpl("=", false);
        OperatorType op2 = new OperatorTypeImpl("=", true);
        assertFalse(op1.equals(op2));

        Set<OperatorType> set = new HashSet<OperatorType>();
        set.add(op1);
        set.add(op2);
        assertEquals(2, set.size());
    }
}
