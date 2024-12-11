plugins {
  id("io.opentelemetry.instrumentation.javaagent-testing")
  id("io.opentelemetry.instrumentation.muzzle-check")
  id("io.opentelemetry.instrumentation.muzzle-generation")
}

dependencies {
  // 将一个opentelemetry-instrumentation-api依赖库，添加到muzzleBootstrap配置中。这意味着在构建过程中，Muzzle将使用这个依赖项来检查兼容性
  // 等效写法：muzzleBootstrap("io.opentelemetry.instrumentation:opentelemetry-instrumentation-api")
  // muzzleBootstrap在gradle-plugins中io.opentelemetry.instrumentation.muzzle-check.gradle.kts被定义
  add("muzzleBootstrap", "io.opentelemetry.instrumentation:opentelemetry-instrumentation-api")
  add("muzzleBootstrap", "io.opentelemetry.instrumentation:opentelemetry-instrumentation-api-semconv")
  add("muzzleBootstrap", "io.opentelemetry.instrumentation:opentelemetry-instrumentation-annotations-support")
  // muzzleTooling在gradle-plugins中io.opentelemetry.instrumentation.muzzle-check.gradle.kts被定义
  add("muzzleTooling", "io.opentelemetry.javaagent:opentelemetry-javaagent-extension-api")
  add("muzzleTooling", "io.opentelemetry.javaagent:opentelemetry-javaagent-tooling")

  /*
    Dependencies added to this configuration will be found by the muzzle gradle plugin during code
    generation phase. These classes become part of the code that plugin inspects and traverses during
    references collection phase.
   */
  // codegen在gradle-plugins中io.opentelemetry.instrumentation.muzzle-generation.gradle.kts被定义
  add("codegen", "io.opentelemetry.javaagent:opentelemetry-javaagent-tooling")
}
