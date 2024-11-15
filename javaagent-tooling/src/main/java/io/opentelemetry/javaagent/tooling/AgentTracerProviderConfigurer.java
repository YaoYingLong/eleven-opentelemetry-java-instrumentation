/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.javaagent.tooling;

import static io.opentelemetry.javaagent.tooling.AgentInstaller.JAVAAGENT_ENABLED_CONFIG;
import static java.util.Collections.emptyList;

import com.google.auto.service.AutoService;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import io.opentelemetry.exporter.logging.LoggingSpanExporter;
import io.opentelemetry.javaagent.tooling.config.AgentConfig;
import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizer;
import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizerProvider;
import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties;
import io.opentelemetry.sdk.trace.SdkTracerProviderBuilder;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;

@AutoService(AutoConfigurationCustomizerProvider.class)
public class AgentTracerProviderConfigurer implements AutoConfigurationCustomizerProvider {
  private static final String ADD_THREAD_DETAILS = "otel.javaagent.add-thread-details";

  @Override
  public void customize(AutoConfigurationCustomizer autoConfigurationCustomizer) {
    autoConfigurationCustomizer.addTracerProviderCustomizer(AgentTracerProviderConfigurer::configure);
  }

  @CanIgnoreReturnValue
  private static SdkTracerProviderBuilder configure(SdkTracerProviderBuilder sdkTracerProviderBuilder, ConfigProperties config) {
    // 获取otel.javaagent.enabled配置，默认为ture
    if (!config.getBoolean(JAVAAGENT_ENABLED_CONFIG, true)) {
      // 默认不会走该分支
      return sdkTracerProviderBuilder;
    }
    // Register additional thread details logging span processor
    // 注册用于获取线程详细信息的SpanProcessor，即AddThreadDetailsSpanProcessor，为每个Span中添加线程名称和线程id属性
    if (config.getBoolean(ADD_THREAD_DETAILS, true)) {
      sdkTracerProviderBuilder.addSpanProcessor(new AddThreadDetailsSpanProcessor());
    }
    // 如果时debug模式，即otel.javaagent.debug配置为true，则默认添加通过logging的方式导出traces的SpanProcessor
    maybeEnableLoggingExporter(sdkTracerProviderBuilder, config);

    return sdkTracerProviderBuilder;
  }

  private static void maybeEnableLoggingExporter(SdkTracerProviderBuilder builder, ConfigProperties config) {
    // 获取otel.javaagent.debug配置，默认为false
    if (AgentConfig.isDebugModeEnabled(config)) {
      // don't install another instance if the user has already explicitly requested it.
      // 获取otel.traces.exporter配置，若该配置不包含logging则返回ture
      if (loggingExporterIsNotAlreadyConfigured(config)) {
        // 添加通过logging的方式导出traces的SpanProcessor
        builder.addSpanProcessor(SimpleSpanProcessor.create(LoggingSpanExporter.create()));
      }
    }
  }

  private static boolean loggingExporterIsNotAlreadyConfigured(ConfigProperties config) {
    // 获取otel.traces.exporter配置，若该配置不包含logging则返回ture
    return !config.getList("otel.traces.exporter", emptyList()).contains("logging");
  }
}
