/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.javaagent.instrumentation.jedis.v4_0;

import static io.opentelemetry.javaagent.bootstrap.Java8BytecodeBridge.currentContext;
import static io.opentelemetry.javaagent.instrumentation.jedis.v4_0.JedisSingletons.instrumenter;
import static java.util.Arrays.asList;
import static net.bytebuddy.matcher.ElementMatchers.is;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.javaagent.extension.instrumentation.TypeInstrumentation;
import io.opentelemetry.javaagent.extension.instrumentation.TypeTransformer;
import io.opentelemetry.javaagent.instrumentation.jedis.JedisRequestContext;
import java.net.Socket;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import redis.clients.jedis.CommandArguments;
import redis.clients.jedis.commands.ProtocolCommand;

/**
 * 该TypeInstrumentation应该是针对通过jedis.getConnection().sendCommand()则会中方式去执行命令
 * 同时也兼容了jedis.get("webkey")这种方式去执行命令，是通过判断ThreadLocal中是否存在JedisRequestContext来判断
 * 到底是在sendCommand直接完成调用Span.end还是在调用jedis的方法完成调用Span.end
 *
 * 同时也兼容通过JedisCluster调用的情况
 */
public class JedisConnectionInstrumentation implements TypeInstrumentation {
  @Override
  public ElementMatcher<TypeDescription> typeMatcher() {
    return named("redis.clients.jedis.Connection");
  }

  @Override
  public void transform(TypeTransformer transformer) {
    // 匹配只有一个参数的sendCommand方法，且参数为CommandArguments
    transformer.applyAdviceToMethod(
        isMethod()
            .and(named("sendCommand"))
            .and(takesArguments(1))
            .and(takesArgument(0, named("redis.clients.jedis.CommandArguments"))),
        this.getClass().getName() + "$SendCommand2Advice");

    // 匹配有两个参数的sendCommand方法，且第一个参数为ProtocolCommand，第二个参数为byte[][]
    transformer.applyAdviceToMethod(
        isMethod()
            .and(named("sendCommand"))
            .and(takesArguments(2))
            .and(takesArgument(0, named("redis.clients.jedis.commands.ProtocolCommand")))
            .and(takesArgument(1, is(byte[][].class))),
        this.getClass().getName() + "$SendCommandAdvice");
  }

  @SuppressWarnings("unused")
  public static class SendCommandAdvice {

    // 匹配有两个参数的sendCommand方法，且第一个参数为ProtocolCommand，第二个参数为byte[][]
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnter(
        // 获取第一个ProtocolCommand参数
        @Advice.Argument(0) ProtocolCommand command,
        @Advice.Argument(1) byte[][] args,
        @Advice.Local("otelJedisRequest") JedisRequest request,
        @Advice.Local("otelContext") Context context,
        @Advice.Local("otelScope") Scope scope) {
      // 其实就是从ThreadLocal中获取Context
      Context parentContext = currentContext();
      request = JedisRequest.create(command, asList(args));
      if (!instrumenter().shouldStart(parentContext, request)) {
        return;
      }

      // 最终的目的是调用SdkSpanBuilder的startSpan生成Span，并将span设置到context中，以便在end方法中使用
      context = instrumenter().start(parentContext, request);
      // 这里的目的是将Context存储到ThreadLocal中
      scope = context.makeCurrent();
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void stopSpan(
        @Advice.FieldValue("socket") Socket socket,
        @Advice.Thrown Throwable throwable,
        @Advice.Local("otelJedisRequest") JedisRequest request,
        @Advice.Local("otelContext") Context context,
        @Advice.Local("otelScope") Scope scope) {
      if (scope == null) {
        return;
      }

      request.setSocket(socket);
      // 注意必须在退出的是调用scope.close()，否则可能造成内存泄漏或上一下文混乱
      // 将之前的Context设置会ThreadLocal中
      scope.close();
      // 这里做了封装，最终的目的是调用Span.end方法
      JedisRequestContext.endIfNotAttached(instrumenter(), context, request, throwable);
    }
  }

  @SuppressWarnings("unused")
  public static class SendCommand2Advice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnter(
        @Advice.Argument(0) CommandArguments command,
        @Advice.Local("otelJedisRequest") JedisRequest request,
        @Advice.Local("otelContext") Context context,
        @Advice.Local("otelScope") Scope scope) {
      Context parentContext = currentContext();
      request = JedisRequest.create(command);
      if (!instrumenter().shouldStart(parentContext, request)) {
        return;
      }

      context = instrumenter().start(parentContext, request);
      scope = context.makeCurrent();
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void stopSpan(
        @Advice.FieldValue("socket") Socket socket,
        @Advice.Thrown Throwable throwable,
        @Advice.Local("otelJedisRequest") JedisRequest request,
        @Advice.Local("otelContext") Context context,
        @Advice.Local("otelScope") Scope scope) {
      if (scope == null) {
        return;
      }

      request.setSocket(socket);

      scope.close();
      // 如果执行的方法异常了，会解析异常堆栈信息，然后创建Event并添加到Span中
      JedisRequestContext.endIfNotAttached(instrumenter(), context, request, throwable);
    }
  }
}
