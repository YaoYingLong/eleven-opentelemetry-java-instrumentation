/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.javaagent.instrumentation.httpurlconnection;

import static io.opentelemetry.javaagent.bootstrap.Java8BytecodeBridge.currentContext;
import static io.opentelemetry.javaagent.extension.matcher.AgentElementMatchers.extendsClass;
import static io.opentelemetry.javaagent.instrumentation.httpurlconnection.HttpUrlConnectionSingletons.instrumenter;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.nameStartsWith;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.namedOneOf;
import static net.bytebuddy.matcher.ElementMatchers.not;

import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.instrumentation.api.util.VirtualField;
import io.opentelemetry.javaagent.bootstrap.CallDepth;
import io.opentelemetry.javaagent.extension.instrumentation.TypeInstrumentation;
import io.opentelemetry.javaagent.extension.instrumentation.TypeTransformer;
import java.net.HttpURLConnection;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.matcher.ElementMatchers;

public class HttpUrlConnectionInstrumentation implements TypeInstrumentation {
  @Override
  public ElementMatcher<TypeDescription> typeMatcher() {
    return nameStartsWith("java.net.")
        .or(ElementMatchers.<TypeDescription>nameStartsWith("sun.net"))
        // In WebLogic, URL.openConnection() returns its own internal implementation of
        // HttpURLConnection, which does not delegate the methods that have to be instrumented to
        // the JDK superclass. Therefore it needs to be instrumented directly.
        .or(named("weblogic.net.http.HttpURLConnection"))
        // This class is a simple delegator. Skip because it does not update its `connected`
        // field.
        .and(not(named("sun.net.www.protocol.https.HttpsURLConnectionImpl")))
        .and(extendsClass(named("java.net.HttpURLConnection")));
  }

  @Override
  public void transform(TypeTransformer transformer) {
    // 对HttpURLConnection的connect、getOutputStream、getInputStream方法进行埋点
    transformer.applyAdviceToMethod(
        isMethod().and(isPublic()).and(namedOneOf("connect", "getOutputStream", "getInputStream")),
        this.getClass().getName() + "$HttpUrlConnectionAdvice");

    // 对HttpURLConnection的getResponseCode方法进行埋点
    transformer.applyAdviceToMethod(
        isMethod().and(isPublic()).and(named("getResponseCode")),
        this.getClass().getName() + "$GetResponseCodeAdvice");
  }

  @SuppressWarnings("unused")
  public static class HttpUrlConnectionAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void methodEnter(@Advice.This HttpURLConnection connection,
        @Advice.FieldValue("connected") boolean connected,
        @Advice.Local("otelHttpUrlState") HttpUrlState httpUrlState,
        @Advice.Local("otelScope") Scope scope,
        @Advice.Local("otelCallDepth") CallDepth callDepth) {

      // 通过ThreadLocal存储HttpURLConnection.class类方法的调用次数CallDepth
      callDepth = CallDepth.forClass(HttpURLConnection.class);
      // 如果CallDepth中记录的调用深度depth大于0，说明可能是嵌套调用，或者connect、getOutputStream、getInputStream多次调用
      if (callDepth.getAndIncrement() > 0) {
        // only want the rest of the instrumentation rules (which are complex enough) to apply to
        // top-level HttpURLConnection calls
        return;
      }

      Context parentContext = currentContext();
      if (!instrumenter().shouldStart(parentContext, connection)) {
        return;
      }

      // using storage for a couple of reasons:
      // - to start an operation in connect() and end it in getInputStream()
      // - to avoid creating a new operation on multiple subsequent calls to getInputStream()
      VirtualField<HttpURLConnection, HttpUrlState> storage =
          VirtualField.find(HttpURLConnection.class, HttpUrlState.class);
      httpUrlState = storage.get(connection);

      // 如果从VirtualField中获取到与HttpURLConnection实例绑定的HttpUrlState实例不为空
      if (httpUrlState != null) {
        // 如果请求状态未完成，需要调用context.makeCurrent还原ThreadLocal中的context
        if (!httpUrlState.finished) {
          scope = httpUrlState.context.makeCurrent();
        }
        return;
      }

      Context context = instrumenter().start(parentContext, connection);
      httpUrlState = new HttpUrlState(context);
      storage.set(connection, httpUrlState);
      scope = context.makeCurrent();
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void methodExit(
        @Advice.This HttpURLConnection connection,
        @Advice.FieldValue("responseCode") int responseCode,
        @Advice.Thrown Throwable throwable,
        @Advice.Origin("#m") String methodName,
        @Advice.Local("otelHttpUrlState") HttpUrlState httpUrlState,
        @Advice.Local("otelScope") Scope scope,
        @Advice.Local("otelCallDepth") CallDepth callDepth) {
      if (callDepth.decrementAndGet() > 0) {
        return;
      }
      if (scope == null) {
        return;
      }
      // prevent infinite recursion in case end() captures response headers due to
      // HttpUrlConnection.getHeaderField() calling HttpUrlConnection.getInputStream() which then
      // enters this advice again
      callDepth.getAndIncrement();
      try {
        scope.close();
        Class<? extends HttpURLConnection> connectionClass = connection.getClass();

        String requestMethod = connection.getRequestMethod();
        GetOutputStreamContext.set(httpUrlState.context, connectionClass, methodName, requestMethod);

        // 如果存在异常
        if (throwable != null) {
          if (responseCode >= 400) {
            // HttpURLConnection unnecessarily throws exception on error response.
            // None of the other http clients do this, so not recording the exception on the span
            // to be consistent with the telemetry for other http clients.
            instrumenter().end(httpUrlState.context, connection, responseCode, null);
          } else {
            instrumenter().end(httpUrlState.context, connection,
                    responseCode > 0 ? responseCode : httpUrlState.statusCode,
                    throwable);
          }
          httpUrlState.finished = true;
          // 不存在异常，且调用的方法为getInputStream，且responseCode大于0
        } else if (methodName.equals("getInputStream") && responseCode > 0) {
          // responseCode field is sometimes not populated.
          // We can't call getResponseCode() due to some unwanted side-effects
          // (e.g. breaks getOutputStream).
          instrumenter().end(httpUrlState.context, connection, responseCode, null);
          httpUrlState.finished = true;
        }
      } finally {
        // 将调用深度减1
        callDepth.decrementAndGet();
      }
    }
  }

  @SuppressWarnings("unused")
  public static class GetResponseCodeAdvice {

    @Advice.OnMethodExit
    public static void methodExit(@Advice.This HttpURLConnection connection, @Advice.Return int returnValue) {

      VirtualField<HttpURLConnection, HttpUrlState> storage =
          VirtualField.find(HttpURLConnection.class, HttpUrlState.class);
      HttpUrlState httpUrlState = storage.get(connection);
      if (httpUrlState != null) {
        httpUrlState.statusCode = returnValue;
      }
    }
  }
}
