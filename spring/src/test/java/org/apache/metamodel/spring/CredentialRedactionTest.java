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
package org.apache.metamodel.spring;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.List;

import org.apache.log4j.AppenderSkeleton;
import org.apache.log4j.Level;
import org.apache.log4j.spi.LoggingEvent;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests that credentials are never written to the log by the Spring module.
 * <p>
 * A JDBC URL routinely carries the credentials of the database account, and a
 * failing driver repeats the URL in its exception message - so both the URL and
 * the exception have to be masked before they are logged.
 * </p>
 */
public class CredentialRedactionTest {

    private static final String SECRET = "supersecret";

    private org.apache.log4j.Logger _rootLogger;
    private Level _originalLevel;
    private CapturingAppender _appender;

    @Before
    public void setUp() {
        _rootLogger = org.apache.log4j.Logger.getRootLogger();
        _originalLevel = _rootLogger.getLevel();
        _rootLogger.setLevel(Level.DEBUG);
        _appender = new CapturingAppender();
        _rootLogger.addAppender(_appender);
    }

    @After
    public void tearDown() {
        _rootLogger.removeAppender(_appender);
        _rootLogger.setLevel(_originalLevel);
    }

    @Test
    public void testJdbcUrlWithQueryParametersIsMasked() {
        final String url = "jdbc:postgresql://db.example.com:5432/sales?user=admin&password=" + SECRET;
        final String redacted = CredentialRedactor.redactUrl(url);

        assertFalse(redacted.contains(SECRET));
        assertFalse(redacted.contains("admin"));
        assertTrue(redacted.contains(CredentialRedactor.MASK));
        // the parts that are needed to diagnose the problem must survive
        assertTrue(redacted.contains("db.example.com:5432/sales"));
    }

    @Test
    public void testJdbcUrlWithUserInfoIsMasked() {
        final String url = "jdbc:postgresql://admin:" + SECRET + "@db.example.com:5432/sales";
        final String redacted = CredentialRedactor.redactUrl(url);

        assertFalse(redacted.contains(SECRET));
        assertFalse(redacted.contains("admin"));
        assertTrue(redacted.contains(CredentialRedactor.MASK));
        assertTrue(redacted.contains("db.example.com:5432/sales"));
    }

    @Test
    public void testSqlServerStyleUrlIsMasked() {
        final String url = "jdbc:sqlserver://db.example.com:1433;database=sales;user=admin;password=" + SECRET;
        final String redacted = CredentialRedactor.redactUrl(url);

        assertFalse(redacted.contains(SECRET));
        assertFalse(redacted.contains("admin"));
        assertTrue(redacted.contains("db.example.com:1433"));
    }

    @Test
    public void testUrlWithoutCredentialsIsUnchanged() {
        final String url = "jdbc:postgresql://db.example.com:5432/sales";
        assertTrue(url.equals(CredentialRedactor.redactUrl(url)));
    }

    @Test
    public void testExceptionMessageIsMasked() {
        final String message = "No suitable driver found for jdbc:postgresql://db.example.com:5432/sales?user=admin"
                + "&password=" + SECRET;
        final String redacted = CredentialRedactor.redactText(message);

        assertFalse(redacted.contains(SECRET));
        assertFalse(redacted.contains("admin"));
        assertTrue(redacted.contains("db.example.com:5432/sales"));
    }

    /**
     * Builds the JDBC delegate with a URL that carries a password and asserts that
     * the password is not written to the log - neither in the message nor in the
     * stack trace of the logged exception.
     */
    @Test
    public void testTheJdbcDelegateDoesNotLogCredentials() throws Exception {
        final DataContextFactoryBean bean = new DataContextFactoryBean();
        bean.setType("jdbc");
        bean.setUrl("jdbc:postgresql://db.example.com:5432/sales?user=admin&password=" + SECRET);
        bean.setUsername("admin");
        bean.setPassword(SECRET);

        try {
            bean.getObject();
            fail("Expected an IllegalStateException, since no driver is registered for the URL");
        } catch (IllegalStateException e) {
            // expected
        }

        // Make sure that the log capture actually works - otherwise the assertions
        // below would pass for the wrong reason.
        assertTrue("Expected the connection failure to be logged",
                _appender.contains("Failed to get JDBC connection"));
        assertTrue("Expected the masked URL to be logged", _appender.contains("db.example.com:5432/sales"));

        assertFalse("The password was written to the log", _appender.contains(SECRET));
        assertFalse("The user name was written to the log", _appender.contains("admin"));
    }

    /**
     * An appender that collects the messages - and the rendered stack traces - of
     * the log events that it receives.
     */
    private static final class CapturingAppender extends AppenderSkeleton {

        private final List<String> _messages = new ArrayList<String>();

        @Override
        protected void append(LoggingEvent event) {
            _messages.add(event.getRenderedMessage());
            final String[] throwable = event.getThrowableStrRep();
            if (throwable != null) {
                for (String line : throwable) {
                    _messages.add(line);
                }
            }
        }

        @Override
        public void close() {
        }

        @Override
        public boolean requiresLayout() {
            return false;
        }

        boolean contains(final String text) {
            for (String message : _messages) {
                if (message.contains(text)) {
                    return true;
                }
            }
            return false;
        }
    }
}
