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

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParserFactory;

import org.apache.metamodel.MetaModelException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Creates XML parser factories that are hardened against XML External Entity (XXE)
 * attacks (CWE-611).
 *
 * <p>
 * The XML documents handled by this module are typically supplied by untrusted
 * parties. A parser that resolves external entities allows an attacker to:
 * </p>
 * <ul>
 * <li>read arbitrary files from the host (e.g. {@code file:///etc/passwd}) and embed
 * their content in the parsed output,</li>
 * <li>make the host issue outbound network requests (SSRF),</li>
 * <li>exhaust memory/CPU via recursive entity expansion ("billion laughs").</li>
 * </ul>
 *
 * <p>
 * The factories produced here therefore:
 * </p>
 * <ol>
 * <li>reject any document containing a {@code DOCTYPE} declaration outright,</li>
 * <li>refuse to resolve external general and parameter entities,</li>
 * <li>enable {@link XMLConstants#FEATURE_SECURE_PROCESSING},</li>
 * <li>disable XInclude and entity reference expansion.</li>
 * </ol>
 *
 * <p>
 * Because DOCTYPE declarations are rejected, documents that legitimately carry a
 * DTD are no longer parsable by this module. This is an intentional behaviour
 * change: accepting a DTD is the root cause of the vulnerability.
 * </p>
 */
final class XmlSecurityUtils {

    private static final Logger logger = LoggerFactory.getLogger(XmlSecurityUtils.class);

    /**
     * Feature that makes the parser reject documents containing a DOCTYPE declaration.
     */
    private static final String DISALLOW_DOCTYPE_DECL_FEATURE =
            "http://apache.org/xml/features/disallow-doctype-decl";

    /**
     * Feature that disables resolution of external general entities.
     */
    private static final String EXTERNAL_GENERAL_ENTITIES_FEATURE =
            "http://xml.org/sax/features/external-general-entities";

    /**
     * Feature that disables resolution of external parameter entities.
     */
    private static final String EXTERNAL_PARAMETER_ENTITIES_FEATURE =
            "http://xml.org/sax/features/external-parameter-entities";

    /**
     * Feature that stops the parser from loading an external DTD.
     */
    private static final String LOAD_EXTERNAL_DTD_FEATURE =
            "http://apache.org/xml/features/nonvalidating/load-external-dtd";

    private XmlSecurityUtils() {
        // prevent instantiation
    }

    /**
     * Creates a {@link SAXParserFactory} that is safe to use for untrusted XML
     * content.
     *
     * @return a hardened SAX parser factory
     */
    public static SAXParserFactory createSafeSaxParserFactory() {
        final SAXParserFactory factory = SAXParserFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setXIncludeAware(false);
        setFeatureQuietly(factory, XMLConstants.FEATURE_SECURE_PROCESSING, true);
        setFeatureQuietly(factory, EXTERNAL_GENERAL_ENTITIES_FEATURE, false);
        setFeatureQuietly(factory, EXTERNAL_PARAMETER_ENTITIES_FEATURE, false);
        setFeatureQuietly(factory, LOAD_EXTERNAL_DTD_FEATURE, false);
        // Fail closed: if the DOCTYPE declaration cannot be banned, the parser
        // would still be able to expand entities.
        setFeature(factory, DISALLOW_DOCTYPE_DECL_FEATURE, true);
        return factory;
    }

    /**
     * Creates a {@link DocumentBuilderFactory} that is safe to use for untrusted XML
     * content.
     *
     * @return a hardened document builder factory
     */
    public static DocumentBuilderFactory createSafeDocumentBuilderFactory() {
        final DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        setFeatureQuietly(factory, XMLConstants.FEATURE_SECURE_PROCESSING, true);
        setFeatureQuietly(factory, EXTERNAL_GENERAL_ENTITIES_FEATURE, false);
        setFeatureQuietly(factory, EXTERNAL_PARAMETER_ENTITIES_FEATURE, false);
        // Fail closed: if the DOCTYPE declaration cannot be banned, the parser
        // would still be able to expand entities.
        setFeature(factory, DISALLOW_DOCTYPE_DECL_FEATURE, true);
        setAttributeQuietly(factory, XMLConstants.ACCESS_EXTERNAL_DTD, "");
        setAttributeQuietly(factory, XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        return factory;
    }

    private static void setFeature(SAXParserFactory factory, String feature, boolean value) {
        try {
            factory.setFeature(feature, value);
        } catch (ParserConfigurationException e) {
            throw new MetaModelException("Failed to harden XML parser: unsupported feature " + feature, e);
        } catch (Exception e) {
            throw new MetaModelException("Failed to harden XML parser: could not set feature " + feature, e);
        }
    }

    private static void setFeatureQuietly(SAXParserFactory factory, String feature, boolean value) {
        try {
            factory.setFeature(feature, value);
        } catch (Exception e) {
            logger.debug("XML parser feature not supported: " + feature, e);
        }
    }

    private static void setFeature(DocumentBuilderFactory factory, String feature, boolean value) {
        try {
            factory.setFeature(feature, value);
        } catch (ParserConfigurationException e) {
            throw new MetaModelException("Failed to harden XML parser: unsupported feature " + feature, e);
        } catch (Exception e) {
            throw new MetaModelException("Failed to harden XML parser: could not set feature " + feature, e);
        }
    }

    private static void setFeatureQuietly(DocumentBuilderFactory factory, String feature, boolean value) {
        try {
            factory.setFeature(feature, value);
        } catch (Exception e) {
            logger.debug("XML parser feature not supported: " + feature, e);
        }
    }

    private static void setAttributeQuietly(DocumentBuilderFactory factory, String attribute, Object value) {
        try {
            factory.setAttribute(attribute, value);
        } catch (Exception e) {
            logger.debug("XML parser attribute not supported: " + attribute, e);
        }
    }
}
