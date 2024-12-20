/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.instrumentation.api.internal;

import java.util.Locale;
import javax.annotation.Nullable;

/**
 * This class is internal and is hence not for public use. Its APIs are unstable and can change at
 * any time.
 */
public final class ConfigPropertiesUtil {

  public static boolean getBoolean(String propertyName, boolean defaultValue) {
    String strValue = getString(propertyName);
    return strValue == null ? defaultValue : Boolean.parseBoolean(strValue);
  }

  public static int getInt(String propertyName, int defaultValue) {
    String strValue = getString(propertyName);
    if (strValue == null) {
      return defaultValue;
    }
    try {
      return Integer.parseInt(strValue);
    } catch (NumberFormatException ignored) {
      return defaultValue;
    }
  }

  @Nullable
  public static String getString(String propertyName) {
    // 从java平台生效的属性中读取配置信息
    String value = System.getProperty(propertyName);
    if (value != null) {
      return value;
    }
    // 从系统环境变量中读取属性名称对应的值
    return System.getenv(toEnvVarName(propertyName));
  }

  private static String toEnvVarName(String propertyName) {
    return propertyName.toUpperCase(Locale.ROOT).replace('-', '_').replace('.', '_');
  }

  private ConfigPropertiesUtil() {}
}
