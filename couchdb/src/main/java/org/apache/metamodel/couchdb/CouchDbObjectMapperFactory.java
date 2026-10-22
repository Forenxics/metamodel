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
package org.apache.metamodel.couchdb;

import java.io.IOException;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

import org.ektorp.CouchDbConnector;
import org.ektorp.impl.ObjectMapperFactory;
import org.ektorp.impl.StdObjectMapperFactory;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.deser.DeserializationProblemHandler;

/**
 * An {@link ObjectMapperFactory} which creates Jackson {@link ObjectMapper}s that can deserialize
 * the database info ({@code GET /db}) that CouchDB 2.3 and newer returns.
 *
 * <p>
 * CouchDB 2.3 changed the {@code purge_seq} (and {@code update_seq}) of a database from a plain
 * number into an opaque sequence id of the shape {@code "0-g1AAAAEzeJzLYWBg..."}, where the
 * prefix is the (numeric) sequence number and the suffix is an opaque, base64-like token. ektorp
 * 1.5.0 declares {@link org.ektorp.DbInfo#purgeSeq} as an {@code int}, so Jackson fails with
 * {@code InvalidFormatException: Cannot deserialize value of type 'int' from String "0-g1AAAA..."}
 * whenever such a database info is read. ektorp already tolerates an opaque {@code update_seq}
 * (it is modelled as a {@code JsonNode}), but it never fixed {@code purge_seq}.
 * </p>
 *
 * <p>
 * This factory delegates to ektorp's own {@link StdObjectMapperFactory}, so that all of ektorp's
 * mapper configuration (and its {@code EktorpJacksonModule}) is preserved, and then registers a
 * {@link DeserializationProblemHandler} which tolerates the opaque {@code purge_seq} value.
 * </p>
 *
 * <p>
 * The tolerance is deliberately narrow: it only applies to the {@code purge_seq} property, and
 * only when the value is being deserialized into an {@code int}/{@link Integer}. Every other
 * "weird string value" is still rejected exactly like before, so that no user data is silently
 * coerced.
 * </p>
 */
public class CouchDbObjectMapperFactory implements ObjectMapperFactory {

    /**
     * The name of the CouchDB property which holds the opaque sequence id.
     */
    static final String PURGE_SEQ_PROPERTY_NAME = "purge_seq";

    private final StdObjectMapperFactory _delegate = new StdObjectMapperFactory();

    /**
     * Keeps track of the mappers that have already been configured, so that the handler is not
     * registered more than once on the same mapper. ektorp caches the mapper returned by
     * {@link StdObjectMapperFactory#createObjectMapper()}, but creates a new one for every
     * connector.
     */
    private final Set<ObjectMapper> _configuredMappers = Collections
            .newSetFromMap(new WeakHashMap<ObjectMapper, Boolean>());

    @Override
    public ObjectMapper createObjectMapper() {
        return configure(_delegate.createObjectMapper());
    }

    @Override
    public ObjectMapper createObjectMapper(CouchDbConnector connector) {
        return configure(_delegate.createObjectMapper(connector));
    }

    private ObjectMapper configure(ObjectMapper objectMapper) {
        synchronized (_configuredMappers) {
            if (_configuredMappers.add(objectMapper)) {
                objectMapper.addHandler(new PurgeSeqProblemHandler());
            }
        }
        return objectMapper;
    }

    /**
     * Parses the numeric prefix of a CouchDB opaque sequence id, e.g. {@code "0-g1AAAA..."} is
     * parsed as {@code 0}.
     *
     * @param value the string value that Jackson was unable to convert
     * @return the numeric prefix of the sequence id, or {@code null} if the value is not shaped
     *         like a CouchDB opaque sequence id.
     */
    static Integer parseOpaqueSequenceId(String value) {
        if (value == null) {
            return null;
        }
        final int length = value.length();
        int index = 0;
        while (index < length && value.charAt(index) >= '0' && value.charAt(index) <= '9') {
            index++;
        }
        if (index == 0) {
            // no numeric prefix at all
            return null;
        }
        if (index < length && value.charAt(index) != '-') {
            // not shaped like an opaque sequence id (e.g. "12abc")
            return null;
        }
        try {
            return Integer.valueOf(Integer.parseInt(value.substring(0, index)));
        } catch (NumberFormatException e) {
            // the numeric prefix does not fit in an int
            return null;
        }
    }

    /**
     * A {@link DeserializationProblemHandler} which tolerates the opaque {@code purge_seq} value
     * that CouchDB 2.3 and newer returns.
     */
    static class PurgeSeqProblemHandler extends DeserializationProblemHandler {

        @Override
        public Object handleWeirdStringValue(DeserializationContext ctxt, Class<?> targetType,
                String valueToConvert, String msg) throws IOException {
            if (isIntTarget(targetType) && isPurgeSeqProperty(ctxt)) {
                final Integer purgeSeq = parseOpaqueSequenceId(valueToConvert);
                if (purgeSeq != null) {
                    return purgeSeq;
                }
            }
            return super.handleWeirdStringValue(ctxt, targetType, valueToConvert, msg);
        }

        private static boolean isIntTarget(Class<?> targetType) {
            return targetType == Integer.TYPE || targetType == Integer.class;
        }

        /**
         * Determines whether the value that is currently being deserialized belongs to the
         * {@code purge_seq} property. Jackson exposes the name of the property that is being
         * processed on the parser.
         */
        @SuppressWarnings("deprecation")
        private static boolean isPurgeSeqProperty(DeserializationContext ctxt) throws IOException {
            final JsonParser parser = ctxt.getParser();
            if (parser == null) {
                return false;
            }
            // JsonParser#getCurrentName() is used (rather than the newer JsonParser#currentName())
            // because the former is available in every Jackson 2.x release.
            return PURGE_SEQ_PROPERTY_NAME.equals(parser.getCurrentName());
        }
    }
}
