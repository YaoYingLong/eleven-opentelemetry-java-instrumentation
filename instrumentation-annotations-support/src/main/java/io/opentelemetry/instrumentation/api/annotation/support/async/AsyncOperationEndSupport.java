/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.instrumentation.api.annotation.support.async;

import io.opentelemetry.context.Context;
import io.opentelemetry.instrumentation.api.instrumenter.Instrumenter;
import javax.annotation.Nullable;

/**
 * A wrapper over {@link Instrumenter} that is able to defer {@link Instrumenter#end(Context,
 * Object, Object, Throwable)} until asynchronous computation finishes.
 */
public final class AsyncOperationEndSupport<REQUEST, RESPONSE> {

  /**
   * Returns a new {@link AsyncOperationEndSupport} that wraps over passed {@code syncInstrumenter},
   * configured for usage with asynchronous computations that are instances of {@code asyncType}. If
   * the result of the async computation ends up being an instance of {@code responseType} it will
   * be passed as the response to the {@code syncInstrumenter} call; otherwise {@code null} value
   * will be used as the response.
   */
  public static <REQUEST, RESPONSE> AsyncOperationEndSupport<REQUEST, RESPONSE> create(
      Instrumenter<REQUEST, RESPONSE> syncInstrumenter,
      Class<RESPONSE> responseType,
      Class<?> asyncType) {
    return new AsyncOperationEndSupport<>(syncInstrumenter, responseType, asyncType,
        // 这里是遍历调用WeakRefAsyncOperationEndStrategies中注册的Strategy列表的resolveStrategy方法
        // 判断其属于哪一个策略，若没有匹配的策略则返回null
        AsyncOperationEndStrategies.instance().resolveStrategy(asyncType));
  }

  private final Instrumenter<REQUEST, RESPONSE> instrumenter;
  private final Class<RESPONSE> responseType;
  private final Class<?> asyncType;
  @Nullable private final AsyncOperationEndStrategy asyncOperationEndStrategy;

  private AsyncOperationEndSupport(Instrumenter<REQUEST, RESPONSE> instrumenter,
      Class<RESPONSE> responseType, Class<?> asyncType,
      @Nullable AsyncOperationEndStrategy asyncOperationEndStrategy) {
    this.instrumenter = instrumenter;
    this.responseType = responseType;
    this.asyncType = asyncType;
    this.asyncOperationEndStrategy = asyncOperationEndStrategy;
  }

  /**
   * Attempts to compose over passed {@code asyncValue} and delay the {@link
   * Instrumenter#end(Context, Object, Object, Throwable)} call until the async operation completes.
   *
   * <p>This method will end the operation immediately if {@code throwable} is passed, if there is
   * no {@link AsyncOperationEndStrategy} for the {@code asyncType} used, or if there is a type
   * mismatch between passed {@code asyncValue} and the {@code asyncType} that was used to create
   * this object.
   *
   * <p>If the passed {@code asyncValue} is recognized as an asynchronous computation, the operation
   * won't be {@link Instrumenter#end(Context, Object, Object, Throwable) ended} until {@code
   * asyncValue} completes.
   */
  @SuppressWarnings("unchecked")
  @Nullable
  public <ASYNC> ASYNC asyncEnd(Context context, REQUEST request, @Nullable ASYNC asyncValue,
      @Nullable Throwable throwable) {
    // we can end early if an exception was thrown
    if (throwable != null) {
      // 如果有异常跑出直接结束
      instrumenter.end(context, request, null, throwable);
      return asyncValue;
    }

    // use the configured strategy to compose over the asyncValue
    // 异步逻辑，如果asyncOperationEndStrategy不为空，且asyncValue值对应的类型未asyncType指定的类型
    if (asyncOperationEndStrategy != null && asyncType.isInstance(asyncValue)) {
      // 调用具体的asyncOperationEndStrategy的end方法
      return (ASYNC) asyncOperationEndStrategy.end(instrumenter, context, request, asyncValue,
          responseType);
    }

    // fall back to sync end() if asyncValue type doesn't match
    // 如果是同步逻辑，通过tryToGetResponse将potentialResponse从Object类型转换为具体的responseType
    instrumenter.end(context, request, tryToGetResponse(responseType, asyncValue), null);
    return asyncValue;
  }

  @Nullable
  public static <RESPONSE> RESPONSE tryToGetResponse(Class<RESPONSE> responseType,
      @Nullable Object asyncValue) {
    // 判断响应类型和asyncValue是不是同一个类型
    if (responseType.isInstance(asyncValue)) {
      // 做类型转换，将Object类型的asyncValue转换成responseType对应的类型
      return responseType.cast(asyncValue);
    }
    return null;
  }
}
