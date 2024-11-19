/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.instrumentation.api.annotation.support.async;

import static io.opentelemetry.instrumentation.api.annotation.support.async.AsyncOperationEndSupport.tryToGetResponse;

import io.opentelemetry.context.Context;
import io.opentelemetry.instrumentation.api.instrumenter.Instrumenter;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public enum Jdk8AsyncOperationEndStrategy implements AsyncOperationEndStrategy {
  INSTANCE;

  @Override
  public boolean supports(Class<?> asyncType) {
    return asyncType == CompletionStage.class || asyncType == CompletableFuture.class;
  }


  /**
   * 调用时机是在AsyncOperationEndSupport中的asyncEnd方法中被调用
   */
  @Override
  public <REQUEST, RESPONSE> Object end(Instrumenter<REQUEST, RESPONSE> instrumenter,
      Context context, REQUEST request, Object asyncValue, Class<RESPONSE> responseType) {
    if (asyncValue instanceof CompletableFuture) {
      CompletableFuture<?> future = (CompletableFuture<?>) asyncValue;
      // 如果future未执行完成，直接返回false，否则执行instrumenter.end方法，然后返回true
      if (tryToEndSynchronously(instrumenter, context, request, future, responseType)) {
        return future;
      }
      // 对于CompletionStage类型的whenComplete进行处理，其实就是在whenComplete中调用instrumenter.end
      return endWhenComplete(instrumenter, context, request, future, responseType);
    }
    // asyncValue强制转为CompletionStage
    CompletionStage<?> stage = (CompletionStage<?>) asyncValue;
    // 对于CompletionStage类型的whenComplete进行处理，其实就是在whenComplete中调用instrumenter.end
    return endWhenComplete(instrumenter, context, request, stage, responseType);
  }

  /**
   * Checks to see if the {@link CompletableFuture} has already been completed and if so
   * synchronously ends the span to avoid additional allocations and overhead registering for
   * notification of completion.
   */
  private static <REQUEST, RESPONSE> boolean tryToEndSynchronously(
      Instrumenter<REQUEST, RESPONSE> instrumenter,
      Context context,
      REQUEST request,
      CompletableFuture<?> future,
      Class<RESPONSE> responseType) {

    // 如果CompletableFuture没有完成直接返回false
    if (!future.isDone()) {
      return false;
    }

    try {
      // 等待异步任务完成并获取其结果
      Object potentialResponse = future.join();
      // 通过tryToGetResponse将potentialResponse从Object类型转换为具体的responseType
      instrumenter.end(context, request, tryToGetResponse(responseType, potentialResponse), null);
    } catch (Throwable t) {
      instrumenter.end(context, request, null, t);
    }
    return true;
  }

  /**
   * Registers for notification of the completion of the {@link CompletionStage} at which time the
   * span will be ended.
   */
  private static <REQUEST, RESPONSE> CompletionStage<?> endWhenComplete(
      Instrumenter<REQUEST, RESPONSE> instrumenter,
      Context context,
      REQUEST request,
      CompletionStage<?> stage,
      Class<RESPONSE> responseType) {

    return stage.whenComplete((result, exception) ->
        // 通过tryToGetResponse将potentialResponse从Object类型转换为具体的responseType
        instrumenter.end(context, request, tryToGetResponse(responseType, result), exception));
  }
}
