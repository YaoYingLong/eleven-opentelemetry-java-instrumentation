/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.javaagent.instrumentation.jdbc;

import static io.opentelemetry.javaagent.extension.matcher.AgentElementMatchers.hasClassesNamed;
import static io.opentelemetry.javaagent.extension.matcher.AgentElementMatchers.implementsInterface;
import static net.bytebuddy.matcher.ElementMatchers.nameStartsWith;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import io.opentelemetry.instrumentation.jdbc.internal.JdbcConnectionUrlParser;
import io.opentelemetry.instrumentation.jdbc.internal.JdbcData;
import io.opentelemetry.javaagent.bootstrap.jdbc.DbInfo;
import io.opentelemetry.javaagent.extension.instrumentation.TypeInstrumentation;
import io.opentelemetry.javaagent.extension.instrumentation.TypeTransformer;
import java.sql.Connection;
import java.util.Properties;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

public class DriverInstrumentation implements TypeInstrumentation {

  @Override
  public ElementMatcher<ClassLoader> classLoaderOptimization() {
    return hasClassesNamed("java.sql.Driver");
  }

  @Override
  public ElementMatcher<TypeDescription> typeMatcher() {
    return implementsInterface(named("java.sql.Driver"));
  }

  @Override
  public void transform(TypeTransformer transformer) {
    // Driver的connect方法、且该方法必须满足第一个参数类型为String、第二个参数类型为Properties，返回类型为Connection
    transformer.applyAdviceToMethod(
        nameStartsWith("connect")
            .and(takesArgument(0, String.class))
            .and(takesArgument(1, Properties.class))
            .and(returns(named("java.sql.Connection"))),
        DriverInstrumentation.class.getName() + "$DriverAdvice");
  }

  @SuppressWarnings("unused")
  public static class DriverAdvice {

    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void addDbInfo(
        @Advice.Argument(0) String url,
        @Advice.Argument(1) Properties props,
        @Advice.Return Connection connection) {
      if (connection == null) {
        // Exception was probably thrown.
        return;
      }
      // 经过一系列的解析，将url和Properties解析分装到DbInfo
      DbInfo dbInfo = JdbcConnectionUrlParser.parse(url, props);
      // 这里其实是通过VirtualField将connection实例与dbInfo绑定，JdbcData.intern只是做了一次弱引用缓存，若缓存有数据直接获取
      JdbcData.connectionInfo.set(connection, JdbcData.intern(dbInfo));
    }
  }
}
