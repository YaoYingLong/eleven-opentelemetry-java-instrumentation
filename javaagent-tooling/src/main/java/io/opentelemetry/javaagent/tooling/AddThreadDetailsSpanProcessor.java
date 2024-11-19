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

  // 这个方法如果是整个Agent中只有一个SpanProcessor，则该方法不会被调用，但是有多个SpanProcessor时
  // 会在MultiSpanProcessor中被调用，目的是将需要执行onStart的单独放到一个spanProcessorsStart中执行
  @Override
  public boolean isStartRequired() {
    return true;
  }

  @Override
  public void onEnd(ReadableSpan span) {}

  // 这个方法如果是整个Agent中只有一个SpanProcessor，则该方法不会被调用，但是有多个SpanProcessor时
  // 会在MultiSpanProcessor中被调用，目的是将需要执行onEnd的单独放到一个spanProcessorsEnd中执行
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
