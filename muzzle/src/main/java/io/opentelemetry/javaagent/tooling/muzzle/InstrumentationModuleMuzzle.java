/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.javaagent.tooling.muzzle;

import io.opentelemetry.javaagent.extension.instrumentation.InstrumentationModule;
import io.opentelemetry.javaagent.tooling.muzzle.references.ClassRef;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 此接口包含muzzle自动添加到InstrumentationModule的方法。它们不应该被最终用户使用，而应该被我们自己的内部代码使用
 *
 * InstrumentationModuleMuzzle没有在代码中直接实现这个接口，而是通过ByteBuddy来构造了一个实现
 * Agent通过构建MuzzleCodeGenerator实现了AsmVisitorWrapper来完整构造了InstrumentationModuleMuzzle的实现方法
 *
 * This interface contains methods that muzzle automatically adds to the {@link
 * InstrumentationModule}. They are not supposed to be used by end-users, only by our own internal
 * code.
 */
public interface InstrumentationModuleMuzzle {

  /**
   * Returns references to helper and library classes used in this module's type instrumentation
   * advices, grouped by {@link ClassRef#getClassName()}.
   */
  Map<String, ClassRef> getMuzzleReferences();

  /** See {@link #getMuzzleReferences()}. */
  static Map<String, ClassRef> getMuzzleReferences(InstrumentationModule module) {
    if (module instanceof InstrumentationModuleMuzzle) {
      return ((InstrumentationModuleMuzzle) module).getMuzzleReferences();
    } else {
      return Collections.emptyMap();
    }
  }

  /**
   * Builds the associations between instrumented library classes and instrumentation context
   * classes. Keys (and their subclasses) will be associated with a context class stored in the
   * value.
   */
  void registerMuzzleVirtualFields(VirtualFieldMappingsBuilder builder);

  /**
   * Returns a list of instrumentation helper classes, automatically detected by muzzle during
   * compilation. Those helpers will be injected into the application class loader.
   */
  List<String> getMuzzleHelperClassNames();

  /**
   * Returns a concatenation of {@link #getMuzzleHelperClassNames()} and {@link
   * InstrumentationModule#getAdditionalHelperClassNames()}.
   */
  static List<String> getHelperClassNames(InstrumentationModule module) {
    // 如果module是InstrumentationModuleMuzzle实例，则调用其getMuzzleHelperClassNames方法，否则返回空列表
    List<String> muzzleHelperClassNames = module instanceof InstrumentationModuleMuzzle
            ? ((InstrumentationModuleMuzzle) module).getMuzzleHelperClassNames()
            : Collections.emptyList();

    // 绝大多数InstrumentationModule实例使用InstrumentationModule中定义的默认方法，返回空列表
    List<String> additionalHelperClassNames = module.getAdditionalHelperClassNames();

    // 下面的逻辑其实就是合并muzzleHelperClassNames和additionalHelperClassNames的内容
    if (additionalHelperClassNames.isEmpty()) {
      return muzzleHelperClassNames;
    }
    if (muzzleHelperClassNames.isEmpty()) {
      return additionalHelperClassNames;
    }

    List<String> result = new ArrayList<>(muzzleHelperClassNames);
    result.addAll(additionalHelperClassNames);
    return result;
  }
}
