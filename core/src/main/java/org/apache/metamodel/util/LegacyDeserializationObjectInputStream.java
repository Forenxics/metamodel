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
package org.apache.metamodel.util;

import java.io.IOException;
import java.io.InputStream;
import java.io.InvalidClassException;
import java.io.ObjectInputStream;
import java.io.ObjectStreamClass;
import java.lang.reflect.Field;
import java.util.Comparator;

import org.apache.metamodel.query.AggregateFunction;
import org.apache.metamodel.query.FunctionType;
import org.apache.metamodel.query.OperatorType;
import org.apache.metamodel.schema.ColumnType;
import org.apache.metamodel.schema.SuperColumnType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A specialized {@link ObjectInputStream} for MetaModel which can be used or
 * extended if it is needed to deserialize legacy MetaModel objects. This is
 * needed since the namespace of MetaModel was changed from org.eobjects.metamodel
 * to org.apache.metamodel.
 * <p>
 * In addition to the namespace change, a few MetaModel types changed shape over
 * time: {@link OperatorType}, {@link FunctionType} and {@link ColumnType} used to
 * be enums and are now interfaces backed by implementation classes. Streams that
 * were written while they were still enums are bound to the legacy enum
 * implementations declared in this class, so that the deserialized objects keep
 * working with the modern, interface-based API.
 * <p>
 * <b>Security note:</b> deserialization is only safe for streams you trust. This
 * class resolves classes from the stream, so a hostile stream can attempt to
 * instantiate arbitrary types. To limit that, {@link #isAllowedClass(String)}
 * rejects classes outside the MetaModel and JDK namespaces by default; override
 * that method if a legacy stream legitimately contains other types. On Java 9 and
 * later you can additionally install a
 * {@link java.io.ObjectInputFilter} on the stream.
 */
public class LegacyDeserializationObjectInputStream extends ObjectInputStream {

    private static final Logger logger = LoggerFactory.getLogger(LegacyDeserializationObjectInputStream.class);

    /**
     * Utility method for setting a field in a class
     * 
     * @param cls
     * @param fieldName
     * @param value
     */
    public static void setField(Class<?> cls, Object instance, String fieldName, Object value) {
        try {
            final Field field = cls.getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(instance, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(
                    "Unable to assign field '" + cls.getSimpleName() + '.' + fieldName + "' to value: " + value, e);
        }
    }

    /**
     * Implementation of the new {@link FunctionType} and
     * {@link AggregateFunction} interfaces which still adheres to the
     * constant/enum values of the old FunctionType definition. While
     * deserializing old FunctionType objects, we will convert them to this
     * enum.
     */
    protected static enum LegacyFunctionType implements AggregateFunction {

        COUNT(FunctionType.COUNT), AVG(FunctionType.AVG), SUM(FunctionType.SUM), MAX(FunctionType.MAX), MIN(
                FunctionType.MIN);

        private final AggregateFunction _delegate;

        private LegacyFunctionType(AggregateFunction delegate) {
            _delegate = delegate;
        }

        @Override
        public ColumnType getExpectedColumnType(ColumnType type) {
            return _delegate.getExpectedColumnType(type);
        }

        @Override
        public String getFunctionName() {
            return _delegate.getFunctionName();
        }

        @Override
        public AggregateBuilder<?> createAggregateBuilder() {
            return _delegate.createAggregateBuilder();
        }

        @Override
        public Object evaluate(Object... values) {
            return _delegate.evaluate(values);
        }
    }

    /**
     * Implementation of the new {@link ColumnType} interface which still
     * adheres to the constant/enum values of the old ColumnType definition.
     * While deserializing old ColumnType objects, we will convert them to this
     * enum.
     */
    protected static enum LegacyColumnType implements ColumnType {

        CHAR(ColumnType.CHAR), VARCHAR(ColumnType.VARCHAR), LONGVARCHAR(ColumnType.LONGVARCHAR), CLOB(ColumnType.CLOB), NCHAR(
                ColumnType.NCHAR), NVARCHAR(ColumnType.NVARCHAR), LONGNVARCHAR(ColumnType.LONGNVARCHAR), NCLOB(
                ColumnType.NCLOB), TINYINT(ColumnType.TINYINT), SMALLINT(ColumnType.SMALLINT), INTEGER(
                ColumnType.INTEGER), BIGINT(ColumnType.BIGINT), FLOAT(ColumnType.FLOAT), REAL(ColumnType.REAL), DOUBLE(
                ColumnType.DOUBLE), NUMERIC(ColumnType.NUMERIC), DECIMAL(ColumnType.DECIMAL), DATE(ColumnType.DATE), TIME(
                ColumnType.TIME), TIMESTAMP(ColumnType.TIMESTAMP), BIT(ColumnType.BIT), BOOLEAN(ColumnType.BOOLEAN), BINARY(
                ColumnType.BINARY), VARBINARY(ColumnType.VARBINARY), LONGVARBINARY(ColumnType.LONGVARBINARY), BLOB(
                ColumnType.BLOB), NULL(ColumnType.NULL), OTHER(ColumnType.OTHER), JAVA_OBJECT(ColumnType.JAVA_OBJECT), DISTINCT(
                ColumnType.DISTINCT), STRUCT(ColumnType.STRUCT), ARRAY(ColumnType.ARRAY), REF(ColumnType.REF), DATALINK(
                ColumnType.DATALINK), ROWID(ColumnType.ROWID), SQLXML(ColumnType.SQLXML), LIST(ColumnType.LIST), MAP(
                ColumnType.MAP);

        private final ColumnType _delegate;

        private LegacyColumnType(ColumnType delegate) {
            _delegate = delegate;
        }

        @Override
        public String getName() {
            return _delegate.getName();
        }

        @Override
        public Comparator<Object> getComparator() {
            return _delegate.getComparator();
        }

        @Override
        public boolean isBoolean() {
            return _delegate.isBoolean();
        }

        @Override
        public boolean isBinary() {
            return _delegate.isBinary();
        }

        @Override
        public boolean isNumber() {
            return _delegate.isNumber();
        }

        @Override
        public boolean isTimeBased() {
            return _delegate.isTimeBased();
        }

        @Override
        public boolean isLiteral() {
            return _delegate.isLiteral();
        }

        @Override
        public boolean isLargeObject() {
            return _delegate.isLargeObject();
        }

        @Override
        public Class<?> getJavaEquivalentClass() {
            return _delegate.getJavaEquivalentClass();
        }

        @Override
        public SuperColumnType getSuperType() {
            return _delegate.getSuperType();
        }

        @Override
        public int getJdbcType() throws IllegalStateException {
            return _delegate.getJdbcType();
        }
    }

    /**
     * Implementation of the new {@link OperatorType} interface which still
     * adheres to the constant/enum values of the old OperatorType definition.
     * While deserializing old OperatorType objects, we will convert them to
     * this enum.
     */
    protected static enum LegacyOperatorType implements OperatorType {

        EQUALS_TO(OperatorType.EQUALS_TO), DIFFERENT_FROM(OperatorType.DIFFERENT_FROM), LIKE(OperatorType.LIKE), GREATER_THAN(
                OperatorType.GREATER_THAN), GREATER_THAN_OR_EQUAL(OperatorType.GREATER_THAN_OR_EQUAL), LESS_THAN(
                OperatorType.LESS_THAN), LESS_THAN_OR_EQUAL(OperatorType.LESS_THAN_OR_EQUAL), IN(OperatorType.IN);

        private final OperatorType _delegate;

        private LegacyOperatorType(OperatorType delegate) {
            _delegate = delegate;
        }
        
        @Override
        public boolean isSpaceDelimited() {
            return _delegate.isSpaceDelimited();
        }

        @Override
        public String toSql() {
            return _delegate.toSql();
        }

    }

    private static final String OLD_CLASS_NAME_COLUMN_TYPE = "org.eobjects.metamodel.schema.ColumnType";
    private static final String OLD_CLASS_NAME_OPERATOR_TYPE = "org.eobjects.metamodel.query.OperatorType";
    private static final String OLD_CLASS_NAME_FUNCTION_TYPE = "org.eobjects.metamodel.query.FunctionType";
    private static final String CLASS_NAME_COLUMN_TYPE = "org.apache.metamodel.schema.ColumnType";
    private static final String CLASS_NAME_OPERATOR_TYPE = "org.apache.metamodel.query.OperatorType";
    private static final String CLASS_NAME_FUNCTION_TYPE = "org.apache.metamodel.query.FunctionType";

    public LegacyDeserializationObjectInputStream(InputStream in) throws IOException, SecurityException {
        super(in);
    }

    /**
     * Determines whether a class descriptor read from the stream described an
     * enum.
     * <p>
     * The JDK enforces two invariants for enum descriptors when it reads them
     * (see {@code ObjectStreamClass.readNonProxy}): an enum descriptor must carry
     * a serialVersionUID of zero and must declare no fields. Both values are
     * readable through the public {@link ObjectStreamClass} API, so they can be
     * used to recognise an enum descriptor without reflecting into JDK-internal
     * state - which is not permitted since Java 9 (JEP 403) and was never
     * portable between JDK versions anyway.
     *
     * @param desc
     *            a class descriptor as read from the stream
     * @return true if the stream described the class as an enum
     */
    private static boolean isEnumDescriptor(ObjectStreamClass desc) {
        return desc.getSerialVersionUID() == 0L && desc.getFields().length == 0;
    }

    /**
     * Determines whether a class may be deserialized by this stream.
     * <p>
     * By default only classes from the MetaModel namespaces and from the JDK
     * itself are allowed. Override this method to widen or narrow that set if a
     * legacy stream legitimately contains other types.
     *
     * @param className
     *            the fully qualified name of the class as it appears in the stream
     * @return true if the class may be deserialized
     */
    protected boolean isAllowedClass(String className) {
        String name = className;
        while (name.startsWith("[")) {
            // strip array dimensions
            name = name.substring(1);
        }
        if (name.startsWith("L") && name.endsWith(";")) {
            // strip the object type signature of an array
            name = name.substring(1, name.length() - 1);
        }
        return name.startsWith("org.apache.metamodel.")
                || name.startsWith("org.eobjects.metamodel.")
                || name.startsWith("java.")
                || name.startsWith("javax.")
                || name.startsWith("org.w3c.dom.")
                || name.startsWith("org.xml.sax.");
    }

    @Override
    protected Class<?> resolveClass(ObjectStreamClass desc) throws IOException, ClassNotFoundException {
        final String className = desc.getName();
        if (!isAllowedClass(className)) {
            throw new InvalidClassException(className, "deserialization of this class is not allowed");
        }
        if (className.startsWith("org.eobjects.metamodel") || className.startsWith("[Lorg.eobjects.metamodel")) {
            final String newClassName = className.replace("org.eobjects", "org.apache");
            return Class.forName(newClassName);
        }
        return super.resolveClass(desc);
    }

    @Override
    protected ObjectStreamClass readClassDescriptor() throws IOException, ClassNotFoundException {
        final ObjectStreamClass objectStreamClass = super.readClassDescriptor();
        final String className = objectStreamClass.getName();
        if (isEnumDescriptor(objectStreamClass)) {
            /*
             * OperatorType, FunctionType and ColumnType used to be enums. A
             * stream that describes them as an enum cannot be bound to the
             * modern interface of the same name - the JDK rejects that with
             * "cannot bind enum descriptor to a non-enum class". Bind them to
             * the legacy enum implementations instead, which implement the
             * modern interfaces.
             */
            if (CLASS_NAME_OPERATOR_TYPE.equals(className) || OLD_CLASS_NAME_OPERATOR_TYPE.equals(className)) {
                return ObjectStreamClass.lookup(LegacyOperatorType.class);
            } else if (CLASS_NAME_FUNCTION_TYPE.equals(className) || OLD_CLASS_NAME_FUNCTION_TYPE.equals(className)) {
                return ObjectStreamClass.lookup(LegacyFunctionType.class);
            } else if (CLASS_NAME_COLUMN_TYPE.equals(className) || OLD_CLASS_NAME_COLUMN_TYPE.equals(className)) {
                return ObjectStreamClass.lookup(LegacyColumnType.class);
            }
        }
        return objectStreamClass;
    }
}
