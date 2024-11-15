/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.instrumentation.api.instrumenter;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.instrumentation.api.internal.InstrumenterAccess;
import io.opentelemetry.instrumentation.api.internal.InstrumenterUtil;
import io.opentelemetry.instrumentation.api.internal.SupportabilityMetrics;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.ListIterator;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;

/**
 * The {@link Instrumenter} encapsulates the entire logic for gathering telemetry, from collecting
 * the data, to starting and ending spans, to recording values using metrics instruments.
 *
 * <p>An {@link Instrumenter} is called at the start and the end of a request/response lifecycle.
 * When instrumenting a library, there will generally be four steps.
 *
 * <ul>
 *   <li>Create an {@link Instrumenter} using {@link InstrumenterBuilder}. Use the builder to
 *       configure any library-specific customizations, and also expose useful knobs to your user.
 *   <li>Call {@link Instrumenter#shouldStart(Context, Object)} and do not proceed if it returns
 *       {@code false}.
 *   <li>Call {@link Instrumenter#start(Context, Object)} at the beginning of a request.
 *   <li>Call {@link Instrumenter#end(Context, Object, Object, Throwable)} at the end of a request.
 * </ul>
 *
 * <p>For more detailed information about using the {@link Instrumenter} see the <a
 * href="https://github.com/open-telemetry/opentelemetry-java-instrumentation/blob/main/docs/contributing/using-instrumenter-api.md">Using
 * the Instrumenter API</a> page.
 */

/**
 * Instrumenter封装了用于收集遥测的整个逻辑，从收集数据到span开始和结束，再到使用 Metrics Instruments 记录值。
 * 通常是在执行一个请求的开始之前和执行完成之后调用，通常分为4步
 *  1、通过InstrumenterBuilder构建Instrumenter
 *  2、调用Instrumenter#shouldStart方法，如果该方法返回false则不执行
 *  3、在执行植入方法之前，调用Instrumenter#start方法
 *  4、在执行植入方法之后，调用Instrumenter#end方法
 *
 * 详情参照：docs/contributing/using-instrumenter-api.md
 *
 * Instrumenter可以配置各种提取器，以增强或修改遥测数据
 */
public class Instrumenter<REQUEST, RESPONSE> {

  /**
   * Returns a new {@link InstrumenterBuilder}.
   *
   * <p>The {@code instrumentationName} indicates the instrumentation library name, not the
   * instrument<b>ed</b> library name. The value passed in this parameter should uniquely identify
   * the instrumentation library so that during troubleshooting it's possible to determine where the
   * telemetry came from.
   *
   * <p>In OpenTelemetry instrumentations we use a convention to encode the minimum supported
   * version of the instrument<b>ed</b> library into the instrumentation name, for example {@code
   * io.opentelemetry.apache-httpclient-4.0}. This way, if there are different instrumentations for
   * different library versions it's easy to find out which instrumentations produced the telemetry
   * data.
   */
  public static <REQUEST, RESPONSE> InstrumenterBuilder<REQUEST, RESPONSE> builder(
      OpenTelemetry openTelemetry,
      String instrumentationName,
      SpanNameExtractor<? super REQUEST> spanNameExtractor) {
    return new InstrumenterBuilder<>(openTelemetry, instrumentationName, spanNameExtractor);
  }

  private static final SupportabilityMetrics supportability = SupportabilityMetrics.instance();

  private final String instrumentationName;
  private final Tracer tracer;
  private final SpanNameExtractor<? super REQUEST> spanNameExtractor;
  private final SpanKindExtractor<? super REQUEST> spanKindExtractor;
  private final SpanStatusExtractor<? super REQUEST, ? super RESPONSE> spanStatusExtractor;
  private final List<? extends SpanLinksExtractor<? super REQUEST>> spanLinksExtractors;
  private final List<? extends AttributesExtractor<? super REQUEST, ? super RESPONSE>>
      attributesExtractors;
  private final List<? extends ContextCustomizer<? super REQUEST>> contextCustomizers;
  private final List<? extends OperationListener> operationListeners;
  private final ErrorCauseExtractor errorCauseExtractor;
  private final boolean enabled;
  private final SpanSuppressor spanSuppressor;

  Instrumenter(InstrumenterBuilder<REQUEST, RESPONSE> builder) {
    this.instrumentationName = builder.instrumentationName;
    this.tracer = builder.buildTracer();
    this.spanNameExtractor = builder.spanNameExtractor;
    this.spanKindExtractor = builder.spanKindExtractor;
    this.spanStatusExtractor = builder.spanStatusExtractor;
    this.spanLinksExtractors = new ArrayList<>(builder.spanLinksExtractors);
    this.attributesExtractors = new ArrayList<>(builder.attributesExtractors);
    this.contextCustomizers = new ArrayList<>(builder.contextCustomizers);
    this.operationListeners = builder.buildOperationListeners();
    this.errorCauseExtractor = builder.errorCauseExtractor;
    this.enabled = builder.enabled;
    this.spanSuppressor = builder.buildSpanSuppressor();
  }

  /**
   * Determines whether the operation should be instrumented for telemetry or not. If the return
   * value is {@code true}, call {@link #start(Context, Object)} and {@link #end(Context, Object,
   * Object, Throwable)} around the instrumented operation; if the return value is false {@code
   * false} execute the operation directly without calling those methods.
   *
   * <p>The {@code parentContext} is the parent of the resulting instrumented operation and should
   * usually be {@link Context#current() Context.current()}. The {@code request} is the request
   * object of this operation.
   */
  public boolean shouldStart(Context parentContext, REQUEST request) {
    if (!enabled) {
      return false;
    }
    // 默认是SpanKind.INTERNAL 在build时被设置为SpanKind.CLIENT
    SpanKind spanKind = spanKindExtractor.extract(request);
    // 这里实际调用的是DelegateBySpanKind的shouldSuppress方法，即spanSuppressor是DelegateBySpanKind
    boolean suppressed = spanSuppressor.shouldSuppress(parentContext, spanKind);
    // suppressed从逻辑上看默认是false，即不抑制
    if (suppressed) {
      supportability.recordSuppressedSpan(spanKind, instrumentationName);
    }
    return !suppressed;  // 这里默认一般返回ture
  }

  /**
   * Starts a new instrumented operation. The returned {@link Context} should be propagated along
   * with the operation and passed to the {@link #end(Context, Object, Object, Throwable)} method
   * when it is finished.
   *
   * <p>The {@code parentContext} is the parent of the resulting instrumented operation and should
   * usually be {@link Context#current() Context.current()}. The {@code request} is the request
   * object of this operation.
   */
  public Context start(Context parentContext, REQUEST request) {
    return doStart(parentContext, request, null);
  }

  /**
   * Ends an instrumented operation. It is of extreme importance for this method to be always called
   * after {@link #start(Context, Object) start()}. Calling {@code start()} without later {@code
   * end()} will result in inaccurate or wrong telemetry and context leaks.
   *
   * <p>The {@code context} must be the same value that was returned from {@link #start(Context,
   * Object)}. The {@code request} parameter is the request object of the operation, {@code
   * response} is the response object of the operation, and {@code error} is an exception that was
   * thrown by the operation or {@code null} if no error occurred.
   */
  public void end(Context context, REQUEST request, @Nullable RESPONSE response, @Nullable Throwable error) {
    doEnd(context, request, response, error, null);
  }

  /** Internal method for creating spans with given start/end timestamps. */
  Context startAndEnd(Context parentContext,
      REQUEST request,
      @Nullable RESPONSE response,
      @Nullable Throwable error,
      Instant startTime,
      Instant endTime) {
    Context context = doStart(parentContext, request, startTime);
    doEnd(context, request, response, error, endTime);
    return context;
  }

  private Context doStart(Context parentContext, REQUEST request, @Nullable Instant startTime) {
    /*
     * 默认是SpanKind.INTERNAL 在build时被设置为SpanKind.CLIENT，其实就是标识Span的类型
     *  - server 用于服务器操作，例如 HTTP 服务器处理程序。
     *  - client 用于客户端操作，例如 HTTP 客户端请求。
     *  - producer 对于消息生产者，例如 Kafka producer。
     *  - consumer 一般用于消息消费者和异步处理，例如 Kafka consumer。
     *  - internal 用于内部操作。
     */
    SpanKind spanKind = spanKindExtractor.extract(request);
    // spanNameExtractor是通过DbClientSpanNameExtractor.create(RedissonDbAttributesGetter)生成的GenericDbClientSpanNameExtractor
    // tracer默认为SdkTracer，通过spanBuilder生成具体名称的SpanBuilder
    // 设置SpanKind.CLIENT到SpanBuilder
    SpanBuilder spanBuilder = tracer.spanBuilder(spanNameExtractor.extract(request)).setSpanKind(spanKind);

    if (startTime != null) {
      spanBuilder.setStartTimestamp(startTime);
    }
    // jedis对应的spanLinksExtractors为空
    SpanLinksBuilder spanLinksBuilder = new SpanLinksBuilderImpl(spanBuilder);
    for (SpanLinksExtractor<? super REQUEST> spanLinksExtractor : spanLinksExtractors) {
      spanLinksExtractor.extract(spanLinksBuilder, parentContext, request);
    }

    /*
     * 通过调用DbClientAttributesExtractor从而调用JedisDbAttributesGetter中的方法获取statement和operation相关的属性
     * 通过调用ServerAttributesExtractor从而调用JedisNetworkAttributesGetter中的方法获取属性
     *
     * 这里是真正调用设置进来的JedisDbAttributesGetter和JedisNetworkAttributesGetter的onStart方法
     * JedisDbAttributesGetter的作用是将以下两个属性设置到UnsafeAttributes中
     *    - db.statement:
     *    - db.operation:
     *
     * JedisNetworkAttributesGetter的作用是将以下两个属性设置到UnsafeAttributes中
     *    - server.address
     *    - server.port
     */
    UnsafeAttributes attributes = new UnsafeAttributes();
    for (AttributesExtractor<? super REQUEST, ? super RESPONSE> extractor : attributesExtractors) {
      extractor.onStart(attributes, parentContext, request);
    }

    Context context = parentContext;

    // context customizers run before span start, so that they can have access to the parent span
    // context, and so that their additions to the context will be visible to span processors
    /*
     * contextCustomizers是在span start之前执行，因此可以访问span的父context，且contextCustomizers添加的属性对span可见
     */
    for (ContextCustomizer<? super REQUEST> contextCustomizer : contextCustomizers) {
      context = contextCustomizer.onStart(context, request, attributes);
    }
    // 这里判断context为空或父Span是一个远程Span，则localRoot返回true
    boolean localRoot = LocalRootSpan.isLocalRoot(context);

    spanBuilder.setAllAttributes(attributes);
    // 通过spanBuilder的startSpan方法生SdkSpan对象，这里虽然设置了父context,但是设置到Span中被处理了只剩下了SpanContext
    Span span = spanBuilder.setParent(context).startSpan();
    // 这里是创建了一个新的Context并将span设置到Context中，其实这里是将span或更新到context
    context = context.with(span);

    if (!operationListeners.isEmpty()) {
      // operation listeners run after span start, so that they have access to the current span
      // for capturing exemplars
      long startNanos = getNanos(startTime);
      // 这里是对Metrics进行处理
      for (OperationListener operationListener : operationListeners) {
        context = operationListener.onStart(context, attributes, startNanos);
      }
    }

    if (localRoot) {  // 这里也是调用context.with(span)
      context = LocalRootSpan.store(context, span);
    }
    // 这里其实针对不同的组件可能自定义了SpanKey，这里将span设置到对应的SpanKey中
    // 如果是
    return spanSuppressor.storeInContext(context, spanKind, span);
  }

  private void doEnd(
      Context context,
      REQUEST request,
      @Nullable RESPONSE response,
      @Nullable Throwable error,
      @Nullable Instant endTime) {
    // 从Context中获取startSpan生产的span
    Span span = Span.fromContext(context);

    if (error != null) {
      // 这里是对方法执行异常提取异常堆栈信息，并通过recordException生成Event添加到Span中
      // errorCauseExtractor默认为DefaultErrorCauseExtractor
      error = errorCauseExtractor.extract(error);
      span.recordException(error);
    }

    UnsafeAttributes attributes = new UnsafeAttributes();
    for (AttributesExtractor<? super REQUEST, ? super RESPONSE> extractor : attributesExtractors) {
      extractor.onEnd(attributes, context, request, response, error);
    }
    span.setAllAttributes(attributes);

    if (!operationListeners.isEmpty()) {
      long endNanos = getNanos(endTime);
      ListIterator<? extends OperationListener> i = operationListeners.listIterator(operationListeners.size());
      while (i.hasPrevious()) {
        i.previous().onEnd(context, attributes, endNanos);
      }
    }

    SpanStatusBuilder spanStatusBuilder = new SpanStatusBuilderImpl(span);
    spanStatusExtractor.extract(spanStatusBuilder, request, response, error);

    // 这里调用span.end，从而调用SdkSpan的end方法，最终调用SpanProcessor的onEnd方法
    // 最终将span添加到Worker的阻塞对列中
    if (endTime != null) {
      span.end(endTime);
    } else {
      span.end();
    }
  }

  private static long getNanos(@Nullable Instant time) {
    if (time == null) {
      return System.nanoTime();
    }
    return TimeUnit.SECONDS.toNanos(time.getEpochSecond()) + time.getNano();
  }

  static {
    InstrumenterUtil.setInstrumenterAccess(new InstrumenterAccess() {
          @Override
          public <RQ, RS> Context startAndEnd(
              Instrumenter<RQ, RS> instrumenter,
              Context parentContext,
              RQ request,
              @Nullable RS response,
              @Nullable Throwable error,
              Instant startTime,
              Instant endTime) {
            return instrumenter.startAndEnd(parentContext, request, response, error, startTime, endTime);
          }
        });
  }
}
