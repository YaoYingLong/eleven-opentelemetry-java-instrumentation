import io.opentelemetry.instrumentation.gradle.OtelBomExtension

plugins {
  id("otel.publish-conventions")
  id("java-platform")
}

// 如果项目名称时以bom开头的直接抛出异常，该插件只允许在:bom和:bom-alpha中使用
if (!project.name.startsWith("bom")) {
  throw IllegalStateException("Name of BOM projects must start with 'bom'.")
}

// 当前项目依赖所有其他子项目的评估完成
rootProject.subprojects.forEach { subproject ->
  if (!subproject.name.startsWith("bom")) {
    evaluationDependsOn(subproject.path)
  }
}
val otelBom = extensions.create<OtelBomExtension>("otelBom")

afterEvaluate {
  otelBom.projectFilter.finalizeValue()
  val bomProjects = rootProject.subprojects
    // 子项目按 archivesName 属性排序
    .sortedBy { it.findProperty("archivesName") as String? }
    // 排除名称以 "bom" 开头的项目
    .filter { !it.name.startsWith("bom") }
    // 通过 otelBom.projectFilter.get()::test 方法应用自定义过滤器
    // 如果是bom则过滤掉otel.stable != true，若是bom-alpha则过滤掉otel.stable == true
    .filter(otelBom.projectFilter.get()::test)
    // 确保项目应用了maven-publish插件
    .filter { it.plugins.hasPlugin("maven-publish") }

  bomProjects.forEach { project ->
    dependencies {
      constraints {
        api(project)
      }
    }
  }

  // bom-alpha中向additionalDependencies添加了semconv
  otelBom.additionalDependencies.forEach { dependency ->
    dependencies {
      constraints {
        api(dependency)
      }
    }
  }
}

// this applies version numbers to the SDK bom and SDK alpha bom which are dependencies of the instrumentation boms
evaluationDependsOn(":dependencyManagement")
val dependencyManagementConf = configurations.create("dependencyManagement") {
  isCanBeConsumed = false
  isCanBeResolved = false
  isVisible = false
}
afterEvaluate {
  configurations.configureEach {
    if (isCanBeResolved && !isCanBeConsumed) {
      extendsFrom(dependencyManagementConf)
    }
  }
}

dependencies {
  add(dependencyManagementConf.name, platform(project(":dependencyManagement")))
}
