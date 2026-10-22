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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileWriter;
import java.io.Writer;
import java.util.List;

import javax.xml.parsers.DocumentBuilder;

import org.apache.metamodel.schema.Column;
import org.apache.metamodel.schema.ColumnType;
import org.apache.metamodel.util.SimpleTableDef;
import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.xml.sax.SAXParseException;

import com.sugarcrm.ws.soap.SelectFields;

/**
 * Tests that the XML handling of the SugarCRM module is hardened against XML
 * External Entity (XXE) attacks (CWE-611).
 * <p>
 * The XML that this module handles is supplied by a remote SugarCRM server, so a
 * hostile (or compromised) server must not be able to make the client read local
 * files or issue outbound requests.
 * </p>
 */
public class SugarCrmXmlSecurityTest {

    private static final String SECRET = "TOP_SECRET_FILE_CONTENT";

    @Test
    public void testExternalEntityIsNotResolved() throws Exception {
        final File secretFile = createSecretFile();

        final String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<!DOCTYPE root [ <!ENTITY xxe SYSTEM \"" + secretFile.toURI() + "\"> ]>\n"
                + "<root>&xxe;</root>";

        final DocumentBuilder builder = SugarCrmXmlHelper.createDocumentBuilderFactory().newDocumentBuilder();

        Document document = null;
        boolean rejected = false;
        try {
            document = builder.parse(new ByteArrayInputStream(xml.getBytes("UTF-8")));
        } catch (SAXParseException e) {
            // expected: the DOCTYPE declaration is rejected outright
            rejected = true;
        }

        final String output = document == null ? "" : document.getDocumentElement().getTextContent();
        assertFalse("The external entity was resolved - the content of a local file leaked into the parsed output",
                output.contains(SECRET));
        assertTrue("The DOCTYPE declaration should have been rejected", rejected);
    }

    /**
     * A document that does not carry a DOCTYPE declaration must still be parsable -
     * the hardening must not break ordinary XML.
     */
    @Test
    public void testDocumentWithoutDoctypeIsStillParsed() throws Exception {
        final String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<root><child name=\"foo\">bar</child></root>";

        final DocumentBuilder builder = SugarCrmXmlHelper.createDocumentBuilderFactory().newDocumentBuilder();
        final Document document = builder.parse(new ByteArrayInputStream(xml.getBytes("UTF-8")));

        assertNotNull(document);
        assertEquals("root", document.getDocumentElement().getNodeName());
        assertEquals("bar", document.getDocumentElement().getTextContent());
    }

    /**
     * The DOM that the module builds for the outgoing SOAP request must be
     * unchanged.
     */
    @Test
    public void testCreateSelectFieldsIsUnchanged() {
        final SimpleTableDef tableDef = new SimpleTableDef("songs", new String[] { "id", "title" },
                new ColumnType[] { ColumnType.VARCHAR, ColumnType.VARCHAR });
        final List<Column> columns = tableDef.toTable().getColumns();

        final SelectFields selectFields = SugarCrmXmlHelper.createSelectFields(columns);

        assertNotNull(selectFields);
        final List<Object> any = selectFields.getAny();
        assertEquals(2, any.size());
        for (int i = 0; i < any.size(); i++) {
            final Element element = (Element) any.get(i);
            assertEquals("v", element.getNodeName());
            assertEquals(columns.get(i).getName(), element.getTextContent());
        }
    }

    private File createSecretFile() throws Exception {
        final File file = File.createTempFile("metamodel-xxe", ".txt");
        file.deleteOnExit();
        final Writer writer = new FileWriter(file);
        try {
            writer.write(SECRET);
        } finally {
            writer.close();
        }
        return file;
    }
}
