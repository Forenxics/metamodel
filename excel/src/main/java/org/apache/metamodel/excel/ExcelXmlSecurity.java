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

import javax.xml.XMLConstants;
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
 * An {@code .xlsx} workbook is a ZIP archive whose parts are XML documents. Those
 * parts are attacker controlled: a crafted workbook can embed a {@code DOCTYPE}
 * declaration with an external entity in {@code xl/workbook.xml} or in one of the
 * worksheet parts. When such a part is parsed with a default {@link SAXParserFactory}
 * the entity is expanded, which allows the attacker to read local files from the host
 * and to make the host issue outbound network requests.
 * </p>
 *
 * <p>
 * The factory produced here therefore rejects any document containing a
 * {@code DOCTYPE} declaration and refuses to resolve external general and parameter
 * entities. This mirrors {@code org.apache.metamodel.xml.XmlSecurityUtils} in the
 * {@code xml} module (the modules cannot share a dependency without a POM change).
 * </p>
 */
final class ExcelXmlSecurity {

    private static final Logger logger = LoggerFactory.getLogger(ExcelXmlSecurity.class);

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

    private ExcelXmlSecurity() {
        // prevent instantiation
    }

    /**
     * Creates a {@link SAXParserFactory} that is safe to use for the XML parts of an
     * untrusted workbook.
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
}
