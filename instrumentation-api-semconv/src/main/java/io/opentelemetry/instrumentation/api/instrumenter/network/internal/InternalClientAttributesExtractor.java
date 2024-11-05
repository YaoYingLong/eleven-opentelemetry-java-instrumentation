/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.instrumentation.api.instrumenter.network.internal;

import static io.opentelemetry.instrumentation.api.internal.AttributesExtractorUtil.internalSet;

import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.instrumentation.api.instrumenter.network.ClientAttributesGetter;
import io.opentelemetry.semconv.SemanticAttributes;
import javax.annotation.Nullable;

/**
 * This class is internal and is hence not for public use. Its APIs are unstable and can change at
 * any time.
 */
public final class InternalClientAttributesExtractor<REQUEST, RESPONSE> {

  private final ClientAttributesGetter<REQUEST, RESPONSE> getter;
  private final FallbackAddressPortExtractor<REQUEST> fallbackAddressPortExtractor;
  private final boolean emitStableUrlAttributes;
  private final boolean emitOldHttpAttributes;

  public InternalClientAttributesExtractor(ClientAttributesGetter<REQUEST, RESPONSE> getter,
      FallbackAddressPortExtractor<REQUEST> fallbackAddressPortExtractor,
      boolean emitStableUrlAttributes, boolean emitOldHttpAttributes) {
    // 这里传入的ClientAttributesGetter一般是ServletHttpAttributesGetter
    this.getter = getter;
    // 传入的ClientAddressAndPortExtractor
    this.fallbackAddressPortExtractor = fallbackAddressPortExtractor;
    this.emitStableUrlAttributes = emitStableUrlAttributes;
    this.emitOldHttpAttributes = emitOldHttpAttributes;
  }

  @SuppressWarnings("deprecation") // until old http semconv are dropped in 2.0
  public void onStart(AttributesBuilder attributes, REQUEST request) {
    // 从Header中获取forwarded或x-forwarded-for中对应的地址和端口
    AddressAndPort clientAddressAndPort = extractClientAddressAndPort(request);
    // emitStableUrlAttributes默认为false
    if (emitStableUrlAttributes) {
      internalSet(attributes, SemanticAttributes.CLIENT_ADDRESS, clientAddressAndPort.address);
      if (clientAddressAndPort.port != null && clientAddressAndPort.port > 0) {
        internalSet(attributes, SemanticAttributes.CLIENT_PORT, (long) clientAddressAndPort.port);
      }
    }
    // emitOldHttpAttributes默认为true
    if (emitOldHttpAttributes) {
      // 设置http.client_ip属性
      internalSet(attributes, SemanticAttributes.HTTP_CLIENT_IP, clientAddressAndPort.address);
    }
  }

  @SuppressWarnings("deprecation") // until old http semconv are dropped in 2.0
  public void onEnd(AttributesBuilder attributes, REQUEST request, @Nullable RESPONSE response) {
    AddressAndPort clientAddressAndPort = extractClientAddressAndPort(request);
    // 调用HttpServletRequest的getRemoteAddr方法，获取发出请求的客户端的IP地址
    String clientSocketAddress = getter.getClientSocketAddress(request, response);
    // 调用HttpServletRequest的getRemotePort方法，获取发出请求的客户端的端口
    Integer clientSocketPort = getter.getClientSocketPort(request, response);

    // 如果getRemoteAddr获取到数据，且与从forwarded或x-forwarded-for中获取到的不相等
    if (clientSocketAddress != null && !clientSocketAddress.equals(clientAddressAndPort.address)) {
      // emitStableUrlAttributes默认为false
      if (emitStableUrlAttributes) {
        internalSet(attributes, SemanticAttributes.CLIENT_SOCKET_ADDRESS, clientSocketAddress);
      }
      // emitOldHttpAttributes默认为true
      if (emitOldHttpAttributes) {
        // 设置net.sock.peer.addr属性
        internalSet(attributes, SemanticAttributes.NET_SOCK_PEER_ADDR, clientSocketAddress);
      }
    }
    if (clientSocketPort != null && clientSocketPort > 0) {
      // emitStableUrlAttributes默认为false
      if (emitStableUrlAttributes) {
        if (!clientSocketPort.equals(clientAddressAndPort.port)) {
          internalSet(attributes, SemanticAttributes.CLIENT_SOCKET_PORT, (long) clientSocketPort);
        }
      }
      // emitOldHttpAttributes默认为true
      if (emitOldHttpAttributes) {
        // 设置net.sock.peer.port属性
        internalSet(attributes, SemanticAttributes.NET_SOCK_PEER_PORT, (long) clientSocketPort);
      }
    }
  }

  private AddressAndPort extractClientAddressAndPort(REQUEST request) {
    AddressAndPort addressAndPort = new AddressAndPort();
    // 如果传入的ClientAttributesGetter为ServletHttpAttributesGetter，但是未实现getClientAddress
    addressAndPort.address = getter.getClientAddress(request);
    addressAndPort.port = getter.getClientPort(request);
    // 若ClientAttributesGetter为ServletHttpAttributesGetter，这里address和port为空
    if (addressAndPort.address == null && addressAndPort.port == null) {
      // 调用ClientAddressAndPortExtractor的extract，从Header中获取forwarded或x-forwarded-for中对应的地址和端口
      fallbackAddressPortExtractor.extract(addressAndPort, request);
    }
    return addressAndPort;
  }
}
