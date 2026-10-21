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

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Comparator;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Comparator that can compare numbers of various kinds (short, integer, float,
 * double etc)
 */
public final class NumberComparator implements Comparator<Object> {

    private static final Logger logger = LoggerFactory.getLogger(NumberComparator.class);

    private static final Comparator<Object> _instance = new NumberComparator();

    public static Comparator<Object> getComparator() {
        return _instance;
    }

    private NumberComparator() {
    }

    public static Comparable<Object> getComparable(Object o) {
        final Number n = toNumber(o);
        return new Comparable<Object>() {

            @Override
            public boolean equals(Object obj) {
                return _instance.equals(obj);
            }

            public int compareTo(Object o) {
                return _instance.compare(n, o);
            }

            @Override
            public String toString() {
                return "NumberComparable[number=" + n + "]";
            }

        };
    }

    public int compare(Object o1, Object o2) {
        // null is handled before the conversion below, because a value that
        // cannot be converted to a number is also represented by null - but a
        // null value is not the same thing as a non-numeric value.
        if (o1 == null && o2 == null) {
            return 0;
        }
        if (o1 == null) {
            return -1;
        }
        if (o2 == null) {
            return 1;
        }

        final Number n1 = toNumber(o1);
        final Number n2 = toNumber(o2);

        if (n1 == null && n2 == null) {
            return 0;
        }
        if (n1 == null) {
            return -1;
        }
        if (n2 == null) {
            return 1;
        }

        if (n1 instanceof BigInteger && n2 instanceof BigInteger) {
            return ((BigInteger) n1).compareTo((BigInteger) n2);
        }

        if (n1 instanceof BigDecimal && n2 instanceof BigDecimal) {
            return ((BigDecimal) n1).compareTo((BigDecimal) n2);
        }

        if (requiresExactComparison(n1, n2)) {
            final BigDecimal d1 = toBigDecimal(n1);
            final BigDecimal d2 = toBigDecimal(n2);
            if (d1 != null && d2 != null) {
                return d1.compareTo(d2);
            }
        }

        if (isIntegerType(n1) && isIntegerType(n2)) {
            return Long.valueOf(n1.longValue()).compareTo(n2.longValue());
        }

        return Double.valueOf(n1.doubleValue()).compareTo(n2.doubleValue());
    }

    /**
     * Determines whether two numbers should be compared with exact arithmetic
     * instead of by converting them to doubles. A double conversion rounds
     * values that are not exactly representable as a double (e.g. a
     * {@link BigInteger} or a long above 2^53), which makes two distinct
     * numbers compare as equal.
     *
     * @param n1
     * @param n2
     * @return true if the two numbers should be compared exactly
     */
    private static boolean requiresExactComparison(Number n1, Number n2) {
        if (isBigType(n1) || isBigType(n2)) {
            return true;
        }
        // an integer compared with a floating point number must not go through
        // a double conversion, since that loses precision for large integers
        return (isIntegerType(n1) && isFloatingType(n2)) || (isFloatingType(n1) && isIntegerType(n2));
    }

    private static boolean isBigType(Number n) {
        return n instanceof BigInteger || n instanceof BigDecimal;
    }

    private static boolean isFloatingType(Number n) {
        return n instanceof Float || n instanceof Double;
    }

    /**
     * Converts a number to a {@link BigDecimal} without loss of precision.
     *
     * @param number
     * @return a {@link BigDecimal} representation of the number, or null if the
     *         number cannot be represented exactly (e.g. NaN or infinity).
     */
    private static BigDecimal toBigDecimal(Number number) {
        if (number instanceof BigDecimal) {
            return (BigDecimal) number;
        }
        if (number instanceof BigInteger) {
            return new BigDecimal((BigInteger) number);
        }
        if (isIntegerType(number)) {
            return BigDecimal.valueOf(number.longValue());
        }
        if (isFloatingType(number)) {
            final double value = number.doubleValue();
            if (Double.isNaN(value) || Double.isInfinite(value)) {
                return null;
            }
            // Double.toString() gives the shortest decimal representation that
            // round-trips to the same double, which is the value the user
            // actually sees (unlike new BigDecimal(double), which exposes the
            // binary representation of the double).
            return new BigDecimal(Double.toString(value));
        }
        return null;
    }

    /**
     * Determines if a particular number is an integer-type number such as
     * {@link Byte}, {@link Short}, {@link Integer}, {@link Long},
     * {@link AtomicInteger} or {@link AtomicLong}.
     * 
     * Note that {@link BigInteger} is not included in this set of number
     * classes since treatment of {@link BigInteger} requires different logic.
     * 
     * @param n
     * @return
     */
    public static boolean isIntegerType(Number n) {
        return n instanceof Byte || n instanceof Short || n instanceof Integer || n instanceof Long
                || n instanceof AtomicInteger || n instanceof AtomicLong;
    }

    public static Number toNumber(Object value) {
        if (value == null) {
            return null;
        } else if (value instanceof Number) {
            return (Number) value;
        } else if (value instanceof Boolean) {
            if (Boolean.TRUE.equals(value)) {
                return 1;
            } else {
                return 0;
            }
        } else {
            final String stringValue = value.toString().trim();
            if (stringValue.isEmpty()) {
                return null;
            }

            try {
                return Integer.parseInt(stringValue);
            } catch (NumberFormatException e) {
            }
            try {
                return Long.parseLong(stringValue);
            } catch (NumberFormatException e) {
            }
            try {
                return new BigInteger(stringValue);
            } catch (NumberFormatException e) {
            }            
            try {
                return Double.parseDouble(stringValue);
            } catch (NumberFormatException e) {
            }

            // note: Boolean.parseBoolean does not throw NumberFormatException -
            // it just returns false in case of invalid values.
            {
                if ("true".equalsIgnoreCase(stringValue)) {
                    return 1;
                }
                if ("false".equalsIgnoreCase(stringValue)) {
                    return 0;
                }
            }
            logger.warn("Could not convert '{}' to number, returning null", value);
            return null;
        }
    }
}