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

  public AgentStarterImpl(
      Instrumentation instrumentation,
      File javaagentFile,
      boolean isSecurityManagerSupportEnabled) {
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
    extensionClassLoader = createExtensionClassLoader(getClass().getClassLoader(), earlyConfig);

    String loggerImplementationName = earlyConfig.getString("otel.javaagent.logging");
    // default to the built-in stderr slf4j-simple logger
    if (loggerImplementationName == null) {
      loggerImplementationName = "simple";
    }

    LoggingCustomizer loggingCustomizer = null;
    for (LoggingCustomizer customizer : ServiceLoader.load(LoggingCustomizer.class, extensionClassLoader)) {
      if (customizer.name().equalsIgnoreCase(loggerImplementationName)) {
        loggingCustomizer = customizer;
        break;
      }
    }
    // unsupported logger implementation; defaulting to noop
    if (loggingCustomizer == null) {
      logUnrecognizedLoggerImplWarning(loggerImplementationName);
      loggingCustomizer = new NoopLoggingCustomizer();
    }

    Throwable startupError = null;
    try {
      loggingCustomizer.init(earlyConfig);
      earlyConfig.logEarlyConfigErrorsIfAny();
      // 核心逻辑
      AgentInstaller.installBytebuddyAgent(instrumentation, extensionClassLoader, earlyConfig);
      WeakConcurrentMapCleaner.start();

      // LazyStorage reads system properties. Initialize it here where we have permissions to avoid
      // failing permission checks when it is initialized from user code.
      if (System.getSecurityManager() != null) {
        Context.current();
      }
    } catch (Throwable t) {
      // this is logged below and not rethrown to avoid logging it twice
      startupError = t;
    }
    if (startupError == null) {
      loggingCustomizer.onStartupSuccess();
    } else {
      loggingCustomizer.onStartupFailure(startupError);
    }
  }

  @SuppressWarnings("SystemOut")
  private static void logUnrecognizedLoggerImplWarning(String loggerImplementationName) {
    System.err.println(
        "Unrecognized value of 'otel.javaagent.logging': '" + loggerImplementationName
            + "'. The agent will use the no-op implementation.");
  }

  @Override
  public ClassLoader getExtensionClassLoader() {
    return extensionClassLoader;
  }

  private ClassLoader createExtensionClassLoader(ClassLoader agentClassLoader, EarlyInitAgentConfig earlyConfig) {
    System.out.println("agentClassLoader: " + agentClassLoader);
    System.out.println("earlyConfig: " + earlyConfig);
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
