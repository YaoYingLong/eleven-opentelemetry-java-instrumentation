/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.javaagent.instrumentation.methods;

import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;

import com.google.auto.service.AutoService;
import io.opentelemetry.javaagent.bootstrap.internal.InstrumentationConfig;
import io.opentelemetry.javaagent.extension.instrumentation.InstrumentationModule;
import io.opentelemetry.javaagent.extension.instrumentation.TypeInstrumentation;
import io.opentelemetry.javaagent.tooling.config.MethodsConfigurationParser;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 此模块的作用其实就是通过otel.instrumentation.methods.include配置额外需要拦截的埋点的方法
 * 这些被配置的方法会被录制和导出Span，其实效果和在方法调用上使用@WithSpan注解效果一样
 *
 * 且通过AsyncOperationEndSupport兼容了对同步和异步逻辑的处理
 */
@AutoService(InstrumentationModule.class)
public class MethodInstrumentationModule extends InstrumentationModule {

  private static final String TRACE_METHODS_CONFIG = "otel.instrumentation.methods.include";

  private final List<TypeInstrumentation> typeInstrumentations;

  public MethodInstrumentationModule() {
    super("methods");

    // 解析otel.instrumentation.methods.include配置中配置的信息
    Map<String, Set<String>> classMethodsToTrace = MethodsConfigurationParser.parse(
            InstrumentationConfig.get().getString(TRACE_METHODS_CONFIG));

    // 遍历解析到的信息，并将每一项都封装为一个MethodInstrumentation
    typeInstrumentations = classMethodsToTrace.entrySet().stream()
            .filter(e -> !e.getValue().isEmpty())
            .map(e -> new MethodInstrumentation(e.getKey(), e.getValue()))
            .collect(Collectors.toList());
  }

  // the default configuration has empty "otel.instrumentation.methods.include", and so doesn't
  // generate any TypeInstrumentation for muzzle to analyze
  // 默认配置为空“otel.instrumentation.methods.include”，因此不会生成任何TypeInstrumentation供muzzle分析
  @Override
  public List<String> getAdditionalHelperClassNames() {
    return typeInstrumentations.isEmpty()
        ? emptyList()
        : singletonList("io.opentelemetry.javaagent.instrumentation.methods.MethodSingletons");
  }

  @Override
  public List<TypeInstrumentation> typeInstrumentations() {
    return typeInstrumentations;
  }
}
