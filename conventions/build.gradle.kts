plugins {
  `kotlin-dsl`
  // When updating, update below in dependencies too
  // 用于代码格式化和清理，自动化代码格式化过程，确保代码库中的代码符合指定的格式标准
  id("com.diffplug.spotless") version "6.22.0"
}

spotless {
  java {
    // 使用 Google Java Format
    googleJavaFormat()
    // 在项目中src/**/*.java匹配的每个文件中包含../buildscripts/spotless.license.java文件内容对应的Licence头
    licenseHeaderFile(
      rootProject.file("../buildscripts/spotless.license.java"),
      "(package|import|public)"
    )
    target("src/**/*.java")
  }
  kotlinGradle {
    // not sure why it's not using the indent settings from .editorconfig
    // editorConfigOverride方法允许在不依赖.editorconfig文件的情况下，直接在Gradle配置中覆盖或指定ktlint的格式化规则
    // 不希望在项目中维护多个配置文件的情况特别有用
    ktlint().editorConfigOverride(mapOf(
      // 设置缩进大小为2
      "indent_size" to "2",
      // 设置续行缩进大小为2
      "continuation_indent_size" to "2",
      // 设置最大行长度为160
      "max_line_length" to "160",
      // 禁用no-wildcard-imports、max-line-length等规则
      "ktlint_standard_no-wildcard-imports" to "disabled",
      // ktlint does not break up long lines, it just fails on them
      "ktlint_standard_max-line-length" to "disabled",
      // ktlint makes it *very* hard to locate where this actually happened
      "ktlint_standard_trailing-comma-on-call-site" to "disabled",
      // depends on ktlint_standard_wrapping
      "ktlint_standard_trailing-comma-on-declaration-site" to "disabled",
      // also very hard to find out where this happens
      "ktlint_standard_wrapping" to "disabled"
    ))
    target("**/*.gradle.kts")
  }
}

repositories {
  mavenCentral()
  gradlePluginPortal()
}

// 用于指定测试任务使用JUnit Platform来运行测试
tasks.withType<Test>().configureEach {
  useJUnitPlatform()
}

dependencies {
  // 将Gradle API添加为编译时依赖项，在构建或编译自定义插件时，可以使用和调用Gradle的API方法
  implementation(gradleApi())
  // 不需要手动指定Groovy的版本号，因为它会自动匹配当前Gradle发行版中使用的Groovy版本
  implementation(localGroovy())

  // dependencySubstitution is applied to this dependency (see seetings.gradle.kts)
  implementation("io.opentelemetry.instrumentation:gradle-plugins")

  implementation("org.eclipse.aether:aether-connector-basic:1.1.0")
  implementation("org.eclipse.aether:aether-transport-http:1.1.0")
  implementation("org.apache.maven:maven-aether-provider:3.3.9")

  // When updating, update above in plugins too
  implementation("com.diffplug.spotless:spotless-plugin-gradle:6.22.0")
  implementation("com.google.guava:guava:32.1.3-jre")
  implementation("gradle.plugin.com.google.protobuf:protobuf-gradle-plugin:0.8.18")
  implementation("com.github.johnrengelman:shadow:8.1.1")
  implementation("org.apache.httpcomponents:httpclient:4.5.14")
  implementation("com.gradle.enterprise:com.gradle.enterprise.gradle.plugin:3.15.1")
  implementation("org.owasp:dependency-check-gradle:8.4.0")
  implementation("ru.vyarus:gradle-animalsniffer-plugin:1.7.1")
  // When updating, also update dependencyManagement/build.gradle.kts
  implementation("net.bytebuddy:byte-buddy-gradle-plugin:1.14.9")
  implementation("gradle.plugin.io.morethan.jmhreport:gradle-jmh-report:0.9.0")
  implementation("me.champeau.jmh:jmh-gradle-plugin:0.7.1")
  implementation("net.ltgt.gradle:gradle-errorprone-plugin:3.1.0")
  implementation("net.ltgt.gradle:gradle-nullaway-plugin:1.6.0")
  implementation("me.champeau.gradle:japicmp-gradle-plugin:0.4.2")

  // enforcedPlatform是一种依赖声明方式，用于确保所有传递性依赖都强制使用BOM中指定的版本
  // 意味着即使其他依赖可能带来了不同版本的JUnit库，Gradle也会强制使用BOM中定义的版本
  testImplementation(enforcedPlatform("org.junit:junit-bom:5.10.0"))
  testImplementation("org.junit.jupiter:junit-jupiter-api")
  testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine")
  testImplementation("org.assertj:assertj-core:3.24.2")
}
