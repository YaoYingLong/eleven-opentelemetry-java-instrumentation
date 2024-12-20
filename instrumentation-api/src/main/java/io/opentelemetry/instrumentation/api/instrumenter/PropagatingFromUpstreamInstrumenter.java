/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.instrumentation.api.instrumenter;

import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.instrumentation.api.internal.ContextPropagationDebug;

/**
 * 用于SpanKind为Server，即RPC服务端
 *
 * 用于需要跨进程传递执行上下文参数的，且获取参数，如HTTP Server
 */
final class PropagatingFromUpstreamInstrumenter<REQUEST, RESPONSE>
    extends Instrumenter<REQUEST, RESPONSE> {

  private final ContextPropagators propagators;
  private final TextMapGetter<REQUEST> getter;

  PropagatingFromUpstreamInstrumenter(InstrumenterBuilder<REQUEST, RESPONSE> builder, TextMapGetter<REQUEST> getter) {
    super(builder);
    this.propagators = builder.openTelemetry.getPropagators();
    this.getter = getter;
  }

  @Override
  public Context start(Context parentContext, REQUEST request) {
    ContextPropagationDebug.debugContextLeakIfEnabled();
    // 从TextMapGetter中获取信息，并设置到parentContext中
    Context extracted = propagators.getTextMapPropagator().extract(parentContext, request, getter);
    return super.start(extracted, request);
  }
}
