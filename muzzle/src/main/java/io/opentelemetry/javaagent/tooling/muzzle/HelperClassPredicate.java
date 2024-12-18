/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.javaagent.tooling.muzzle;

import java.util.function.Predicate;

public final class HelperClassPredicate {
  // javaagent instrumentation packages
  private static final String JAVAAGENT_INSTRUMENTATION_PACKAGE =
      "io.opentelemetry.javaagent.instrumentation.";

  // library instrumentation packages (both shaded in the agent)
  private static final String LIBRARY_INSTRUMENTATION_PACKAGE = "io.opentelemetry.instrumentation.";
  private static final String INSTRUMENTATION_API_PACKAGE = "io.opentelemetry.instrumentation.api.";

  private final Predicate<String> additionalLibraryHelperClassPredicate;

  public HelperClassPredicate(Predicate<String> additionalLibraryHelperClassPredicate) {
    this.additionalLibraryHelperClassPredicate = additionalLibraryHelperClassPredicate;
  }

  /**
   * Defines which classes are treated by muzzle as "internal", "helper" instrumentation classes.
   *
   * <p>This set of classes is defined by a package naming convention: all javaagent and library
   * instrumentation classes are treated as "helper" classes and are subjected to the reference
   * collection process. All others, including {@code instrumentation-api} and {@code
   * javaagent-extension-api} modules, are not scanned for references (but references to them are
   * collected).
   *
   * <p>Aside from "standard" instrumentation helper class packages, instrumentation modules can
   * pass an additional predicate to include instrumentation helper classes from 3rd party packages.
   *
   * className是否以io.opentelemetry.javaagent.instrumentation.为前缀
   * 或className是以io.opentelemetry.instrumentation.为前缀，且不以io.opentelemetry.instrumentation.api.为前缀
   * 或调用的具体的InstrumentationModule::isHelperClass方法返回为true
   * 满足以上条件之一返回true
   */
  public boolean isHelperClass(String className) {
    // 包路径是否以io.opentelemetry.javaagent.instrumentation.为前缀，是的话返回true
    return isJavaagentHelperClass(className)
        // className是以io.opentelemetry.instrumentation.为前缀，且不以io.opentelemetry.instrumentation.api.为前缀
        || isLibraryHelperClass(className)
        // 调用的具体的InstrumentationModule::isHelperClass方法返回的值
        || additionalLibraryHelperClassPredicate.test(className);
  }

  public boolean isLibraryClass(String className) {
    return !isHelperClass(className) && !isBootstrapClass(className);
  }

  /**
   * className是以io.opentelemetry.instrumentation.api.为前缀
   * 或以io.opentelemetry.javaagent.bootstrap.为前缀
   * 或以io.opentelemetry.api.为前缀
   * 或以io.opentelemetry.context.为前缀
   * 或以io.opentelemetry.semconv.为前缀
   * 满足以上条件之一返回true
   */
  private static boolean isBootstrapClass(String className) {
    return className.startsWith(INSTRUMENTATION_API_PACKAGE)
        || className.startsWith("io.opentelemetry.javaagent.bootstrap.")
        || className.startsWith("io.opentelemetry.api.")
        || className.startsWith("io.opentelemetry.context.")
        || className.startsWith("io.opentelemetry.semconv.");
  }

  /**
   * 包路径是否以io.opentelemetry.javaagent.instrumentation.为前缀，是的话返回true
   */
  private static boolean isJavaagentHelperClass(String className) {
    return className.startsWith(JAVAAGENT_INSTRUMENTATION_PACKAGE);
  }

  /**
   * className是以io.opentelemetry.instrumentation.为前缀，且不以io.opentelemetry.instrumentation.api.为前缀
   */
  private static boolean isLibraryHelperClass(String className) {
    return className.startsWith(LIBRARY_INSTRUMENTATION_PACKAGE)
        && !className.startsWith(INSTRUMENTATION_API_PACKAGE);
  }
}
