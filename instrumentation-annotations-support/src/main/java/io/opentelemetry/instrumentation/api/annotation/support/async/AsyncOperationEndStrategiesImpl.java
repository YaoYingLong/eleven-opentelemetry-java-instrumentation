/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.instrumentation.api.annotation.support.async;

import static java.util.Objects.requireNonNull;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.annotation.Nullable;

/**
 * 这里虽然注释时默认的策略实现，但是在Agent的启动过程中并没有时使用，而是使用的WeakRefAsyncOperationEndStrategies弱引用策略
 * 该策略的作用是支持通过otel.instrumentation.methods.include环境变量配置的类，以及通过@WithSpan注解标注的方法执行时导出Span
 *
 * 由于这些是用户自定义的需要添加Span录制导出的方法，可能存在异步方法，为了兼容异步方法所设计的
 *
 * Default strategies' registry implementation that uses strong references.
 */
final class AsyncOperationEndStrategiesImpl extends AsyncOperationEndStrategies {
  // 默认是有Jdk8AsyncOperationEndStrategy策略的
  private final List<AsyncOperationEndStrategy> strategies = new CopyOnWriteArrayList<>();

  AsyncOperationEndStrategiesImpl() {
    registerStrategy(Jdk8AsyncOperationEndStrategy.INSTANCE);
  }

  @Override
  public void registerStrategy(AsyncOperationEndStrategy strategy) {
    strategies.add(requireNonNull(strategy));
  }

  @Override
  public void unregisterStrategy(AsyncOperationEndStrategy strategy) {
    strategies.remove(strategy);
  }

  @Nullable
  @Override
  public AsyncOperationEndStrategy resolveStrategy(Class<?> returnType) {
    for (AsyncOperationEndStrategy strategy : strategies) {
      if (strategy.supports(returnType)) {
        return strategy;
      }
    }
    return null;
  }
}
