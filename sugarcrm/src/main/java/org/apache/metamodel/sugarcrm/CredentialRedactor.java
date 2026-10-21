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

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Utility that prevents credentials from being disclosed in log files.
 * <p>
 * SugarCRM session IDs are bearer credentials: whoever presents a valid session ID
 * gains access to the corresponding account. The same applies to the (hashed) password
 * that is sent in the SOAP <code>login</code> request. Neither of them may be written
 * to a log.
 */
final class CredentialRedactor {

    /** The value that replaces a secret in log output. */
    static final String MASK = "[REDACTED]";

    /**
     * Matches XML elements whose name indicates that their content is a secret, like
     * 'password' or 'session_id'. The element name may be namespace prefixed and the
     * element may carry attributes.
     */
    private static final Pattern SENSITIVE_ELEMENT = Pattern.compile(
            "<([A-Za-z0-9_.:-]*(?:password|passwd|secret|token|session|session_?id)[A-Za-z0-9_.:-]*)(\\s[^>]*)?>[^<]*</\\1>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    /** Matches a 'login_result' element, which carries the session ID. */
    private static final Pattern LOGIN_RESULT_BLOCK = Pattern.compile(
            "(<login_result\\b[^>]*>)(.*?)(</login_result>)", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    /** Matches the 'id' element within a 'login_result' element, which holds the session ID. */
    private static final Pattern LOGIN_RESULT_ID = Pattern.compile("(<id\\b[^>]*>)([^<]*)(</id>)",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private CredentialRedactor() {
        // Utility class
    }

    /**
     * Redacts a single secret value. The secret itself is never returned - not even
     * truncated - so that it cannot be recovered from the log.
     *
     * @param secret
     *            the value to redact
     * @return a masked representation of the secret
     */
    static String redact(final String secret) {
        if (secret == null) {
            return null;
        }
        if (secret.isEmpty()) {
            return "";
        }
        return MASK;
    }

    /**
     * Redacts the secrets that may be present in an XML/SOAP payload, so that the
     * payload can safely be written to a log.
     *
     * @param payload
     *            the payload to redact
     * @return the payload with all secrets replaced by {@link #MASK}
     */
    static String redactPayload(final String payload) {
        if (payload == null || payload.isEmpty()) {
            return payload;
        }
        String result = SENSITIVE_ELEMENT.matcher(payload).replaceAll("<$1$2>" + MASK + "</$1>");
        return redactLoginResultSessionId(result);
    }

    /**
     * Redacts the session ID that the SugarCRM <code>login</code> response carries in
     * its 'id' element.
     */
    private static String redactLoginResultSessionId(final String payload) {
        final Matcher blockMatcher = LOGIN_RESULT_BLOCK.matcher(payload);
        final StringBuffer result = new StringBuffer();
        while (blockMatcher.find()) {
            final String redactedBlock = LOGIN_RESULT_ID.matcher(blockMatcher.group(2))
                    .replaceAll("<id>" + MASK + "</id>");
            blockMatcher.appendReplacement(result, Matcher
                    .quoteReplacement(blockMatcher.group(1) + redactedBlock + blockMatcher.group(3)));
        }
        blockMatcher.appendTail(result);
        return result.toString();
    }
}
