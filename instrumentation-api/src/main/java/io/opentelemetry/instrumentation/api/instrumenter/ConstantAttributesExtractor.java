/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.instrumentation.api.instrumenter;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.context.Context;
import javax.annotation.Nullable;

/**
 * 用于向Attribute中添加一个固定的键值对
 */
final class ConstantAttributesExtractor<REQUEST, RESPONSE, T>
    implements AttributesExtractor<REQUEST, RESPONSE> {

  private final AttributeKey<T> attributeKey;
  private final T attributeValue;

  ConstantAttributesExtractor(AttributeKey<T> attributeKey, T attributeValue) {
    this.attributeKey = attributeKey;
    this.attributeValue = attributeValue;
  }

  @Override
  public void onStart(AttributesBuilder attributes, Context parentContext, REQUEST request) {
    attributes.put(attributeKey, attributeValue);
  }

  @Override
  public void onEnd(
      AttributesBuilder attributes,
      Context context,
      REQUEST request,
      @Nullable RESPONSE response,
      @Nullable Throwable error) {}
}
