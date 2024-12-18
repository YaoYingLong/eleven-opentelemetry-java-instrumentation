import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
  id("com.github.johnrengelman.shadow")
}

// NOTE: any modifications below should also be made in
//       io.opentelemetry.instrumentation.muzzle-check.gradle.kts
tasks.withType<ShadowJar>().configureEach {
  // 用于合并JAR文件中的服务提供者配置文件（通常位于 META-INF/services/ 目录）
  mergeServiceFiles()
  // Merge any AWS SDK service files that may be present (too bad they didn't just use normal
  // service loader...)
  // 用于合并AWS SDK的服务文件，AWS SDK使用自定义的服务加载机制，因此需要显式地合并这些服务文件
  mergeServiceFiles("software/amazon/awssdk/global/handlers")

  // 用于排除模块化JAR文件中的module-info.class文件
  exclude("**/module-info.class")

  // rewrite dependencies calling Logger.getLogger
  // relocate用于重定位JAR文件中的类或包。它可以防止类冲突或修改类的加载行为
  relocate("java.util.logging.Logger", "io.opentelemetry.javaagent.bootstrap.PatchLogger")

  // 防止与库工具发生冲突，因为这些类位于引导类加载器中
  if (project.findProperty("disableShadowRelocate") != "true") {
    // prevents conflict with library instrumentation, since these classes live in the bootstrap class loader
    relocate("io.opentelemetry.instrumentation", "io.opentelemetry.javaagent.shaded.instrumentation") {
      // Exclude resource providers since they live in the agent class loader
      exclude("io.opentelemetry.instrumentation.resources.*")
      exclude("io.opentelemetry.instrumentation.spring.resources.*")
    }
  }

  // relocate(OpenTelemetry API) since these classes live in the bootstrap class loader
  // 重新定位（OpenTelemetry API），因为这些类位于bootstrap类加载器中
  relocate("io.opentelemetry.api", "io.opentelemetry.javaagent.shaded.io.opentelemetry.api")
  relocate("io.opentelemetry.semconv", "io.opentelemetry.javaagent.shaded.io.opentelemetry.semconv")
  relocate("io.opentelemetry.context", "io.opentelemetry.javaagent.shaded.io.opentelemetry.context")
  relocate("io.opentelemetry.extension.incubator", "io.opentelemetry.javaagent.shaded.io.opentelemetry.extension.incubator")

  // relocate(the OpenTelemetry extensions that are used by instrumentation modules)
  // these extensions live in the AgentClassLoader, and are injected into the user's class loader
  // by the instrumentation modules that use them
  relocate("io.opentelemetry.extension.aws", "io.opentelemetry.javaagent.shaded.io.opentelemetry.extension.aws")
  relocate("io.opentelemetry.extension.kotlin", "io.opentelemetry.javaagent.shaded.io.opentelemetry.extension.kotlin")

  // this is for instrumentation of opentelemetry-api and opentelemetry-instrumentation-api
  relocate("application.io.opentelemetry", "io.opentelemetry")
  relocate("application.io.opentelemetry.instrumentation.api", "io.opentelemetry.instrumentation.api")

  // this is for instrumentation on java.util.logging (since java.util.logging itself is shaded above)
  relocate("application.java.util.logging", "java.util.logging")
}
