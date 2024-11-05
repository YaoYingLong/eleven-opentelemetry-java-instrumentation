/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.javaagent.instrumentation.servlet;

import static io.opentelemetry.api.common.AttributeKey.longKey;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.context.Context;
import io.opentelemetry.instrumentation.api.instrumenter.AttributesExtractor;
import io.opentelemetry.javaagent.bootstrap.internal.InstrumentationConfig;
import io.opentelemetry.semconv.SemanticAttributes;
import java.security.Principal;
import javax.annotation.Nullable;

public class ServletAdditionalAttributesExtractor<REQUEST, RESPONSE>
    implements AttributesExtractor<
        ServletRequestContext<REQUEST>, ServletResponseContext<RESPONSE>> {

  // CAPTURE_EXPERIMENTAL_SPAN_ATTRIBUTES默认为false
  private static final boolean CAPTURE_EXPERIMENTAL_SPAN_ATTRIBUTES = InstrumentationConfig.get()
          .getBoolean("otel.instrumentation.servlet.experimental-span-attributes", false);
  private static final AttributeKey<Long> SERVLET_TIMEOUT = longKey("servlet.timeout");

  private final ServletAccessor<REQUEST, RESPONSE> accessor;

  public ServletAdditionalAttributesExtractor(ServletAccessor<REQUEST, RESPONSE> accessor) {
    this.accessor = accessor;
  }

  @Override
  public void onStart(
      AttributesBuilder attributes,
      Context parentContext,
      ServletRequestContext<REQUEST> requestContext) {}

  @Override
  public void onEnd(
      AttributesBuilder attributes,
      Context context,
      ServletRequestContext<REQUEST> requestContext,
      @Nullable ServletResponseContext<RESPONSE> responseContext,
      @Nullable Throwable error) {
    // 调用ServletAccessor的getRequestUserPrincipal，最终调用HttpServletRequest的getUserPrincipal方法
    // 获取用户的身份信息，包括用户的权限和角色
    Principal principal = accessor.getRequestUserPrincipal(requestContext.request());
    // 如果Principal不为空，且获取到name不为空，将enduser.id添加到attributes中
    if (principal != null) {
      String name = principal.getName();
      if (name != null) {
        attributes.put(SemanticAttributes.ENDUSER_ID, name);
      }
    }
    // 默认为false，直接返回
    if (!CAPTURE_EXPERIMENTAL_SPAN_ATTRIBUTES) {
      return;
    }
    // 如果出现超时，将servlet.timeout添加到attributes中
    if (responseContext != null && responseContext.hasTimeout()) {
      attributes.put(SERVLET_TIMEOUT, responseContext.getTimeout());
    }
  }
}
