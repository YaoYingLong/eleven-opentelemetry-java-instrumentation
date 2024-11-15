/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.javaagent.tooling.config;

import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties;

public final class AgentConfig {

  /**
   * instrumentationNames - InstrumentationModule实现类中调用super访法，设置的mainInstrumentationName和additionalInstrumentationNames去重后的列表
   * defaultEnabled       - 具体的InstrumentationModule可以实现defaultEnabled，若未实现默认未true
   */
  public static boolean isInstrumentationEnabled(ConfigProperties config, Iterable<String> instrumentationNames, boolean defaultEnabled) {
    // If default is enabled, we want to enable individually,
    // if default is disabled, we want to disable individually.
    // 具体的InstrumentationModule可以实现defaultEnabled，若未实现默认未true
    boolean anyEnabled = defaultEnabled;
    // 遍历组件名称，可以针对具体的组件名称配置是否enabled
    for (String name : instrumentationNames) {
      String propertyName = "otel.instrumentation." + name + ".enabled";
      // 一般没有配置会使用默认值defaultEnabled，该值默认为true
      boolean enabled = config.getBoolean(propertyName, defaultEnabled);

      // defaultEnabled值默认为true
      if (defaultEnabled) {
        anyEnabled &= enabled;
      } else {
        anyEnabled |= enabled;
      }
    }
    return anyEnabled;
  }

  public static boolean isDebugModeEnabled(ConfigProperties config) {
    return config.getBoolean("otel.javaagent.debug", false);
  }

  private AgentConfig() {}
}
