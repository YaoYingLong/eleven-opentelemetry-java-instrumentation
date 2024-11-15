/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.javaagent.tooling;

import static io.opentelemetry.javaagent.tooling.OpenTelemetryInstaller.installOpenTelemetrySdk;
import static io.opentelemetry.javaagent.tooling.SafeServiceLoader.load;
import static io.opentelemetry.javaagent.tooling.SafeServiceLoader.loadOrdered;
import static io.opentelemetry.javaagent.tooling.Utils.getResourceName;
import static java.util.Arrays.asList;
import static java.util.logging.Level.FINE;
import static java.util.logging.Level.SEVERE;
import static net.bytebuddy.matcher.ElementMatchers.any;

import io.opentelemetry.context.Context;
import io.opentelemetry.context.ContextStorage;
import io.opentelemetry.context.Scope;
import io.opentelemetry.instrumentation.api.internal.EmbeddedInstrumentationProperties;
import io.opentelemetry.javaagent.bootstrap.AgentClassLoader;
import io.opentelemetry.javaagent.bootstrap.BootstrapPackagePrefixesHolder;
import io.opentelemetry.javaagent.bootstrap.ClassFileTransformerHolder;
import io.opentelemetry.javaagent.bootstrap.DefineClassHelper;
import io.opentelemetry.javaagent.bootstrap.InstrumentedTaskClasses;
import io.opentelemetry.javaagent.bootstrap.http.HttpServerResponseCustomizer;
import io.opentelemetry.javaagent.bootstrap.http.HttpServerResponseCustomizerHolder;
import io.opentelemetry.javaagent.bootstrap.http.HttpServerResponseMutator;
import io.opentelemetry.javaagent.bootstrap.internal.InstrumentationConfig;
import io.opentelemetry.javaagent.extension.AgentListener;
import io.opentelemetry.javaagent.extension.ignore.IgnoredTypesConfigurer;
import io.opentelemetry.javaagent.tooling.asyncannotationsupport.WeakRefAsyncOperationEndStrategies;
import io.opentelemetry.javaagent.tooling.bootstrap.BootstrapPackagesBuilderImpl;
import io.opentelemetry.javaagent.tooling.bootstrap.BootstrapPackagesConfigurer;
import io.opentelemetry.javaagent.tooling.config.AgentConfig;
import io.opentelemetry.javaagent.tooling.config.ConfigPropertiesBridge;
import io.opentelemetry.javaagent.tooling.config.EarlyInitAgentConfig;
import io.opentelemetry.javaagent.tooling.ignore.IgnoredClassLoadersMatcher;
import io.opentelemetry.javaagent.tooling.ignore.IgnoredTypesBuilderImpl;
import io.opentelemetry.javaagent.tooling.ignore.IgnoredTypesMatcher;
import io.opentelemetry.javaagent.tooling.muzzle.AgentTooling;
import io.opentelemetry.javaagent.tooling.util.Trie;
import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdk;
import io.opentelemetry.sdk.autoconfigure.internal.AutoConfigureUtil;
import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties;
import java.lang.instrument.Instrumentation;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.LogManager;
import java.util.logging.Logger;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import javax.annotation.Nullable;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.agent.builder.AgentBuilderUtil;
import net.bytebuddy.agent.builder.ResettableClassFileTransformer;
import net.bytebuddy.description.type.TypeDefinition;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.dynamic.VisibilityBridgeStrategy;
import net.bytebuddy.dynamic.scaffold.InstrumentedType;
import net.bytebuddy.dynamic.scaffold.MethodGraph;
import net.bytebuddy.utility.JavaModule;

public class AgentInstaller {

  private static final Logger logger = Logger.getLogger(AgentInstaller.class.getName());

  static final String JAVAAGENT_ENABLED_CONFIG = "otel.javaagent.enabled";

  // This property may be set to force synchronous AgentListener#afterAgent() execution: the
  // condition for delaying the AgentListener initialization is pretty broad and in case it covers
  // too much javaagent users can file a bug, force sync execution by setting this property to true
  // and continue using the javaagent
  private static final String FORCE_SYNCHRONOUS_AGENT_LISTENERS_CONFIG = "otel.javaagent.experimental.force-synchronous-agent-listeners";

  private static final String STRICT_CONTEXT_STRESSOR_MILLIS = "otel.javaagent.testing.strict-context-stressor-millis";

  private static final Map<String, List<Runnable>> CLASS_LOAD_CALLBACKS = new HashMap<>();

  public static void installBytebuddyAgent(Instrumentation inst, ClassLoader extensionClassLoader,
      EarlyInitAgentConfig earlyConfig) {
    addByteBuddyRawSetting();

    Integer strictContextStressorMillis = Integer.getInteger(STRICT_CONTEXT_STRESSOR_MILLIS);
    if (strictContextStressorMillis != null) {
      io.opentelemetry.context.ContextStorage.addWrapper(
          storage -> new StrictContextStressor(storage, strictContextStressorMillis));
    }
    // 打印版本相关的日志
    logVersionInfo();
    // 可以通过otel.javaagent.enabled配置不使用OTel
    if (earlyConfig.getBoolean(JAVAAGENT_ENABLED_CONFIG, true)) {
      // 提供一种机制来初始化某些需要使用sun.misc.Unsafe类的功能
      setupUnsafe(inst);
      // 通过SPI机制加载实现了AgentListener接口的类，且这些类是通过AgentClassLoader来加载，这里仅加载不执行
      List<AgentListener> agentListeners = loadOrdered(AgentListener.class, extensionClassLoader);
      // 构建全局的OpenTelemetry对象，执行InstrumentationLoader的extend方法，通过SPI机制加载所有的InstrumentationModule
      installBytebuddyAgent(inst, extensionClassLoader, agentListeners);
    } else {
      logger.fine("Tracing is disabled, not installing instrumentations.");
    }
  }

  private static void installBytebuddyAgent(Instrumentation inst, ClassLoader extensionClassLoader,
      Iterable<AgentListener> agentListeners) {

    WeakRefAsyncOperationEndStrategies.initialize();

    EmbeddedInstrumentationProperties.setPropertiesLoader(extensionClassLoader);

    setDefineClassHandler();

    // If noop OpenTelemetry is enabled, autoConfiguredSdk will be null and AgentListeners are not called
    /*
     * 通过调用OpenTelemetryInstaller的installOpenTelemetrySdk方法，最中通过AutoConfiguredOpenTelemetrySdk的build OpenTelemetry对象
     */
    AutoConfiguredOpenTelemetrySdk autoConfiguredSdk = installOpenTelemetrySdk(extensionClassLoader);

    // 通过反射的方式调用AutoConfiguredOpenTelemetrySdk的getConfig方法获取OTel系统配置
    ConfigProperties sdkConfig = AutoConfigureUtil.getConfig(autoConfiguredSdk);
    // 这个地方仅仅就是将sdkConfig包装了一下到InstrumentationConfig
    InstrumentationConfig.internalInitializeConfig(new ConfigPropertiesBridge(sdkConfig));
    // 从sdkConfig中将以下三个配置设置到环境变量中
    // - otel.instrumentation.experimental.span-suppression-strategy、
    // - otel.instrumentation.http.prefer-forwarded-url-scheme
    // - otel.semconv-stability.opt-in
    copyNecessaryConfigToSystemProperties(sdkConfig);

    setBootstrapPackages(sdkConfig, extensionClassLoader);
    // 这个地方是一个扩展点，
    for (BeforeAgentListener agentListener : loadOrdered(BeforeAgentListener.class, extensionClassLoader)) {
      agentListener.beforeAgent(autoConfiguredSdk);
    }

    AgentBuilder agentBuilder = new AgentBuilder.Default(
        // default method graph compiler inspects the class hierarchy, we don't need it, so
        // we use a simpler and faster strategy instead
        // 默认方法Graph编译器检查类层次结构，我们不需要它因此我们使用更简单、更快速的策略
        new ByteBuddy()
            // MethodGraph是一个表示方法调用的图结构，它可以帮助开发者更好地理解和分析类的结构
            // Compiler部分则负责将这个图编译成实际的字节码，使得开发者可以动态地修改和增强Java程序的行为‌
            // 用于处理类的直接方法，不包括继承的方法
            .with(MethodGraph.Compiler.ForDeclaredMethods.INSTANCE)  // bytebuddy的默认编译器
            // 作用是阻止构造函数调用父类的构造函数
            .with(VisibilityBridgeStrategy.Default.NEVER)
            // 作用‌是用于创建一个不可变的类定义
            .with(InstrumentedType.Factory.Default.FROZEN))
        // 在类加载时，当ByteBuddy检测到一个类需要被增强时，它会使用这个类来代理原始类，从而在不修改原始代码的情况下增加新的行为
        .with(AgentBuilder.TypeStrategy.Default.DECORATE)
        // 禁止在字节码转换过程中对类的结构进行某些更改，保持类结构的稳定
        .disableClassFormatChanges()
        // 允许在运行时重新转换已经加载的类。这意味着即使类已经被加载到JVM中，代理仍然可以修改这些类的字节码，而不需要卸载和重新加载类
        .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
        // 为类的重新定义或重新转换过程提供一种机制，以便在运行时动态发现和选择需要处理的类。
        // 通过自定义类发现策略，可以更精确地控制哪些类应该被代理，从而优化性能和资源使用。
        // 如果类加载器是AgentClassLoader或ExtensionClassLoader时被过滤掉
        .with(new RedefinitionDiscoveryStrategy())
        // 主要是指在描述类时，仅使用类文件池（Class File Pool）来获取类的元数据，而不是通过反射或其他方式
        .with(AgentBuilder.DescriptionStrategy.Default.POOL_ONLY)
        // 用于定义类文件池（Class File Pool）的策略
        .with(AgentTooling.poolStrategy())
        // 监听器，用于类加载完成后执行
        .with(new ClassLoadListener())
        .with(AgentTooling.transformListener())
        .with(AgentTooling.locationStrategy());
    // 如果JDK版大于5小于等于9
    if (JavaModule.isSupported()) {
      agentBuilder = agentBuilder.with(new ExposeAgentBootstrapListener(inst));
    }

    // 这里配置一些忽略的类或者类加载器的前缀
    agentBuilder = configureIgnoredTypes(sdkConfig, extensionClassLoader, agentBuilder);

    // 判断otel.javaagent.debug配置是否为true，默认是false
    if (AgentConfig.isDebugModeEnabled(sdkConfig)) {
      agentBuilder = agentBuilder.with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
          .with(new RedefinitionDiscoveryStrategy())
          .with(new RedefinitionLoggingListener())
          .with(new TransformLoggingListener());
    }

    int numberOfLoadedExtensions = 0;
    // 这里加载了InstrumentationLoader
    for (AgentExtension agentExtension : loadOrdered(AgentExtension.class, extensionClassLoader)) {
      if (logger.isLoggable(FINE)) {
        logger.log(FINE, "Loading extension {0} [class {1}]",
            new Object[] {agentExtension.extensionName(), agentExtension.getClass().getName()});
      }
      try {
        // 执行InstrumentationLoader的extend方法，通过SPI机制加载所有的InstrumentationModule
        agentBuilder = agentExtension.extend(agentBuilder, sdkConfig);
        numberOfLoadedExtensions++;
      } catch (Exception | LinkageError e) {
        logger.log(SEVERE, "Unable to load extension " + agentExtension.extensionName() + " [class "
            + agentExtension.getClass().getName() + "]", e);
      }
    }
    logger.log(FINE, "Installed {0} extension(s)", numberOfLoadedExtensions);

    agentBuilder = AgentBuilderUtil.optimize(agentBuilder);
    // 这里将真正将构建的代理配置应用到目标JVM中，使其能够对类进行拦截、修改或增强
    ResettableClassFileTransformer resettableClassFileTransformer = agentBuilder.installOn(inst);
    // 这里resettableClassFileTransformer设置到一个全局的Holder，方便在其他地方可以获取使用
    ClassFileTransformerHolder.setClassFileTransformer(resettableClassFileTransformer);

    // 通过SPI机制加载所有的实现了HttpServerResponseCustomizer接口的类，并将这些实现类列表封装到了一个新的HttpServerResponseCustomizer
    // 且其customize就是遍历执行所有实现类的customize访法，并设置到HttpServerResponseCustomizerHolder中，在具体的HTTP的组件中获取并执行
    addHttpServerResponseCustomizers(extensionClassLoader);
    // 最后才真正执行通过SPI加载的AgentListener实现类，作用大多是用来创建Meter来获取指标
    runAfterAgentListeners(agentListeners, autoConfiguredSdk);
  }

  private static void copyNecessaryConfigToSystemProperties(ConfigProperties config) {
    for (String property : asList("otel.instrumentation.experimental.span-suppression-strategy",
        "otel.instrumentation.http.prefer-forwarded-url-scheme",
        "otel.semconv-stability.opt-in")) {
      String value = config.getString(property);
      if (value != null) {
        System.setProperty(property, value);
      }
    }
  }

  private static void setupUnsafe(Instrumentation inst) {
    try {
      UnsafeInitializer.initialize(inst, AgentInstaller.class.getClassLoader());
    } catch (UnsupportedClassVersionError exception) {
      // ignore
    }
  }

  private static void setBootstrapPackages(ConfigProperties config,
      ClassLoader extensionClassLoader) {
    BootstrapPackagesBuilderImpl builder = new BootstrapPackagesBuilderImpl();
    for (BootstrapPackagesConfigurer configurer : load(BootstrapPackagesConfigurer.class,
        extensionClassLoader)) {
      configurer.configure(builder, config);
    }
    BootstrapPackagePrefixesHolder.setBoostrapPackagePrefixes(builder.build());
  }

  private static void setDefineClassHandler() {
    DefineClassHelper.internalSetHandler(DefineClassHandler.INSTANCE);
  }

  private static AgentBuilder configureIgnoredTypes(ConfigProperties config,
      ClassLoader extensionClassLoader, AgentBuilder agentBuilder) {
    IgnoredTypesBuilderImpl builder = new IgnoredTypesBuilderImpl();
    for (IgnoredTypesConfigurer configurer : loadOrdered(IgnoredTypesConfigurer.class,
        extensionClassLoader)) {
      configurer.configure(builder, config);
    }

    Trie<Boolean> ignoredTasksTrie = builder.buildIgnoredTasksTrie();
    InstrumentedTaskClasses.setIgnoredTaskClassesPredicate(ignoredTasksTrie::contains);

    return agentBuilder
        .ignore(any(), new IgnoredClassLoadersMatcher(builder.buildIgnoredClassLoadersTrie()))
        .or(new IgnoredTypesMatcher(builder.buildIgnoredTypesTrie()))
        .or((typeDescription, classLoader, module, classBeingRedefined, protectionDomain) -> {
          return HelperInjector.isInjectedClass(classLoader, typeDescription.getName());
        });
  }

  private static void addHttpServerResponseCustomizers(ClassLoader extensionClassLoader) {
    // 通过SPI机制加载所有的实现了HttpServerResponseCustomizer接口的类
    List<HttpServerResponseCustomizer> customizers = load(HttpServerResponseCustomizer.class, extensionClassLoader);

    // 将通过SPI机制获取到的HttpServerResponseCustomizer接口实现类列表再次封装成一个统一的HttpServerResponseCustomizer
    HttpServerResponseCustomizerHolder.setCustomizer(new HttpServerResponseCustomizer() {
          @Override
          public <T> void customize(Context serverContext, T response, HttpServerResponseMutator<T> responseMutator) {
            // 遍历执行具体的HttpServerResponseCustomizer的customize访法
            for (HttpServerResponseCustomizer modifier : customizers) {
              modifier.customize(serverContext, response, responseMutator);
            }
          }
        });
  }

  private static void runAfterAgentListeners(Iterable<AgentListener> agentListeners, AutoConfiguredOpenTelemetrySdk autoConfiguredSdk) {
    // java.util.logging.LogManager maintains a final static LogManager, which is created during
    // class initialization. Some AgentListener implementations may use JRE bootstrap classes
    // which touch this class (e.g. JFR classes or some MBeans).
    // It is worth noting that starting from Java 9 (JEP 264) Java platform classes no longer use
    // JUL directly, but instead they use a new System.Logger interface, so the LogManager issue
    // applies mainly to Java 8.
    // This means applications which require a custom LogManager may not have a chance to set the
    // global LogManager if one of those AgentListeners runs first: it will incorrectly
    // set the global LogManager to the default JVM one in cases where the instrumented application
    // sets the LogManager system property or when the custom LogManager class is not on the system
    // classpath.
    // Our solution is to delay the initialization of AgentListeners when we detect a custom
    // log manager being used.
    // Once we see the LogManager class loading, it's safe to run AgentListener#afterAgent() because
    // the application is already setting the global LogManager and AgentListener won't be able
    // to touch it due to class loader locking.
    boolean shouldForceSynchronousAgentListenersCalls = AutoConfigureUtil.getConfig(autoConfiguredSdk)
        .getBoolean(FORCE_SYNCHRONOUS_AGENT_LISTENERS_CONFIG, false);
    boolean javaBefore9 = isJavaBefore9();
    if (!shouldForceSynchronousAgentListenersCalls && javaBefore9 && isAppUsingCustomLogManager()) {
      logger.fine("Custom JUL LogManager detected: delaying AgentListener#afterAgent() calls");
      registerClassLoadCallback(
          "java.util.logging.LogManager",
          new DelayedAfterAgentCallback(agentListeners, autoConfiguredSdk));
    } else {
      if (javaBefore9) {
        // force LogManager to be initialized while we are single-threaded, because if we wait,
        // LogManager initialization can cause a deadlock in Java 8 if done by two different threads
        LogManager.getLogManager();
      }
      for (AgentListener agentListener : agentListeners) {
        agentListener.afterAgent(autoConfiguredSdk);
      }
    }
  }

  private static boolean isJavaBefore9() {
    return System.getProperty("java.version").startsWith("1.");
  }

  private static void addByteBuddyRawSetting() {
    // 系统环境变量中读取net.bytebuddy.raw配置
    String savedPropertyValue = System.getProperty(TypeDefinition.RAW_TYPES_PROPERTY);
    try {
      // 设置net.bytebuddy.raw为true配置
      System.setProperty(TypeDefinition.RAW_TYPES_PROPERTY, "true");
      boolean rawTypes = TypeDescription.AbstractBase.RAW_TYPES;
      if (!rawTypes) {
        logger.log(FINE, "Too late to enable {0}", TypeDefinition.RAW_TYPES_PROPERTY);
      }
    } finally {
      if (savedPropertyValue == null) {
        System.clearProperty(TypeDefinition.RAW_TYPES_PROPERTY);
      } else {
        System.setProperty(TypeDefinition.RAW_TYPES_PROPERTY, savedPropertyValue);
      }
    }
  }

  static class RedefinitionLoggingListener implements AgentBuilder.RedefinitionStrategy.Listener {

    private static final Logger logger = Logger.getLogger(
        RedefinitionLoggingListener.class.getName());

    @Override
    public void onBatch(int index, List<Class<?>> batch, List<Class<?>> types) {}

    @Override
    public Iterable<? extends List<Class<?>>> onError(
        int index, List<Class<?>> batch, Throwable throwable, List<Class<?>> types) {
      if (logger.isLoggable(FINE)) {
        logger.log(
            FINE,
            "Exception while retransforming " + batch.size() + " classes: " + batch,
            throwable);
      }
      return Collections.emptyList();
    }

    @Override
    public void onComplete(
        int amount, List<Class<?>> types, Map<List<Class<?>>, Throwable> failures) {}
  }

  static class TransformLoggingListener extends AgentBuilder.Listener.Adapter {

    private static final TransformSafeLogger logger = TransformSafeLogger.getLogger(
        TransformLoggingListener.class);

    @Override
    public void onError(String typeName, ClassLoader classLoader, JavaModule module, boolean loaded,
        Throwable throwable) {
      if (logger.isLoggable(FINE)) {
        logger.log(FINE, "Failed to handle {0} for transformation on class loader {1}",
            new Object[] {typeName, classLoader}, throwable);
      }
    }

    @Override
    public void onTransformation(TypeDescription typeDescription, ClassLoader classLoader,
        JavaModule module, boolean loaded, DynamicType dynamicType) {
      if (logger.isLoggable(FINE)) {
        logger.log(FINE, "Transformed {0} -- {1}",
            new Object[] {typeDescription.getName(), classLoader});
      }
    }
  }

  /**
   * Register a callback to run when a class is loading.
   *
   * <p>Caveats:
   *
   * <ul>
   *   <li>This callback will be invoked by a jvm class transformer.
   *   <li>Classes filtered out by {@link AgentInstaller}'s skip list will not be matched.
   * </ul>
   *
   * @param className name of the class to match against
   * @param callback runnable to invoke when class name matches
   */
  public static void registerClassLoadCallback(String className, Runnable callback) {
    synchronized (CLASS_LOAD_CALLBACKS) {
      List<Runnable> callbacks = CLASS_LOAD_CALLBACKS.computeIfAbsent(className, k -> new ArrayList<>());
      callbacks.add(callback);
    }
  }

  private static class DelayedAfterAgentCallback implements Runnable {

    private final Iterable<AgentListener> agentListeners;
    private final AutoConfiguredOpenTelemetrySdk autoConfiguredSdk;

    private DelayedAfterAgentCallback(Iterable<AgentListener> agentListeners, AutoConfiguredOpenTelemetrySdk autoConfiguredSdk) {
      this.agentListeners = agentListeners;
      this.autoConfiguredSdk = autoConfiguredSdk;
    }

    @Override
    public void run() {
      /*
       * This callback is called from within bytecode transformer. This can be a problem if callback tries
       * to load classes being transformed. To avoid this we start a thread here that calls the callback.
       * This seems to resolve this problem.
       */
      Thread thread = new Thread(this::runAgentListeners);
      thread.setName("delayed-agent-listeners");
      thread.setDaemon(true);
      thread.start();
    }

    private void runAgentListeners() {
      for (AgentListener agentListener : agentListeners) {
        try {
          agentListener.afterAgent(autoConfiguredSdk);
        } catch (RuntimeException e) {
          logger.log(SEVERE, "Failed to execute " + agentListener.getClass().getName(), e);
        }
      }
    }
  }

  private static class ClassLoadListener extends AgentBuilder.Listener.Adapter {
    @Override
    public void onComplete(String typeName, ClassLoader classLoader, JavaModule javaModule, boolean b) {
      synchronized (CLASS_LOAD_CALLBACKS) {
        List<Runnable> callbacks = CLASS_LOAD_CALLBACKS.get(typeName);
        if (callbacks != null) {
          for (Runnable callback : callbacks) {
            callback.run();
          }
        }
      }
    }
  }

  private static class RedefinitionDiscoveryStrategy implements AgentBuilder.RedefinitionStrategy.DiscoveryStrategy {
    private static final AgentBuilder.RedefinitionStrategy.DiscoveryStrategy delegate =
        AgentBuilder.RedefinitionStrategy.DiscoveryStrategy.Reiterating.INSTANCE;

    @Override
    public Iterable<Iterable<Class<?>>> resolve(Instrumentation instrumentation) {
      // filter out our agent classes and injected helper classes
      return () -> streamOf(delegate.resolve(instrumentation))
          .map(RedefinitionDiscoveryStrategy::filterClasses)
          .iterator();
    }

    private static Iterable<Class<?>> filterClasses(Iterable<Class<?>> classes) {
      // 过滤掉类加载器AgentClassLoader或ExtensionClassLoader的类，以及生成的ByteBuddy帮助程序类
      return () -> streamOf(classes).filter(c -> !isIgnored(c)).iterator();
    }

    private static <T> Stream<T> streamOf(Iterable<T> iterable) {
      return StreamSupport.stream(iterable.spliterator(), false);
    }

    private static boolean isIgnored(Class<?> c) {
      ClassLoader cl = c.getClassLoader();
      // 如果类加载器是AgentClassLoader或ExtensionClassLoader时返回ture，即被过滤掉
      if (cl instanceof AgentClassLoader || cl instanceof ExtensionClassLoader) {
        return true;
      }
      // ignore generate byte buddy helper class  若是生成的ByteBuddy帮助程序类返回ture，即被过滤掉
      if (c.getName().startsWith("java.lang.ClassLoader$ByteBuddyAccessor$")) {
        return true;
      }

      return HelperInjector.isInjectedClass(c);
    }
  }

  /** Detect if the instrumented application is using a custom JUL LogManager. */
  private static boolean isAppUsingCustomLogManager() {
    String jbossHome = System.getenv("JBOSS_HOME");
    if (jbossHome != null) {
      logger.log(FINE, "Found JBoss: {0}; assuming app is using custom LogManager", jbossHome);
      // JBoss/Wildfly is known to set a custom log manager after startup.
      // Originally we were checking for the presence of a jboss class,
      // but it seems some non-jboss applications have jboss classes on the classpath.
      // This would cause AgentListener#afterAgent() calls to be delayed indefinitely.
      // Checking for an environment variable required by jboss instead.
      return true;
    }

    String customLogManager = System.getProperty("java.util.logging.manager");
    if (customLogManager != null) {
      logger.log(
          FINE,
          "Detected custom LogManager configuration: java.util.logging.manager={0}",
          customLogManager);
      boolean onSysClasspath =
          ClassLoader.getSystemResource(getResourceName(customLogManager)) != null;
      if (logger.isLoggable(FINE)) {
        logger.log(
            FINE,
            "Class {0} is on system classpath: {1}delaying AgentInstaller#afterAgent()",
            new Object[] {customLogManager, onSysClasspath ? "not " : ""});
      }
      // Some applications set java.util.logging.manager but never actually initialize the logger.
      // Check to see if the configured manager is on the system classpath.
      // If so, it should be safe to initialize AgentInstaller which will setup the log manager:
      // LogManager tries to load the implementation first using system CL, then falls back to
      // current context CL
      return !onSysClasspath;
    }

    return false;
  }

  private static void logVersionInfo() {
    VersionLogger.logAllVersions();
    if (logger.isLoggable(FINE)) {
      logger.log(
          FINE,
          "{0} loaded on {1}",
          new Object[] {AgentInstaller.class.getName(), AgentInstaller.class.getClassLoader()});
    }
  }

  private AgentInstaller() {}

  /**
   * 主要作用是确保上下文在多线程环境中正确传播和管理。它通过模拟并发环境中的上下文使用场景来测试上下文管理器的健壮性和一致性
   */
  private static class StrictContextStressor implements ContextStorage, AutoCloseable {

    private final ContextStorage contextStorage;
    private final int sleepMillis;

    private StrictContextStressor(ContextStorage contextStorage, int sleepMillis) {
      this.contextStorage = contextStorage;
      this.sleepMillis = sleepMillis;
    }

    @Override
    public Scope attach(Context toAttach) {
      return wrap(contextStorage.attach(toAttach));
    }

    @Nullable
    @Override
    public Context current() {
      return contextStorage.current();
    }

    @Override
    public void close() throws Exception {
      if (contextStorage instanceof AutoCloseable) {
        ((AutoCloseable) contextStorage).close();
      }
    }

    private Scope wrap(Scope scope) {
      return () -> {
        try {
          Thread.sleep(sleepMillis);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
        scope.close();
      };
    }
  }
}
