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

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.TimeZone;

import org.junit.Test;

/**
 * Tests the day arithmetic of {@link DateUtils}. A day is not always 24 hours
 * long, so adding a number of days must not be implemented by adding
 * milliseconds.
 */
public class DateUtilsDayArithmeticTest {

    private static final TimeZone NEW_YORK = TimeZone.getTimeZone("America/New_York");

    @Test
    public void testAddDaysAcrossDaylightSavingTimeEnd() throws Exception {
        // 2010-11-07 is the day on which the daylight saving time ends in
        // America/New_York. Adding a day to that date must still yield
        // midnight on 2010-11-08 - not 23:00 on 2010-11-07.
        assertEquals("2010-11-08 00:00:00", addOneDay("2010-11-07", "2010-11-08"));
    }

    @Test
    public void testAddDaysAcrossDaylightSavingTimeStart() throws Exception {
        // 2010-03-14 is the day on which the daylight saving time starts in
        // America/New_York. Adding a day to that date must still yield
        // midnight on 2010-03-15 - not 01:00 on 2010-03-15.
        assertEquals("2010-03-15 00:00:00", addOneDay("2010-03-14", "2010-03-15"));
    }

    @Test
    public void testAddDaysOnOrdinaryDay() throws Exception {
        assertEquals("2010-12-25 00:00:00", addOneDay("2010-12-24", "2010-12-25"));
    }

    /**
     * Adds one day to the given date in the America/New_York time zone and
     * returns the formatted result.
     */
    private static String addOneDay(String date, String expected) {
        final TimeZone original = TimeZone.getDefault();
        try {
            TimeZone.setDefault(NEW_YORK);
            final SimpleDateFormat inputFormat = new SimpleDateFormat("yyyy-MM-dd");
            final Date base = inputFormat.parse(date);
            final Date result = DateUtils.get(base, 1);
            final SimpleDateFormat outputFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
            return outputFormat.format(result);
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            TimeZone.setDefault(original);
        }
    }
}
