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
package org.apache.metamodel.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Comparator;
import java.util.TreeMap;
import java.util.TreeSet;

import org.junit.Test;

/**
 * Tests that the comparators in this package agree on how nulls are ordered,
 * that they are symmetric (compare(a,b) is the negation of compare(b,a)) and
 * that they do not lose precision when comparing numbers of different types.
 */
public class ComparatorConsistencyTest {

    private static final Comparator<Object>[] COMPARATORS = new Comparator[] { NumberComparator.getComparator(),
            ObjectComparator.getComparator(), BooleanComparator.getComparator(), ToStringComparator.getComparator(),
            TimeComparator.getComparator() };

    @Test
    public void testAllComparatorsReturnZeroForTwoNulls() throws Exception {
        for (Comparator<Object> comparator : COMPARATORS) {
            assertEquals(comparator.getClass().getName(), 0, comparator.compare(null, null));
        }
    }

    @Test
    public void testToStringComparatorDoesNotDuplicateNullKeys() throws Exception {
        final TreeMap<Object, Object> map = new TreeMap<Object, Object>(ToStringComparator.getComparator());
        map.put(null, "first");
        map.put(null, "second");

        // the same key must be recognized as the same key, otherwise the map
        // ends up with two entries for it
        assertEquals(1, map.size());
        assertEquals("second", map.get(null));
    }

    @Test
    public void testObjectComparatorIsSymmetricForNumbersAndStrings() throws Exception {
        final Comparator<Object> comparator = ObjectComparator.getComparator();

        assertTrue(comparator.compare(50, "10") > 0);
        assertTrue(comparator.compare("10", 50) < 0);

        assertTrue(comparator.compare(9, "10") < 0);
        assertTrue(comparator.compare("10", 9) > 0);
    }

    @Test
    public void testObjectComparatorComparesNumericStringsNumerically() throws Exception {
        final Comparator<Object> comparator = ObjectComparator.getComparator();

        // this is the comparison performed by FilterItem when a filter
        // operand is a number and the row value is a numeric string (or vice
        // versa). Comparing "10" with 9 lexicographically would say that 10 is
        // smaller than 9, which is wrong.
        assertTrue(comparator.compare("10", 9) > 0);
        assertTrue(comparator.compare(9, "10") < 0);
        assertTrue(comparator.compare("10", 10) == 0);
    }

    @Test
    public void testNumberComparatorDistinguishesBigIntegerFromLong() throws Exception {
        final Comparator<Object> comparator = NumberComparator.getComparator();

        // 9007199254740993 is not exactly representable as a double - it gets
        // rounded to 9007199254740992 - which made the two distinct numbers
        // compare as equal.
        assertTrue(comparator.compare(new BigInteger("9007199254740993"), 9007199254740992L) > 0);
        assertTrue(comparator.compare(9007199254740992L, new BigInteger("9007199254740993")) < 0);
        assertTrue(comparator.compare(new BigInteger("9007199254740993"), 9007199254740993L) == 0);
    }

    @Test
    public void testNumberComparatorDistinguishesBigIntegerFromBigDecimal() throws Exception {
        final Comparator<Object> comparator = NumberComparator.getComparator();

        assertTrue(comparator.compare(new BigInteger("9007199254740993"), new BigDecimal("9007199254740992")) > 0);
        assertTrue(comparator.compare(new BigDecimal("9007199254740992"), new BigInteger("9007199254740993")) < 0);
        assertTrue(comparator.compare(new BigInteger("9007199254740993"), new BigDecimal("9007199254740993.00")) == 0);
    }

    @Test
    public void testNumberComparatorDistinguishesLargeLongFromDouble() throws Exception {
        final Comparator<Object> comparator = NumberComparator.getComparator();

        assertTrue(comparator.compare(16777217L, 16777216.0) > 0);
        assertTrue(comparator.compare(16777216.0, 16777217L) < 0);
        assertTrue(comparator.compare(16777216L, 16777216.0) == 0);
    }

    @Test
    public void testNumberComparatorKeepsDistinctValuesInSortedSet() throws Exception {
        final TreeSet<Object> set = new TreeSet<Object>(NumberComparator.getComparator());
        set.add(new BigInteger("9007199254740993"));
        set.add(9007199254740992L);

        // the two values are distinct, so neither of them may be dropped
        assertEquals(2, set.size());
    }

    @Test
    public void testNumberComparatorOrdersNullBeforeAnyValue() throws Exception {
        final Comparator<Object> comparator = NumberComparator.getComparator();

        assertTrue(comparator.compare(null, "not a number") < 0);
        assertTrue(comparator.compare("not a number", null) > 0);
        assertTrue(comparator.compare(null, "1234") < 0);
        assertTrue(comparator.compare("1234", null) > 0);
    }
}
