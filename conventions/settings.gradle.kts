// 将gradle-plugins构建项目，当成当前构建的一部分
includeBuild("../gradle-plugins") {
  // 定义如何在当前构建中替代依赖项
  dependencySubstitution {
    // 任何对io.opentelemetry.instrumentation:gradle-plugins的依赖都会替换为对../gradle-plugins的依赖
    substitute(module("io.opentelemetry.instrumentation:gradle-plugins")).using(project(":"))
  }
}
