/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.javaagent.instrumentation.jdbc;

import static java.util.Arrays.asList;

import com.google.auto.service.AutoService;
import io.opentelemetry.javaagent.extension.instrumentation.InstrumentationModule;
import io.opentelemetry.javaagent.extension.instrumentation.TypeInstrumentation;
import java.util.List;

@AutoService(InstrumentationModule.class)
public class JdbcInstrumentationModule extends InstrumentationModule {
  public JdbcInstrumentationModule() {
    super("jdbc");
  }

  @Override
  public List<TypeInstrumentation> typeInstrumentations() {
    // 这里只是对执行SQL进行了加强，但是会有一个问题，使用像hikari之类的数据库连接池，在执行SQL之前获取连接的时间没有算进来
    // 可能会出现有于网络导致请求阻塞，从而导致获取连接等待时间比较长，会导致导出的Span时间上不连续
    return asList(
        new ConnectionInstrumentation(),
        new DriverInstrumentation(),
        new PreparedStatementInstrumentation(),
        new StatementInstrumentation());
  }
}
