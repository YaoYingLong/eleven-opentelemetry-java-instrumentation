/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.javaagent.logging.application;

import com.google.auto.service.AutoService;
import io.opentelemetry.javaagent.bootstrap.InternalLogger;
import io.opentelemetry.javaagent.bootstrap.logging.ApplicationLoggerBridge;
import io.opentelemetry.javaagent.tooling.LoggingCustomizer;
import io.opentelemetry.javaagent.tooling.config.EarlyInitAgentConfig;

@AutoService(LoggingCustomizer.class)
public final class ApplicationLoggingCustomizer implements LoggingCustomizer {

  @Override
  public String name() {
    // 该名称用于与otel.javaagent.logging配置的名称匹配
    return "application";
  }

  @Override
  public void init(EarlyInitAgentConfig earlyConfig) {
    // 获取日志最大buffer长度，默认是2048
    int limit = earlyConfig.getInt("otel.javaagent.logging.application.logs-buffer-max-records", 2048);
    InMemoryLogStore inMemoryLogStore = new InMemoryLogStore(limit);
    ApplicationLoggerFactory loggerFactory = new ApplicationLoggerFactory(inMemoryLogStore);
    // register a shutdown hook that'll dump the logs to stderr in case something goes wrong
    Runtime.getRuntime().addShutdownHook(new Thread(() -> inMemoryLogStore.dump(System.err)));
    // 将ApplicationLoggerFactory通过CAS设置到ApplicationLoggerBridge
    ApplicationLoggerBridge.set(loggerFactory);
    // 其实就是将ApplicationLoggerFactory设置到InternalLoggerFactoryHolder中
    InternalLogger.initialize(loggerFactory);
  }

  // AgentStarterImpl的start结束时判断是否产生异常时调用
  @Override
  public void onStartupSuccess() {}

  // AgentStarterImpl的start结束时判断是否产生异常时调用
  @Override
  @SuppressWarnings("SystemOut")
  public void onStartupFailure(Throwable throwable) {
    // most likely the application bridge wasn't initialized, let's just print
    System.err.println("OpenTelemetry Javaagent failed to start");
    throwable.printStackTrace();
  }
}
