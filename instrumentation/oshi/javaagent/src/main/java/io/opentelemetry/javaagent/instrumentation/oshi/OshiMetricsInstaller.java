/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.javaagent.instrumentation.oshi;

import com.google.auto.service.AutoService;
import io.opentelemetry.javaagent.extension.AgentListener;
import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdk;
import io.opentelemetry.sdk.autoconfigure.internal.AutoConfigureUtil;
import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties;
import java.lang.reflect.Method;

/**
 * 主要作用是集成OSHI提供的系统指标到OpenTelemetry的指标收集和报告框架中。用于安装和配置系统级别指标收集的工具
 * 这样开发者可以在其应用程序中监控和分析系统性能指标，帮助识别和解决潜在的性能问题。
 *
 * An {@link AgentListener} that enables oshi metrics during agent startup if oshi is present on the
 * system classpath.
 */
@AutoService(AgentListener.class)
public class OshiMetricsInstaller implements AgentListener {

  @Override
  public void afterAgent(AutoConfiguredOpenTelemetrySdk autoConfiguredSdk) {
    // 通过反射调用AutoConfiguredOpenTelemetrySdk的getConfig访法，获取配置信息
    ConfigProperties config = AutoConfigureUtil.getConfig(autoConfiguredSdk);

    // defaultEnabled默认为true
    boolean defaultEnabled = config.getBoolean("otel.instrumentation.common.default-enabled", true);
    if (!config.getBoolean("otel.instrumentation.oshi.enabled", defaultEnabled)) {
      // 默认不进入这里
      return;
    }

    try {
      // Call oshi.SystemInfo.getCurrentPlatformEnum() to activate SystemMetrics.
      // Oshi instrumentation will intercept this call and enable SystemMetrics.
      // 调用oshi.SystemInfo.getCurrentPlatformEnum()来激活SystemMetrics, Oshi检测将拦截此调用并启用SystemMetrics。
      Class<?> oshiSystemInfoClass = ClassLoader.getSystemClassLoader().loadClass("oshi.SystemInfo");
      Method getCurrentPlatformEnumMethod = getCurrentPlatformMethod(oshiSystemInfoClass);
      getCurrentPlatformEnumMethod.invoke(null);
    } catch (Throwable ex) {
      // OK
    }
  }

  private static Method getCurrentPlatformMethod(Class<?> oshiSystemInfoClass) throws NoSuchMethodException {
    try {
      return oshiSystemInfoClass.getMethod("getCurrentPlatformEnum");
    } catch (NoSuchMethodException exception) {
      // renamed in oshi 6.0.0
      return oshiSystemInfoClass.getMethod("getCurrentPlatform");
    }
  }
}
