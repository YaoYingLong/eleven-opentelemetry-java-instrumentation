/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.javaagent.instrumentation.servlet;

import com.google.errorprone.annotations.CanIgnoreReturnValue;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.instrumentation.api.instrumenter.AttributesExtractor;
import io.opentelemetry.instrumentation.api.instrumenter.ContextCustomizer;
import io.opentelemetry.instrumentation.api.instrumenter.Instrumenter;
import io.opentelemetry.instrumentation.api.instrumenter.InstrumenterBuilder;
import io.opentelemetry.instrumentation.api.instrumenter.SpanNameExtractor;
import io.opentelemetry.instrumentation.api.instrumenter.http.HttpServerAttributesExtractor;
import io.opentelemetry.instrumentation.api.instrumenter.http.HttpServerAttributesGetter;
import io.opentelemetry.instrumentation.api.instrumenter.http.HttpServerExperimentalMetrics;
import io.opentelemetry.instrumentation.api.instrumenter.http.HttpServerMetrics;
import io.opentelemetry.instrumentation.api.instrumenter.http.HttpServerRoute;
import io.opentelemetry.instrumentation.api.instrumenter.http.HttpSpanNameExtractor;
import io.opentelemetry.instrumentation.api.instrumenter.http.HttpSpanStatusExtractor;
import io.opentelemetry.javaagent.bootstrap.internal.CommonConfig;
import java.util.ArrayList;
import java.util.List;

public final class ServletInstrumenterBuilder<REQUEST, RESPONSE> {

  private ServletInstrumenterBuilder() {}

  private final List<ContextCustomizer<? super ServletRequestContext<REQUEST>>> contextCustomizers =
      new ArrayList<>();

  public static <REQUEST, RESPONSE> ServletInstrumenterBuilder<REQUEST, RESPONSE> create() {
    return new ServletInstrumenterBuilder<>();
  }

  @CanIgnoreReturnValue
  public ServletInstrumenterBuilder<REQUEST, RESPONSE> addContextCustomizer(
      ContextCustomizer<? super ServletRequestContext<REQUEST>> contextCustomizer) {
    contextCustomizers.add(contextCustomizer);
    return this;
  }

  /**
   *
   * @param instrumentationName
   * @param accessor              - Servlet5Accessor或Servlet3Accessor
   * @param spanNameExtractor     - HttpSpanNameExtractor
   * @param httpAttributesGetter  - ServletHttpAttributesGetter
   * @return
   */
  public Instrumenter<ServletRequestContext<REQUEST>, ServletResponseContext<RESPONSE>> build(
      String instrumentationName,
      ServletAccessor<REQUEST, RESPONSE> accessor,
      SpanNameExtractor<ServletRequestContext<REQUEST>> spanNameExtractor,
      HttpServerAttributesGetter<ServletRequestContext<REQUEST>, ServletResponseContext<RESPONSE>> httpAttributesGetter) {
    // 用于发生异常是提取异常原因Throwable::getCause
    ServletErrorCauseExtractor<REQUEST, RESPONSE> errorCauseExtractor = new ServletErrorCauseExtractor<>(accessor);
    // 作用是在onEnd中获取HttpServletRequest上的Principal信息或若出现超时，将Principal中获取的用户名称和超时信息添加到AttributesBuilder中
    AttributesExtractor<ServletRequestContext<REQUEST>, ServletResponseContext<RESPONSE>>
        additionalAttributesExtractor = new ServletAdditionalAttributesExtractor<>(accessor);

    InstrumenterBuilder<ServletRequestContext<REQUEST>, ServletResponseContext<RESPONSE>> builder =
        Instrumenter.<ServletRequestContext<REQUEST>, ServletResponseContext<RESPONSE>>builder(GlobalOpenTelemetry.get(), instrumentationName, spanNameExtractor)
            // 或HTTP状态，其实最终就是调用HttpServletResponse的getStatus方法
            .setSpanStatusExtractor(HttpSpanStatusExtractor.create(httpAttributesGetter))
            .setErrorCauseExtractor(errorCauseExtractor)
            .addAttributesExtractor(
                HttpServerAttributesExtractor.builder(httpAttributesGetter)
                    .setCapturedRequestHeaders(CommonConfig.get().getServerRequestHeaders())
                    .setCapturedResponseHeaders(CommonConfig.get().getServerResponseHeaders())
                    .setKnownMethods(CommonConfig.get().getKnownHttpRequestMethods())
                    .build())
            .addAttributesExtractor(additionalAttributesExtractor)
            .addOperationMetrics(HttpServerMetrics.get())
            .addContextCustomizer(
                HttpServerRoute.builder(httpAttributesGetter)
                    .setKnownMethods(CommonConfig.get().getKnownHttpRequestMethods())
                    .build());
    if (ServletRequestParametersExtractor.enabled()) {
      AttributesExtractor<ServletRequestContext<REQUEST>, ServletResponseContext<RESPONSE>>
          requestParametersExtractor = new ServletRequestParametersExtractor<>(accessor);
      builder.addAttributesExtractor(requestParametersExtractor);
    }
    for (ContextCustomizer<? super ServletRequestContext<REQUEST>> contextCustomizer :
        contextCustomizers) {
      builder.addContextCustomizer(contextCustomizer);
    }
    if (CommonConfig.get().shouldEmitExperimentalHttpServerMetrics()) {
      builder.addOperationMetrics(HttpServerExperimentalMetrics.get());
    }
    return builder.buildServerInstrumenter(new ServletRequestGetter<>(accessor));
  }

  /**
   * @param instrumentationName
   * @param accessor：Servlet5Accessor或Servlet3Accessor
   * @return
   */
  public Instrumenter<ServletRequestContext<REQUEST>, ServletResponseContext<RESPONSE>> build(
      String instrumentationName, ServletAccessor<REQUEST, RESPONSE> accessor) {
    // 这里其实就是将ServletAccessor再次封装一次，目的是调用ServletAccessor的方法获取HttpServletRequest和HttpServletResponse中的属性
    HttpServerAttributesGetter<ServletRequestContext<REQUEST>, ServletResponseContext<RESPONSE>>
        httpAttributesGetter = new ServletHttpAttributesGetter<>(accessor);
    // 构建SpanName，这里默认是以HttpServletRequest的Method作为SpanName
    SpanNameExtractor<ServletRequestContext<REQUEST>> spanNameExtractor =
        HttpSpanNameExtractor.builder(httpAttributesGetter)
            .setKnownMethods(CommonConfig.get().getKnownHttpRequestMethods())
            .build();

    return build(instrumentationName, accessor, spanNameExtractor, httpAttributesGetter);
  }
}
