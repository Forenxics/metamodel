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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamReader;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

/**
 * Tests that the <em>inbound</em> SOAP response of the SugarCRM web service cannot
 * be used to perform an XML External Entity (XXE) attack (CWE-611) against this
 * module.
 * <p>
 * The module's own DOM parsing is hardened by {@link SugarCrmXmlSecurity}, but the
 * SOAP response itself is parsed by the JAX-WS runtime (the JAX-WS RI) before the
 * module ever sees it. This test drives the module's real SOAP call path
 * ({@link SugarCrmDataContext}) against a local HTTP server that answers with a
 * SOAP response carrying an XXE payload, and asserts that the entity is never
 * expanded.
 * </p>
 * <p>
 * The relevant configuration is owned by the JAX-WS RI and not by this module:
 * {@code com.sun.xml.ws.api.streaming.XMLStreamReaderFactory} creates the inbound
 * StAX reader and sets {@code javax.xml.stream.supportDTD=false} and
 * {@code javax.xml.stream.isSupportingExternalEntities=false} on it (see
 * {@code com.sun.xml.ws.util.xml.XmlUtil#newXMLInputFactory(boolean)} and
 * {@code XMLStreamReaderFactory#getXMLInputFactory()}). With DTD support
 * disabled, the reader skips the {@code DOCTYPE} declaration entirely, so an
 * entity reference is simply undeclared and the message is rejected instead of
 * being expanded.
 * </p>
 * <p>
 * The tests below are self-contained: they only use a loopback HTTP server on an
 * ephemeral port, they never touch the network, and they do not depend on a
 * running SugarCRM instance.
 * </p>
 */
public class SugarCrmSoapXxeTest {

    /**
     * The content of the file that the XXE payload points at. It must never show
     * up in anything that the module parses or sends.
     */
    private static final String SECRET = "TOP_SECRET_FILE_CONTENT";

    /**
     * The value that the internal entity of the XXE payload expands to.
     */
    private static final String INTERNAL_MARKER = "INTERNAL_ENTITY_MARKER_VALUE";

    /**
     * The session ID that the fake SugarCRM server hands out. The module sends it
     * back to the server in its next request, which is how this test observes what
     * the module actually parsed.
     */
    private static final String SESSION_ID = "SESSION_ID_FROM_TEST_SERVER";

    private static final String ENDPOINT_PATH = "/service/v4/soap.php";

    private HttpServer _server;
    private int _port;
    private byte[] _wsdl;

    /**
     * The SOAP response that the fake server returns for the 'login' operation.
     */
    private volatile String _loginResponse;

    /**
     * The content type that the fake server answers with. It is a field so that the
     * tests can check that the verdict does not depend on it.
     */
    private volatile String _contentType = "text/xml; charset=utf-8";

    /**
     * Every request body that the fake server received.
     */
    private final List<String> _requestBodies = Collections.synchronizedList(new ArrayList<String>());

    @Before
    public void setUp() throws Exception {
        _wsdl = readAllBytes(locateWsdl());

        _server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        _server.createContext(ENDPOINT_PATH, new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                try {
                    final String query = exchange.getRequestURI().getQuery();
                    final String requestBody = new String(readAllBytes(exchange.getRequestBody()), "UTF-8");
                    _requestBodies.add(requestBody);

                    final byte[] response;
                    if (query != null && query.contains("wsdl")) {
                        response = _wsdl;
                    } else if (requestBody.contains("get_module_fields")) {
                        response = getModuleFieldsResponse().getBytes("UTF-8");
                    } else {
                        response = _loginResponse.getBytes("UTF-8");
                    }

                    exchange.getResponseHeaders().add("Content-Type", _contentType);
                    exchange.sendResponseHeaders(200, response.length);
                    exchange.getResponseBody().write(response);
                } finally {
                    exchange.close();
                }
            }
        });
        _server.start();
        _port = _server.getAddress().getPort();
    }

    @After
    public void tearDown() {
        if (_server != null) {
            _server.stop(0);
            _server = null;
        }
    }

    /**
     * Control test: a SOAP response without a {@code DOCTYPE} declaration is parsed
     * by the JAX-WS runtime and the module picks up the session ID from it. This
     * proves that the test harness really drives the module's SOAP call path, and
     * therefore that the assertions made by the XXE tests below are meaningful and
     * not merely the result of a broken harness.
     */
    @Test
    public void testBenignSoapResponseIsParsedByTheSoapCallPath() throws Exception {
        _loginResponse = soapResponse(null, "<id>" + SESSION_ID + "</id>");

        final SugarCrmDataContext dataContext = newContext();
        try {
            final int columnCount = dataContext.getDefaultSchema().getTableByName("Accounts").getColumns().size();
            assertTrue("The benign SOAP response should have been parsed by the SOAP call path", columnCount > 0);
        } finally {
            dataContext.close();
        }

        assertTrue("The module should have sent the session ID that it parsed from the SOAP response back to the "
                + "server", traffic().contains(SESSION_ID));
    }

    /**
     * An external entity in the inbound SOAP response must not be resolved: the
     * content of a local file must never reach the module.
     */
    @Test
    public void testInboundSoapResponseWithExternalEntityIsNotExpanded() throws Exception {
        final File secretFile = createSecretFile();
        final String doctype = "<!DOCTYPE soapenv:Envelope [ <!ENTITY xxe SYSTEM \"" + secretFile.toURI() + "\"> ]>";

        _loginResponse = soapResponse(doctype, "<id>&xxe;</id>");

        driveSoapCallPath();

        assertNotLeaked(SECRET, "the content of a local file was resolved by the SOAP response parser");
    }

    /**
     * An internal entity in the inbound SOAP response must not be expanded either.
     */
    @Test
    public void testInboundSoapResponseWithInternalEntityIsNotExpanded() throws Exception {
        final String doctype = "<!DOCTYPE soapenv:Envelope [ <!ENTITY xxe \"" + INTERNAL_MARKER + "\"> ]>";

        _loginResponse = soapResponse(doctype, "<id>&xxe;</id>");

        driveSoapCallPath();

        assertNotLeaked(INTERNAL_MARKER, "an entity declared in the SOAP response was expanded");
    }

    /**
     * An <em>external parameter entity</em> in the inbound SOAP response must not be
     * resolved either. This is the classic bypass shape: a parameter entity is
     * declared in the internal DTD subset and referenced from the subset itself, so
     * a parser that only checks for general entity references can still be made to
     * read a local file.
     */
    @Test
    public void testInboundSoapResponseWithParameterEntityIsNotExpanded() throws Exception {
        final File secretFile = createSecretFile();
        final String doctype = "<!DOCTYPE soapenv:Envelope [ <!ENTITY % xxe SYSTEM \"" + secretFile.toURI()
                + "\"> %xxe; ]>";

        _loginResponse = soapResponse(doctype, "<id>&xxe;</id>");

        driveSoapCallPath();

        assertNotLeaked(SECRET, "the content of a local file was resolved by the SOAP response parser");
    }

    /**
     * An <em>external DTD subset</em> must not be fetched. The DTD subset itself is
     * where an entity would be declared, so fetching it is the first step of the
     * attack even when the response body itself contains no entity reference.
     */
    @Test
    public void testInboundSoapResponseWithExternalDtdSubsetIsNotResolved() throws Exception {
        final File secretFile = createSecretFile();
        final String doctype = "<!DOCTYPE soapenv:Envelope SYSTEM \"" + secretFile.toURI() + "\">";

        _loginResponse = soapResponse(doctype, "<id>x</id>");

        driveSoapCallPath();

        assertNotLeaked(SECRET, "an external DTD subset was resolved by the SOAP response parser");
    }

    /**
     * A "billion laughs" payload must not be expanded either: the parser has to skip
     * the DTD declaration, not read it and expand the entities it declares.
     */
    @Test
    public void testInboundSoapResponseWithNestedEntitiesIsNotExpanded() throws Exception {
        final String doctype = "<!DOCTYPE soapenv:Envelope [ <!ENTITY a \"" + INTERNAL_MARKER
                + "\"> <!ENTITY b \"&a;&a;&a;&a;\"> <!ENTITY c \"&b;&b;&b;&b;\"> ]>";

        _loginResponse = soapResponse(doctype, "<id>&c;</id>");

        driveSoapCallPath();

        assertNotLeaked(INTERNAL_MARKER, "an entity declared in the SOAP response was expanded");
    }

    /**
     * The verdict must not depend on the content type the server answers with. The
     * JAX-WS runtime only accepts a SOAP content type; the two it accepts are
     * checked here, and for each of them the XXE payload must not be expanded.
     */
    @Test
    public void testVerdictDoesNotDependOnTheContentType() throws Exception {
        final File secretFile = createSecretFile();
        final String doctype = "<!DOCTYPE soapenv:Envelope [ <!ENTITY xxe SYSTEM \"" + secretFile.toURI() + "\"> ]>";

        for (String contentType : new String[] { "text/xml; charset=utf-8", "text/xml", "application/soap+xml",
                "application/xml" }) {
            _contentType = contentType;
            _loginResponse = soapResponse(doctype, "<id>&xxe;</id>");
            _requestBodies.clear();

            driveSoapCallPath();

            assertNotLeaked(SECRET, "the content of a local file was resolved by the SOAP response parser (content type: "
                    + contentType + ")");
        }
    }

    /**
     * Positive control: the very same payload <em>is</em> expanded by a parser that
     * has not been hardened. This proves that the payload used by the tests above
     * is a genuine XXE and that those tests would fail if the SOAP response
     * parser were ever weakened.
     */
    @Test
    public void testXxePayloadIsExpandedByAPermissiveParser() throws Exception {
        final File secretFile = createSecretFile();
        final String payload = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<!DOCTYPE root [ <!ENTITY xxe SYSTEM \"" + secretFile.toURI() + "\"> ]>\n"
                + "<root>&xxe;</root>";

        final XMLInputFactory factory = XMLInputFactory.newInstance();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, Boolean.TRUE);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, Boolean.TRUE);

        final XMLStreamReader reader = factory
                .createXMLStreamReader(new java.io.ByteArrayInputStream(payload.getBytes("UTF-8")));
        final StringBuilder text = new StringBuilder();
        try {
            while (reader.hasNext()) {
                if (reader.next() == XMLStreamReader.CHARACTERS) {
                    text.append(reader.getText());
                }
            }
        } finally {
            reader.close();
        }

        assertTrue("A permissive parser should have expanded the external entity - if it does not, the XXE tests "
                + "above no longer prove anything", text.toString().contains(SECRET));
    }

    /**
     * Drives the module's SOAP call path against the fake server and swallows any
     * failure: the module is expected to reject the malformed (entity bearing)
     * response, and the assertions are made on what the module actually did.
     */
    private void driveSoapCallPath() {
        final SugarCrmDataContext dataContext = newContext();
        try {
            dataContext.getDefaultSchema().getTableByName("Accounts").getColumns();
        } catch (Throwable t) {
            // expected: the JAX-WS runtime rejects the entity bearing response.
            // What matters is that the entity was not expanded, which is asserted
            // on the traffic that the module produced.
        } finally {
            try {
                dataContext.close();
            } catch (Exception e) {
                // ignore
            }
        }
    }

    /**
     * Asserts that a value did not show up anywhere in the traffic that the module
     * produced.
     *
     * @param value
     *            the value that must not have been produced
     * @param description
     *            what the value represents, used in the failure message
     */
    private void assertNotLeaked(String value, String description) {
        final String traffic = traffic().replaceAll("\\s+", " ");
        assertFalse("The SOAP response parser expanded an entity: " + description + ". Traffic: " + traffic,
                traffic.contains(value));
    }

    /**
     * @return all request bodies that the fake server received
     */
    private String traffic() {
        final StringBuilder sb = new StringBuilder();
        synchronized (_requestBodies) {
            for (String body : _requestBodies) {
                sb.append(body).append('\n');
            }
        }
        return sb.toString();
    }

    private SugarCrmDataContext newContext() {
        return new SugarCrmDataContext("http://127.0.0.1:" + _port, "admin", "password", "Test");
    }

    /**
     * Builds a SOAP response for the 'login' operation.
     *
     * @param doctype
     *            the (optional) DOCTYPE declaration to put in front of the
     *            document
     * @param idElement
     *            the content of the 'id' element, which is where the session ID is
     *            carried
     * @return a SOAP response document
     */
    private String soapResponse(String doctype, String idElement) {
        final StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        if (doctype != null) {
            sb.append(doctype).append('\n');
        }
        sb.append("<soapenv:Envelope xmlns:soapenv=\"http://schemas.xmlsoap.org/soap/envelope/\">\n");
        sb.append(" <soapenv:Body>\n");
        sb.append("  <ns4:loginResponse xmlns:ns4=\"http://www.sugarcrm.com/sugarcrm\">\n");
        sb.append("   <return xsi:type=\"ns4:entry_value\" "
                + "xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\">\n");
        sb.append("    ").append(idElement).append('\n');
        sb.append("    <module_name>Users</module_name>\n");
        sb.append("   </return>\n");
        sb.append("  </ns4:loginResponse>\n");
        sb.append(" </soapenv:Body>\n");
        sb.append("</soapenv:Envelope>\n");
        return sb.toString();
    }

    /**
     * Builds a valid response for the 'get_module_fields' operation, which the
     * module calls after it has logged in.
     *
     * @return a SOAP response document
     */
    private String getModuleFieldsResponse() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<soapenv:Envelope xmlns:soapenv=\"http://schemas.xmlsoap.org/soap/envelope/\">\n"
                + " <soapenv:Body>\n"
                + "  <ns4:get_module_fieldsResponse xmlns:ns4=\"http://www.sugarcrm.com/sugarcrm\">\n"
                + "   <return xsi:type=\"ns4:new_module_fields\" "
                + "xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\">\n"
                + "    <module_fields><field><name>id</name><type>id</type><label>ID</label></field>"
                + "</module_fields>\n"
                + "    <link_fields/>\n"
                + "   </return>\n"
                + "  </ns4:get_module_fieldsResponse>\n"
                + " </soapenv:Body>\n"
                + "</soapenv:Envelope>\n";
    }

    /**
     * Creates the file that the external entity payload points at.
     *
     * @return the file that holds {@link #SECRET}
     */
    private File createSecretFile() throws IOException {
        final File file = File.createTempFile("metamodel-sugarcrm-xxe", ".txt");
        file.deleteOnExit();
        final OutputStream out = new FileOutputStream(file);
        try {
            out.write(SECRET.getBytes("UTF-8"));
        } finally {
            out.close();
        }
        return file;
    }

    /**
     * Locates the WSDL of the SugarCRM web service. It is needed because the
     * module reads the WSDL from the server before it can make any SOAP call.
     *
     * @return the WSDL file of this module
     */
    private static File locateWsdl() {
        File dir = new File(System.getProperty("user.dir")).getAbsoluteFile();
        while (dir != null) {
            final File candidate = new File(dir, "src/wsdl/sugarcrm.wsdl");
            if (candidate.isFile()) {
                return candidate;
            }
            final File moduleCandidate = new File(dir, "sugarcrm/src/wsdl/sugarcrm.wsdl");
            if (moduleCandidate.isFile()) {
                return moduleCandidate;
            }
            dir = dir.getParentFile();
        }
        fail("Could not locate src/wsdl/sugarcrm.wsdl (working directory: "
                + System.getProperty("user.dir") + ")");
        return null;
    }

    private static byte[] readAllBytes(File file) throws IOException {
        final InputStream in = new java.io.FileInputStream(file);
        try {
            return readAllBytes(in);
        } finally {
            in.close();
        }
    }

    private static byte[] readAllBytes(InputStream in) throws IOException {
        final ByteArrayOutputStream baos = new ByteArrayOutputStream();
        final byte[] buffer = new byte[4096];
        int read;
        while ((read = in.read(buffer)) > 0) {
            baos.write(buffer, 0, read);
        }
        return baos.toByteArray();
    }
}
