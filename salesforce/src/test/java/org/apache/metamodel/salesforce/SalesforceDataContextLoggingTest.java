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
package org.apache.metamodel.salesforce;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.apache.log4j.AppenderSkeleton;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.apache.log4j.spi.LoggingEvent;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests that a SOQL query - and in particular the literal values that it contains - is never written
 * to the log at INFO level.
 */
public class SalesforceDataContextLoggingTest {

    private static final String SECRET_VALUE = "John Doe";

    private static final String SOQL_QUERY =
            "SELECT Id FROM Contact WHERE Name = '" + SECRET_VALUE + "' AND Age > 42";

    /**
     * Captures the log events of the logger under test.
     */
    private static class CapturingAppender extends AppenderSkeleton {
        private final List<LoggingEvent> events = new ArrayList<>();

        @Override
        protected void append(LoggingEvent event) {
            events.add(event);
        }

        @Override
        public void close() {
        }

        @Override
        public boolean requiresLayout() {
            return false;
        }
    }

    private Logger _logger;
    private CapturingAppender _appender;
    private Level _originalLevel;

    @Before
    public void before() {
        _logger = Logger.getLogger(SalesforceDataContext.class);
        _appender = new CapturingAppender();
        _originalLevel = _logger.getLevel();
        _logger.addAppender(_appender);
        _logger.setLevel(Level.DEBUG);
    }

    @After
    public void after() {
        _logger.setLevel(_originalLevel);
        _logger.removeAppender(_appender);
    }

    /**
     * The query - and above all its literal values - must not reach the log at INFO level or
     * above. Before the fix the query was logged at INFO level with all of its literals.
     */
    @Test
    public void testSoqlQueryIsNotLoggedAtInfoLevel() {
        SalesforceDataContext.logSoqlQuery(SOQL_QUERY);

        boolean sawInfoOrAbove = false;
        for (final LoggingEvent event : _appender.events) {
            if (event.getLevel().isGreaterOrEqual(Level.INFO)) {
                sawInfoOrAbove = true;
                final String message = event.getRenderedMessage();
                assertFalse("The SOQL query was logged at " + event.getLevel() + " level: " + message,
                        message.contains(SECRET_VALUE));
                assertFalse("The SOQL query was logged at " + event.getLevel() + " level: " + message,
                        message.contains("SELECT Id FROM Contact"));
            }
        }
        assertFalse("Expected the SOQL query not to be logged at INFO level or above", sawInfoOrAbove);
    }

    /**
     * The query may be logged at DEBUG level, but then only without its literal values.
     */
    @Test
    public void testSoqlQueryIsLoggedAtDebugLevelWithoutLiterals() {
        SalesforceDataContext.logSoqlQuery(SOQL_QUERY);

        boolean sawDebug = false;
        for (final LoggingEvent event : _appender.events) {
            if (Level.DEBUG.equals(event.getLevel())) {
                sawDebug = true;
                final String message = event.getRenderedMessage();
                assertFalse("The literal value of the SOQL query was logged: " + message,
                        message.contains(SECRET_VALUE));
                assertFalse("The literal value of the SOQL query was logged: " + message, message.contains("42"));
                assertTrue("Expected the shape of the query to be logged: " + message,
                        message.contains("SELECT Id FROM Contact"));
            }
        }
        assertTrue("Expected the SOQL query to be logged at DEBUG level", sawDebug);
    }

    /**
     * The redaction of the literals must not remove the structure of the query.
     */
    @Test
    public void testLiteralRedactionKeepsTheQueryStructure() {
        final String redacted = SalesforceDataContext.redactSoqlLiterals(SOQL_QUERY);

        assertFalse(redacted, redacted.contains(SECRET_VALUE));
        assertFalse(redacted, redacted.contains("42"));
        assertTrue(redacted, redacted.contains("SELECT Id FROM Contact"));
        assertTrue(redacted, redacted.contains("WHERE Name = ?"));
        assertTrue(redacted, redacted.contains("AND Age > ?"));
    }

    /**
     * Date literals are literals too.
     */
    @Test
    public void testDateLiteralsAreRedacted() {
        final String redacted = SalesforceDataContext
                .redactSoqlLiterals("SELECT Id FROM Event WHERE CreatedDate > 2013-01-23T00:00:00.000+0000");

        assertFalse(redacted, redacted.contains("2013-01-23"));
        assertTrue(redacted, redacted.contains("WHERE CreatedDate > ?"));
    }
}
