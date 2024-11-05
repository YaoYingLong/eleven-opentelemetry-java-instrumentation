/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.javaagent.instrumentation.tomcat.v10_0;

import io.opentelemetry.instrumentation.api.instrumenter.Instrumenter;
import io.opentelemetry.javaagent.instrumentation.servlet.v5_0.Servlet5Accessor;
import io.opentelemetry.javaagent.instrumentation.servlet.v5_0.Servlet5Singletons;
import io.opentelemetry.javaagent.instrumentation.tomcat.common.TomcatHelper;
import io.opentelemetry.javaagent.instrumentation.tomcat.common.TomcatInstrumenterFactory;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.coyote.Request;
import org.apache.coyote.Response;

public final class Tomcat10Singletons {
  private static final String INSTRUMENTATION_NAME = "io.opentelemetry.tomcat-10.0";

  /**
   * Servlet5Accessor中提供了各种用于获取HttpServletRequest上的各种信息的方法，如contextPath、requestUrl、scheme等
   * 但是这里只是用其来构造ServletErrorCauseExtractor，当执行发生异常时，调用Servlet5Accessor的isServletException方法
   * 用于判断Throwable的类型是否为ServletException
   */
  private static final Instrumenter<Request, Response> INSTRUMENTER =
      TomcatInstrumenterFactory.create(INSTRUMENTATION_NAME, Servlet5Accessor.INSTANCE);

  /**
   * 这里的
   */
  private static final TomcatHelper<HttpServletRequest, HttpServletResponse> HELPER =
      new TomcatHelper<>(INSTRUMENTER, Tomcat10ServletEntityProvider.INSTANCE, Servlet5Singletons.helper());

  public static TomcatHelper<HttpServletRequest, HttpServletResponse> helper() {
    return HELPER;
  }

  private Tomcat10Singletons() {}
}
