plugins {
  id("otel.java-conventions")
}

dependencies {
  implementation("com.google.errorprone:error_prone_core")

  annotationProcessor("com.google.auto.service:auto-service")
  compileOnly("com.google.auto.service:auto-service-annotations")

  testImplementation("com.google.errorprone:error_prone_test_helpers")
}

// otelJava在插件otel.java-conventions中被定义，且在该插件中使用
otelJava {
  // 设置otel.java-conventions插件中使用到的otelJava.minJavaVersionSupported为JDK11
  minJavaVersionSupported.set(JavaVersion.VERSION_11)
}

// We cannot use "--release" javac option here because that will forbid exporting com.sun.tools package.
// We also can't seem to use the toolchain without the "--release" option. So disable everything.

java {
  sourceCompatibility = JavaVersion.VERSION_11
  targetCompatibility = JavaVersion.VERSION_11
  toolchain {
    languageVersion.set(null as JavaLanguageVersion?)
  }
}

tasks {
  withType<JavaCompile>().configureEach {
    with(options) {
      release.set(null as Int?)

      compilerArgs.addAll(
        listOf(
          "--add-exports",
          "jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED",
          "--add-exports",
          "jdk.compiler/com.sun.tools.javac.comp=ALL-UNNAMED",
          "--add-exports",
          "jdk.compiler/com.sun.tools.javac.tree=ALL-UNNAMED",
          "--add-exports",
          "jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED",
        ),
      )
    }
  }
}

tasks.withType<Test>().configureEach {
  // required on jdk17
  jvmArgs("--add-opens=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED")
  jvmArgs("--add-opens=jdk.compiler/com.sun.tools.javac.code=ALL-UNNAMED")
  jvmArgs("--add-opens=jdk.compiler/com.sun.tools.javac.comp=ALL-UNNAMED")
  jvmArgs("--add-opens=jdk.compiler/com.sun.tools.javac.file=ALL-UNNAMED")
  jvmArgs("--add-opens=jdk.compiler/com.sun.tools.javac.main=ALL-UNNAMED")
  jvmArgs("--add-opens=jdk.compiler/com.sun.tools.javac.parser=ALL-UNNAMED")
  jvmArgs("--add-opens=jdk.compiler/com.sun.tools.javac.tree=ALL-UNNAMED")
  jvmArgs("--add-opens=jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED")
  jvmArgs("-XX:+IgnoreUnrecognizedVMOptions")
}

// Our conventions apply this project as a dependency in the errorprone configuration, which would cause
// a circular dependency if trying to compile this project with that still there. So we filter this
// project out.
configurations {
  // 我们的约定将该项目应用为容易出错的配置中的依赖项，如果尝试在该项目仍然存在的情况下编译该项目
  // 则会导致循环依赖项。所以我们过滤掉这个项目
  named("errorprone") {
    dependencies.removeIf {
      it is ProjectDependency && it.dependencyProject == project
    }
  }
}
