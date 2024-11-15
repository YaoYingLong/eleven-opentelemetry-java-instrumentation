/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.javaagent.tooling;

import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.semconv.SemanticAttributes;

public class AddThreadDetailsSpanProcessor implements SpanProcessor {

  @Override
  public void onStart(Context context, ReadWriteSpan span) {
    Thread currentThread = Thread.currentThread();
    // 获取当前线程的id设置到Span的Attribute中，key为thread.id
    span.setAttribute(SemanticAttributes.THREAD_ID, currentThread.getId());
    // 获取当前线程的名称设置到Span的Attribute中，key为thread.name
    span.setAttribute(SemanticAttributes.THREAD_NAME, currentThread.getName());
  }

  @Override
  public boolean isStartRequired() {
    return true;
  }

  @Override
  public void onEnd(ReadableSpan span) {}

  @Override
  public boolean isEndRequired() {
    return false;
  }

  @Override
  public CompletableResultCode shutdown() {
    return CompletableResultCode.ofSuccess();
  }

  @Override
  public CompletableResultCode forceFlush() {
    return CompletableResultCode.ofSuccess();
  }
}
