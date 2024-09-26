/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.instrumentation.kafka.internal;

import javax.annotation.Nullable;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecords;

/**
 * This class is internal and is hence not for public use. Its APIs are unstable and can change at
 * any time.
 */
public class KafkaReceiveRequest extends AbstractKafkaConsumerRequest {

  private final ConsumerRecords<?, ?> records;

  public static KafkaReceiveRequest create(
      ConsumerRecords<?, ?> records, @Nullable Consumer<?, ?> consumer) {
    return create(records, KafkaUtil.getConsumerGroup(consumer), KafkaUtil.getClientId(consumer));
  }

  public static KafkaReceiveRequest create(
      KafkaConsumerContext consumerContext, ConsumerRecords<?, ?> records) {
    return create(records, consumerContext != null ? consumerContext.getConsumer() : null);
  }

  public static KafkaReceiveRequest create(
      ConsumerRecords<?, ?> records, String consumerGroup, String clientId) {
    return new KafkaReceiveRequest(records, consumerGroup, clientId);
  }

  private KafkaReceiveRequest(
      ConsumerRecords<?, ?> records, String consumerGroup, String clientId) {
    super(consumerGroup, clientId);
    this.records = records;
  }

  public ConsumerRecords<?, ?> getRecords() {
    return records;
  }
}
