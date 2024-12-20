/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.javaagent;

import io.opentelemetry.javaagent.bootstrap.AgentInitializer;
import io.opentelemetry.javaagent.bootstrap.InstrumentationHolder;
import io.opentelemetry.javaagent.bootstrap.JavaagentFileHolder;
import java.io.File;
import java.io.IOException;
import java.lang.instrument.Instrumentation;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

/**
 * Premain-Class for the OpenTelemetry Java agent.
 *
 * <p>The bootstrap process of the agent is somewhat complicated and care has to be taken to make
 * sure things do not get broken by accident.
 *
 * <p>JVM loads this class onto app's class loader, afterwards agent needs to inject its classes
 * onto bootstrap classpath. This leads to this class being visible on bootstrap. This in turn means
 * that this class may be loaded again on bootstrap by accident if we ever reference it after
 * bootstrap has been setup.
 *
 * <p>In order to avoid this we need to make sure we do a few things:
 *
 * <ul>
 *   <li>Do as little as possible here
 *   <li>Never reference this class after we have setup bootstrap and jumped over to 'real' agent
 *       code
 *   <li>Do not store any static data in this class
 *   <li>Do dot touch any logging facilities here so we can configure them later
 * </ul>
 */
// Too early for logging
@SuppressWarnings("SystemOut")
public final class OpenTelemetryAgent {

  public static void premain(String agentArgs, Instrumentation inst) {
    startAgent(inst, true);
  }

  public static void agentmain(String agentArgs, Instrumentation inst) {
    startAgent(inst, false);
  }

  private static void startAgent(Instrumentation inst, boolean fromPremain) {
    try {
      // javaagentFile其实就是**/opentelemetry-javaagent-1.31.0.jar，也就是最终的Agent包
      File javaagentFile = installBootstrapJar(inst);
      // 将Instrumentation对象保存到InstrumentationHolder，方便其他地方使用直接获取
      InstrumentationHolder.setInstrumentation(inst);
      // 将opentelemetry-javaagent-1.31.0.jar对应File保存到JavaagentFileHolder，方便其他地方使用直接获取，目前没有被使用
      JavaagentFileHolder.setJavaagentFile(javaagentFile);
      AgentInitializer.initialize(inst, javaagentFile, fromPremain);
    } catch (Throwable ex) {
      // Don't rethrow.  We don't have a log manager here, so just print.
      System.err.println("ERROR " + OpenTelemetryAgent.class.getName());
      ex.printStackTrace();
    }
  }

  private static synchronized File installBootstrapJar(Instrumentation inst)
      throws IOException, URISyntaxException {
    // we are not using OpenTelemetryAgent.class.getProtectionDomain().getCodeSource() to get agent
    // location because getProtectionDomain does a permission check with security manager
    ClassLoader classLoader = OpenTelemetryAgent.class.getClassLoader();
    if (classLoader == null) {
      classLoader = ClassLoader.getSystemClassLoader();
    }
    // file:**/opentelemetry-javaagent-1.31.0.jar!/io/opentelemetry/javaagent/OpenTelemetryAgent.class
    URL url = classLoader.getResource(OpenTelemetryAgent.class.getName().replace('.', '/') + ".class");
    if (url == null || !"jar".equals(url.getProtocol())) {
      throw new IllegalStateException("could not get agent jar location from url " + url);
    }
    // file:**/opentelemetry-javaagent-1.31.0.jar!/io/opentelemetry/javaagent/OpenTelemetryAgent.class
    String resourcePath = url.toURI().getSchemeSpecificPart();
    int protocolSeparatorIndex = resourcePath.indexOf(":");
    int resourceSeparatorIndex = resourcePath.indexOf("!/");
    if (protocolSeparatorIndex == -1 || resourceSeparatorIndex == -1) {
      throw new IllegalStateException("could not get agent location from url " + url);
    }
    // **/opentelemetry-javaagent-1.31.0.jar，就是jar包的绝对路径
    String agentPath = resourcePath.substring(protocolSeparatorIndex + 1, resourceSeparatorIndex);
    File javaagentFile = new File(agentPath);

    if (!javaagentFile.isFile()) {
      throw new IllegalStateException("agent jar location doesn't appear to be a file: " + javaagentFile.getAbsolutePath());
    }

    // verification is very slow before the JIT compiler starts up, which on Java 8 is not until after premain execution completes
    // 这里的agentJar就是opentelemetry-javaagent-1.31.0.jar
    JarFile agentJar = new JarFile(javaagentFile, false);
    // 校验opentelemetry-javaagent-1.31.0.jar中是否有配置Premain-Class
    verifyJarManifestMainClassIsThis(javaagentFile, agentJar);
    // 把打包后的javaagent包整个加入到bootstrap classloader的搜索路径中，javaagent中里面所有的类都可以在bootstrap classloader中被找到
    // 在打包的时候，对于一些可能和用户依赖产生冲突的工具类(比如json处理类)，OTel agent会把这些类编译后的class文件单独挪到一个inst目录下
    // 且inst目录下的所有.class文件都被重命名为.classdata，bootstrap classloader就没法识别这些.classdata文件，也就不会加载inst这个文件夹里面的类
    inst.appendToBootstrapClassLoaderSearch(agentJar);
    return javaagentFile;
  }

  // this protects against the case where someone adds the contents of opentelemetry-javaagent.jar
  // by mistake to their application's "uber.jar"
  //
  // the reason this can cause issues is because we locate the agent jar based on the CodeSource of
  // the OpenTelemetryAgent class, and then we add that jar file to the bootstrap class path
  //
  // but if we find the OpenTelemetryAgent class in an uber jar file, and we add that (whole) uber
  // jar file to the bootstrap class loader, that can cause some applications to break, as there's a
  // lot of application and library code that doesn't handle getClassLoader() returning null
  // (e.g. https://github.com/qos-ch/logback/pull/291)
  private static void verifyJarManifestMainClassIsThis(File jarFile, JarFile agentJar) throws IOException {
    Manifest manifest = agentJar.getManifest();
    if (manifest.getMainAttributes().getValue("Premain-Class") == null) {
      throw new IllegalStateException("The agent was not installed, because the agent was found in '" + jarFile
              + "', which doesn't contain a Premain-Class manifest attribute. Make sure that you"
              + " haven't included the agent jar file inside of an application uber jar.");
    }
  }

  /**
   * Main entry point.
   *
   * @param args command line arguments
   */
  public static void main(String... args) {
    try {
      System.out.println(OpenTelemetryAgent.class.getPackage().getImplementationVersion());
    } catch (RuntimeException e) {
      System.out.println("Failed to parse agent version");
      e.printStackTrace();
    }
  }

  private OpenTelemetryAgent() {}
}
