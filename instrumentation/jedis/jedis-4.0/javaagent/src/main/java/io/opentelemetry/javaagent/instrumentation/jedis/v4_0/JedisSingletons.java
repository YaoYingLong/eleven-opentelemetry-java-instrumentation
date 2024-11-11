/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.javaagent.instrumentation.jedis.v4_0;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.instrumentation.api.instrumenter.Instrumenter;
import io.opentelemetry.instrumentation.api.instrumenter.SpanKindExtractor;
import io.opentelemetry.instrumentation.api.instrumenter.db.DbClientAttributesExtractor;
import io.opentelemetry.instrumentation.api.instrumenter.db.DbClientSpanNameExtractor;
import io.opentelemetry.instrumentation.api.instrumenter.net.PeerServiceAttributesExtractor;
import io.opentelemetry.instrumentation.api.instrumenter.network.ServerAttributesExtractor;
import io.opentelemetry.javaagent.bootstrap.internal.CommonConfig;

public final class JedisSingletons {
  private static final String INSTRUMENTATION_NAME = "io.opentelemetry.jedis-4.0";

  private static final Instrumenter<JedisRequest, Void> INSTRUMENTER;

  static {
    JedisDbAttributesGetter dbAttributesGetter = new JedisDbAttributesGetter();
    JedisNetworkAttributesGetter netAttributesGetter = new JedisNetworkAttributesGetter();

    INSTRUMENTER = Instrumenter.<JedisRequest, Void>builder(GlobalOpenTelemetry.get(),
                INSTRUMENTATION_NAME,
                DbClientSpanNameExtractor.create(dbAttributesGetter))
            // 构建DbClientAttributesExtractor，用于提取db.statement和db.operation属性
            .addAttributesExtractor(DbClientAttributesExtractor.create(dbAttributesGetter))
            // 构建ServerAttributesExtractor，用于提取Mode.PEER相关的属性
            .addAttributesExtractor(ServerAttributesExtractor.create(netAttributesGetter))
            // 构建PeerServiceAttributesExtractor，用于提取peer.service属性，大概率是提取不到的
            .addAttributesExtractor(PeerServiceAttributesExtractor.create(
                    netAttributesGetter, CommonConfig.get().getPeerServiceResolver()))
            .buildInstrumenter(SpanKindExtractor.alwaysClient());
  }

  public static Instrumenter<JedisRequest, Void> instrumenter() {
    return INSTRUMENTER;
  }

  private JedisSingletons() {}
}
