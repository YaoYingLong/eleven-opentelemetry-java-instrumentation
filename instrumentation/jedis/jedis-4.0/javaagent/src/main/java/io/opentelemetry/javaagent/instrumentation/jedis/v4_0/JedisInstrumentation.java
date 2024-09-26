/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.javaagent.instrumentation.jedis.v4_0;

import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.isStatic;
import static net.bytebuddy.matcher.ElementMatchers.namedOneOf;
import static net.bytebuddy.matcher.ElementMatchers.not;

import io.opentelemetry.javaagent.extension.instrumentation.TypeInstrumentation;
import io.opentelemetry.javaagent.extension.instrumentation.TypeTransformer;
import io.opentelemetry.javaagent.instrumentation.jedis.JedisRequestContext;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

public class JedisInstrumentation implements TypeInstrumentation {

  /**
   * redis.clients.jedis.UnifiedJedis是针对使用JedisCluster的情况，因为JedisCluster继承自UnifiedJedis
   */
  @Override
  public ElementMatcher<TypeDescription> typeMatcher() {
    return namedOneOf("redis.clients.jedis.Jedis", "redis.clients.jedis.UnifiedJedis");
  }

  @Override
  public void transform(TypeTransformer transformer) {
    // 针对redis.clients.jedis.Jedis中所有公共非静态方法，且非namedOneOf中列出的方法
    transformer.applyAdviceToMethod(
        isMethod()
            .and(isPublic())
            .and(not(isStatic()))
            .and(
                not(
                    namedOneOf(
                        "close",
                        "setDataSource",
                        "getDB",
                        "isConnected",
                        "connect",
                        "resetState",
                        "getClient",
                        "disconnect",
                        "getConnection",
                        "isConnected",
                        "isBroken",
                        "toString"))),
        this.getClass().getName() + "$JedisMethodAdvice");
  }

  @SuppressWarnings("unused")
  public static class JedisMethodAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static JedisRequestContext<JedisRequest> onEnter() {
      // 这里是如果能从ThreadLocal获取到JedisRequestContext，则直接返回，若获取不到，则创建一个新的
      return JedisRequestContext.attach();
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void onExit(@Advice.Enter JedisRequestContext<JedisRequest> requestContext) {
      if (requestContext != null) {
        // 这里其实是移除ThreadLocal中的JedisRequestContext，且调用Span.end方法
        requestContext.detachAndEnd();
      }
    }
  }
}
