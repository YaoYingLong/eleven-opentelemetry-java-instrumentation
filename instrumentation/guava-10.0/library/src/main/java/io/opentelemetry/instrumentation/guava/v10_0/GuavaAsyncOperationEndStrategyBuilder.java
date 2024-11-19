/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.instrumentation.guava.v10_0;

import com.google.errorprone.annotations.CanIgnoreReturnValue;

public final class GuavaAsyncOperationEndStrategyBuilder {
  // 获取otel.instrumentation.guava.experimental-span-attributes配置的值，默认为false
  private boolean captureExperimentalSpanAttributes = false;

  GuavaAsyncOperationEndStrategyBuilder() {}

  @CanIgnoreReturnValue
  public GuavaAsyncOperationEndStrategyBuilder setCaptureExperimentalSpanAttributes(
      boolean captureExperimentalSpanAttributes) {
    // 获取otel.instrumentation.guava.experimental-span-attributes配置的值，默认为false
    this.captureExperimentalSpanAttributes = captureExperimentalSpanAttributes;
    return this;
  }

  public GuavaAsyncOperationEndStrategy build() {
    return new GuavaAsyncOperationEndStrategy(captureExperimentalSpanAttributes);
  }
}
