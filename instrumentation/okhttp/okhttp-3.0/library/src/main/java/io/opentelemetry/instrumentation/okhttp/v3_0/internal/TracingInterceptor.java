/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.instrumentation.okhttp.v3_0.internal;

import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.instrumentation.api.instrumenter.Instrumenter;
import java.io.IOException;
import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.Response;

/**
 * This class is internal and is hence not for public use. Its APIs are unstable and can change at
 * any time.
 */
public final class TracingInterceptor implements Interceptor {

  private final Instrumenter<Request, Response> instrumenter;
  private final ContextPropagators propagators;

  public TracingInterceptor(
      Instrumenter<Request, Response> instrumenter, ContextPropagators propagators) {
    this.instrumenter = instrumenter;
    this.propagators = propagators;
  }

  @Override
  public Response intercept(Chain chain) throws IOException {
    Request request = chain.request();
    Context parentContext = Context.current();

    if (!instrumenter.shouldStart(parentContext, request)) {
      return chain.proceed(chain.request());
    }

    Context context = instrumenter.start(parentContext, request);
    request = injectContextToRequest(request, context);

    Response response = null;
    Throwable error = null;
    try (Scope ignored = context.makeCurrent()) {
      response = chain.proceed(request);
      return response;
    } catch (Exception e) {
      error = e;
      throw e;
    } finally {
      instrumenter.end(context, request, response, error);
    }
  }

  // Context injection is being handled manually for a reason: we want to use the OkHttp Request
  // type for additional AttributeExtractors provided by the user of this library
  // thus we must use Instrumenter<Request, Response>, and Request is immutable
  private Request injectContextToRequest(Request request, Context context) {
    Request.Builder requestBuilder = request.newBuilder();
    propagators
        .getTextMapPropagator()
        .inject(context, requestBuilder, RequestHeaderSetter.INSTANCE);
    return requestBuilder.build();
  }
}
