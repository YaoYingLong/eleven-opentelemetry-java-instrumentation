/** Common setup for manual instrumentation of libraries and javaagent instrumentation. */

plugins {
  `java-library`
}

/**
 * We define three dependency configurations to use when adding dependencies to libraries being
 * instrumented.
 *
 * 定义了三种依赖配置，以便在向正在检测的库添加依赖项时使用
 *
 * - library: 对检测库的依赖，结果将依赖项添加到compileOnly和testImplementation
 *      如果使用-PtestLatestDeps=true运行构建，则添加到testImplementation时的版本将被“+”（可能的最新版本）覆盖。
 *      对于版本之间没有不同行为的简单库， 可以仅对库有单一依赖关系。
 *
 * - library: A dependency on the instrumented library. Results in the dependency being added to
 *     compileOnly and testImplementation. If the build is run with -PtestLatestDeps=true, the
 *     version when added to testImplementation will be overridden by `+`, the latest version
 *     possible. For simple libraries without different behavior between versions, it is possible
 *     to have a single dependency on library only.
 *
 * - testLibrary: 对测试库的依赖。这通常用于
 *     a) 使用不同版本的库进行编译和测试
 *     b) 添加仅测试所需的帮助程序（例如，库测试工件）
 *     该依赖项将被添加到testImplementation中，并且在测试最新的deps时将具有“+”版本，如上所述。
 *
 * - testLibrary: A dependency on a library for testing. This will usually be used to either
 *     a) use a different version of the library for compilation and testing and b) to add a helper
 *     that is only required for tests (e.g., library-testing artifact). The dependency will be
 *     added to testImplementation and will have a version of `+` when testing latest deps as
 *     described above.
 *
 * - latestDepTestLibrary: 当启用最新依赖项版本的测试时，对用于测试的库的依赖项
 *    此依赖项将按原样添加到testImplementation，但前提是-PtestLatestDeps=true。
 *    该版本不会被修改，但会被赋予最高优先级。使用它来限制默认“+”的最新版本依赖关系，
 *    例如通过指定“2.+”限制为仅主要版本。
 *
 * - latestDepTestLibrary: A dependency on a library for testing when testing of latest dependency
 *   version is enabled. This dependency will be added as-is to testImplementation, but only if
 *   -PtestLatestDeps=true. The version will not be modified but it will be given highest
 *   precedence. Use this to restrict the latest version dependency from the default `+`, for
 *   example to restrict to just a major version by specifying `2.+`.
 */

// 获取在命令行中通过-P选项传递的项目属性
val testLatestDeps = gradle.startParameter.projectProperties["testLatestDeps"] == "true"
extra["testLatestDeps"] = testLatestDeps

// 标记一个规则类，使其能够在Gradle的构建缓存中被缓存
@CacheableRule
// ComponentMetadataRule用于在解析依赖项时动态地修改组件元数据
abstract class TestLatestDepsRule : ComponentMetadataRule {
  // execute方法在解析每个依赖项时被调用
  override fun execute(context: ComponentMetadataContext) {
    // ComponentMetadataContext提供了访问当前依赖项的上下文信息
    // context.details即ComponentMetadataDetails对依赖项元数据的访问和修改能力
    // context.details.id即ModuleVersionIdentifier用于唯一标识一个组件，它包含了组件的基本信息，如组、名称和版本
    val version = context.details.id.version
    if (version.contains("-alpha", true) ||
      version.contains("-beta", true) ||
      version.contains("-rc", true) ||
      version.contains("-m", true) || // e.g. spring milestones are published to grails repo
      version.contains(".alpha", true) || // e.g. netty
      version.contains(".beta", true) || // e.g. hibernate
      version.contains(".cr", true) // e.g. hibernate
    ) {
      // status属性用于表示组件的当前状态或稳定性级别["integration", "milestone", "release"]
      context.details.status = "milestone"
    }
  }
}

configurations {
  val library by creating {
    isCanBeResolved = false
    isCanBeConsumed = false
  }
  val testLibrary by creating {
    isCanBeResolved = false
    isCanBeConsumed = false
  }
  val latestDepTestLibrary by creating {
    isCanBeResolved = false
    isCanBeConsumed = false
  }

  val testImplementation by getting

  listOf(library, testLibrary).forEach { configuration ->
    // We use whenObjectAdded and copy into the real configurations instead of extension to allow
    // mutating the version for latest dep tests.
    // whenObjectAdded是一种监听器机制，用于在依赖项被添加到项目的依赖配置中时执行特定的操作
    // 使用whenObjectAdded将依赖复制到真实配置而不是扩展中，以允许为最新的dep测试改变版本
    configuration.dependencies.whenObjectAdded {
      val dep = copy()
      // 如果指定gradle编译时指定-PtestLatestDeps=true
      if (testLatestDeps) {
        (dep as ExternalDependency).version {
          // require方法用于指定依赖项必须满足的版本要求，表示选择该依赖项的最新发布版本
          require("latest.release")
        }
      }
      // 将依赖添加到testImplementation依赖分组中
      testImplementation.dependencies.add(dep)
    }
  }

  // 如果指定gradle编译时指定-PtestLatestDeps=true
  if (testLatestDeps) {
    // Gradle在解析每个依赖项时，会调用TestLatestDepsRule的execute方法，从而应用你定义的版本选择逻辑
    dependencies {
      components {
        all<TestLatestDepsRule>()
      }
    }

    // whenObjectAdded是一种监听器机制，用于在依赖项被添加到项目的依赖配置中时执行特定的操作
    latestDepTestLibrary.dependencies.whenObjectAdded {
      val dep = copy()
      val declaredVersion = dep.version
      println("group=$dep.group declaredVersion=$declaredVersion")
      if (declaredVersion != null) {
        // version方法用于配置依赖项的版本策略
        (dep as ExternalDependency).version {
          // 是一种版本约束策略，指定依赖项必须使用declaredVersion版本
          // 可以确保构建过程中不会因为依赖传递性或其他原因导致使用了不同的版本
          strictly(declaredVersion)
        }
      }
      // 将依赖添加到testImplementation依赖分组中
      testImplementation.dependencies.add(dep)
    }
  }
  named("compileOnly") {
    extendsFrom(library)
  }
}

// 如果指定gradle编译时指定-PtestLatestDeps=true，与测试相关的内容
if (testLatestDeps) {
  // 用于在项目的配置阶段结束后执行特定的代码块
  afterEvaluate {
    tasks {
      // 通过添加-Xlint:-deprecation编译选项，来抑制与使用已过时（deprecated）API相关的警告
      withType<JavaCompile>().configureEach {
        with(options) {
          // We may use methods that are deprecated in future versions, we check lint on the normal
          // build and don't need this for testLatestDeps.
          // 可能会使用在未来版本中已弃用的方法，我们会在正常构建上检查lint，并且testLatestDeps不需要此方法
          compilerArgs.add("-Xlint:-deprecation")
        }
      }
    }

    // 如果包含latestDepTest任务
    if (tasks.names.contains("latestDepTest")) {
      val latestDepTest by tasks.existing
      tasks.named("test").configure {
        dependsOn(latestDepTest)
      }
    }
  }
} else {
  afterEvaluate {
    // Disable compiling latest dep tests for non latest dep builds in CI. This is needed to avoid
    // breaking build because of a new library version which could force backporting latest dep
    // fixes to release branches.
    // This is only needed for modules where base version and latest dep tests use a different
    // source directory.
    // 禁止在CI中为非最新的dep构建编译最新的dep测试，这是为了避免由于新的库版本可能会强制向后移植最新的dep修复以发布分支而破坏构建
    // 仅当基本版本和最新dep测试使用不同源目录的模块才需要这样做
    var latestDepCompileTaskNames = arrayOf("compileLatestDepTestJava", "compileLatestDepTestGroovy", "compileLatestDepTestScala")
    for (compileTaskName in latestDepCompileTaskNames) {
      if (tasks.names.contains(compileTaskName)) {
        tasks.named(compileTaskName).configure {
          // 该属性用于控制任务是否会被执行，这里即不执行
          enabled = false
        }
      }
    }
  }
}

tasks {
  // 生成版本文件到对应项目的build/generated/instrumentationVersion目录
  val generateInstrumentationVersionFile by registering {
    // 如果项目目录名称为javaagent、library、library-autoconfigure其中的一个，则返回其父及目录的的名称，否则返回当前项目名称
    val name = computeInstrumentationName()
    val version = project.version as String
    inputs.property("instrumentation.name", name)
    inputs.property("instrumentation.version", version)

    val propertiesDir = layout.buildDirectory.dir("generated/instrumentationVersion/META-INF/io/opentelemetry/instrumentation/")
    outputs.dir(propertiesDir)

    doLast {
      File(propertiesDir.get().asFile, "$name.properties").writeText("version=$version")
    }
  }
}

// 如果项目目录名称为javaagent、library、library-autoconfigure其中的一个，则返回其父及目录的的名称，否则返回当前项目名称
fun computeInstrumentationName(): String {
  val name = when (projectDir.name) {
    // 如果项目目录名称为javaagent、library、library-autoconfigure其中的一个，则返回其父及目录的的名称
    "javaagent", "library", "library-autoconfigure" -> projectDir.parentFile.name
    else -> project.name
  }
  return "io.opentelemetry.$name"
}

// 定义项目中的不同源代码集
sourceSets {
  main {
    // 通过builtBy属性指定generateInstrumentationVersionFile任务来生成build/generated/instrumentationVersion目录的内容
    output.dir("build/generated/instrumentationVersion", "builtBy" to "generateInstrumentationVersionFile")
  }
}
