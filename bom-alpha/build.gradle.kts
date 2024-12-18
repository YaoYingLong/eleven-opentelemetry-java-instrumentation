plugins {
  id("otel.bom-conventions")
}

description = "OpenTelemetry Instrumentation Bill of Materials (Alpha)"
group = "io.opentelemetry.instrumentation"
base.archivesName.set("opentelemetry-instrumentation-bom-alpha")

javaPlatform {
  allowDependencies()
}

dependencies {
  api(platform("io.opentelemetry:opentelemetry-bom"))
  api(platform("io.opentelemetry:opentelemetry-bom-alpha"))
  api(platform(project(":bom")))

  // Get the semconv version from :dependencyManagement
  // 从 :dependencyManagement 获取 semconv 版本
  val semconvConstraint = project(":dependencyManagement").dependencyProject.configurations["api"].allDependencyConstraints
    .find { it.group.equals("io.opentelemetry.semconv")
            && it.name.equals("opentelemetry-semconv") }
    ?: throw Exception("semconv constraint not found")
  // 将从:dependencyManagement 获取 semconv 版本，通过otelBom.addExtra添加到additionalDependencies
  otelBom.addExtra(semconvConstraint.group, semconvConstraint.name, semconvConstraint.version ?: throw Exception("missing version"))
}

// 定义过滤条件 otel.stable != true
otelBom.projectFilter.set { it.findProperty("otel.stable") != "true" }
