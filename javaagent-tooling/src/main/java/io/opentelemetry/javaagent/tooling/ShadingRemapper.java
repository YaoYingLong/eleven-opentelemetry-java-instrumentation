/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.javaagent.tooling;

import java.util.Map;
import java.util.TreeMap;
import org.objectweb.asm.commons.Remapper;

public class ShadingRemapper extends Remapper {
  public static class Rule {
    private final String from;
    private final String to;

    // 将from和to开头的"#"号截掉，且将'.'替换为'/'
    public Rule(String from, String to) {
      // Strip prefix added to prevent the build-time relocation from changing the names
      if (from.startsWith("#")) {
        from = from.substring(1);
      }
      if (to.startsWith("#")) {
        to = to.substring(1);
      }
      this.from = from.replace('.', '/');
      this.to = to.replace('.', '/');
    }
  }

  public static Rule rule(String from, String to) {
    return new Rule(from, to);
  }

  private final TreeMap<String, String> map = new TreeMap<>();

  public ShadingRemapper(Rule... rules) {
    for (Rule rule : rules) {
      map.put(rule.from, rule.to);
    }
  }

  /**
   * 其实这里的作用：若传入的路径为io.opentelemetry.context.ContextKey，则替换为io.opentelemetry.javaagent.shaded.io.opentelemetry.context.ContextKey
   * 当然要与ShadingRemapper中rule匹配，如传入的io.opentelemetry.semconv.*，则替换为io.opentelemetry.javaagent.shaded.io.opentelemetry.semconv.*
   */
  @Override
  public String map(String internalName) {
    // 返回小于等于给定键internalName的最大键值对
    Map.Entry<String, String> e = map.floorEntry(internalName);
    // 如果匹配到的Map不为空，且internalName是以e.getKey()开始的
    if (e != null && internalName.startsWith(e.getKey())) {
      /*
       * 其实这里的作用：若传入的路径为io.opentelemetry.context.ContextKey，则替换为io.opentelemetry.javaagent.shaded.io.opentelemetry.context.ContextKey
       * 当然要与ShadingRemapper中rule匹配，如传入的io.opentelemetry.semconv.*，则替换为io.opentelemetry.javaagent.shaded.io.opentelemetry.semconv.*
       */
      return e.getValue() + internalName.substring(e.getKey().length());
    }
    return super.map(internalName);
  }
}
