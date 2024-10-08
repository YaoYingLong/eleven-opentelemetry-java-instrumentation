/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.javaagent.instrumentation.lettuce.v5_1;

import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.isStatic;
import static net.bytebuddy.matcher.ElementMatchers.named;

import io.lettuce.core.resource.DefaultClientResources;
import io.opentelemetry.javaagent.extension.instrumentation.TypeInstrumentation;
import io.opentelemetry.javaagent.extension.instrumentation.TypeTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

/**
 * Lettuce本身实现了Tracer，这里要做的其实是将OTel的功能嵌入到Lettuce中
 * 这里其实就是将实现了Lettuce的Tracing的OpenTelemetryTracing设置到Lettuce中从而实现tracer的功能
 */
public class DefaultClientResourcesInstrumentation implements TypeInstrumentation {
  @Override
  public ElementMatcher<TypeDescription> typeMatcher() {
    return named("io.lettuce.core.resource.DefaultClientResources");
  }

  @Override
  public void transform(TypeTransformer transformer) {
    transformer.applyAdviceToMethod(
        isMethod().and(isPublic()).and(isStatic()).and(named("builder")),
        this.getClass().getName() + "$BuilderAdvice");
  }

  @SuppressWarnings("unused")
  public static class BuilderAdvice {

    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void methodEnter(@Advice.Return DefaultClientResources.Builder builder) {
      // 这里其实就是将我们自定义的OpenTelemetryTracing设置为DefaultClientResources中持有的Tracing
      builder.tracing(TracingHolder.TRACING);
    }
  }
}
