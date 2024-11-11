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

    return Instrumenter.<RedissonRequest, Void>builder(GlobalOpenTelemetry.get(),
            // 模块名称，如当前：io.opentelemetry.redisson-3.17
            instrumentationName,
            // 若operation和dbName都为空则返回"DB Query"作为SpanName
            // 若operation为空，dbName不为空则返回dbName作为SpanName
            // 若dbName为空，operation不为空则返回operation作为SpanName
            // 若operation和dbName都不为空，则SpanName为"operation dbName"
            DbClientSpanNameExtractor.create(dbAttributesGetter))
        // 提取db.statement和db.operation属性
        .addAttributesExtractor(DbClientAttributesExtractor.create(dbAttributesGetter))
        // 提取Mode.PEER中的属性
        .addAttributesExtractor(ServerAttributesExtractor.create(netAttributesGetter))
        // 将允许的父Span类型即SpanKind设置为SpanKind.CLIENT
        .buildInstrumenter(SpanKindExtractor.alwaysClient());
  }

  private RedissonInstrumenterFactory() {}
}
