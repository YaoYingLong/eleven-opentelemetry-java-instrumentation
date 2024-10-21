/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.instrumentation.netty.v4.common.internal.client;

import io.netty.channel.Channel;
import io.netty.handler.codec.http.HttpResponse;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.instrumentation.api.instrumenter.AttributesExtractor;
import io.opentelemetry.instrumentation.api.instrumenter.Instrumenter;
import io.opentelemetry.instrumentation.api.instrumenter.InstrumenterBuilder;
import io.opentelemetry.instrumentation.api.instrumenter.SpanKindExtractor;
import io.opentelemetry.instrumentation.api.instrumenter.http.HttpClientAttributesExtractor;
import io.opentelemetry.instrumentation.api.instrumenter.http.HttpClientAttributesExtractorBuilder;
import io.opentelemetry.instrumentation.api.instrumenter.http.HttpClientExperimentalMetrics;
import io.opentelemetry.instrumentation.api.instrumenter.http.HttpClientMetrics;
import io.opentelemetry.instrumentation.api.instrumenter.http.HttpClientPeerServiceAttributesExtractor;
import io.opentelemetry.instrumentation.api.instrumenter.http.HttpSpanNameExtractor;
import io.opentelemetry.instrumentation.api.instrumenter.http.HttpSpanNameExtractorBuilder;
import io.opentelemetry.instrumentation.api.instrumenter.http.HttpSpanStatusExtractor;
import io.opentelemetry.instrumentation.api.instrumenter.net.PeerServiceAttributesExtractor;
import io.opentelemetry.instrumentation.api.instrumenter.net.PeerServiceResolver;
import io.opentelemetry.instrumentation.netty.common.internal.NettyConnectionRequest;
import io.opentelemetry.instrumentation.netty.v4.common.HttpRequestAndChannel;
import java.util.List;
import java.util.function.Consumer;

/**
 * This class is internal and is hence not for public use. Its APIs are unstable and can change at
 * any time.
 */
public final class NettyClientInstrumenterFactory {

  private final OpenTelemetry openTelemetry;
  private final String instrumentationName;
  private final NettyConnectionInstrumentationFlag connectionTelemetryState;
  private final NettyConnectionInstrumentationFlag sslTelemetryState;
  private final PeerServiceResolver peerServiceResolver;
  private final boolean emitExperimentalHttpClientMetrics;

  public NettyClientInstrumenterFactory(
      OpenTelemetry openTelemetry,
      String instrumentationName,
      NettyConnectionInstrumentationFlag connectionTelemetryState,
      NettyConnectionInstrumentationFlag sslTelemetryState,
      PeerServiceResolver peerServiceResolver,
      boolean emitExperimentalHttpClientMetrics) {
    this.openTelemetry = openTelemetry;
    this.instrumentationName = instrumentationName;
    this.connectionTelemetryState = connectionTelemetryState;
    this.sslTelemetryState = sslTelemetryState;
    this.peerServiceResolver = peerServiceResolver;
    this.emitExperimentalHttpClientMetrics = emitExperimentalHttpClientMetrics;
  }

  public Instrumenter<HttpRequestAndChannel, HttpResponse> createHttpInstrumenter(
      Consumer<HttpClientAttributesExtractorBuilder<HttpRequestAndChannel, HttpResponse>> extractorConfigurer,
      Consumer<HttpSpanNameExtractorBuilder<HttpRequestAndChannel>> spanNameExtractorConfigurer,
      List<AttributesExtractor<HttpRequestAndChannel, HttpResponse>> additionalHttpAttributeExtractors) {

    NettyHttpClientAttributesGetter httpAttributesGetter = new NettyHttpClientAttributesGetter();
    HttpClientAttributesExtractorBuilder<HttpRequestAndChannel, HttpResponse> extractorBuilder =
        HttpClientAttributesExtractor.builder(httpAttributesGetter);
    extractorConfigurer.accept(extractorBuilder);

    HttpSpanNameExtractorBuilder<HttpRequestAndChannel> httpSpanNameExtractorBuilder =
        HttpSpanNameExtractor.builder(httpAttributesGetter);
    spanNameExtractorConfigurer.accept(httpSpanNameExtractorBuilder);

    InstrumenterBuilder<HttpRequestAndChannel, HttpResponse> builder = Instrumenter.<HttpRequestAndChannel, HttpResponse>builder(
                openTelemetry, instrumentationName, httpSpanNameExtractorBuilder.build())
            .setSpanStatusExtractor(HttpSpanStatusExtractor.create(httpAttributesGetter))
            .addAttributesExtractor(extractorBuilder.build())
            .addAttributesExtractor(HttpClientPeerServiceAttributesExtractor.create(
                    httpAttributesGetter, peerServiceResolver))
            .addAttributesExtractors(additionalHttpAttributeExtractors)
            .addOperationMetrics(HttpClientMetrics.get());
    if (emitExperimentalHttpClientMetrics) {
      builder.addOperationMetrics(HttpClientExperimentalMetrics.get());
    }
    return builder.buildClientInstrumenter(HttpRequestHeadersSetter.INSTANCE);
  }

  @SuppressWarnings("deprecation") // have to use the deprecated Net*AttributesExtractor for now
  public NettyConnectionInstrumenter createConnectionInstrumenter() {
    // 如果允许创建连接的Span的导出状态是禁用，则直接返回NoopConnectionInstrumenter，其shouldStart默认返回false，end是空实现
    if (connectionTelemetryState == NettyConnectionInstrumentationFlag.DISABLED) {
      return NoopConnectionInstrumenter.INSTANCE;
    }

    // 如果是允许全部的创建连接的Span的导出，默认是仅出现错误的时候会有导出
    boolean connectionTelemetryFullyEnabled = connectionTelemetryState == NettyConnectionInstrumentationFlag.ENABLED;
    NettyConnectHttpAttributesGetter getter = NettyConnectHttpAttributesGetter.INSTANCE;

    InstrumenterBuilder<NettyConnectionRequest, Channel> builder =
        // NettyConnectionRequest.spanName获取到的内容要么是CONNECT，要么是RESOLVE
        Instrumenter.<NettyConnectionRequest, Channel>builder(openTelemetry, instrumentationName, NettyConnectionRequest::spanName)
            .addAttributesExtractor(HttpClientPeerServiceAttributesExtractor.create(getter, peerServiceResolver));

    // 如果是允许全部的创建连接的Span的导出
    if (connectionTelemetryFullyEnabled) {
      // when the connection telemetry is fully enabled, CONNECT spans are created for every
      // request; and semantically they're not HTTP spans, they must not use the HTTP client
      // extractor
      builder.addAttributesExtractor(io.opentelemetry.instrumentation.api.instrumenter.net.NetClientAttributesExtractor.create(getter));
    } else {
      // 允许仅创建连接发生异常的Span的导出
      // in case the connection telemetry is emitted only on errors, the CONNECT span is a stand-in
      // for the HTTP client span
      builder.addAttributesExtractor(HttpClientAttributesExtractor.create(getter));
    }

    // 如果是允许全部的创建连接的Span的导出，则SpindKind为INTERNAL，如果仅创建连接发生异常的Span的导出，则SpindKind为CLIENT
    Instrumenter<NettyConnectionRequest, Channel> instrumenter = builder.buildInstrumenter(
            connectionTelemetryFullyEnabled ? SpanKindExtractor.alwaysInternal() : SpanKindExtractor.alwaysClient());
    // 如果是允许全部的创建连接的Span的导出，则NettyConnectionInstrumenterImpl，如果仅创建连接发生异常的Span的导出，则NettyErrorOnlyConnectionInstrumenter
    // NettyConnectionInstrumenterImpl仅仅是对Instrumenter包了一层而已，其他的什么都是透传调用的逻辑其实与Instrumenter一模一样
    // NettyErrorOnlyConnectionInstrumenter的shouldStart默认返回true, start中记录了一下开始执行的时间，在end方法中才真正实现
    return connectionTelemetryFullyEnabled
        ? new NettyConnectionInstrumenterImpl(instrumenter)
        : new NettyErrorOnlyConnectionInstrumenter(instrumenter);
  }

  @SuppressWarnings("deprecation") // have to use the deprecated Net*AttributesExtractor for now
  public NettySslInstrumenter createSslInstrumenter() {
    if (sslTelemetryState == NettyConnectionInstrumentationFlag.DISABLED) {
      return NoopSslInstrumenter.INSTANCE;
    }

    boolean sslTelemetryFullyEnabled =
        sslTelemetryState == NettyConnectionInstrumentationFlag.ENABLED;
    NettySslNetAttributesGetter netAttributesGetter = new NettySslNetAttributesGetter();
    Instrumenter<NettySslRequest, Void> instrumenter =
        Instrumenter.<NettySslRequest, Void>builder(
                openTelemetry, instrumentationName, NettySslRequest::spanName)
            .addAttributesExtractor(
                io.opentelemetry.instrumentation.api.instrumenter.net.NetClientAttributesExtractor
                    .create(netAttributesGetter))
            .addAttributesExtractor(
                PeerServiceAttributesExtractor.create(netAttributesGetter, peerServiceResolver))
            .buildInstrumenter(
                sslTelemetryFullyEnabled
                    ? SpanKindExtractor.alwaysInternal()
                    : SpanKindExtractor.alwaysClient());

    return sslTelemetryFullyEnabled
        ? new NettySslInstrumenterImpl(instrumenter)
        : new NettySslErrorOnlyInstrumenter(instrumenter);
  }
}
