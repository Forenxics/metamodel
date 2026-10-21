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
package org.apache.metamodel.xml;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;

import junit.framework.TestCase;

import org.apache.metamodel.DataContext;
import org.apache.metamodel.data.DataSet;
import org.apache.metamodel.schema.Schema;
import org.apache.metamodel.schema.Table;

/**
 * Tests that the XML module does not resolve XML external entities (XXE - CWE-611).
 *
 * <p>
 * The documents parsed by this module are untrusted input. A document that declares a
 * {@code DOCTYPE} with a {@code SYSTEM} entity must never cause the contents of a local
 * file to be read and embedded in the parsed result.
 * </p>
 */
public class XmlXxeTest extends TestCase {

    /**
     * The content that must never leak into parsed output.
     */
    private static final String SECRET = "TOP-SECRET-CONTENT-8f3a91";

    public void testSaxExternalEntityIsNotExpanded() throws Exception {
        final File secretFile = createSecretFile();
        final File xmlFile = createXmlFile(
                "<!DOCTYPE root [ <!ENTITY xxe SYSTEM \"" + toFileUrl(secretFile) + "\"> ]>");

        final XmlSaxTableDef tableDef = new XmlSaxTableDef("/root", new String[] { "/root/value" });
        final DataContext dataContext = new XmlSaxDataContext(xmlFile, tableDef);

        final String output = drain(dataContext);
        assertFalse("External entity was expanded, leaking local file contents: " + output,
                output.contains(SECRET));
    }

    public void testSaxInternalEntityIsNotExpanded() throws Exception {
        final File xmlFile = createXmlFile(
                "<!DOCTYPE root [ <!ENTITY xxe \"" + SECRET + "\"> ]>");

        final XmlSaxTableDef tableDef = new XmlSaxTableDef("/root", new String[] { "/root/value" });
        final DataContext dataContext = new XmlSaxDataContext(xmlFile, tableDef);

        final String output = drain(dataContext);
        assertFalse("Internal entity was expanded: " + output, output.contains(SECRET));
    }

    public void testSaxRejectsDoctypeDeclaration() throws Exception {
        final File xmlFile = createXmlFile("<!DOCTYPE root [ <!ENTITY xxe \"" + SECRET + "\"> ]>");

        final XmlSaxTableDef tableDef = new XmlSaxTableDef("/root", new String[] { "/root/value" });
        final DataContext dataContext = new XmlSaxDataContext(xmlFile, tableDef);

        try {
            dataContext.getDefaultSchema();
            fail("Expected the SAX parser to reject a document with a DOCTYPE declaration");
        } catch (Exception e) {
            // expected: the DOCTYPE declaration is rejected outright
        }
    }

    public void testDomExternalEntityIsNotExpanded() throws Exception {
        final File secretFile = createSecretFile();
        final File xmlFile = createXmlFile(
                "<!DOCTYPE root [ <!ENTITY xxe SYSTEM \"" + toFileUrl(secretFile) + "\"> ]>");

        final XmlDomDataContext dataContext = new XmlDomDataContext(xmlFile, true);

        final String output = drain(dataContext);
        assertFalse("External entity was expanded, leaking local file contents: " + output,
                output.contains(SECRET));
    }

    public void testDomInternalEntityIsNotExpanded() throws Exception {
        final File xmlFile = createXmlFile(
                "<!DOCTYPE root [ <!ENTITY xxe \"" + SECRET + "\"> ]>");

        final XmlDomDataContext dataContext = new XmlDomDataContext(xmlFile, true);

        final String output = drain(dataContext);
        assertFalse("Internal entity was expanded: " + output, output.contains(SECRET));
    }

    public void testDomRejectsDoctypeDeclaration() throws Exception {
        final File xmlFile = createXmlFile("<!DOCTYPE root [ <!ENTITY xxe \"" + SECRET + "\"> ]>");

        final XmlDomDataContext dataContext = new XmlDomDataContext(xmlFile, true);

        try {
            dataContext.getDefaultSchema();
            fail("Expected the DOM parser to reject a document with a DOCTYPE declaration");
        } catch (Exception e) {
            // expected: the DOCTYPE declaration is rejected outright
        }
    }

    /**
     * Executes a query against the given context and returns everything that was
     * published, so that the test can assert on what did (not) leak into the output.
     */
    private String drain(DataContext dataContext) {
        final StringBuilder sb = new StringBuilder();
        try {
            final Schema schema = dataContext.getDefaultSchema();
            for (Table table : schema.getTables()) {
                sb.append(table.toString());
                final DataSet dataSet = dataContext.query().from(table).select(table.getColumns()).execute();
                try {
                    while (dataSet.next()) {
                        sb.append(dataSet.getRow().toString());
                    }
                } finally {
                    dataSet.close();
                }
            }
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

    private File createXmlFile(String doctype) throws IOException {
        final File file = File.createTempFile("metamodel-xxe-document", ".xml");
        file.deleteOnExit();
        final String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" + doctype + "\n"
                + "<root><value>&xxe;</value></root>\n";
        final OutputStream out = new FileOutputStream(file);
        try {
            out.write(xml.getBytes("UTF-8"));
        } finally {
            out.close();
        }
        return file;
    }

    private String toFileUrl(File file) throws IOException {
        return file.toURI().toURL().toString();
    }
}
