/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.javaagent.tooling;

import io.opentelemetry.javaagent.tooling.config.EarlyInitAgentConfig;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.file.Files;
import java.security.AllPermission;
import java.security.CodeSource;
import java.security.PermissionCollection;
import java.security.Permissions;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import javax.annotation.Nullable;
import net.bytebuddy.dynamic.loading.MultipleParentClassLoader;

/**
 * This class creates a class loader which encapsulates arbitrary extensions for Otel Java
 * instrumentation agent. Such extensions may include SDK components (exporters or propagators) and
 * additional instrumentations. They have to be isolated and shaded to reduce interference with the
 * user application and to make it compatible with shaded SDK used by the agent. Thus each extension
 * jar gets a separate class loader and all of them are aggregated with the help of {@link
 * MultipleParentClassLoader}.
 */
// TODO find a way to initialize logging before using this class
@SuppressWarnings("SystemOut")
public class ExtensionClassLoader extends URLClassLoader {
  public static final String EXTENSIONS_CONFIG = "otel.javaagent.extensions";

  private final boolean isSecurityManagerSupportEnabled;

  // NOTE it's important not to use logging in this class, because this class is used before logging
  // is initialized

  static {
    ClassLoader.registerAsParallelCapable();
  }

  /**
   * 加载extensions/目录下的jar，每一个extensions/目下下的Jar都会生成一个协议为otel的URL
   * 为每一个extensions/目录下的jar都单独创建一个ExtensionClassLoader来加载
   *
   * - parent：        AgentClassLoader
   * - javaagentFile： 就是Agent对应Jar包
   */
  public static ClassLoader getInstance(ClassLoader parent, File javaagentFile, boolean isSecurityManagerSupportEnabled, EarlyInitAgentConfig earlyConfig) {
    List<URL> extensions = new ArrayList<>();

    // 加载extensions/目录下的jar，每一个extensions/目下下的Jar都会生成一个协议为otel的URL
    includeEmbeddedExtensionsIfFound(extensions, javaagentFile);

    // 加载自定义扩展，通过otel.javaagent.extensions配置jar表列表，若有多个用逗号隔开
    extensions.addAll(parseLocation(earlyConfig.getString(EXTENSIONS_CONFIG), javaagentFile));

    // TODO when logging is configured add warning about deprecated property

    if (extensions.isEmpty()) {
      return parent;
    }

    List<ClassLoader> delegates = new ArrayList<>(extensions.size());
    // 这里为每一个extensions/目录下的jar都单独创建一个ExtensionClassLoader来加载
    for (URL url : extensions) {
      delegates.add(getDelegate(parent, url, isSecurityManagerSupportEnabled));
    }
    return new MultipleParentClassLoader(parent, delegates);
  }

  private static void includeEmbeddedExtensionsIfFound(List<URL> extensions, File javaagentFile) {
    try {
      JarFile jarFile = new JarFile(javaagentFile, false);
      Enumeration<JarEntry> entryEnumeration = jarFile.entries();
      String prefix = "extensions/";
      File tempDirectory = null;
      while (entryEnumeration.hasMoreElements()) {
        JarEntry jarEntry = entryEnumeration.nextElement();
        String name = jarEntry.getName();

        // 这里的name值为：extensions/*.jar
        if (name.startsWith(prefix) && !jarEntry.isDirectory()) {
          // 如果tempDirectory为空，创建otel-extensions临时目录，且在退出时删除
          tempDirectory = ensureTempDirectoryExists(tempDirectory);

          // 生成临时文件对象：otel-extensions/*.jar
          File tempFile = new File(tempDirectory, name.substring(prefix.length()));
          // reject extensions that would be extracted outside of temp directory
          // https://security.snyk.io/research/zip-slip-vulnerability
          // getCanonicalFile返回一个新的File对象，表示文件路径的标准化版本，去除了冗余的部分，如"."和".."
          if (!tempFile.getCanonicalFile().toPath().startsWith(tempDirectory.getCanonicalFile().toPath())) {
            throw new IllegalStateException("Invalid extension " + name);
          }
          // 创建临时文件：otel-extensions/*.jar
          if (tempFile.createNewFile()) {
            // 退出时删除该文件
            tempFile.deleteOnExit();
            // 将jarEntry即对应的extensions/*.jar文件内容写入到otel-extensions/*.jar中
            extractFile(jarFile, jarEntry, tempFile);
            // 通过传入otel-extensions/*.jar构建自定义UrlStreamHandler，然后在构建自定义otel协议的URL，将URL添加到extensions列表中
            addFileUrl(extensions, tempFile);
          } else {
            System.err.println("Failed to create temp file " + tempFile);
          }
        }
      }
    } catch (IOException ex) {
      System.err.println("Failed to open embedded extensions " + ex.getMessage());
    }
  }

  // 如果tempDirectory为空，创建otel-extensions临时目录，且在退出时删除
  private static File ensureTempDirectoryExists(File tempDirectory) throws IOException {
    if (tempDirectory == null) {
      tempDirectory = Files.createTempDirectory("otel-extensions").toFile();
      tempDirectory.deleteOnExit();
    }
    return tempDirectory;
  }

  // 创建一个新的ExtensionClassLoader为extensionUrl
  private static URLClassLoader getDelegate(ClassLoader parent, URL extensionUrl, boolean isSecurityManagerSupportEnabled) {
    return new ExtensionClassLoader(extensionUrl, parent, isSecurityManagerSupportEnabled);
  }

  // visible for testing
  static List<URL> parseLocation(@Nullable String locationName, File javaagentFile) {
    // 如果配置的locationName为空，则直接返回空列表
    if (locationName == null) {
      return Collections.emptyList();
    }

    List<URL> result = new ArrayList<>();
    // 将用逗号隔开的locationName拆封开，遍历
    for (String location : locationName.split(",")) {
      parseLocation(location, javaagentFile, result);
    }

    return result;
  }

  private static void parseLocation(String locationName, File javaagentFile, List<URL> locations) {
    // 过滤掉空值
    if (locationName.isEmpty()) {
      return;
    }

    File location = new File(locationName);
    // 判断location是否是一个Jar文件
    if (isJar(location)) {
      // 通过传入jar构建自定义UrlStreamHandler，然后在构建自定义otel协议的URL，将URL添加到locations列表中
      addFileUrl(locations, location);
      // 如果location是一个目录
    } else if (location.isDirectory()) {
      // 遍历过滤出目录中所有的jar文件
      File[] files = location.listFiles(ExtensionClassLoader::isJar);
      // 如果过滤出的jar文件列表不为空
      if (files != null) {
        // 遍历过滤出的jar文件列表不为空
        for (File file : files) {
          // 如果文件的绝对路径与opentelemetry-javaagent-1.31.0.jar的绝对路径不相等，才执行addFileUrl
          if (isJar(file) && !file.getAbsolutePath().equals(javaagentFile.getAbsolutePath())) {
            addFileUrl(locations, file);
          }
        }
      }
    }
  }

  private static boolean isJar(File f) {
    return f.isFile() && f.getName().endsWith(".jar");
  }

  /**
   * 通过传入otel-extensions/*.jar构建自定义UrlStreamHandler，然后在构建自定义otel协议的URL，将URL添加到extensions列表中
   */
  private static void addFileUrl(List<URL> result, File file) {
    try {
      // 自定义了otel协议，通过传入的文件自定义URLStreamHandler
      URL wrappedUrl = new URL("otel", null, -1, "/", new RemappingUrlStreamHandler(file));
      // 将生成的URL添加到result列表中
      result.add(wrappedUrl);
    } catch (MalformedURLException ignored) {
      System.err.println("Ignoring " + file);
    }
  }

  /**
   *  将jarEntry即对应的extensions/*.jar文件内容写入到otel-extensions/*.jar中
   */
  private static void extractFile(JarFile jarFile, JarEntry jarEntry, File outputFile)
      throws IOException {
    try (InputStream in = jarFile.getInputStream(jarEntry);
        ReadableByteChannel rbc = Channels.newChannel(in);
        FileOutputStream fos = new FileOutputStream(outputFile)) {
      fos.getChannel().transferFrom(rbc, 0, Long.MAX_VALUE);
    }
  }

  @Override
  protected PermissionCollection getPermissions(CodeSource codesource) {
    if (isSecurityManagerSupportEnabled) {
      Permissions permissions = new Permissions();
      permissions.add(new AllPermission());
      return permissions;
    }
    return super.getPermissions(codesource);
  }

  private ExtensionClassLoader(URL url, ClassLoader parent, boolean isSecurityManagerSupportEnabled) {
    super(new URL[] {url}, parent);
    this.isSecurityManagerSupportEnabled = isSecurityManagerSupportEnabled;
  }
}
