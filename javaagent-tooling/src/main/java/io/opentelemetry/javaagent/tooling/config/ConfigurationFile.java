/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.javaagent.tooling.config;

import static java.util.Collections.emptyMap;
import static java.util.logging.Level.SEVERE;

import io.opentelemetry.instrumentation.api.internal.ConfigPropertiesUtil;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Properties;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import javax.annotation.Nullable;

/**
 * 读取环境变量otel.javaagent.configuration-file中配置的配置文件地址，并将配置解析为HashMap存储到configFileContents
 */
final class ConfigurationFile {

  static final String CONFIGURATION_FILE_PROPERTY = "otel.javaagent.configuration-file";

  private static Map<String, String> configFileContents;

  // this class is used early, and must not use logging in most of its methods
  // in case any file loading/parsing error occurs, we save the error message and log it later, when
  // the logging subsystem is initialized
  @Nullable private static String fileLoadErrorMessage;

  static Map<String, String> getProperties() {
    if (configFileContents == null) {
      configFileContents = loadConfigFile();
    }
    // TODO 用于代码调试验证，验证完成后需要删除
    for (Map.Entry<String, String> entry : configFileContents.entrySet()) {
      System.out.println("configFileContents: " + entry.getKey() + "=" + entry.getValue());
    }
    return configFileContents;
  }

  // visible for tests
  static Map<String, String> loadConfigFile() {
    // Reading from system property first and from env after
    // 从系统环境变量和用户环境变量中获取otel.javaagent.configuration-file配置的配置文件路径
    String configurationFilePath = ConfigPropertiesUtil.getString(CONFIGURATION_FILE_PROPERTY);
    if (configurationFilePath == null) {
      // 如果未进行配置，则直接返回一个空的Map，注意不是返回null
      return emptyMap();
    }
    // Normalizing tilde (~) paths for unix systems
    // 对应unix类型的系统进行处理，将开始的～替换为环境变量中配置的user.home
    configurationFilePath = configurationFilePath.replaceFirst("^~", System.getProperty("user.home"));

    // Configuration properties file is optional
    File configurationFile = new File(configurationFilePath);
    if (!configurationFile.exists()) {
      fileLoadErrorMessage = "Configuration file \"" + configurationFilePath + "\" not found.";
      // 若文件不存在，记录加载错误信息然后返回空Map，这里是记录不是直接打印的原因是因为此时logging还没有配置加载
      return emptyMap();
    }

    Properties properties = new Properties();
    try (InputStreamReader reader = new InputStreamReader(new FileInputStream(configurationFile), StandardCharsets.UTF_8)) {
      properties.load(reader);
    } catch (FileNotFoundException fnf) {
      fileLoadErrorMessage = "Configuration file \"" + configurationFilePath + "\" not found.";
    } catch (IOException ioe) {
      fileLoadErrorMessage = "Configuration file \"" + configurationFilePath + "\" cannot be accessed or correctly parsed.";
    }
    return properties.entrySet().stream()
        .collect(Collectors.toMap(e -> e.getKey().toString(), e -> e.getValue().toString()));
  }

  static void logErrorIfAny() {
    if (fileLoadErrorMessage != null) {
      Logger.getLogger(ConfigurationPropertiesSupplier.class.getName()).log(SEVERE, fileLoadErrorMessage);
    }
  }

  private ConfigurationFile() {}
}
