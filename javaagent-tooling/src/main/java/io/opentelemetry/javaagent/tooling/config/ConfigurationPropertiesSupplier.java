/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.javaagent.tooling.config;

import com.google.auto.service.AutoService;
import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizer;
import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizerProvider;

@AutoService(AutoConfigurationCustomizerProvider.class)
public final class ConfigurationPropertiesSupplier implements AutoConfigurationCustomizerProvider {

  @Override
  public void customize(AutoConfigurationCustomizer autoConfiguration) {
    // 从系统环境变量和用户环境变量中获取otel.javaagent.configuration-file配置的配置文件路径，并加载配置
    autoConfiguration.addPropertiesSupplier(ConfigurationFile::getProperties);
  }

  @Override
  public int order() {
    // make sure it runs after all the user-provided customizers
    // 设置为最大值，确保它在所有用户提供的定制器之后运行
    return Integer.MAX_VALUE;
  }
}
