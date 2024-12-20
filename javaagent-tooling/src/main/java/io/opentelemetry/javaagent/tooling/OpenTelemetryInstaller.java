/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.javaagent.tooling;

import static java.util.Collections.singletonMap;

import io.opentelemetry.javaagent.bootstrap.OpenTelemetrySdkAccess;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdk;
import io.opentelemetry.sdk.common.CompletableResultCode;
import java.util.Arrays;

public final class OpenTelemetryInstaller {

  /**
   * Install the {@link OpenTelemetrySdk} using autoconfigure, and return the {@link
   * AutoConfiguredOpenTelemetrySdk}.
   *
   * @return the {@link AutoConfiguredOpenTelemetrySdk}
   */
  public static AutoConfiguredOpenTelemetrySdk installOpenTelemetrySdk(ClassLoader extensionClassLoader) {

    // 首先通过builder方法实例化AutoConfiguredOpenTelemetrySdkBuilder，然后设置ServiceClassLoader等属性
    // 最终调用AutoConfiguredOpenTelemetrySdkBuilder的build
    AutoConfiguredOpenTelemetrySdk autoConfiguredSdk = AutoConfiguredOpenTelemetrySdk.builder()
        // 设置为ture后会将生成的OpenTelemetrySdk设置到GlobalOpenTelemetry中
        .setResultAsGlobal()
        .setServiceClassLoader(extensionClassLoader)
        // disable the logs exporter by default for the time being
        // FIXME remove this in the 2.x branch
        // 默认禁用logs exporter
        .addPropertiesSupplier(() -> singletonMap("otel.logs.exporter", "none"))
        .build();
    OpenTelemetrySdk sdk = autoConfiguredSdk.getOpenTelemetrySdk();

    OpenTelemetrySdkAccess.internalSetForceFlush((timeout, unit) -> {
      CompletableResultCode traceResult = sdk.getSdkTracerProvider().forceFlush();
      CompletableResultCode metricsResult = sdk.getSdkMeterProvider().forceFlush();
      CompletableResultCode logsResult = sdk.getSdkLoggerProvider().forceFlush();
      CompletableResultCode.ofAll(Arrays.asList(traceResult, metricsResult, logsResult)).join(timeout, unit);
    });
    return autoConfiguredSdk;
  }

  private OpenTelemetryInstaller() {}
}
