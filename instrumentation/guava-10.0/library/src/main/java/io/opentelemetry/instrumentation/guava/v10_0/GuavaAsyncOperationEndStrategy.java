/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.instrumentation.guava.v10_0;

import static io.opentelemetry.instrumentation.api.annotation.support.async.AsyncOperationEndSupport.tryToGetResponse;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.Uninterruptibles;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Context;
import io.opentelemetry.instrumentation.api.annotation.support.async.AsyncOperationEndStrategy;
import io.opentelemetry.instrumentation.api.instrumenter.Instrumenter;

public final class GuavaAsyncOperationEndStrategy implements AsyncOperationEndStrategy {
  // Visible for testing
  static final AttributeKey<Boolean> CANCELED_ATTRIBUTE_KEY = AttributeKey.booleanKey("guava.canceled");

  public static GuavaAsyncOperationEndStrategy create() {
    return builder().build();
  }

  public static GuavaAsyncOperationEndStrategyBuilder builder() {
    return new GuavaAsyncOperationEndStrategyBuilder();
  }

  // 获取otel.instrumentation.guava.experimental-span-attributes配置的值，默认为false
  private final boolean captureExperimentalSpanAttributes;

  GuavaAsyncOperationEndStrategy(boolean captureExperimentalSpanAttributes) {
    // 获取otel.instrumentation.guava.experimental-span-attributes配置的值，默认为false
    this.captureExperimentalSpanAttributes = captureExperimentalSpanAttributes;
  }

  @Override
  public boolean supports(Class<?> returnType) {
    // 判断returnType对应的类是不是继承自ListenableFuture
    return ListenableFuture.class.isAssignableFrom(returnType);
  }

  /**
   * 调用时机是在AsyncOperationEndSupport中的asyncEnd方法中被调用
   */
  @Override
  public <REQUEST, RESPONSE> Object end(Instrumenter<REQUEST, RESPONSE> instrumenter,
      Context context, REQUEST request, Object asyncValue, Class<RESPONSE> responseType) {

    // 将asyncValue的类型强制转换为ListenableFuture
    ListenableFuture<?> future = (ListenableFuture<?>) asyncValue;
    end(instrumenter, context, request, future, responseType);
    return future;
  }

  private <REQUEST, RESPONSE> void end(Instrumenter<REQUEST, RESPONSE> instrumenter,
      Context context, REQUEST request, ListenableFuture<?> future, Class<RESPONSE> responseType) {
    // 如果异步执行完成
    if (future.isDone()) {
      // 如果此任务在正常完成之前被取消，则返回true
      if (future.isCancelled()) {
        // 获取otel.instrumentation.guava.experimental-span-attributes配置的值，默认为false
        if (captureExperimentalSpanAttributes) {
          Span.fromContext(context).setAttribute(CANCELED_ATTRIBUTE_KEY, true);
        }
        instrumenter.end(context, request, null, null);
      } else {
        // 任务是正常结束完成的
        try {
          // 其实就是调用future.get获取响应结果
          Object response = Uninterruptibles.getUninterruptibly(future);
          // 通过tryToGetResponse将potentialResponse从Object类型转换为具体的responseType
          instrumenter.end(context, request, tryToGetResponse(responseType, response), null);
        } catch (Throwable exception) {
          instrumenter.end(context, request, null, exception);
        }
      }
    } else {
      // 添加一个Listener，当future完成时，会执行end方法
      future.addListener(() -> end(instrumenter, context, request, future, responseType), Runnable::run);
    }
  }
}
