/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.javaagent.instrumentation.spring.ws.v2_0;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.instrumentation.api.instrumenter.Instrumenter;
import io.opentelemetry.instrumentation.api.instrumenter.code.CodeAttributesExtractor;
import io.opentelemetry.instrumentation.api.instrumenter.code.CodeSpanNameExtractor;
import io.opentelemetry.javaagent.bootstrap.internal.ExperimentalConfig;

public class SpringWsSingletons {
  private static final String INSTRUMENTATION_NAME = "io.opentelemetry.spring-ws-2.0";

  private static final Instrumenter<SpringWsRequest, Void> INSTRUMENTER;

  static {
    SpringWsCodeAttributesGetter codeAttributesGetter = new SpringWsCodeAttributesGetter();

    INSTRUMENTER = Instrumenter.<SpringWsRequest, Void>builder(GlobalOpenTelemetry.get(),
            INSTRUMENTATION_NAME,
            CodeSpanNameExtractor.create(codeAttributesGetter))
        .addAttributesExtractor(CodeAttributesExtractor.create(codeAttributesGetter))
        // 可以通过配置类控制是否生效
        .setEnabled(ExperimentalConfig.get().controllerTelemetryEnabled())
        .buildInstrumenter();
  }

  public static Instrumenter<SpringWsRequest, Void> instrumenter() {
    return INSTRUMENTER;
  }

  private SpringWsSingletons() {}
}
