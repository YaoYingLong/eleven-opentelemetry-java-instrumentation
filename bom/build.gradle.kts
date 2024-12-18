plugins {
  id("otel.bom-conventions")
}

description = "OpenTelemetry Instrumentation Bill of Materials"
group = "io.opentelemetry.instrumentation"
base.archivesName.set("opentelemetry-instrumentation-bom")

javaPlatform {
  allowDependencies()
}

dependencies {
  api(platform("io.opentelemetry:opentelemetry-bom"))
}

// 定义过滤条件 otel.stable = true
otelBom.projectFilter.set { it.findProperty("otel.stable") == "true" }
