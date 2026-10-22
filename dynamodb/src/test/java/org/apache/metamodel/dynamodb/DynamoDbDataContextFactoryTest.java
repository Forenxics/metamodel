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
package org.apache.metamodel.dynamodb;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.apache.metamodel.factory.DataContextPropertiesImpl;
import org.junit.Test;

/**
 * Tests the credential handling of {@link DynamoDbDataContextFactory}.
 */
public class DynamoDbDataContextFactoryTest {

    private DataContextPropertiesImpl createProperties() {
        final DataContextPropertiesImpl properties = new DataContextPropertiesImpl();
        properties.put("type", "dynamodb");
        properties.put("region", "us-east-1");
        return properties;
    }

    /**
     * A user name without a password used to be handed to the AWS SDK as
     * <code>new BasicAWSCredentials(username, null)</code>, which made the SDK throw
     * <code>IllegalArgumentException("Secret key cannot be null.")</code> - a message that says
     * nothing about which MetaModel property is missing.
     */
    @Test
    public void testUsernameWithoutPasswordFailsWithAClearMessage() {
        final DataContextPropertiesImpl properties = createProperties();
        properties.put("username", "my-access-key-id");

        try {
            new DynamoDbDataContextFactory().create(properties, null);
            fail("Exception expected");
        } catch (IllegalArgumentException e) {
            final String message = e.getMessage();
            assertTrue(message, message.contains("password"));
            assertTrue(message, message.contains("username"));
            assertFalse(message, message.contains("Secret key cannot be null"));
        }
    }

    /**
     * The symmetric case: a password without a user name would silently be dropped.
     */
    @Test
    public void testPasswordWithoutUsernameFailsWithAClearMessage() {
        final DataContextPropertiesImpl properties = createProperties();
        properties.put("password", "my-secret-access-key");

        try {
            new DynamoDbDataContextFactory().create(properties, null);
            fail("Exception expected");
        } catch (IllegalArgumentException e) {
            final String message = e.getMessage();
            assertTrue(message, message.contains("username"));
            assertTrue(message, message.contains("password"));
        }
    }
}
