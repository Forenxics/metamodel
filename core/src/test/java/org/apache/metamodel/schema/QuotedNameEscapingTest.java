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
package org.apache.metamodel.schema;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Collections;
import java.util.List;

import org.junit.Test;

/**
 * Tests that quoted identifiers escape any occurrence of the quote character
 * inside the name, so that a hostile identifier name cannot terminate the
 * quoted identifier early and inject SQL (CWE-89).
 */
public class QuotedNameEscapingTest {

    private static final String INJECTION = "x\" OR \"1\"=\"1";

    @Test
    public void testColumnQuotedNameEscapesEmbeddedQuote() throws Exception {
        final MutableColumn column = new MutableColumn(INJECTION);
        column.setQuote("\"");

        assertEquals("\"x\"\" OR \"\"1\"\"=\"\"1\"", column.getQuotedName());
    }

    @Test
    public void testTableQuotedNameEscapesEmbeddedQuote() throws Exception {
        final MutableTable table = new MutableTable(INJECTION);
        table.setQuote("\"");

        assertEquals("\"x\"\" OR \"\"1\"\"=\"\"1\"", table.getQuotedName());
    }

    @Test
    public void testSchemaQuotedNameEscapesEmbeddedQuote() throws Exception {
        final QuotedSchema schema = new QuotedSchema(INJECTION, "\"");

        assertEquals("\"x\"\" OR \"\"1\"\"=\"\"1\"", schema.getQuotedName());
    }

    @Test
    public void testQuotedNameIsSingleInertIdentifier() throws Exception {
        final MutableColumn column = new MutableColumn(INJECTION);
        column.setQuote("\"");

        final String quoted = column.getQuotedName();

        // the identifier must start and end with the quote character ...
        assertTrue(quoted.startsWith("\""));
        assertTrue(quoted.endsWith("\""));

        // ... and contain an even number of quote characters, so that no quote
        // character can terminate the identifier before the very last one.
        assertEquals(0, countQuotes(quoted, '"') % 2);

        // every quote character inside the identifier must be doubled, i.e.
        // there must be no lone (unescaped) quote character in the content.
        final String content = quoted.substring(1, quoted.length() - 1);
        for (int i = 0; i < content.length(); i++) {
            if (content.charAt(i) == '"') {
                assertTrue("unescaped quote character at index " + i + " of " + quoted,
                        i + 1 < content.length() && content.charAt(i + 1) == '"');
                i++;
            }
        }

        // the content between the outer quotes must decode back to the
        // original name, i.e. the name is inert data and not executable SQL.
        assertEquals(INJECTION, unescape(content));
    }

    @Test
    public void testQuotedNameEscapesEveryQuoteStyle() throws Exception {
        final String[] quotes = new String[] { "\"", "`", "]" };
        for (String quote : quotes) {
            final MutableColumn column = new MutableColumn("a" + quote + "b");
            column.setQuote(quote);

            final String quoted = column.getQuotedName();
            assertEquals(quote + "a" + quote + quote + "b" + quote, quoted);
            assertEquals(0, countQuotes(quoted, quote.charAt(0)) % 2);
        }
    }

    @Test
    public void testNameWithoutQuoteCharacterIsUnchanged() throws Exception {
        final MutableColumn column = new MutableColumn("normal_name");
        column.setQuote("\"");

        assertEquals("\"normal_name\"", column.getQuotedName());
    }

    @Test
    public void testNoQuoteReturnsUnquotedName() throws Exception {
        final MutableColumn column = new MutableColumn(INJECTION);
        assertNull(column.getQuote());

        assertEquals(INJECTION, column.getQuotedName());
    }

    @Test
    public void testNullNameReturnsNull() throws Exception {
        final MutableColumn column = new MutableColumn(null);
        column.setQuote("\"");

        assertNull(column.getQuotedName());
    }

    private static int countQuotes(String str, char quote) {
        int count = 0;
        for (int i = 0; i < str.length(); i++) {
            if (str.charAt(i) == quote) {
                count++;
            }
        }
        return count;
    }

    private static String unescape(String content) {
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < content.length(); i++) {
            final char c = content.charAt(i);
            sb.append(c);
            if (c == '"' && i + 1 < content.length() && content.charAt(i + 1) == '"') {
                i++;
            }
        }
        return sb.toString();
    }

    /**
     * Minimal {@link Schema} implementation that allows a quote string to be
     * specified, since {@link MutableSchema} does not support quoting.
     */
    private static final class QuotedSchema extends AbstractSchema {

        private static final long serialVersionUID = 1L;

        private final String name;
        private final String quote;

        public QuotedSchema(String name, String quote) {
            this.name = name;
            this.quote = quote;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public String getQuote() {
            return quote;
        }

        @Override
        public List<Table> getTables() {
            return Collections.emptyList();
        }
    }
}
