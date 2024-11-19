/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.javaagent.bootstrap;

import javax.annotation.Nullable;

public interface InternalLogger {

  static void initialize(Factory factory) {
    // 通过CAS将Factory设置到InternalLoggerFactoryHolder中
    InternalLoggerFactoryHolder.initialize(factory);
  }

  static InternalLogger getLogger(String name) {
    // 从InternalLoggerFactoryHolder中获取initialize时设置的Factory
    return InternalLoggerFactoryHolder.get().create(name);
  }

  boolean isLoggable(Level level);

  void log(Level level, String message, @Nullable Throwable error);

  String name();

  enum Level {
    ERROR,
    WARN,
    INFO,
    DEBUG,
    TRACE
  }

  @FunctionalInterface
  interface Factory {

    // 该函数表达式是在getLogger时被调用
    InternalLogger create(String name);
  }
}
