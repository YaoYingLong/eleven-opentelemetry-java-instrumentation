/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.javaagent.instrumentation.jedis.v4_0;

import io.opentelemetry.instrumentation.api.instrumenter.db.DbClientAttributesGetter;
import io.opentelemetry.semconv.SemanticAttributes;
import javax.annotation.Nullable;


/**
 * 这里定义的方法是在DbClientCommonAttributesExtractor的onStart方法中被调用从而设置Attributes
 */
final class JedisDbAttributesGetter implements DbClientAttributesGetter<JedisRequest> {

  @Override
  public String getSystem(JedisRequest request) {
    return SemanticAttributes.DbSystemValues.REDIS;
  }

  @Override
  @Nullable
  public String getUser(JedisRequest request) {
    return null;
  }

  @Override
  public String getName(JedisRequest request) {
    return null;
  }

  @Override
  public String getConnectionString(JedisRequest request) {
    return null;
  }

  @Override
  public String getStatement(JedisRequest request) {
    return request.getStatement();
  }

  @Override
  public String getOperation(JedisRequest request) {
    return request.getOperation();
  }
}
