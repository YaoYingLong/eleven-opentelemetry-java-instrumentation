/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.javaagent.instrumentation.guava.v10_0;

import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.named;

import io.opentelemetry.context.Context;
import io.opentelemetry.instrumentation.api.util.VirtualField;
import io.opentelemetry.javaagent.bootstrap.Java8BytecodeBridge;
import io.opentelemetry.javaagent.bootstrap.executors.ExecutorAdviceHelper;
import io.opentelemetry.javaagent.bootstrap.executors.PropagatedContext;
import io.opentelemetry.javaagent.extension.instrumentation.TypeInstrumentation;
import io.opentelemetry.javaagent.extension.instrumentation.TypeTransformer;
import java.util.concurrent.Executor;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.matcher.ElementMatchers;

public class GuavaListenableFutureInstrumentation implements TypeInstrumentation {
  @Override
  public ElementMatcher<TypeDescription> typeMatcher() {
    return named("com.google.common.util.concurrent.AbstractFuture");
  }

  @Override
  public void transform(TypeTransformer transformer) {
    transformer.applyAdviceToMethod(isConstructor(), this.getClass().getName() + "$AbstractFutureAdvice");
    transformer.applyAdviceToMethod(
        named("addListener").and(ElementMatchers.takesArguments(Runnable.class, Executor.class)),
        this.getClass().getName() + "$AddListenerAdvice");
  }

  @SuppressWarnings("unused")
  public static class AbstractFutureAdvice {

    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void onConstruction() {
      // 这里是空执行，看似什么都没有做，但是实例化时执行类static代码快，向WeakRefAsyncOperationEndStrategies中
      // 注册了GuavaAsyncOperationEndStrategy策略，只会执行一次
      InstrumentationHelper.initialize();
    }
  }

  @SuppressWarnings("unused")
  public static class AddListenerAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static PropagatedContext addListenerEnter(@Advice.Argument(0) Runnable task) {
      Context context = Java8BytecodeBridge.currentContext();
      if (ExecutorAdviceHelper.shouldPropagateContext(context, task)) {
        VirtualField<Runnable, PropagatedContext> virtualField =
            VirtualField.find(Runnable.class, PropagatedContext.class);
        return ExecutorAdviceHelper.attachContextToTask(context, virtualField, task);
      }
      return null;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void addListenerExit(@Advice.Enter PropagatedContext propagatedContext, @Advice.Thrown Throwable throwable) {
      ExecutorAdviceHelper.cleanUpAfterSubmit(propagatedContext, throwable);
    }
  }
}
