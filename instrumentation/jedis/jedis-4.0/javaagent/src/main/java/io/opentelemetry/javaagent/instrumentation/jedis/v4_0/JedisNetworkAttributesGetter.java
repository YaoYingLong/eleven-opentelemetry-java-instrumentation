/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.javaagent.instrumentation.jedis.v4_0;

import io.opentelemetry.instrumentation.api.instrumenter.network.ServerAttributesGetter;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import javax.annotation.Nullable;

/**
 * 这里定义的方法是在ServerAttributesExtractor中被调用
 */
final class JedisNetworkAttributesGetter implements ServerAttributesGetter<JedisRequest, Void> {

  @Override
  @Nullable
  public InetSocketAddress getServerInetSocketAddress(
      JedisRequest jedisRequest, @Nullable Void unused) {
    SocketAddress socketAddress = jedisRequest.getRemoteSocketAddress();
    if (socketAddress instanceof InetSocketAddress) {
      return (InetSocketAddress) socketAddress;
    }
    return null;
  }
}
