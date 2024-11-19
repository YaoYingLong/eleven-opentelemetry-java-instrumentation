/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.javaagent.instrumentation.extensionannotations;

import static java.util.logging.Level.FINE;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.instrumentation.api.annotation.support.MethodSpanAttributesExtractor;
import io.opentelemetry.instrumentation.api.instrumenter.Instrumenter;
import io.opentelemetry.instrumentation.api.instrumenter.code.CodeAttributesExtractor;
import io.opentelemetry.instrumentation.api.instrumenter.util.SpanNames;
import java.lang.reflect.Method;
import java.util.logging.Logger;

@SuppressWarnings("deprecation") // instrumenting deprecated class for backwards compatibility
public final class WithSpanSingletons {

  private static final String INSTRUMENTATION_NAME =
      "io.opentelemetry.opentelemetry-extension-annotations-1.0";

  private static final Logger logger = Logger.getLogger(WithSpanSingletons.class.getName());
  private static final Instrumenter<Method, Object> INSTRUMENTER = createInstrumenter();
  private static final Instrumenter<MethodRequest, Object> INSTRUMENTER_WITH_ATTRIBUTES =
      createInstrumenterWithAttributes();

  public static Instrumenter<Method, Object> instrumenter() {
    return INSTRUMENTER;
  }

  public static Instrumenter<MethodRequest, Object> instrumenterWithAttributes() {
    return INSTRUMENTER_WITH_ATTRIBUTES;
  }

  private static Instrumenter<Method, Object> createInstrumenter() {
    // 如果通过@WithSpan(value="spanName")指定了就使用，若未指定则通过类名和方法名作为span的名称返回
    return Instrumenter.builder(GlobalOpenTelemetry.get(), INSTRUMENTATION_NAME,
            WithSpanSingletons::spanNameFromMethod)
        // 就是获取类和方法，设置到属性中
        .addAttributesExtractor(CodeAttributesExtractor.create(MethodCodeAttributesGetter.INSTANCE))
        // @WithSpan注解默认的kind是SpanKind.INTERNAL，也可以通过kind属性修改，如果指定的值不在枚举范围内则还是SpanKind.INTERNAL
        .buildInstrumenter(WithSpanSingletons::spanKindFromMethod);
  }

  private static Instrumenter<MethodRequest, Object> createInstrumenterWithAttributes() {
    return Instrumenter.builder(GlobalOpenTelemetry.get(), INSTRUMENTATION_NAME,
            WithSpanSingletons::spanNameFromMethodRequest)
        .addAttributesExtractor(
            CodeAttributesExtractor.create(MethodRequestCodeAttributesGetter.INSTANCE))
        .addAttributesExtractor(MethodSpanAttributesExtractor.create(
            MethodRequest::method,
            WithSpanParameterAttributeNamesExtractor.INSTANCE,
            MethodRequest::args))
        .buildInstrumenter(WithSpanSingletons::spanKindFromMethodRequest);
  }

  private static SpanKind spanKindFromMethodRequest(MethodRequest request) {
    return spanKindFromMethod(request.method());
  }

  private static SpanKind spanKindFromMethod(Method method) {
    application.io.opentelemetry.extension.annotations.WithSpan annotation =
        method.getDeclaredAnnotation(
            application.io.opentelemetry.extension.annotations.WithSpan.class);
    if (annotation == null) {
      return SpanKind.INTERNAL;
    }
    // 如果@WithSpan注解默认的kind是SpanKind.INTERNAL，也可以修改
    return toAgentOrNull(annotation.kind());
  }

  private static SpanKind toAgentOrNull(
      application.io.opentelemetry.api.trace.SpanKind applicationSpanKind) {
    try {
      return SpanKind.valueOf(applicationSpanKind.name());
    } catch (IllegalArgumentException e) {
      logger.log(FINE, "unexpected span kind: {0}", applicationSpanKind.name());
      return SpanKind.INTERNAL;
    }
  }

  private static String spanNameFromMethodRequest(MethodRequest request) {
    return spanNameFromMethod(request.method());
  }

  private static String spanNameFromMethod(Method method) {
    application.io.opentelemetry.extension.annotations.WithSpan annotation =
        method.getDeclaredAnnotation(
            application.io.opentelemetry.extension.annotations.WithSpan.class);
    String spanName = annotation.value();
    if (spanName.isEmpty()) {
      spanName = SpanNames.fromMethod(method);
    }
    // 如果通过@WithSpan(value="spanName")指定了就使用，若未指定则通过类名和方法名作为span的名称返回
    return spanName;
  }

  private WithSpanSingletons() {}
}
