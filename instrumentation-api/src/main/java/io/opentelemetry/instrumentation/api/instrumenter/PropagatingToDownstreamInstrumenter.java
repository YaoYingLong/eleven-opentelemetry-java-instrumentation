/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.instrumentation.api.instrumenter;

import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.context.propagation.TextMapSetter;

/**
 * 用于SpanKind为Client，即RPC客户端
 *
 * 用于需要跨进程传递执行上下文参数的，且向下传递参数，如HTTP Client
 */
final class PropagatingToDownstreamInstrumenter<REQUEST, RESPONSE>
    extends Instrumenter<REQUEST, RESPONSE> {

  private final ContextPropagators propagators;
  private final TextMapSetter<REQUEST> setter;

  PropagatingToDownstreamInstrumenter(InstrumenterBuilder<REQUEST, RESPONSE> builder, TextMapSetter<REQUEST> setter) {
    super(builder);
    this.propagators = builder.openTelemetry.getPropagators();
    this.setter = setter;
  }

  @Override
  public Context start(Context parentContext, REQUEST request) {
    Context newContext = super.start(parentContext, request);
    // 向newContext中设置
    propagators.getTextMapPropagator().inject(newContext, request, setter);
    return newContext;
  }
}
