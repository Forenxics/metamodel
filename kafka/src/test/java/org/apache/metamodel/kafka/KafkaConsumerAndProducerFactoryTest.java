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
package org.apache.metamodel.kafka;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import java.util.Properties;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.IntegerDeserializer;
import org.apache.kafka.common.serialization.IntegerSerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.Test;

/**
 * Tests the serializer/deserializer configuration of {@link KafkaConsumerAndProducerFactory}.
 * <p>
 * No Kafka broker is needed for these tests: the client only resolves (but does not
 * connect to) the bootstrap servers.
 */
public class KafkaConsumerAndProducerFactoryTest {

    private static final String BOOTSTRAP_SERVERS = "localhost:9092";

    /**
     * The producer must be configured with a serializer for the key type and a
     * serializer for the value type.
     */
    @Test
    public void testProducerPropertiesUseKeyAndValueType() {
        final KafkaConsumerAndProducerFactory factory = new KafkaConsumerAndProducerFactory(BOOTSTRAP_SERVERS);
        final Properties properties = factory.getProducerProperties(String.class, Integer.class);
        assertEquals(StringSerializer.class.getName(),
                properties.getProperty(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG));
        assertEquals(IntegerSerializer.class.getName(),
                properties.getProperty(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG));
        assertEquals(BOOTSTRAP_SERVERS, properties.getProperty(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG));
    }

    /**
     * The consumer must be configured with a deserializer for the key type and a
     * deserializer for the value type.
     */
    @Test
    public void testConsumerPropertiesUseKeyAndValueType() {
        final KafkaConsumerAndProducerFactory factory = new KafkaConsumerAndProducerFactory(BOOTSTRAP_SERVERS);
        final Properties properties = factory.getConsumerProperties("apache_metamodel_test_topic", String.class,
                Integer.class);
        assertEquals(StringDeserializer.class.getName(),
                properties.getProperty(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG));
        assertEquals(IntegerDeserializer.class.getName(),
                properties.getProperty(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG));
        assertEquals(BOOTSTRAP_SERVERS, properties.getProperty(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG));
    }

    /**
     * Creating a producer must not fail. Before the fix the locally assembled properties
     * were discarded, so that every producer creation failed with a
     * {@code ConfigException} about the missing 'key.serializer' property.
     */
    @Test
    public void testCreateProducer() {
        final KafkaConsumerAndProducerFactory factory = new KafkaConsumerAndProducerFactory(BOOTSTRAP_SERVERS);
        final Producer<String, Integer> producer = factory.createProducer(String.class, Integer.class);
        try {
            assertNotNull(producer);
        } finally {
            producer.close();
        }
    }

    @Test
    public void testCreateConsumer() {
        final KafkaConsumerAndProducerFactory factory = new KafkaConsumerAndProducerFactory(BOOTSTRAP_SERVERS);
        final Consumer<String, Integer> consumer = factory.createConsumer("apache_metamodel_test_topic", String.class,
                Integer.class);
        try {
            assertNotNull(consumer);
        } finally {
            consumer.close();
        }
    }
}
