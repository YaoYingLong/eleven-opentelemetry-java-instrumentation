/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.instrumentation.netty.v4.common.internal.client;

/**
 * This class is internal and is hence not for public use. Its APIs are unstable and can change at
 * any time.
 */
public enum NettyConnectionInstrumentationFlag {
  ENABLED,    // 启用
  ERROR_ONLY, // 仅错误
  DISABLED;   // 禁用

  public static NettyConnectionInstrumentationFlag enabledOrErrorOnly(boolean b) {
    return b ? ENABLED : ERROR_ONLY;
  }
}
