/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.javaagent.tooling.bootstrap;

import com.google.errorprone.annotations.CanIgnoreReturnValue;
import io.opentelemetry.javaagent.tooling.Constants;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public class BootstrapPackagesBuilderImpl implements BootstrapPackagesBuilder {

  // TODO: consider using a Trie
  // 默认是：io.opentelemetry.javaagent.bootstrap，io.opentelemetry.javaagent.shaded
  private final List<String> packages = new ArrayList<>(Constants.BOOTSTRAP_PACKAGE_PREFIXES);

  @Override
  @CanIgnoreReturnValue
  public BootstrapPackagesBuilder add(String classNameOrPrefix) {
    packages.add(classNameOrPrefix);
    return this;
  }

  @Override
  @CanIgnoreReturnValue
  public BootstrapPackagesBuilder addAll(Collection<String> classNamesOrPrefixes) {
    packages.addAll(classNamesOrPrefixes);
    return this;
  }

  public List<String> build() {
    // 默认返回：io.opentelemetry.javaagent.bootstrap，io.opentelemetry.javaagent.shaded
    return packages;
  }
}
