/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.instrumentation.javaagent.runtimemetrics.java17;

import com.google.auto.service.AutoService;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.instrumentation.runtimemetrics.java17.RuntimeMetrics;
import io.opentelemetry.javaagent.extension.AgentListener;
import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdk;
import io.opentelemetry.sdk.autoconfigure.internal.AutoConfigureUtil;
import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties;

/** An {@link AgentListener} that enables runtime metrics during agent startup. */
@AutoService(AgentListener.class)
public class Java17RuntimeMetricsInstaller implements AgentListener {

  @Override
  public void afterAgent(AutoConfiguredOpenTelemetrySdk autoConfiguredSdk) {
    ConfigProperties config = AutoConfigureUtil.getConfig(autoConfiguredSdk);

    OpenTelemetry openTelemetry = GlobalOpenTelemetry.get();
    RuntimeMetrics runtimeMetrics = null;
    /*
    By default don't use any JFR metrics. May change this once semantic conventions are updated.
    If enabled, default to only the metrics not already covered by runtime-telemetry-java8

    默认情况下，不使用任何 JFR 指标。更新语义约定后，可能会更改此情况。如果启用，则默认仅为 runtime-telemetry-java8 尚未涵盖的指标
    */
    boolean defaultEnabled = config.getBoolean("otel.instrumentation.common.default-enabled", true);
    if (config.getBoolean("otel.instrumentation.runtime-telemetry-java17.enable-all", false)) {
      // 默认不会走该分支
      runtimeMetrics = RuntimeMetrics.builder(openTelemetry).enableAllFeatures().build();
    } else if (config.getBoolean("otel.instrumentation.runtime-telemetry-java17.enabled", false)) {
      // 默认不会走该分支
      runtimeMetrics = RuntimeMetrics.create(openTelemetry);
    } else if (config.getBoolean("otel.instrumentation.runtime-telemetry.enabled", defaultEnabled)) {
      // This only uses metrics gathered by JMX
      // 默认走该分支，仅使用JMX收集的指标
      runtimeMetrics = RuntimeMetrics.builder(openTelemetry).disableAllFeatures().build();
    }

    if (runtimeMetrics != null) {
      RuntimeMetrics finalJfrTelemetry = runtimeMetrics;
      Thread cleanupTelemetry = new Thread(() -> finalJfrTelemetry.close());
      Runtime.getRuntime().addShutdownHook(cleanupTelemetry);
    }
  }
}
