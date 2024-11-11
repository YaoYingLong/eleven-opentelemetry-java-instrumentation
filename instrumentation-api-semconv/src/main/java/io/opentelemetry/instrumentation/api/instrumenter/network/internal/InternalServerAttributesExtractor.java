/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.instrumentation.api.instrumenter.network.internal;

import static io.opentelemetry.instrumentation.api.internal.AttributesExtractorUtil.internalSet;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.instrumentation.api.instrumenter.network.ServerAttributesGetter;
import io.opentelemetry.semconv.SemanticAttributes;
import java.util.function.BiPredicate;
import javax.annotation.Nullable;

/**
 * This class is internal and is hence not for public use. Its APIs are unstable and can change at
 * any time.
 */
public final class InternalServerAttributesExtractor<REQUEST, RESPONSE> {

  private final ServerAttributesGetter<REQUEST, RESPONSE> getter;
  private final BiPredicate<Integer, REQUEST> captureServerPortCondition;
  private final FallbackAddressPortExtractor<REQUEST> fallbackAddressPortExtractor;
  private final boolean emitStableUrlAttributes;
  private final boolean emitOldHttpAttributes;
  private final Mode oldSemconvMode;
  private final boolean captureServerSocketAttributes;

  public InternalServerAttributesExtractor(ServerAttributesGetter<REQUEST, RESPONSE> getter,
      BiPredicate<Integer, REQUEST> captureServerPortCondition,
      FallbackAddressPortExtractor<REQUEST> fallbackAddressPortExtractor,
      boolean emitStableUrlAttributes,
      boolean emitOldHttpAttributes,
      // 若是lettuce、jedis过来的，则mode为InternalServerAttributesExtractor.Mode.PEER
      Mode oldSemconvMode,
      boolean captureServerSocketAttributes) {
    this.getter = getter;
    this.captureServerPortCondition = captureServerPortCondition;
    // Tomcat中fallbackAddressPortExtractor一般传入的是HttpAddressPortExtractor
    // ServerAttributesExtractor中构建时传入的FallbackAddressPortExtractor.noop()
    this.fallbackAddressPortExtractor = fallbackAddressPortExtractor;
    // emitStableUrlAttributes默认为false
    this.emitStableUrlAttributes = emitStableUrlAttributes;
    // emitOldHttpAttributes默认为true
    this.emitOldHttpAttributes = emitOldHttpAttributes;
    // 若是lettuce过来的，则mode为InternalServerAttributesExtractor.Mode.PEER
    this.oldSemconvMode = oldSemconvMode;
    this.captureServerSocketAttributes = captureServerSocketAttributes;
  }

  /**
   * 提取net.peer.name和net.peer.port属性：
   * 如果对应组件的具体的ServerAttributesGetter中未实现getServerAddress方法且fallbackAddressPortExtractor
   * 为FallbackAddressPortExtractor.noop()则最终不会提取出net.peer.name和net.peer.port属性
   */
  public void onStart(AttributesBuilder attributes, REQUEST request) {
    AddressAndPort serverAddressAndPort = extractServerAddressAndPort(request);

    // emitStableUrlAttributes默认为false
    if (emitStableUrlAttributes) {
      internalSet(attributes, SemanticAttributes.SERVER_ADDRESS, serverAddressAndPort.address);
    }
    // emitOldHttpAttributes默认为true
    if (emitOldHttpAttributes) {
      // 这里的oldSemconvMode默认为Mode.HOST或Mode.PEER，address为net.host.name或net.peer.name
      internalSet(attributes, oldSemconvMode.address, serverAddressAndPort.address);
    }

    if (serverAddressAndPort.port != null && serverAddressAndPort.port > 0
        && captureServerPortCondition.test(serverAddressAndPort.port, request)) {
      // emitStableUrlAttributes默认为false
      if (emitStableUrlAttributes) {
        internalSet(attributes, SemanticAttributes.SERVER_PORT, (long) serverAddressAndPort.port);
      }
      // emitOldHttpAttributes默认为true
      if (emitOldHttpAttributes) {
        // 这里的oldSemconvMode默认为Mode.HOST或Mode.PEER，port为net.host.port或net.peer.port
        internalSet(attributes, oldSemconvMode.port, (long) serverAddressAndPort.port);
      }
    }
  }

  /**
   * 提取net.sock.peer.name、net.sock.peer.addr.sock.peer.port属性
   * 若对应组件的ServerAttributesGetter实现了getServerSocketAddress
   */
  public void onEnd(AttributesBuilder attributes, REQUEST request, @Nullable RESPONSE response) {
    AddressAndPort serverAddressAndPort = extractServerAddressAndPort(request);
    // 这里区分不同的组件，若调用HttpServletRequest的getLocalAddr方法，获取当前处理请求的服务器接口绑定的IP地址
    String serverSocketAddress = getter.getServerSocketAddress(request, response);
    // 如果getLocalAddr获取到的地址不为空，且与getServerAddress获取到的地址不相等
    if (serverSocketAddress != null && !serverSocketAddress.equals(serverAddressAndPort.address)) {
      // emitStableUrlAttributes默认为false, emitOldHttpAttributes默认为true
      if (emitStableUrlAttributes && captureServerSocketAttributes) {
        internalSet(attributes, SemanticAttributes.SERVER_SOCKET_ADDRESS, serverSocketAddress);
      }
      // emitOldHttpAttributes默认为true
      if (emitOldHttpAttributes) {
        // 这里的oldSemconvMode默认为Mode.HOST或Mode.PEER，socketAddress为net.sock.host.addr或net.sock.peer.addr
        internalSet(attributes, oldSemconvMode.socketAddress, serverSocketAddress);
      }
    }

    // 调用HttpServletRequest的getLocalPort方法
    Integer serverSocketPort = getter.getServerSocketPort(request, response);
    if (serverSocketPort != null && serverSocketPort > 0 && !serverSocketPort.equals(serverAddressAndPort.port)) {
      // emitStableUrlAttributes默认为false, emitOldHttpAttributes默认为true
      if (emitStableUrlAttributes && captureServerSocketAttributes) {
        internalSet(attributes, SemanticAttributes.SERVER_SOCKET_PORT, (long) serverSocketPort);
      }
      // emitOldHttpAttributes默认为true
      if (emitOldHttpAttributes) {
        // 这里的oldSemconvMode默认为Mode.HOST或Mode.PEER，socketPort为net.sock.host.port或net.sock.peer.port
        internalSet(attributes, oldSemconvMode.socketPort, (long) serverSocketPort);
      }
    }

    String serverSocketDomain = getter.getServerSocketDomain(request, response);
    if (serverSocketDomain != null && !serverSocketDomain.equals(serverAddressAndPort.address)) {
      // emitStableUrlAttributes默认为false, emitOldHttpAttributes默认为true
      if (emitStableUrlAttributes && captureServerSocketAttributes) {
        internalSet(attributes, SemanticAttributes.SERVER_SOCKET_DOMAIN, serverSocketDomain);
      }
      // emitOldHttpAttributes默认为true，如果Mode.HOST则socketDomain为空
      if (emitOldHttpAttributes && oldSemconvMode.socketDomain != null) {
        internalSet(attributes, oldSemconvMode.socketDomain, serverSocketDomain);
      }
    }
  }

  private AddressAndPort extractServerAddressAndPort(REQUEST request) {
    AddressAndPort addressAndPort = new AddressAndPort();
    // 调用ServletHttpAttributesGetter的getServerAddress，最终调用HttpServletRequest.getServerName
    // 客户端请求时所使用的服务器域名或IP地址
    addressAndPort.address = getter.getServerAddress(request);
    // 最终调用HttpServletRequest.getServerPort
    addressAndPort.port = getter.getServerPort(request);
    if (addressAndPort.address == null && addressAndPort.port == null) {
      // 调用HttpAddressPortExtractor的extract方法，从Header中获取host
      fallbackAddressPortExtractor.extract(addressAndPort, request);
    }
    return addressAndPort;
  }

  /**
   * This class is internal and is hence not for public use. Its APIs are unstable and can change at
   * any time.
   */
  @SuppressWarnings({
    "ImmutableEnumChecker",
    "deprecation"
  }) // until old http semconv are dropped in 2.0
  public enum Mode {
    PEER(
        SemanticAttributes.NET_PEER_NAME,
        SemanticAttributes.NET_PEER_PORT,
        SemanticAttributes.NET_SOCK_PEER_NAME,
        SemanticAttributes.NET_SOCK_PEER_ADDR,
        SemanticAttributes.NET_SOCK_PEER_PORT),
    HOST(
        SemanticAttributes.NET_HOST_NAME,
        SemanticAttributes.NET_HOST_PORT,
        // the old semconv does not have an attribute for this
        null,
        SemanticAttributes.NET_SOCK_HOST_ADDR,
        SemanticAttributes.NET_SOCK_HOST_PORT);

    final AttributeKey<String> address;
    final AttributeKey<Long> port;
    @Nullable final AttributeKey<String> socketDomain;
    final AttributeKey<String> socketAddress;
    final AttributeKey<Long> socketPort;

    Mode(
        AttributeKey<String> address,
        AttributeKey<Long> port,
        AttributeKey<String> socketDomain,
        AttributeKey<String> socketAddress,
        AttributeKey<Long> socketPort) {
      this.address = address;
      this.port = port;
      this.socketDomain = socketDomain;
      this.socketAddress = socketAddress;
      this.socketPort = socketPort;
    }
  }
}
