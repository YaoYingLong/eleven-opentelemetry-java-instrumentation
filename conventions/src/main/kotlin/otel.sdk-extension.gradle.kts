// SDK extensions are very similar to library instrumentations, they can be used without the javaagent
// but since they depend on the SDK they must be loaded by the agent CL in the javaagent

plugins {
  // 可以使用library、testLibrary、latestDepTestLibrary直接添加依赖，生成projectName.properties版本文件
  id("io.opentelemetry.instrumentation.library-instrumentation")

  // conventions模块中定义的插件，用于生成测试覆盖率报告
  id("otel.jacoco-conventions")
  id("otel.java-conventions")
  id("otel.publish-conventions")
}

extra["mavenGroupId"] = "io.opentelemetry.instrumentation"

// 用于设置生成的jar的基础名称，projectDir表示当前项目的目录，即当前项目的父目录的名称
base.archivesName.set(projectDir.parentFile.name)
