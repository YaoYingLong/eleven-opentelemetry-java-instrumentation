import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.time.Duration

plugins {
  `kotlin-dsl`
  // 配合下面的io.github.gradle-nexus.publish-plugin插件一起使用的
  `maven-publish`
  // 配合下面的io.github.gradle-nexus.publish-plugin插件一起使用的，发布到Maven Central通常需要对构建产物进行签名
  signing

  id("com.gradle.plugin-publish")
  // 提供了一种方便的方式来配置和执行发布任务，可以简化将构建产物发布到Maven仓库的过程
  id("io.github.gradle-nexus.publish-plugin")
}

group = "io.opentelemetry.instrumentation"

// 将外部的Gradle脚本文件应用到当前的构建脚本中，通过这种方式外部脚本中的所有配置和任务都将被引入并在当前脚本中生效
apply(from = "../version.gradle.kts")

repositories {
  mavenCentral()
  gradlePluginPortal()
}

val bbGradlePlugin by configurations.creating
configurations.named("compileOnly") {
  extendsFrom(bbGradlePlugin)
}

val byteBuddyVersion = "1.14.9"
val aetherVersion = "1.1.0"

dependencies {
  implementation("com.google.guava:guava:32.1.3-jre")
  // we need to use byte buddy variant that does not shade asm
  implementation("net.bytebuddy:byte-buddy-gradle-plugin:${byteBuddyVersion}") {
    exclude(group = "net.bytebuddy", module = "byte-buddy")
  }
  implementation("net.bytebuddy:byte-buddy-dep:${byteBuddyVersion}")

  implementation("org.eclipse.aether:aether-connector-basic:${aetherVersion}")
  implementation("org.eclipse.aether:aether-transport-http:${aetherVersion}")
  implementation("org.apache.maven:maven-aether-provider:3.3.9")

  implementation("gradle.plugin.com.github.johnrengelman:shadow:8.0.0")

  testImplementation("org.assertj:assertj-core:3.24.2")

  testImplementation(enforcedPlatform("org.junit:junit-bom:5.10.0"))
  testImplementation("org.junit.jupiter:junit-jupiter-api")
  testImplementation("org.junit.jupiter:junit-jupiter-params")
  testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine")
}

tasks {
  // 用于配置所有类型的Test任务中的通用属性
  withType<Test>().configureEach {
    useJUnitPlatform()
  }

  withType<JavaCompile>().configureEach {
    with(options) {
      release.set(8)
    }
  }

  withType(KotlinCompile::class).configureEach {
    kotlinOptions {
      jvmTarget = "1.8"
    }
  }
}

// 用于创建和发布自定义Gradle插件
gradlePlugin {
  website.set("https://opentelemetry.io")
  vcsUrl.set("https://github.com/open-telemetry/opentelemetry-java-instrumentation")
  plugins {
    get("io.opentelemetry.instrumentation.muzzle-generation").apply {
      displayName = "Muzzle safety net generation"
      description =
        "https://github.com/open-telemetry/opentelemetry-java-instrumentation/blob/main/docs/contributing/muzzle.md"
      tags.set(listOf("opentelemetry", "instrumentation", "java"))
    }
    get("io.opentelemetry.instrumentation.muzzle-check").apply {
      displayName = "Checks instrumented libraries against muzzle safety net"
      description =
        "https://github.com/open-telemetry/opentelemetry-java-instrumentation/blob/main/docs/contributing/muzzle.md"
      tags.set(listOf("opentelemetry", "instrumentation", "java"))
    }
  }
}

java {
  // toolchain允许开发者在构建过程中指定和使用不同版本的Java，而不必依赖于构建环境中默认安装的Java版本
  toolchain {   // 默认情况下，Gradle会自动下载所需的 JDK
    // 配置项目的jdk版本为jdk11
    languageVersion.set(JavaLanguageVersion.of(11))
  }
  // 用于生成Javadoc JAR文件的快捷配置方法，Javadoc JAR文件是包含项目API文档的压缩文件
  withJavadocJar()
  withSourcesJar()
}

// 提供了一种方便的方式来配置和执行发布任务，可以简化将构建产物发布到Maven仓库的过程
nexusPublishing {
  // 指定要发布的包的Maven GroupID为：io.opentelemetry
  packageGroup.set("io.opentelemetry")

  repositories {
    sonatype {
      // 用于认证，可以通过Gradle属性或环境变量传递，以保持敏感信息的安全
      username.set(System.getenv("SONATYPE_USER"))
      password.set(System.getenv("SONATYPE_KEY"))
    }
  }

  connectTimeout.set(Duration.ofMinutes(5))
  clientTimeout.set(Duration.ofMinutes(5))
}

tasks {
  // 如果打的jar包的版本信息包含SNAPSHOT，则禁用publishPlugins任务
  publishPlugins {
    enabled = !version.toString().contains("SNAPSHOT")
  }
}

// 在项目配置阶段完成后执行发布插件钩子
afterEvaluate {
  // 发布插件
  publishing {
    publications {
      // 用于获取一个MavenPublication实例，且实例名称为pluginMaven
      named<MavenPublication>("pluginMaven") {
        pom {
          name.set("OpenTelemetry Instrumentation Gradle Plugins")
          description.set("Gradle plugins to assist developing OpenTelemetry instrumentation")
          url.set("https://github.com/open-telemetry/opentelemetry-java-instrumentation")

          licenses {
            license {
              name.set("The Apache License, Version 2.0")
              url.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
            }
          }

          developers {
            developer {
              id.set("opentelemetry")
              name.set("OpenTelemetry")
              url.set("https://github.com/open-telemetry/opentelemetry-java-instrumentation/discussions")
            }
          }

          scm {
            connection.set("scm:git:git@github.com:open-telemetry/opentelemetry-java-instrumentation.git")
            developerConnection.set("scm:git:git@github.com:open-telemetry/opentelemetry-java-instrumentation.git")
            url.set("git@github.com:open-telemetry/opentelemetry-java-instrumentation.git")
          }
        }
      }
    }
  }
}

// Sign only if we have a key to do so
val signingKey: String? = System.getenv("GPG_PRIVATE_KEY")
signing {
  setRequired({
    // only require signing on CI and when a signing key is present
    System.getenv("CI") != null && signingKey != null
  })
  useInMemoryPgpKeys(signingKey, System.getenv("GPG_PASSWORD"))
}
