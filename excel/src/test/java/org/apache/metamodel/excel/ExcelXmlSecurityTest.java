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
package org.apache.metamodel.excel;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.StringReader;

import org.junit.Test;
import org.xml.sax.InputSource;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.DefaultHandler;

/**
 * Tests that the XML parts of a workbook are parsed without resolving XML
 * external entities (XXE - CWE-611).
 *
 * <p>
 * An {@code .xlsx} file is a ZIP archive whose parts are XML documents that the
 * author of the file fully controls. A workbook that declares a {@code DOCTYPE}
 * with a {@code SYSTEM} entity must never cause the contents of a local file to
 * be read and embedded in the parsed result.
 * </p>
 */
public class ExcelXmlSecurityTest {

    /**
     * Content that must never leak into the parsed output.
     */
    private static final String SECRET = "TOP-SECRET-CONTENT-8f3a91";

    @Test
    public void testExternalEntityIsNotExpanded() throws Exception {
        final File secretFile = createSecretFile();
        final String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<!DOCTYPE root [ <!ENTITY xxe SYSTEM \"" + toFileUrl(secretFile) + "\"> ]>\n"
                + "<root><value>&xxe;</value></root>\n";

        final String output = parse(xml);

        assertFalse("external entity was expanded, leaking local file contents: " + output,
                output.contains(SECRET));
    }

    @Test
    public void testInternalEntityIsNotExpanded() throws Exception {
        final String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<!DOCTYPE root [ <!ENTITY xxe \"" + SECRET + "\"> ]>\n"
                + "<root><value>&xxe;</value></root>\n";

        final String output = parse(xml);

        assertFalse("internal entity was expanded: " + output, output.contains(SECRET));
    }

    @Test
    public void testDoctypeDeclarationIsRejected() throws Exception {
        final String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<!DOCTYPE root [ <!ENTITY xxe \"" + SECRET + "\"> ]>\n"
                + "<root><value>&xxe;</value></root>\n";

        try {
            final XMLReader reader = ExcelUtils.createXmlReader();
            reader.parse(new InputSource(new StringReader(xml)));
            fail("expected the parser to reject a document with a DOCTYPE declaration");
        } catch (Exception e) {
            // expected - the parser fails closed on a hostile document
        }
    }

    @Test
    public void testWellFormedDocumentStillParses() throws Exception {
        final String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<root><value>hello &amp; goodbye</value></root>\n";

        final String output = parse(xml);

        assertTrue("a well-formed document must still parse: " + output,
                output.contains("hello & goodbye"));
    }

    /**
     * Parses the given document with the hardened reader used for workbook parts
     * and returns everything that was published as character data.
     */
    private String parse(String xml) throws Exception {
        final StringBuilder sb = new StringBuilder();
        try {
            final XMLReader reader = ExcelUtils.createXmlReader();
            reader.setContentHandler(new DefaultHandler() {
                @Override
                public void characters(char[] ch, int start, int length) {
                    sb.append(ch, start, length);
                }
            });
            reader.parse(new InputSource(new StringReader(xml)));
        } catch (Exception e) {
            // The parser is expected to fail closed on a hostile document.
            sb.append(e.toString());
        }
        return sb.toString();
    }

    private File createSecretFile() throws IOException {
        final File file = File.createTempFile("metamodel-xxe-secret", ".txt");
        file.deleteOnExit();
        final OutputStream out = new FileOutputStream(file);
        try {
            out.write(SECRET.getBytes("UTF-8"));
        } finally {
            out.close();
        }
        return file;
    }

    private String toFileUrl(File file) throws IOException {
        return file.toURI().toURL().toString();
    }
}
