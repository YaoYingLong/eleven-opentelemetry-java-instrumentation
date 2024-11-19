/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.instrumentation.api.instrumenter.util;

import io.opentelemetry.instrumentation.api.internal.ClassNames;
import io.opentelemetry.instrumentation.api.internal.cache.Cache;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class SpanNames {

  // 这里做了一个缓存，同一个类的同一个方法只会解析一次
  private static final Cache<Class<?>, Map<String, String>> spanNameCaches = Cache.weak();

  /**
   * This method is used to generate a span name based on a method. Anonymous classes are named
   * based on their parent.
   */
  public static String fromMethod(Method method) {
    return fromMethod(method.getDeclaringClass(), method.getName());
  }

  /**
   * This method is used to generate a span name based on a method. Anonymous classes are named
   * based on their parent.
   */
  public static String fromMethod(Class<?> clazz, String methodName) {
    // the cache (ConcurrentHashMap) is naturally bounded by the number of methods in a class
    Map<String, String> spanNameCache = spanNameCaches.computeIfAbsent(clazz, c -> new ConcurrentHashMap<>());

    // not using computeIfAbsent, because it would require a capturing (allocating) lambda
    String spanName = spanNameCache.get(methodName);
    if (spanName != null) {
      // 从缓存中获取到了就直接返回
      return spanName;
    }
    // 这里其实就是将类的简单名称即不带包名的与方法名称通过.拼接返回
    spanName = ClassNames.simpleName(clazz) + "." + methodName;
    spanNameCache.put(methodName, spanName);
    return spanName;
  }

  private SpanNames() {}
}
