/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.javaagent.tooling;

import io.opentelemetry.context.Context;
import io.opentelemetry.instrumentation.api.internal.cache.weaklockfree.WeakConcurrentMapCleaner;
import io.opentelemetry.javaagent.bootstrap.AgentInitializer;
import io.opentelemetry.javaagent.bootstrap.AgentStarter;
import io.opentelemetry.javaagent.tooling.config.EarlyInitAgentConfig;
import java.io.File;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.instrument.UnmodifiableClassException;
import java.security.ProtectionDomain;
import java.util.ServiceLoader;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/**
 * Main entry point into code that is running inside agent class loader, used reflectively from
 * {@code io.opentelemetry.javaagent.bootstrap.AgentInitializer}.
 */
public class AgentStarterImpl implements AgentStarter {
  private final Instrumentation instrumentation;
  private final File javaagentFile;
  private final boolean isSecurityManagerSupportEnabled;
  private ClassLoader extensionClassLoader;

  public AgentStarterImpl(Instrumentation instrumentation, File javaagentFile, boolean isSecurityManagerSupportEnabled) {
    this.instrumentation = instrumentation;
    this.javaagentFile = javaagentFile;
    this.isSecurityManagerSupportEnabled = isSecurityManagerSupportEnabled;
  }

  @Override
  public boolean delayStart() {
    // 先对sun.launcher.LauncherHelper做字节码增强，执行完其checkAndLoadMain方法后执行AgentInitializer的delayedStartHook方法
    LaunchHelperClassFileTransformer transformer = new LaunchHelperClassFileTransformer();
    instrumentation.addTransformer(transformer, true);

    try {
      Class<?> clazz = Class.forName("sun.launcher.LauncherHelper", false, null);
      // 如果LaunchHelperClassFileTransformer中的增强已经被执行过了，则不在执行返回true
      if (transformer.transformed) {
        // LauncherHelper was loaded and got transformed
        return transformer.hookInserted;
      }
      // LauncherHelper was already loaded before we set up transformer
      // 如果LaunchHelperClassFileTransformer中的增强未被执行过，则重新加载LauncherHelper执行增强代码
      instrumentation.retransformClasses(clazz);
      return transformer.hookInserted;
    } catch (ClassNotFoundException | UnmodifiableClassException ignore) {
      // ignore
    } finally {
      instrumentation.removeTransformer(transformer);
    }

    return false;
  }

  @Override
  public void start() {
    // 从系统环境变量和用户环境变量中获取otel.javaagent.configuration-file配置的配置文件路径并加载
    EarlyInitAgentConfig earlyConfig = EarlyInitAgentConfig.create();
    // 这里的getClass().getClassLoader()获取到的是AgentClassLoader
    // 将AgentClassLoader作为ExtensionClassLoader的父类加载器，并创建ExtensionClassLoader类加载器
    // 因为使用的是双亲委派模型，所有后面使用的都是extensionClassLoader
    // 所以extensions/目录下的jar会通过ExtensionClassLoader类加载器加载
    // inst/目录下的类会被AgentClassLoader类加载器加载，其他的会被BootstrapClassLoader类加载器加载
    extensionClassLoader = createExtensionClassLoader(getClass().getClassLoader(), earlyConfig);

    // 从earlyConfig中读取配置信息，如果配置了置文件路径，会先从配置文件中读取，如果没有读取到会再读取环境变量和系统变量
    String loggerImplementationName = earlyConfig.getString("otel.javaagent.logging");
    // default to the built-in stderr slf4j-simple logger
    if (loggerImplementationName == null) {
      // 如果配置文件、环境变量、系统变量中均未读取到otel.javaagent.logging对应的配置，则默认设置未simple
      // simplem是指代Slf4jSimpleLoggingCustomizer
      loggerImplementationName = "simple";
    }

    LoggingCustomizer loggingCustomizer = null;
    /*
     *  通过SPI机制加载实现了LoggingCustomizer接口的实现类，这里系统默认有三个实现类
     *    - application：ApplicationLoggingCustomizer
     *    - none：NoopLoggingCustomizer，即使调用日志打印的方法也不会打印任何日志
     *    - simple：Slf4jSimpleLoggingCustomizer（默认情况下是该类）
     */
    for (LoggingCustomizer customizer : ServiceLoader.load(LoggingCustomizer.class, extensionClassLoader)) {
      if (customizer.name().equalsIgnoreCase(loggerImplementationName)) {
        loggingCustomizer = customizer;
        break;
      }
    }
    // unsupported logger implementation; defaulting to noop
    if (loggingCustomizer == null) {
      // 这里是向控制台输出一个err信息
      logUnrecognizedLoggerImplWarning(loggerImplementationName);
      // 如果配置的logging名称，系统中未找到，则返回默认的NoopLoggingCustomizer
      loggingCustomizer = new NoopLoggingCustomizer();
    }

    Throwable startupError = null;
    try {
      // 对日志的初始化
      loggingCustomizer.init(earlyConfig);
      // 如果上面EarlyInitAgentConfig.create()加载配置文件有任何错误，都会在这里被通过日志被打印
      // 之所有前面不直接打印，是因为前面日志还没有被初始化
      earlyConfig.logEarlyConfigErrorsIfAny();
      // 核心逻辑
      AgentInstaller.installBytebuddyAgent(instrumentation, extensionClassLoader, earlyConfig);
      WeakConcurrentMapCleaner.start();

      // LazyStorage reads system properties. Initialize it here where we have permissions to avoid
      // failing permission checks when it is initialized from user code.
      // LazyStorage读取系统属性。在我们有权限的地方初始化它，以避免在从用户代码初始化时权限检查失败
      if (System.getSecurityManager() != null) {
        Context.current();
      }
    } catch (Throwable t) {
      // this is logged below and not rethrown to avoid logging it twice
      startupError = t;
    }
    if (startupError == null) {
      // 成功是不会输出任何信息的
      loggingCustomizer.onStartupSuccess();
    } else {
      // 通过System.err.println输出错误信息，表示Agent启动失败
      loggingCustomizer.onStartupFailure(startupError);
    }
  }

  @SuppressWarnings("SystemOut")
  private static void logUnrecognizedLoggerImplWarning(String loggerImplementationName) {
    System.err.println("Unrecognized value of 'otel.javaagent.logging': '" + loggerImplementationName
            + "'. The agent will use the no-op implementation.");
  }

  @Override
  public ClassLoader getExtensionClassLoader() {
    return extensionClassLoader;
  }

  private ClassLoader createExtensionClassLoader(ClassLoader agentClassLoader, EarlyInitAgentConfig earlyConfig) {
    // 这里传入的agentClassLoader就是AgentClassLoader
    return ExtensionClassLoader.getInstance(agentClassLoader, javaagentFile, isSecurityManagerSupportEnabled, earlyConfig);
  }

  private static class LaunchHelperClassFileTransformer implements ClassFileTransformer {
    boolean hookInserted = false;
    boolean transformed = false;

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
        ProtectionDomain protectionDomain, byte[] classfileBuffer) {
      if (!"sun/launcher/LauncherHelper".equals(className)) {
        return null;
      }
      transformed = true;
      ClassReader cr = new ClassReader(classfileBuffer);
      ClassWriter cw = new ClassWriter(cr, 0);
      // 执行完成sun/launcher/LauncherHelper的checkAndLoadMain方法后执行AgentInitializer的delayedStartHook方法
      ClassVisitor cv = new ClassVisitor(Opcodes.ASM7, cw) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                String signature, String[] exceptions) {
              MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
              if ("checkAndLoadMain".equals(name)) {
                return new MethodVisitor(api, mv) {
                  @Override
                  public void visitCode() {
                    // 获取checkAndLoadMain方法原始的自驾吗
                    super.visitCode();
                    hookInserted = true;
                    // 调用AgentInitializer的delayedStartHook方法实现延迟加载，其实还是调用AgentInitializer的start方法
                    mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                        Type.getInternalName(AgentInitializer.class), "delayedStartHook", "()V",
                        false);
                  }
                };
              }
              return mv;
            }
          };
      cr.accept(cv, 0);
      // 返回最终的字节码
      return hookInserted ? cw.toByteArray() : null;
    }
  }
}
