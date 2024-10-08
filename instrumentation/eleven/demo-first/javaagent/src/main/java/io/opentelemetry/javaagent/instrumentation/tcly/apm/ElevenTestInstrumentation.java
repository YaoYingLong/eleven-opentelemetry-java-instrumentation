package io.opentelemetry.javaagent.instrumentation.tcly.apm;

import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.nameStartsWith;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.javaagent.extension.instrumentation.TypeInstrumentation;
import io.opentelemetry.javaagent.extension.instrumentation.TypeTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

public class ElevenTestInstrumentation implements TypeInstrumentation {
  @Override
  public ElementMatcher<TypeDescription> typeMatcher() {
    return nameStartsWith("com.eleven.demo.demo.controller");
  }

  @Override
  public void transform(TypeTransformer transformer) {
    transformer.applyAdviceToMethod(isMethod().and(isPublic()),
        this.getClass().getName() + "$ElevenAdvice");
  }

  /**
   * com.tcly.eleven.demo.demo.controller 下所有方法，都会被 AOP 方式拦拦截进 ElevenAdvice 这个 Advice
   */
  @SuppressWarnings("unused")
  public static class ElevenAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void methodEnter(@Advice.Origin String method,
        @Advice.AllArguments Object[] allArguments,
        @Advice.Local("elevenStartTime") long startTime) {
      System.out.println();
      System.out.println("[" + method + "] ------------> methodEnter start ! ------------>");
      // Around Advice 打印方法所有入参 AllArguments
      if (allArguments != null) {
        for (Object a : allArguments) {
          System.out.println("- - - type : " + a.getClass().getName() + ", value : " + a);
        }
      }
      System.out.println(" method methodEnter | local var elevenStartTime : " + startTime);
      if (startTime <= 0) {
        startTime = System.currentTimeMillis();
      }

      //从Span中获取方法开始时间
      Span span = Span.current();
      System.out.println(
          "OnMethodEnter traceId:" + span.getSpanContext().getTraceId() + " | spanId:"
              + span.getSpanContext().getSpanId());
      span.setAttribute("tagTime", System.currentTimeMillis());
      System.out.println("[" + method + "] ------------> methodEnter end ! ------------>");
      System.out.println();
    }

    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void methodExit(@Advice.Origin String method,
        @Advice.Local("elevenStartTime") long startTime) {
      System.out.println();
      System.out.println("[" + method + "] <------------ methodExit start ! <------------ ");
      //从Span中获取方法开始时间
      Span span = Span.current();
      System.out.println(
          "OnMethodEnter traceId:" + span.getSpanContext().getTraceId() + " | spanId:"
              + span.getSpanContext().getSpanId());
      //通过 Advice.Local 拿到时间戳，统计 method 执行时间
      System.out.println(
          method + " method cost time :" + (System.currentTimeMillis() - startTime) + " ms ");
      System.out.println("[" + method + "] <------------ methodExit end ! <------------ ");
      System.out.println();
    }
  }
}
