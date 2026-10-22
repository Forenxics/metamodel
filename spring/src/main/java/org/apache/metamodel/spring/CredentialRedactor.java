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

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Utility that prevents credentials from being disclosed in log files.
 * <p>
 * A JDBC URL routinely carries the credentials of the database account, either as
 * query parameters (<code>jdbc:postgresql://host/db?user=u&amp;password=p</code>) or
 * as user info (<code>jdbc:postgresql://u:p@host/db</code>). A failing driver
 * repeats the URL in its exception message, so an exception that is written to a
 * log leaks the credentials just as the URL itself does.
 * </p>
 */
final class CredentialRedactor {

    /** The value that replaces a secret in log output. */
    static final String MASK = "[REDACTED]";

    /**
     * Matches a 'name=value' pair whose name denotes a credential. The value may be
     * quoted, in which case the closing quote must match the opening one.
     */
    private static final Pattern SENSITIVE_PARAMETER = Pattern.compile(
            "(?i)\\b(password|passwd|pwd|pass|user|username|secret|token|session|session_?id|api_?key"
                    + "|access_?token|auth|authorization|sasl|sslkey|sslpassword|keystorepassword"
                    + "|truststorepassword)\\b(\\s*=\\s*)(\"?)([^\"&\\s,;]*)(\\3)");

    /**
     * Matches the 'user:password@' part of the authority of a URL.
     */
    private static final Pattern USER_INFO = Pattern
            .compile("(?i)([a-z][a-z0-9+.\\-]*://)([^/@\\s:]+):([^/@\\s]*)@");

    private CredentialRedactor() {
        // Utility class
    }

    /**
     * Masks the credential-bearing parts of a URL, so that it can safely be written
     * to a log.
     *
     * @param url
     *            the URL (or any other text) that may carry credentials
     * @return the text with all credentials replaced by {@link #MASK}
     */
    static String redactUrl(final String url) {
        if (url == null || url.isEmpty()) {
            return url;
        }
        return redactUserInfo(redactParameters(url));
    }

    /**
     * Masks the credential-bearing parts of a text - typically the message of an
     * exception that repeats the URL that it was raised for.
     *
     * @param text
     *            the text that may carry credentials
     * @return the text with all credentials replaced by {@link #MASK}
     */
    static String redactText(final String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        return redactUserInfo(redactParameters(text));
    }

    /**
     * Creates a throwable that carries the same type name and the same stack trace
     * as the given one, but whose message has been masked. It is used for writing an
     * exception to a log without disclosing the credentials that the exception
     * message may contain.
     * <p>
     * The original exception is not modified - and is not part of the returned
     * throwable's cause chain - so nothing that is masked here can be recovered
     * from the log.
     * </p>
     *
     * @param throwable
     *            the exception that may carry credentials
     * @return a throwable that is safe to log
     */
    static RuntimeException redactThrowable(final Throwable throwable) {
        if (throwable == null) {
            return null;
        }
        final String message = throwable.getMessage();
        final RuntimeException redacted = new RuntimeException(throwable.getClass().getName()
                + (message == null ? "" : ": " + redactText(message)));
        redacted.setStackTrace(throwable.getStackTrace());
        return redacted;
    }

    private static String redactParameters(final String text) {
        final Matcher matcher = SENSITIVE_PARAMETER.matcher(text);
        final StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            matcher.appendReplacement(result,
                    Matcher.quoteReplacement(matcher.group(1) + matcher.group(2) + MASK));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private static String redactUserInfo(final String text) {
        final Matcher matcher = USER_INFO.matcher(text);
        final StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            matcher.appendReplacement(result,
                    Matcher.quoteReplacement(matcher.group(1) + MASK + ":" + MASK + "@"));
        }
        matcher.appendTail(result);
        return result.toString();
    }
}
