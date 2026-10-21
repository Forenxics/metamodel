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
package org.apache.metamodel.convert;

import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.function.Function;

import org.apache.metamodel.util.TimeComparator;

/**
 * A {@link TypeConverter} that converts String values (on the physical layer)
 * to interpreted {@link Date}s.
 */
public class StringToDateConverter implements TypeConverter<String, Date> {

    private final Function<Date, String> _serializeFunc;
    private final Function<String, Date> _deserializeFunc;

    /**
     * Constructs a new {@link StringToDateConverter} which will use the
     * {@link TimeComparator#toDate(Object)} method for parsing dates and the
     * {@link DateFormat#MEDIUM} date time format for physical representation.
     * <p>
     * The physical representation is parsed back using the very same
     * {@link DateFormat#MEDIUM} format that produced it, so that a value can
     * always be converted back into the {@link Date} it was created from.
     */
    public StringToDateConverter() {
        _deserializeFunc = string -> {
            Date date = parseWithMediumDateFormat(string);
            if (date == null) {
                date = TimeComparator.toDate(string);
            }
            return date;
        };
        _serializeFunc = date -> {
            return DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.MEDIUM).format(date);
        };
    }

    /**
     * Constructs a new {@link StringToDateConverter} using a given date
     * pattern.
     * <p>
     * The pattern is interpreted using the JVM's default {@link Locale}. Use
     * {@link #StringToDateConverter(String, Locale)} in order to specify the
     * locale explicitly.
     *
     * @param datePattern
     *            a String date pattern, corresponding to the syntax of a
     *            {@link SimpleDateFormat}.
     */
    public StringToDateConverter(String datePattern) {
        this(datePattern, null);
    }

    /**
     * Constructs a new {@link StringToDateConverter} using a given date
     * pattern and {@link Locale}.
     *
     * @param datePattern
     *            a String date pattern, corresponding to the syntax of a
     *            {@link SimpleDateFormat}.
     * @param locale
     *            the {@link Locale} to use for interpreting the pattern, or
     *            null in order to use the JVM's default {@link Locale}.
     */
    public StringToDateConverter(String datePattern, Locale locale) {
        // create a format eagerly, in order to fail fast on an invalid
        // pattern (and to preserve the behaviour of previous versions of this
        // class, which validated the pattern up front)
        createDateFormat(datePattern, locale);

        _deserializeFunc = string -> {
            DateFormat dateFormat = createDateFormat(datePattern, locale);
            try {
                return dateFormat.parse(string);
            } catch (ParseException e) {
                throw new IllegalArgumentException("Could not parse date string: " + string);
            }
        };
        _serializeFunc = date -> {
            return createDateFormat(datePattern, locale).format(date);
        };
    }

    /**
     * Constructs a new {@link StringToDateConverter} using a given
     * {@link DateFormat}.
     * <p>
     * Note that {@link DateFormat} implementations - notably
     * {@link SimpleDateFormat} - are not thread safe. The supplied instance is
     * therefore guarded by its own monitor, so that the converter can safely
     * be used by multiple threads.
     *
     * @param dateFormat
     *            the {@link DateFormat} to use for parsing and formatting
     *            dates.
     */
    public StringToDateConverter(final DateFormat dateFormat) {
        if (dateFormat == null) {
            throw new IllegalArgumentException("DateFormat cannot be null");
        }
        _deserializeFunc = string -> {
            synchronized (dateFormat) {
                try {
                    return dateFormat.parse(string);
                } catch (ParseException e) {
                    throw new IllegalArgumentException("Could not parse date string: " + string);
                }
            }
        };
        _serializeFunc = date -> {
            synchronized (dateFormat) {
                return dateFormat.format(date);
            }
        };
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String toPhysicalValue(Date virtualValue) {
        if (virtualValue == null) {
            return null;
        }
        return _serializeFunc.apply(virtualValue);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Date toVirtualValue(String physicalValue) {
        if (physicalValue == null || physicalValue.length() == 0) {
            return null;
        }
        return _deserializeFunc.apply(physicalValue);
    }

    private static DateFormat createDateFormat(String datePattern, Locale locale) {
        if (locale == null) {
            return new SimpleDateFormat(datePattern);
        }
        return new SimpleDateFormat(datePattern, locale);
    }

    /**
     * Parses a string using the same {@link DateFormat#MEDIUM} format that is
     * used for the physical representation of a date.
     *
     * @param string
     * @return the parsed date, or null if the string could not be parsed.
     */
    private static Date parseWithMediumDateFormat(String string) {
        DateFormat dateFormat = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.MEDIUM);
        try {
            return dateFormat.parse(string);
        } catch (ParseException e) {
            return null;
        }
    }
}
