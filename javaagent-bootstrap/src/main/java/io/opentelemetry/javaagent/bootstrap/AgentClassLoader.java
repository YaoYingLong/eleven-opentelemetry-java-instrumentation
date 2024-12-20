/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.javaagent.bootstrap;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.security.AllPermission;
import java.security.CodeSource;
import java.security.Permission;
import java.security.PermissionCollection;
import java.security.Permissions;
import java.security.cert.Certificate;
import java.util.Enumeration;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import javax.annotation.Nullable;

/**
 * Classloader used to run the core agent.
 *
 * <p>It is built around the concept of a jar inside another jar. This class loader loads the files
 * of the internal jar to load classes and resources.
 */
public class AgentClassLoader extends URLClassLoader {

  // NOTE it's important not to use logging in this class, because this class is used before logging
  // is initialized

  static {
    ClassLoader.registerAsParallelCapable();
  }

  // 默认是空，可以通过JVM参数设置
  private static final String AGENT_INITIALIZER_JAR = System.getProperty("otel.javaagent.experimental.initializer.jar", "");

  private static final String META_INF = "META-INF/";
  private static final String META_INF_VERSIONS = META_INF + "versions/";

  // multi release jars were added in java 9
  private static final int MIN_MULTI_RELEASE_JAR_JAVA_VERSION = 9;
  // current java version  当前JDK版本
  private static final int JAVA_VERSION = getJavaVersion();
  // 当前JDK版本大于等于9
  private static final boolean MULTI_RELEASE_JAR_ENABLE = JAVA_VERSION >= MIN_MULTI_RELEASE_JAR_JAVA_VERSION;

  // Calling java.lang.instrument.Instrumentation#appendToBootstrapClassLoaderSearch
  // adds a jar to the bootstrap class lookup, but not to the resource lookup.
  // As a workaround, we keep a reference to the bootstrap jar
  // to use only for resource lookups.
  // 调用java.lang.instrument.InstrumentationappendToBootstrapClassLoaderSearch会将jar添加到引导程序类查找中
  // 但不会将jar添加到资源查找中。作为解决方法，我们保留对引导jar的引用，仅用于资源查找
  private final BootstrapClassLoaderProxy bootstrapProxy;

  // 就是**/opentelemetry-javaagent-1.31.0.jar
  private final JarFile jarFile;
  private final URL jarBase;
  private final String jarEntryPrefix;
  private final CodeSource codeSource;
  private final boolean isSecurityManagerSupportEnabled;
  private final Manifest manifest;

  // Used by tests
  public AgentClassLoader(File javaagentFile) {
    this(javaagentFile, "", false);
  }

  /**
   * Construct a new AgentClassLoader.
   *
   * @param javaagentFile Used for resource lookups.
   * @param internalJarFileName File name of the internal jar
   * @param isSecurityManagerSupportEnabled Whether this class loader should define classes with all
   *     permissions
   */
  public AgentClassLoader(File javaagentFile, String internalJarFileName, boolean isSecurityManagerSupportEnabled) {
    super(new URL[] {}, getParentClassLoader());
    // javaagentFile就是**/opentelemetry-javaagent-1.31.0.jar
    if (javaagentFile == null) {
      throw new IllegalArgumentException("Agent jar location should be set");
    }
    // 这里传入的internalJarFileName固定为inst
    if (internalJarFileName == null) {
      throw new IllegalArgumentException("Internal jar file name should be set");
    }

    // 默认为false
    this.isSecurityManagerSupportEnabled = isSecurityManagerSupportEnabled;
    bootstrapProxy = new BootstrapClassLoaderProxy(this);
    // jarEntryPrefix默认为“inst/”，其实就是加载inst目录下的类
    jarEntryPrefix = internalJarFileName + (internalJarFileName.isEmpty() || internalJarFileName.endsWith("/") ? "" : "/");
    try {
      jarFile = new JarFile(javaagentFile, false);
      // base url for constructing jar entry urls
      // we use a custom protocol instead of typical jar:file: because we don't want to be affected
      // by user code disabling URLConnection caching for jar protocol e.g. tomcat does this

      // 这里自定义了一个URL的protocol为x-internal-jar，目的是防止用户代码的干扰，目的是做jar包隔离
      jarBase = new URL("x-internal-jar", null, 0, "/", new AgentClassLoaderUrlStreamHandler(jarFile));
      // 用于表示代码来源，可以关联安全信息
      codeSource = new CodeSource(javaagentFile.toURI().toURL(), (Certificate[]) null);
      manifest = jarFile.getManifest();
    } catch (IOException e) {
      throw new IllegalStateException("Unable to open agent jar", e);
    }

    // AGENT_INITIALIZER_JAR默认是空，可以通过JVM参数设置
    if (!AGENT_INITIALIZER_JAR.isEmpty()) {
      URL url;
      try {
        url = new File(AGENT_INITIALIZER_JAR).toURI().toURL();
      } catch (MalformedURLException e) {
        throw new IllegalStateException("Filename could not be parsed: " + AGENT_INITIALIZER_JAR + ". Initializer is not installed", e);
      }
      addURL(url);
    }
  }

  private static ClassLoader getParentClassLoader() {
    // JDK版本大于1.8
    if (JAVA_VERSION > 8) {
      // PlatformDelegatingClassLoader的父类加载器其实也是指定的null，即BootstrapClassLoader
      // JDK1.9及以后的版本中ExtClassLoader更名为PlatFromClassLoader，加载lib/modules目录下的class
      // 这个我理解只是一种规范上的变化，即要求所有Java SE平台上的类都需要保证对PlatFromClassLoader可见，应该是另外一种类型的划分
      return new PlatformDelegatingClassLoader();
    }
    // JDK版本小于等于1.8，这里null其实是表示BootstrapClassLoader
    return null;
  }

  private static int getJavaVersion() {
    String javaSpecVersion = System.getProperty("java.specification.version");
    if ("1.8".equals(javaSpecVersion)) {
      return 8;
    }
    return Integer.parseInt(javaSpecVersion);
  }

  @Override
  public Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
    // ContextStorageOverride is meant for library instrumentation we don't want it to apply to our bundled grpc
    if ("io.grpc.override.ContextStorageOverride".equals(name)) {
      throw new ClassNotFoundException(name);
    }

    synchronized (getClassLoadingLock(name)) {
      // 还是先从父类加载器中去寻找，但是由于inst目录中的类名字都是以classdata结尾所以其实是找不到的
      Class<?> clazz = findLoadedClass(name);
      // first search agent classes
      // 如果没有找到再从inst目录中查找
      if (clazz == null) {
        clazz = findAgentClass(name);
      }
      // search from parent and urls added to this loader
      if (clazz == null) {
        clazz = super.loadClass(name, false);
      }
      if (resolve) {
        resolveClass(clazz);
      }

      return clazz;
    }
  }

  private Class<?> findAgentClass(String name) throws ClassNotFoundException {
    // 这里是加载inst/目录下的类
    JarEntry jarEntry = findJarEntry(name.replace('.', '/') + ".class");
    if (jarEntry != null) {
      byte[] bytes;
      try {
        bytes = getJarEntryBytes(jarEntry);
      } catch (IOException exception) {
        throw new ClassNotFoundException(name, exception);
      }

      definePackageIfNeeded(name);
      return defineClass(name, bytes);
    }

    return null;
  }

  public Class<?> defineClass(String name, byte[] bytes) {
    return defineClass(name, bytes, 0, bytes.length, codeSource);
  }

  @Override
  protected PermissionCollection getPermissions(CodeSource codeSource) {
    if (isSecurityManagerSupportEnabled) {
      Permissions permissions = new Permissions();
      permissions.add(new AllPermission());
      return permissions;
    }

    return super.getPermissions(codeSource);
  }

  private byte[] getJarEntryBytes(JarEntry jarEntry) throws IOException {
    int size = (int) jarEntry.getSize();
    byte[] buffer = new byte[size];
    try (InputStream is = jarFile.getInputStream(jarEntry)) {
      int offset = 0;
      int read;

      while (offset < size && (read = is.read(buffer, offset, size - offset)) != -1) {
        offset += read;
      }
    }

    return buffer;
  }

  private void definePackageIfNeeded(String className) {
    String packageName = getPackageName(className);
    if (packageName == null) {
      return;
    }
    if (getPackage(packageName) == null) {
      try {
        definePackage(packageName, manifest, codeSource.getLocation());
      } catch (IllegalArgumentException exception) {
        if (getPackage(packageName) == null) {
          throw new IllegalStateException("Failed to define package", exception);
        }
      }
    }
  }

  private static String getPackageName(String className) {
    int index = className.lastIndexOf('.');
    return index == -1 ? null : className.substring(0, index);
  }

  private JarEntry findJarEntry(String name) {
    // shading renames .class to .classdata
    boolean isClass = name.endsWith(".class");
    // 将.class后缀修改为.classdata后缀
    if (isClass) {
      name += getClassSuffix();
    }
    // 加载inst目录下的类
    JarEntry jarEntry = jarFile.getJarEntry(jarEntryPrefix + name);
    // JDK版本大于等于9
    if (MULTI_RELEASE_JAR_ENABLE) {
      jarEntry = findVersionedJarEntry(jarEntry, name);
    }
    return jarEntry;
  }

  // suffix appended to class resource names
  // this is in a protected method so that unit tests could override it
  protected String getClassSuffix() {
    return "data";
  }

  private JarEntry findVersionedJarEntry(JarEntry jarEntry, String name) {
    // same logic as in JarFile.getVersionedEntry
    if (!name.startsWith(META_INF)) {
      // search for versioned entry by looping over possible versions form high to low
      int version = JAVA_VERSION;
      while (version >= MIN_MULTI_RELEASE_JAR_JAVA_VERSION) {
        JarEntry versionedJarEntry = jarFile.getJarEntry(jarEntryPrefix + META_INF_VERSIONS + version + "/" + name);
        if (versionedJarEntry != null) {
          return versionedJarEntry;
        }
        version--;
      }
    }

    return jarEntry;
  }

  @Override
  public URL getResource(String resourceName) {
    URL bootstrapResource = bootstrapProxy.getResource(resourceName);
    if (null == bootstrapResource) {
      return super.getResource(resourceName);
    } else {
      return bootstrapResource;
    }
  }

  @Override
  public URL findResource(String name) {
    URL url = findJarResource(name);
    if (url != null) {
      return url;
    }

    // find resource from agent initializer jar
    return super.findResource(name);
  }

  private URL findJarResource(String name) {
    JarEntry jarEntry = findJarEntry(name);
    return getJarEntryUrl(jarEntry);
  }

  private URL getJarEntryUrl(JarEntry jarEntry) {
    if (jarEntry != null) {
      try {
        return new URL(jarBase, jarEntry.getName());
      } catch (MalformedURLException e) {
        throw new IllegalStateException(
            "Failed to construct url for jar entry " + jarEntry.getName(), e);
      }
    }

    return null;
  }

  @Override
  public Enumeration<URL> findResources(String name) throws IOException {
    // find resources from agent initializer jar
    Enumeration<URL> delegate = super.findResources(name);
    // agent jar can have only one resource for given name
    URL url = findJarResource(name);
    if (url != null) {
      return new Enumeration<URL>() {
        boolean first = true;

        @Override
        public boolean hasMoreElements() {
          return first || delegate.hasMoreElements();
        }

        @Override
        public URL nextElement() {
          if (first) {
            first = false;
            return url;
          }
          return delegate.nextElement();
        }
      };
    }

    return delegate;
  }

  public BootstrapClassLoaderProxy getBootstrapProxy() {
    return bootstrapProxy;
  }

  /**
   * A stand-in for the bootstrap class loader. Used to look up bootstrap resources and resources
   * appended by instrumentation.
   *
   * <p>This class is thread safe.
   */
  public static final class BootstrapClassLoaderProxy extends ClassLoader {
    private final AgentClassLoader agentClassLoader;

    static {
      ClassLoader.registerAsParallelCapable();
    }

    public BootstrapClassLoaderProxy(AgentClassLoader agentClassLoader) {
      super(null);
      this.agentClassLoader = agentClassLoader;
    }

    @Override
    public URL getResource(String resourceName) {
      // find resource from boot loader
      URL url = super.getResource(resourceName);
      if (url != null) {
        return url;
      }
      // find from agent jar
      if (agentClassLoader != null) {
        JarEntry jarEntry = agentClassLoader.jarFile.getJarEntry(resourceName);
        return agentClassLoader.getJarEntryUrl(jarEntry);
      }
      return null;
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
      throw new ClassNotFoundException(name);
    }
  }

  /**
   * 用于打开到指定协议的连接
   */
  private static class AgentClassLoaderUrlStreamHandler extends URLStreamHandler {
    private final JarFile jarFile;

    AgentClassLoaderUrlStreamHandler(JarFile jarFile) {
      this.jarFile = jarFile;
    }

    @Override
    protected URLConnection openConnection(URL url) {
      return new AgentClassLoaderUrlConnection(url, jarFile);
    }
  }

  private static class AgentClassLoaderUrlConnection extends URLConnection {
    private final JarFile jarFile;
    @Nullable private final String entryName;
    @Nullable private JarEntry jarEntry;

    AgentClassLoaderUrlConnection(URL url, JarFile jarFile) {
      super(url);
      this.jarFile = jarFile;
      String path = url.getFile();
      // 截掉path开头的/
      if (path.startsWith("/")) {
        path = path.substring(1);
      }
      if (path.isEmpty()) {
        path = null;
      }
      this.entryName = path;
    }

    @Override
    public void connect() throws IOException {
      if (!connected) {
        if (entryName != null) {
          jarEntry = jarFile.getJarEntry(entryName);
          if (jarEntry == null) {
            throw new FileNotFoundException("JAR entry " + entryName + " not found in " + jarFile.getName());
          }
        }
        connected = true;
      }
    }

    @Override
    public InputStream getInputStream() throws IOException {
      connect();

      if (entryName == null) {
        throw new IOException("no entry name specified");
      } else {
        if (jarEntry == null) {
          throw new FileNotFoundException("JAR entry " + entryName + " not found in " + jarFile.getName());
        }
        return jarFile.getInputStream(jarEntry);
      }
    }

    @Override
    public Permission getPermission() {
      return null;
    }

    @Override
    public long getContentLengthLong() {
      try {
        connect();

        if (jarEntry != null) {
          return jarEntry.getSize();
        }
      } catch (IOException ignored) {
        // Ignore
      }
      return -1;
    }
  }

  // We don't always delegate to platform loader because platform class loader also contains user
  // classes when running a modular application. We don't want these classes interfering with the agent.
  // 我们并不总是委托给平台loader，因为在运行模块化应用程序时，platform class loader也包含用户类。我们不希望这些类干扰agent。
  private static class PlatformDelegatingClassLoader extends ClassLoader {

    static {
      // this class loader doesn't load any classes, so this is technically unnecessary,
      // but included for safety, just in case we every change Class.forName() below back to super.loadClass()
      // 这个class loader不会加载任何class，所以这在技术上是不必要的，但为了安全起见，以防万一我们每次将下面的Class.forName()都改回super.loadClass()
      registerAsParallelCapable();
    }

    private final ClassLoader platformClassLoader = getPlatformLoader();

    public PlatformDelegatingClassLoader() {
      super(null);
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
      // prometheus exporter uses jdk http server, load it from the platform class loader
      // some custom extensions use java.sql classes, make these available to agent and extensions
      if (name != null && (name.startsWith("com.sun.net.httpserver.") || name.startsWith("java.sql."))) {
        return platformClassLoader.loadClass(name);
      }
      return Class.forName(name, false, null);
    }

    private static ClassLoader getPlatformLoader() {
      /*
       Must invoke ClassLoader.getPlatformClassLoader by reflection to remain
       compatible with java 8.
      */
      try {
        Method method = ClassLoader.class.getDeclaredMethod("getPlatformClassLoader");
        return (ClassLoader) method.invoke(null);
      } catch (InvocationTargetException
          | NoSuchMethodException
          | IllegalAccessException exception) {
        throw new IllegalStateException(exception);
      }
    }
  }
}
