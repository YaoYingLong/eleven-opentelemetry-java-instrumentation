/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.javaagent.tooling.asyncannotationsupport;

import io.opentelemetry.instrumentation.api.annotation.support.async.AsyncOperationEndStrategies;
import io.opentelemetry.instrumentation.api.annotation.support.async.AsyncOperationEndStrategy;
import io.opentelemetry.instrumentation.api.annotation.support.async.Jdk8AsyncOperationEndStrategy;
import java.lang.ref.WeakReference;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.annotation.Nullable;

/**
 * 该策略的作用是支持通过otel.instrumentation.methods.include环境变量配置的类，以及通过@WithSpan注解标注的方法执行时导出Span
 *
 * 由于这些是用户自定义的需要添加Span录制导出的方法，可能存在异步方法，为了兼容异步方法所设计的，比如返回是一个CompletableFuture
 */
public final class WeakRefAsyncOperationEndStrategies extends AsyncOperationEndStrategies {

  /**
   * Use the weak reference strategy in the agent. This will prevent leaking reference to
   * strategies' classloaders, in case applications get undeployed (and all their classes unloaded).
   */
  public static void initialize() {
    AsyncOperationEndStrategies.internalSetStrategiesStorage(new WeakRefAsyncOperationEndStrategies());
  }

  private final List<WeakReference<AsyncOperationEndStrategy>> strategies = new CopyOnWriteArrayList<>();

  private WeakRefAsyncOperationEndStrategies() {
    // 这里注册了一个AsyncOperationEndStrategy策略，用于处理异步
    registerStrategy(Jdk8AsyncOperationEndStrategy.INSTANCE);
  }

  @Override
  public void registerStrategy(AsyncOperationEndStrategy strategy) {
    strategies.add(new WeakReference<>(strategy));
  }

  @Override
  public void unregisterStrategy(AsyncOperationEndStrategy strategy) {
    strategies.removeIf(
        ref -> {
          AsyncOperationEndStrategy s = ref.get();
          return s == null || s == strategy;
        });
  }

  @Nullable
  @Override
  public AsyncOperationEndStrategy resolveStrategy(Class<?> returnType) {
    boolean purgeCollectedWeakReferences = false;
    try {
      // 遍历所有的AsyncOperationEndStrategy策略列表
      for (WeakReference<AsyncOperationEndStrategy> ref : strategies) {
        AsyncOperationEndStrategy s = ref.get();
        if (s == null) {
          purgeCollectedWeakReferences = true;
          // 执行具体策略的supports方法，匹配具体的策略返回，用于调用具体策略的end方法
        } else if (s.supports(returnType)) {
          return s;
        }
      }
      return null;
    } finally {
      if (purgeCollectedWeakReferences) {
        strategies.removeIf(ref -> ref.get() == null);
      }
    }
  }
}
