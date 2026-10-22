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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.apache.metamodel.DataContext;
import org.apache.metamodel.factory.DataContextPropertiesImpl;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.amazonaws.auth.AWSCredentialsProvider;
import com.amazonaws.auth.DefaultAWSCredentialsProviderChain;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDB;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClientBuilder;

/**
 * Tests the credential handling of {@link DynamoDbDataContextFactory}.
 */
public class DynamoDbDataContextFactoryTest {

    /**
     * System property that makes the AWS SDK skip the EC2 instance metadata service. It is set
     * while the tests run, so that a test never waits for (or connects to) the metadata service.
     */
    private static final String DISABLE_EC2_METADATA_PROPERTY = "com.amazonaws.sdk.disableEc2Metadata";

    /**
     * System property through which the AWS SDK resolves a region from the environment.
     */
    private static final String AWS_REGION_PROPERTY = "aws.region";

    private String _previousEc2MetadataSetting;

    @Before
    public void disableEc2MetadataService() {
        _previousEc2MetadataSetting = System.getProperty(DISABLE_EC2_METADATA_PROPERTY);
        System.setProperty(DISABLE_EC2_METADATA_PROPERTY, "true");
    }

    @After
    public void restoreEc2MetadataService() {
        if (_previousEc2MetadataSetting == null) {
            System.clearProperty(DISABLE_EC2_METADATA_PROPERTY);
        } else {
            System.setProperty(DISABLE_EC2_METADATA_PROPERTY, _previousEc2MetadataSetting);
        }
    }

    private DataContextPropertiesImpl createProperties() {
        final DataContextPropertiesImpl properties = new DataContextPropertiesImpl();
        properties.put("type", "dynamodb");
        properties.put("region", "us-east-1");
        return properties;
    }

    private DataContextPropertiesImpl createPropertiesWithoutRegion() {
        final DataContextPropertiesImpl properties = new DataContextPropertiesImpl();
        properties.put("type", "dynamodb");
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

    /**
     * Without a region the AWS SDK throws an opaque
     * <code>SdkClientException("Unable to find a region via the region provider chain...")</code>,
     * which says nothing about which of the MetaModel properties is missing.
     */
    @Test
    public void testMissingRegionFailsWithAClearMessage() {
        final DataContextPropertiesImpl properties = createPropertiesWithoutRegion();

        try {
            new DynamoDbDataContextFactory().create(properties, null);
            fail("Exception expected");
        } catch (IllegalArgumentException e) {
            final String message = e.getMessage();
            assertTrue(message, message.contains("region"));
            assertTrue(message, message.contains("AWS_REGION"));
            assertFalse(message, message.contains("region provider chain"));
        }
    }

    /**
     * The "default credentials" branch of the factory: when neither a user name nor a password is
     * given, the AWS default credentials provider chain is used.
     */
    @Test
    public void testDefaultCredentialsProviderChainIsUsedWhenNoUsernameAndPasswordAreGiven() {
        final DataContextPropertiesImpl properties = createProperties();

        final AmazonDynamoDBClientBuilder clientBuilder =
                new DynamoDbDataContextFactory().createClientBuilder(properties);

        final AWSCredentialsProvider credentialsProvider = clientBuilder.getCredentials();
        assertNotNull(credentialsProvider);
        assertTrue("Expected the AWS default credentials provider chain, but got: "
                + credentialsProvider.getClass().getName(),
                credentialsProvider instanceof DefaultAWSCredentialsProviderChain);
    }

    /**
     * A data context can actually be created with the default credentials provider chain. Building
     * the client does not open a connection, and no operation is executed against DynamoDB here.
     */
    @Test
    public void testDataContextCanBeCreatedWithTheDefaultCredentialsProviderChain() {
        final DataContextPropertiesImpl properties = createProperties();
        final DynamoDbDataContextFactory factory = new DynamoDbDataContextFactory();

        // the region is what makes it possible to build the client at all
        assertEquals("us-east-1", factory.createClientBuilder(properties).getRegion());

        final DataContext dataContext = factory.create(properties, null);
        assertTrue(dataContext instanceof DynamoDbDataContext);
        final AmazonDynamoDB client = ((DynamoDbDataContext) dataContext).getDynamoDb();
        assertNotNull(client);
        client.shutdown();
    }

    /**
     * A region may also come from the AWS environment instead of from the "region" property. The
     * AWS SDK resolves the "aws.region" system property, so the factory must not reject such a
     * configuration.
     */
    @Test
    public void testRegionIsResolvedFromTheAwsEnvironment() {
        final DataContextPropertiesImpl properties = createPropertiesWithoutRegion();
        final DynamoDbDataContextFactory factory = new DynamoDbDataContextFactory();
        final String previousRegion = System.getProperty(AWS_REGION_PROPERTY);
        System.setProperty(AWS_REGION_PROPERTY, "eu-west-1");
        try {
            assertEquals("eu-west-1", factory.createClientBuilder(properties).getRegion());

            final DataContext dataContext = factory.create(properties, null);
            assertTrue(dataContext instanceof DynamoDbDataContext);
            final AmazonDynamoDB client = ((DynamoDbDataContext) dataContext).getDynamoDb();
            assertNotNull(client);
            client.shutdown();
        } finally {
            if (previousRegion == null) {
                System.clearProperty(AWS_REGION_PROPERTY);
            } else {
                System.setProperty(AWS_REGION_PROPERTY, previousRegion);
            }
        }
    }
}
