/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.javaagent.instrumentation.lettuce.v4_0;

import com.lambdaworks.redis.RedisURI;
import com.lambdaworks.redis.protocol.RedisCommand;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.ContextKey;
import io.opentelemetry.instrumentation.api.instrumenter.Instrumenter;
import io.opentelemetry.instrumentation.api.instrumenter.SpanKindExtractor;
import io.opentelemetry.instrumentation.api.instrumenter.db.DbClientAttributesExtractor;
import io.opentelemetry.instrumentation.api.instrumenter.db.DbClientSpanNameExtractor;
import io.opentelemetry.instrumentation.api.instrumenter.net.PeerServiceAttributesExtractor;
import io.opentelemetry.instrumentation.api.instrumenter.network.ServerAttributesExtractor;
import io.opentelemetry.javaagent.bootstrap.internal.CommonConfig;

public final class LettuceSingletons {
  private static final String INSTRUMENTATION_NAME = "io.opentelemetry.lettuce-4.0";

  private static final Instrumenter<RedisCommand<?, ?, ?>, Void> INSTRUMENTER;
  private static final Instrumenter<RedisURI, Void> CONNECT_INSTRUMENTER;

  public static final ContextKey<Context> COMMAND_CONTEXT_KEY =
      ContextKey.named("opentelemetry-lettuce-v4_0-context-key");

  static {
    LettuceDbAttributesGetter dbAttributesGetter = new LettuceDbAttributesGetter();

    INSTRUMENTER = Instrumenter.<RedisCommand<?, ?, ?>, Void>builder(GlobalOpenTelemetry.get(),
                INSTRUMENTATION_NAME,
                DbClientSpanNameExtractor.create(dbAttributesGetter))
            .addAttributesExtractor(DbClientAttributesExtractor.create(dbAttributesGetter))
            .buildInstrumenter(SpanKindExtractor.alwaysClient());

    LettuceConnectNetworkAttributesGetter netAttributesGetter = new LettuceConnectNetworkAttributesGetter();

    CONNECT_INSTRUMENTER = Instrumenter.<RedisURI, Void>builder(GlobalOpenTelemetry.get(), INSTRUMENTATION_NAME, redisUri -> "CONNECT")
            .addAttributesExtractor(ServerAttributesExtractor.create(netAttributesGetter))
            .addAttributesExtractor(PeerServiceAttributesExtractor.create(
                    netAttributesGetter, CommonConfig.get().getPeerServiceResolver()))
            .addAttributesExtractor(new LettuceConnectAttributesExtractor())
            .buildInstrumenter(SpanKindExtractor.alwaysClient());
  }

  public static Instrumenter<RedisCommand<?, ?, ?>, Void> instrumenter() {
    return INSTRUMENTER;
  }

  public static Instrumenter<RedisURI, Void> connectInstrumenter() {
    return CONNECT_INSTRUMENTER;
  }

  private LettuceSingletons() {}
}
