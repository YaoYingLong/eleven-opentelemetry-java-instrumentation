/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.javaagent.tooling;

import static io.opentelemetry.javaagent.tooling.ShadingRemapper.rule;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.security.Permission;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.commons.ClassRemapper;

public class RemappingUrlConnection extends URLConnection {
  // We need to prefix the names to prevent the gradle shadowJar relocation rules from touching
  // them. It's possible to do this by excluding this class from shading, but it may cause issue
  // with transitive dependencies down the line.
  // 我们需要为名称添加前缀，以防止gradle shadowJar重定位规则触及它们。可以通过从着色中排除此类来执行此操作，但这可能会导致后续传递依赖项出现问题。
  // 其实这里的作用：若传入的路径为io.opentelemetry.context.ContextKey，则替换为io.opentelemetry.javaagent.shaded.io.opentelemetry.context.ContextKey
  // 当然要与ShadingRemapper中rule匹配，如传入的io.opentelemetry.semconv.*，则替换为io.opentelemetry.javaagent.shaded.io.opentelemetry.semconv.*
  private static final ShadingRemapper remapper =
      new ShadingRemapper(
          rule("#io.opentelemetry.api", "#io.opentelemetry.javaagent.shaded.io.opentelemetry.api"),
          rule(
              "#io.opentelemetry.context",
              "#io.opentelemetry.javaagent.shaded.io.opentelemetry.context"),
          rule(
              "#io.opentelemetry.instrumentation",
              "#io.opentelemetry.javaagent.shaded.instrumentation"),
          rule(
              "#io.opentelemetry.semconv",
              "#io.opentelemetry.javaagent.shaded.io.opentelemetry.semconv"),
          rule(
              "#io.opentelemetry.extension.aws",
              "#io.opentelemetry.javaagent.shaded.io.opentelemetry.extension.aws"),
          rule("#application.io.opentelemetry", "#io.opentelemetry"),
          rule("#java.util.logging.Logger", "#io.opentelemetry.javaagent.bootstrap.PatchLogger"));

  private final JarFile delegateJarFile;
  private final JarEntry entry;

  private byte[] cacheClassBytes;

  public RemappingUrlConnection(URL url, JarFile delegateJarFile, JarEntry entry) {
    super(url);
    this.delegateJarFile = delegateJarFile;
    this.entry = entry;
  }

  @Override
  public void connect() {
    connected = true;
  }

  @Override
  public InputStream getInputStream() throws IOException {
    if (cacheClassBytes == null) {
      cacheClassBytes = readAndRemap();
    }

    return new ByteArrayInputStream(cacheClassBytes);
  }

  private byte[] readAndRemap() throws IOException {
    try {
      InputStream inputStream = delegateJarFile.getInputStream(entry);
      return remapClassBytes(inputStream);
    } catch (IOException e) {
      throw new IOException(
          String.format("Failed to remap bytes for %s: %s%n", url.toString(), e.getMessage()));
    }
  }

  private static byte[] remapClassBytes(InputStream in) throws IOException {
    ClassReader cr = new ClassReader(in);
    ClassWriter cw = new ClassWriter(cr, 0);
    cr.accept(new ClassRemapper(cw, remapper), ClassReader.EXPAND_FRAMES);
    return cw.toByteArray();
  }

  @Override
  public Permission getPermission() {
    // No permissions needed because all classes are in memory
    return null;
  }
}
