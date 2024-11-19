/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.javaagent.logging.simple;

import com.google.auto.service.AutoService;
import io.opentelemetry.javaagent.bootstrap.InternalLogger;
import io.opentelemetry.javaagent.tooling.LoggingCustomizer;
import io.opentelemetry.javaagent.tooling.config.EarlyInitAgentConfig;
import org.slf4j.LoggerFactory;

@AutoService(LoggingCustomizer.class)
public final class Slf4jSimpleLoggingCustomizer implements LoggingCustomizer {

  // org.slf4j package name in the constants will be shaded too
  private static final String SIMPLE_LOGGER_SHOW_DATE_TIME_PROPERTY =
      "org.slf4j.simpleLogger.showDateTime";
  private static final String SIMPLE_LOGGER_DATE_TIME_FORMAT_PROPERTY =
      "org.slf4j.simpleLogger.dateTimeFormat";
  private static final String SIMPLE_LOGGER_DATE_TIME_FORMAT_DEFAULT =
      "'[otel.javaagent 'yyyy-MM-dd HH:mm:ss:SSS Z']'";
  private static final String SIMPLE_LOGGER_DEFAULT_LOG_LEVEL_PROPERTY =
      "org.slf4j.simpleLogger.defaultLogLevel";
  private static final String SIMPLE_LOGGER_PREFIX = "org.slf4j.simpleLogger.log.";

  @Override
  public String name() {
    // 该名称用于与otel.javaagent.logging配置的名称匹配
    return "simple";
  }

  @Override
  public void init(EarlyInitAgentConfig earlyConfig) {
    // 将org.slf4j.simpleLogger.showDateTime系统属性设置true
    setSystemPropertyDefault(SIMPLE_LOGGER_SHOW_DATE_TIME_PROPERTY, "true");
    // 将org.slf4j.simpleLogger.dateTimeFormat系统属性设置为'[otel.javaagent 'yyyy-MM-dd HH:mm:ss:SSS Z']'
    setSystemPropertyDefault(SIMPLE_LOGGER_DATE_TIME_FORMAT_PROPERTY, SIMPLE_LOGGER_DATE_TIME_FORMAT_DEFAULT);

    // 从配置文件、系统属性、环境变量中读取otel.javaagent.debug属性值，默认为false
    if (earlyConfig.getBoolean("otel.javaagent.debug", false)) {
      // 将org.slf4j.simpleLogger.defaultLogLevel系统属性设置为DEBUG
      setSystemPropertyDefault(SIMPLE_LOGGER_DEFAULT_LOG_LEVEL_PROPERTY, "DEBUG");
      // 将org.slf4j.simpleLogger.log.okhttp3.internal.http2系统属性设置为INFO
      setSystemPropertyDefault(SIMPLE_LOGGER_PREFIX + "okhttp3.internal.http2", "INFO");
    }

    // trigger loading the provider from the agent CL
    LoggerFactory.getILoggerFactory();

    // 其实就是将Slf4jSimpleLogger设置到InternalLoggerFactoryHolder中
    InternalLogger.initialize(Slf4jSimpleLogger::create);
  }

  @Override
  @SuppressWarnings("SystemOut")
  public void onStartupFailure(Throwable throwable) {
    // not sure if we have a log manager here, so just print
    System.err.println("OpenTelemetry Javaagent failed to start");
    throwable.printStackTrace();
  }

  @Override
  public void onStartupSuccess() {}

  /**
   * 向系统属性中设置属性值
   */
  private static void setSystemPropertyDefault(String property, String value) {
    if (System.getProperty(property) == null) {
      System.setProperty(property, value);
    }
  }
}
