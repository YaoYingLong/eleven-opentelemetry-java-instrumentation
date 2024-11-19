/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.javaagent.tooling.config;

import io.opentelemetry.instrumentation.api.internal.ConfigPropertiesUtil;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * Agent config class that is only supposed to be used before the SDK (and {@link
 * io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties}) is initialized.
 */
public final class EarlyInitAgentConfig {

  public static EarlyInitAgentConfig create() {
    // 读取环境变量otel.javaagent.configuration-file中配置的配置文件地址，并将配置解析为HashMap存储到configFileContents
    return new EarlyInitAgentConfig(ConfigurationFile.getProperties());
  }

  private final Map<String, String> configFileContents;

  private EarlyInitAgentConfig(Map<String, String> configFileContents) {
    this.configFileContents = configFileContents;
  }

  @Nullable
  public String getString(String propertyName) {
    // 先从系统或环境变量中读取属性名称对应的值
    String value = ConfigPropertiesUtil.getString(propertyName);
    if (value != null) {
      return value;
    }
    // 如果未从系统或环境变量中读取属性名称对应的值，则从配置文件中解析的配置中读取
    return configFileContents.get(propertyName);
  }

  public boolean getBoolean(String propertyName, boolean defaultValue) {
    // 先从配置文件中解析的配置中读取
    String configFileValueStr = configFileContents.get(propertyName);
    boolean configFileValue = configFileValueStr == null ? defaultValue : Boolean.parseBoolean(configFileValueStr);
    // 从系统或环境变量中读取属性名称对应的值，如果未读取到返回configFileValue对应的默认值
    return ConfigPropertiesUtil.getBoolean(propertyName, configFileValue);
  }

  public int getInt(String propertyName, int defaultValue) {
    try {
      // 先从配置文件中解析的配置中读取
      String configFileValueStr = configFileContents.get(propertyName);
      int configFileValue = configFileValueStr == null ? defaultValue : Integer.parseInt(configFileValueStr);
      // 从系统或环境变量中读取属性名称对应的值，如果未读取到返回configFileValue对应的默认值
      return ConfigPropertiesUtil.getInt(propertyName, configFileValue);
    } catch (NumberFormatException ignored) {
      return defaultValue;
    }
  }

  /*
   * 打印日志：在加载&解析配置文件过程中如果产生了异常，会直接打印出来，如果没有异常产生则不会产生日志信息
   */
  public void logEarlyConfigErrorsIfAny() {
    ConfigurationFile.logErrorIfAny();
  }
}
