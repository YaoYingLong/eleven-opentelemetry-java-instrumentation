/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.javaagent.instrumentation.redisson;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.instrumentation.api.instrumenter.Instrumenter;
import io.opentelemetry.instrumentation.api.instrumenter.SpanKindExtractor;
import io.opentelemetry.instrumentation.api.instrumenter.db.DbClientAttributesExtractor;
import io.opentelemetry.instrumentation.api.instrumenter.db.DbClientSpanNameExtractor;
import io.opentelemetry.instrumentation.api.instrumenter.network.ServerAttributesExtractor;

public final class RedissonInstrumenterFactory {

  public static Instrumenter<RedissonRequest, Void> createInstrumenter(String instrumentationName) {
    RedissonDbAttributesGetter dbAttributesGetter = new RedissonDbAttributesGetter();
    RedissonNetAttributesGetter netAttributesGetter = new RedissonNetAttributesGetter();

    return Instrumenter.<RedissonRequest, Void>builder(
            GlobalOpenTelemetry.get(),
            // 模块名称，如当前：io.opentelemetry.redisson-3.17
            instrumentationName,
            // 构建SpanNameExtractor，用于生成Span名称，GenericDbClientSpanNameExtractor
            DbClientSpanNameExtractor.create(dbAttributesGetter))
        .addAttributesExtractor(DbClientAttributesExtractor.create(dbAttributesGetter))
        .addAttributesExtractor(ServerAttributesExtractor.create(netAttributesGetter))
        // 默认SpanKind.CLIENT
        .buildInstrumenter(SpanKindExtractor.alwaysClient());
  }

  private RedissonInstrumenterFactory() {}
}
