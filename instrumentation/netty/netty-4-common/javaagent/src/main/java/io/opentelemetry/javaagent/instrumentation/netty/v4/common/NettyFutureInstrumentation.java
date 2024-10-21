/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.javaagent.instrumentation.netty.v4.common;

import static io.opentelemetry.javaagent.extension.matcher.AgentElementMatchers.hasClassesNamed;
import static io.opentelemetry.javaagent.extension.matcher.AgentElementMatchers.implementsInterface;
import static net.bytebuddy.matcher.ElementMatchers.isArray;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import io.opentelemetry.context.Context;
import io.opentelemetry.javaagent.bootstrap.Java8BytecodeBridge;
import io.opentelemetry.javaagent.extension.instrumentation.TypeInstrumentation;
import io.opentelemetry.javaagent.extension.instrumentation.TypeTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

public class NettyFutureInstrumentation implements TypeInstrumentation {

  @Override
  public ElementMatcher<ClassLoader> classLoaderOptimization() {
    return hasClassesNamed("io.netty.util.concurrent.Future");
  }

  @Override
  public ElementMatcher<TypeDescription> typeMatcher() {
    return implementsInterface(named("io.netty.util.concurrent.Future"));
  }

  @Override
  public void transform(TypeTransformer transformer) {
    transformer.applyAdviceToMethod(
        isMethod()
            .and(named("addListener"))
            .and(takesArgument(0, named("io.netty.util.concurrent.GenericFutureListener"))),
        NettyFutureInstrumentation.class.getName() + "$AddListenerAdvice");
    transformer.applyAdviceToMethod(
        isMethod().and(named("addListeners")).and(takesArgument(0, isArray())),
        NettyFutureInstrumentation.class.getName() + "$AddListenersAdvice");
    transformer.applyAdviceToMethod(
        isMethod()
            .and(named("removeListener"))
            .and(takesArgument(0, named("io.netty.util.concurrent.GenericFutureListener"))),
        NettyFutureInstrumentation.class.getName() + "$RemoveListenerAdvice");
    transformer.applyAdviceToMethod(
        isMethod().and(named("removeListeners")).and(takesArgument(0, isArray())),
        NettyFutureInstrumentation.class.getName() + "$RemoveListenersAdvice");
  }

  @SuppressWarnings("unused")
  public static class AddListenerAdvice {

    @Advice.OnMethodEnter
    public static void wrapListener(@Advice.Argument(value = 0, readOnly = false)
            GenericFutureListener<? extends Future<?>> listener) {
      // 判断当前的listener对应的类如果是io.opentelemetry.javaagent或io.netty下的类，则返回false
      if (FutureListenerWrappers.shouldWrap(listener)) {
        // 这里其实就是对GenericFutureListener的operationComplete方法调用进行一次context.makeCurrent()的包装，保证context在GenericFutureListener中的传递
        listener = FutureListenerWrappers.wrap(Java8BytecodeBridge.currentContext(), listener);
      }
    }
  }

  @SuppressWarnings("unused")
  public static class AddListenersAdvice {

    @Advice.OnMethodEnter
    public static void wrapListener(@Advice.Argument(value = 0, readOnly = false)
            GenericFutureListener<? extends Future<?>>[] listeners) {

      Context context = Java8BytecodeBridge.currentContext();
      @SuppressWarnings({"unchecked", "rawtypes"})
      GenericFutureListener<? extends Future<?>>[] wrappedListeners = new GenericFutureListener[listeners.length];
      for (int i = 0; i < listeners.length; ++i) {
        // 判断当前的listener对应的类如果是io.opentelemetry.javaagent或io.netty下的类，则返回false
        if (FutureListenerWrappers.shouldWrap(listeners[i])) {
          // 这里其实就是对GenericFutureListener的operationComplete方法调用进行一次context.makeCurrent()的包装，保证context在GenericFutureListener中的传递
          wrappedListeners[i] = FutureListenerWrappers.wrap(context, listeners[i]);
        }
      }
      listeners = wrappedListeners;
    }
  }

  @SuppressWarnings("unused")
  public static class RemoveListenerAdvice {

    @Advice.OnMethodEnter
    public static void wrapListener(@Advice.Argument(value = 0, readOnly = false)
            GenericFutureListener<? extends Future<?>> listener) {
      // 获取对GenericFutureListener的operationComplete方法调用进行一次context.makeCurrent()的包装的GenericFutureListener
      listener = FutureListenerWrappers.getWrapper(listener);
    }
  }

  @SuppressWarnings("unused")
  public static class RemoveListenersAdvice {

    @Advice.OnMethodEnter
    public static void wrapListener(@Advice.Argument(value = 0, readOnly = false)
            GenericFutureListener<? extends Future<?>>[] listeners) {
      @SuppressWarnings({"unchecked", "rawtypes"})
      GenericFutureListener<? extends Future<?>>[] wrappedListeners = new GenericFutureListener[listeners.length];
      for (int i = 0; i < listeners.length; ++i) {
        // 获取对GenericFutureListener的operationComplete方法调用进行一次context.makeCurrent()的包装的GenericFutureListener
        wrappedListeners[i] = FutureListenerWrappers.getWrapper(listeners[i]);
      }
      listeners = wrappedListeners;
    }
  }
}
