/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.javaagent.instrumentation.tomcat.v7_0;

import static io.opentelemetry.javaagent.instrumentation.tomcat.v7_0.Tomcat7Singletons.helper;

import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.javaagent.bootstrap.Java8BytecodeBridge;
import io.opentelemetry.javaagent.bootstrap.http.HttpServerResponseCustomizerHolder;
import net.bytebuddy.asm.Advice;
import org.apache.coyote.Request;
import org.apache.coyote.Response;

@SuppressWarnings("unused")
public class Tomcat7ServerHandlerAdvice {

  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static void onEnter(
      @Advice.Argument(0) Request request,
      @Advice.Argument(1) Response response,
      @Advice.Local("otelContext") Context context,
      @Advice.Local("otelScope") Scope scope) {

    Context parentContext = Java8BytecodeBridge.currentContext();
    if (!helper().shouldStart(parentContext, request)) {
      return;
    }

    context = helper().start(parentContext, request);

    scope = context.makeCurrent();
    // HttpServerResponseCustomizerHolder中获取的HttpServerResponseCustomizer是在启动时，通过SPI机制加载的所有的实现类HttpServerResponseCustomizer接口的
    // 并将这些实现类列表，封装到了一个新的HttpServerResponseCustomizer，且其customize就是遍历执行所有实现类的customize访法
    HttpServerResponseCustomizerHolder.getCustomizer().customize(context, response, Tomcat7ResponseMutator.INSTANCE);
  }

  @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
  public static void stopSpan(
      @Advice.Argument(0) Request request,
      @Advice.Argument(1) Response response,
      @Advice.Thrown Throwable throwable,
      @Advice.Local("otelContext") Context context,
      @Advice.Local("otelScope") Scope scope) {

    helper().end(request, response, throwable, context, scope);
  }
}
