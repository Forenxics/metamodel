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
package org.apache.metamodel.sugarcrm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Tests that credentials are not disclosed in log output.
 */
public class CredentialRedactorTest {

    private static final String SECRET = "5up3r53cr3t";

    @Test
    public void testRedactHidesSecret() {
        final String redacted = CredentialRedactor.redact(SECRET);
        assertFalse("The secret must not be disclosed: " + redacted, redacted.contains(SECRET));
        assertEquals(CredentialRedactor.MASK, redacted);
    }

    @Test
    public void testRedactOfNullAndEmpty() {
        assertNull(CredentialRedactor.redact(null));
        assertEquals("", CredentialRedactor.redact(""));
    }

    /**
     * The SugarCRM 'login' request carries the (hashed) password. It must not end up in
     * a log.
     */
    @Test
    public void testRedactPayloadHidesPassword() {
        final String payload = "<soap:Body><login><user_auth><user_name>admin</user_name><password>" + SECRET
                + "</password></user_auth></login></soap:Body>";
        final String redacted = CredentialRedactor.redactPayload(payload);
        assertFalse("The password must not be disclosed: " + redacted, redacted.contains(SECRET));
        assertTrue(redacted.contains(CredentialRedactor.MASK));
        // the non-sensitive parts of the payload are retained
        assertTrue(redacted.contains("admin"));
    }

    @Test
    public void testRedactPayloadHidesNamespacedPassword() {
        final String payload = "<ns2:password xmlns:ns2=\"http://www.sugarcrm.com/sugarcrm\">" + SECRET
                + "</ns2:password>";
        final String redacted = CredentialRedactor.redactPayload(payload);
        assertFalse("The password must not be disclosed: " + redacted, redacted.contains(SECRET));
    }

    /**
     * The SugarCRM 'login' response carries the session ID, which is a bearer
     * credential.
     */
    @Test
    public void testRedactPayloadHidesSessionId() {
        final String payload = "<soap:Body><login_response><login_result><id>" + SECRET
                + "</id><name_value_list/></login_result></login_response></soap:Body>";
        final String redacted = CredentialRedactor.redactPayload(payload);
        assertFalse("The session ID must not be disclosed: " + redacted, redacted.contains(SECRET));
        assertTrue(redacted.contains(CredentialRedactor.MASK));
    }

    @Test
    public void testRedactPayloadLeavesHarmlessPayloadAlone() {
        final String payload = "<soap:Body><get_entry_list><module_name>Contacts</module_name></get_entry_list></soap:Body>";
        assertEquals(payload, CredentialRedactor.redactPayload(payload));
    }

    @Test
    public void testRedactPayloadOfNullAndEmpty() {
        assertNull(CredentialRedactor.redactPayload(null));
        assertEquals("", CredentialRedactor.redactPayload(""));
    }

    /**
     * The message that is logged when a session is started must not contain the session
     * ID.
     */
    @Test
    public void testSessionStartedMessageDoesNotDiscloseSessionId() {
        final String message = SugarCrmDataContext.sessionStartedMessage(SECRET);
        assertFalse("The session ID must not be disclosed: " + message, message.contains(SECRET));
        assertTrue(message.contains("Started session with SugarCRM"));
    }

    /**
     * The SOAP payload that is logged by the {@link LoggingMessageHandler} must not
     * contain credentials.
     */
    @Test
    public void testLoggedPayloadDoesNotDisclosePassword() {
        final String payload = "<soap:Body><login><user_auth><user_name>admin</user_name><password>" + SECRET
                + "</password></user_auth></login></soap:Body>";
        final String logged = LoggingMessageHandler.redactPayload(payload);
        assertFalse("The password must not be disclosed: " + logged, logged.contains(SECRET));
        assertTrue(logged.contains(CredentialRedactor.MASK));
    }

    @Test
    public void testLoggedPayloadDoesNotDiscloseSessionId() {
        final String payload = "<soap:Body><login_response><login_result><id>" + SECRET
                + "</id></login_result></login_response></soap:Body>";
        final String logged = LoggingMessageHandler.redactPayload(payload);
        assertFalse("The session ID must not be disclosed: " + logged, logged.contains(SECRET));
    }

    /**
     * The WSDL URL that is logged may carry credentials.
     */
    @Test
    public void testWsdlUrlMessageDoesNotDiscloseCredentials() {
        final String url = "http://admin:" + SECRET + "@sugarcrm.example.com/sugar/service/v4/soap.php?wsdl";
        final String message = SugarCrmDataContext.wsdlUrlMessage(url);
        assertFalse("The password must not be disclosed: " + message, message.contains(SECRET));
        assertFalse("The user name must not be disclosed: " + message, message.contains("admin"));
        assertTrue(message.contains("sugarcrm.example.com/sugar/service/v4/soap.php"));
    }

    /**
     * The WSDL URL that is logged may carry credentials as query parameters.
     */
    @Test
    public void testWsdlUrlMessageWithQueryParametersDoesNotDiscloseCredentials() {
        final String url = "http://sugarcrm.example.com/soap.php?wsdl&user=admin&password=" + SECRET;
        final String message = SugarCrmDataContext.wsdlUrlMessage(url);
        assertFalse("The password must not be disclosed: " + message, message.contains(SECRET));
        assertFalse("The user name must not be disclosed: " + message, message.contains("admin"));
        assertTrue(message.contains("sugarcrm.example.com/soap.php"));
    }

    /**
     * The user name is part of the credentials of the account and must not be logged.
     */
    @Test
    public void testLoginMessageDoesNotDiscloseUserName() {
        final String message = SugarCrmDataContext.loginMessage("admin", "MetaModel");
        assertFalse("The user name must not be disclosed: " + message, message.contains("admin"));
        assertTrue(message.contains("MetaModel"));
    }
}
