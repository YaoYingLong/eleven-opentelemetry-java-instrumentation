plugins {
  // 可以使用library、testLibrary、latestDepTestLibrary直接添加依赖，生成projectName.properties版本文件
  id("io.opentelemetry.instrumentation.base")
}

dependencies {
  api("io.opentelemetry.instrumentation:opentelemetry-instrumentation-api")
  api("io.opentelemetry.instrumentation:opentelemetry-instrumentation-api-semconv")

  api("io.opentelemetry:opentelemetry-api")

  testImplementation("io.opentelemetry.javaagent:opentelemetry-testing-common")
}
