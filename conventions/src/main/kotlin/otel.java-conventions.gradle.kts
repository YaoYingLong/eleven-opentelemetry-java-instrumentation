import com.gradle.enterprise.gradleplugin.testretry.retry
import io.opentelemetry.instrumentation.gradle.OtelJavaExtension
import org.gradle.api.internal.artifacts.dsl.dependencies.DependenciesExtensionModule.module
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import java.time.Duration

plugins {
  `java-library`
  groovy
  checkstyle
  codenarc
  idea

  // 检测Java代码中的常见错误和潜在问题
  id("otel.errorprone-conventions")
  // conventions模块中定义的插件，用于代码格式化和样式检查
  id("otel.spotless-conventions")
  // 帮助开发者识别和管理软件中的安全漏洞，特别是在使用第三方库时
  id("org.owasp.dependencycheck")
}

val otelJava = extensions.create<OtelJavaExtension>("otelJava")

// afterEvaluate用于在项目的配置阶段结束后执行特定的代码块
afterEvaluate {
  val previousBaseArchiveName = base.archivesName.get()
  // 如果存在mavenGroupId属性且值为io.opentelemetry.javaagent.instrumentation，生产的jar包名称加上opentelemetry-javaagent-前缀
  if (findProperty("mavenGroupId") == "io.opentelemetry.javaagent.instrumentation") {
    base.archivesName.set("opentelemetry-javaagent-$previousBaseArchiveName")
  } else if (!previousBaseArchiveName.startsWith("opentelemetry-")) {
    // 如果生产的jar包名称不是以opentelemetry-开头的，加上opentelemetry-前缀
    base.archivesName.set("opentelemetry-$previousBaseArchiveName")
  }
}

// Version to use to compile code and run tests.
// 默认的JDK版本设置为17
val DEFAULT_JAVA_VERSION = JavaVersion.VERSION_17

java {
  toolchain {
    languageVersion.set(
      // 如果项目的JDK版本比17高，则使用更高的版本，否则使用JDK17
      otelJava.minJavaVersionSupported.map { JavaLanguageVersion.of(Math.max(it.majorVersion.toInt(), DEFAULT_JAVA_VERSION.majorVersion.toInt())) }
    )
  }

  // See https://docs.gradle.org/current/userguide/upgrading_version_5.html, Automatic target JVM version
  disableAutoTargetJvm()
  withJavadocJar()
  withSourcesJar()
}

// this task is helpful for tracking down and aligning dependency versions across modules in order
// to limit versions that are be loaded by Intellij
// (the normal dependencies task selector only executes the task on a single project)
//
// ./gradlew intellijDeps
//           | grep -Po "\--- \K.*"
//           | sed "s/:[0-9].* -> \(.*\)/:\1/"
//           | sed "s/:[0-9].* -> \(.*\)/:\1/"
//           | sort -u
tasks.register<DependencyReportTask>("intellijDeps")

tasks.withType<JavaCompile>().configureEach {
  with(options) {
    release.set(otelJava.minJavaVersionSupported.map { it.majorVersion.toInt() })

    if (name != "jmhCompileGeneratedClasses") {
      compilerArgs.addAll(
        listOf(
          "-Xlint:all",
          // We suppress the "try" warning because it disallows managing an auto-closeable with
          // try-with-resources without referencing the auto-closeable within the try block.
          "-Xlint:-try",
          // We suppress the "processing" warning as suggested in
          // https://groups.google.com/forum/#!topic/bazel-discuss/_R3A9TJSoPM
          "-Xlint:-processing",
          // We suppress the "options" warning because it prevents compilation on modern JDKs
          "-Xlint:-options",

          // Fail build on any warning
          "-Werror"
        )
      )
    }

    encoding = "UTF-8"

    if (name.contains("Test")) {
      // serialVersionUID is basically guaranteed to be useless in tests
      compilerArgs.add("-Xlint:-serial")
    }
  }
}

// Groovy and Scala compilers don't actually understand --release option
afterEvaluate {
  tasks.withType<GroovyCompile>().configureEach {
    var javaVersion = otelJava.minJavaVersionSupported.get().majorVersion
    if (javaVersion == "8") {
      javaVersion = "1.8"
    }
    sourceCompatibility = javaVersion
    targetCompatibility = javaVersion
  }
  tasks.withType<ScalaCompile>().configureEach {
    sourceCompatibility = otelJava.minJavaVersionSupported.get().majorVersion
    targetCompatibility = otelJava.minJavaVersionSupported.get().majorVersion
  }
}

// 用于控制项目评估顺序的方法。具体来说，它用于确保在评估当前项目之前
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

// Force 4.0, or 4.1 to the highest version of that branch. Since 4.0 and 4.1 often have
// compatibility issues we can't just force to the highest version using normal BOM dependencies.
// 强制4.0或4.1为该分支的最高版本。由于4.0和4.1经常存在兼容性问题，我们不能仅使用正常的BOM依赖项强制使用最高版本
abstract class NettyAlignmentRule : ComponentMetadataRule {
  override fun execute(ctx: ComponentMetadataContext) {
    with(ctx.details) {
      if (id.group == "io.netty" && id.name != "netty") {
        if (id.version.startsWith("4.1.")) {
          belongsTo("io.netty:netty-bom:4.1.100.Final", false)
        } else if (id.version.startsWith("4.0.")) {
          belongsTo("io.netty:netty-bom:4.0.56.Final", false)
        }
      }
    }
  }
}

dependencies {
  add(dependencyManagementConf.name, platform(project(":dependencyManagement")))

  // 强制netty版本
  components.all<NettyAlignmentRule>()

  compileOnly("com.google.code.findbugs:jsr305")
  compileOnly("com.google.errorprone:error_prone_annotations")

  // 用于Groovy语言的静态代码分析工具
  codenarc("org.codenarc:CodeNarc:3.3.0")
  codenarc(platform("org.codehaus.groovy:groovy-bom:3.0.19"))

  modules {
    // checkstyle uses the very old google-collections which causes Java 9 module conflict with
    // guava which is also on the classpath
    module("com.google.collections:google-collections") {
      replacedBy("com.google.guava:guava", "google-collections is now part of Guava")
    }
  }
}

testing {
  // 对项目中定义的所有测试套件类型为JvmTestSuite
  suites.withType(JvmTestSuite::class).configureEach {
    dependencies {
      implementation("org.junit.jupiter:junit-jupiter-api")
      implementation("org.junit.jupiter:junit-jupiter-params")
      runtimeOnly("org.junit.jupiter:junit-jupiter-engine")
      runtimeOnly("org.junit.vintage:junit-vintage-engine")
      implementation("org.junit-pioneer:junit-pioneer")

      implementation("org.assertj:assertj-core")
      implementation("org.awaitility:awaitility")
      implementation("org.mockito:mockito-core")
      implementation("org.mockito:mockito-inline")
      implementation("org.mockito:mockito-junit-jupiter")

      implementation("org.objenesis:objenesis")
      implementation("org.spockframework:spock-core") {
        with(this as ExternalDependency) {
          // exclude optional dependencies
          exclude(group = "cglib", module = "cglib-nodep")
          exclude(group = "net.bytebuddy", module = "byte-buddy")
          exclude(group = "org.junit.platform", module = "junit-platform-testkit")
          exclude(group = "org.jetbrains", module = "annotations")
          exclude(group = "org.objenesis", module = "objenesis")
          exclude(group = "org.ow2.asm", module = "asm")
        }
      }
      implementation("org.spockframework:spock-junit4") {
        with(this as ExternalDependency) {
          // spock-core is already added as dependency
          // exclude it here to avoid pulling in optional dependencies
          exclude(group = "org.spockframework", module = "spock-core")
        }
      }
      implementation("ch.qos.logback:logback-classic")
      implementation("org.slf4j:log4j-over-slf4j")
      implementation("org.slf4j:jcl-over-slf4j")
      implementation("org.slf4j:jul-to-slf4j")
      implementation("com.github.stefanbirkner:system-rules")
    }
  }
}

/**
 * 这里是对path进行处理，比如
 * - :instrumentation:akka:akka-actor-2.3:javaagent, 最终转换为:instrumentation:javaagent:akka-actor-2.3
 * - :instrumentation:c3p0-0.9:library, 最终转换为:instrumentation:c3p0-0.9
 * - :instrumentation:apache-dubbo-2.7:library-autoconfigure, 最终转换为:instrumentation.apache-dubbo-2.7_autoconfigure
 */
var path = project.path
if (path.startsWith(":instrumentation:")) {
  // remove segments that are a prefix of the next segment
  // for example :instrumentation:log4j:log4j-context-data:log4j-context-data-2.17 is transformed to log4j-context-data-2.17
  var tmpPath = path
  // 从字符串中提取最后一个指定字符（在这个例子中是冒号:）之后的子字符串
  val suffix = tmpPath.substringAfterLast(':')
  var prefix = ":instrumentation:"
  if (suffix == "library") {
    // strip ":library" suffix
    // 从字符串中提取最后一个指定字符（在这个例子中是冒号 :）之前的子字符串
    tmpPath = tmpPath.substringBeforeLast(':')
  } else if (suffix == "library-autoconfigure") {
    // replace ":library-autoconfigure" with "-autoconfigure"
    tmpPath = tmpPath.substringBeforeLast(':') + "-autoconfigure"
  } else if (suffix == "javaagent") {
    // strip ":javaagent" suffix and add it to prefix
    prefix += "javaagent:"
    tmpPath = tmpPath.substringBeforeLast(':')
  }
  val segments = tmpPath.substring(":instrumentation:".length).split(':')
  var newPath = ""
  var done = false
  for (s in segments) {
    if (!done && (newPath.isEmpty() || s.startsWith(newPath))) {
      newPath = s
    } else {
      newPath += ":$s"
      done = true
    }
  }
  if (newPath.isNotEmpty()) {
    path = prefix + newPath
  }
}
var javaModuleName = "io.opentelemetry" + path.replace(".", "_").replace("-", "_").replace(":", ".")

tasks {
  named<Jar>("jar") {
    // By default Gradle Jar task can put multiple files with the same name
    // into a Jar. This may lead to confusion. For example if auto-service
    // annotation processing creates files with same name in `scala` and
    // `java` directory this would result in Jar having two files with the
    // same name in it. Which in turn would result in only one of those
    // files being actually considered when that Jar is used leading to very
    // confusing failures. Instead we should 'fail early' and avoid building such Jars.
    duplicatesStrategy = DuplicatesStrategy.FAIL

    manifest {
      attributes(
        "Implementation-Title" to project.name,
        "Implementation-Version" to project.version,
        "Implementation-Vendor" to "OpenTelemetry",
        "Implementation-URL" to "https://github.com/open-telemetry/opentelemetry-java-instrumentation",
        "Automatic-Module-Name" to javaModuleName
      )
    }
  }

  named<Javadoc>("javadoc") {
    with(options as StandardJavadocDocletOptions) {
      source = "8"
      encoding = "UTF-8"
      docEncoding = "UTF-8"
      charSet = "UTF-8"
      breakIterator(true)

      // TODO (trask) revisit to see if url is fixed
      // currently broken because https://docs.oracle.com/javase/8/docs/api/element-list is missing
      // and redirects
      // links("https://docs.oracle.com/javase/8/docs/api/")

      addStringOption("Xdoclint:none", "-quiet")
      // non-standard option to fail on warnings, see https://bugs.openjdk.java.net/browse/JDK-8200363
      addStringOption("Xwerror", "-quiet")
    }
  }

  withType<AbstractArchiveTask>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    dirMode = Integer.parseInt("0755", 8)
    fileMode = Integer.parseInt("0644", 8)
  }

  // Convenient when updating errorprone
  register("compileAllJava") {
    dependsOn(withType<JavaCompile>())
  }
}

normalization {
  // 用于指定在运行时需要的所有类和资源
  runtimeClasspath {
    // 用于配置 META-INF 目录下文件的规范化规则
    metaInf {
      // 通过忽略这个属性，Gradle在检查输入的变化时将不会考虑该属性的变化，从而避免因版本号变化导致的任务重新执行
      ignoreAttribute("Implementation-Version")
    }
  }
}

// 测试相关
fun isJavaVersionAllowed(version: JavaVersion): Boolean {
  if (otelJava.minJavaVersionSupported.get().compareTo(version) > 0) {
    return false
  }
  if (otelJava.maxJavaVersionForTests.isPresent && otelJava.maxJavaVersionForTests.get().compareTo(version) < 0) {
    return false
  }
  return true
}

abstract class TestcontainersBuildService : BuildService<BuildServiceParameters.None>

// To limit number of concurrently running resource intensive tests add
// tasks {
//   test {
//     usesService(gradle.sharedServices.registrations["testcontainersBuildService"].service)
//   }
// }
// registerIfAbsent注册一个共享服务，只有在该服务尚未注册时才会进行注册，服务名称为testcontainersBuildService，类型为TestcontainersBuildService
gradle.sharedServices.registerIfAbsent("testcontainersBuildService", TestcontainersBuildService::class.java) {
  // 限制服务的最大并发数为2
  maxParallelUsages.convention(2)
}

val resourceNames = listOf("Host", "Os", "Process", "ProcessRuntime")
// resourceClassesCsv="io.opentelemetry.sdk.extension.resources.HostResourceProvider,io.opentelemetry.sdk.extension.resources.OsResourceProvider,io.opentelemetry.sdk.extension.resources.ProcessResourceProvider,io.opentelemetry.sdk.extension.resources.ProcessRuntimeResourceProvider"
val resourceClassesCsv = resourceNames.joinToString(",") { "io.opentelemetry.sdk.extension.resources.${it}ResourceProvider" }

// 测试相关的
tasks.withType<Test>().configureEach {
  useJUnitPlatform()

  // There's no real harm in setting this for all tests even if any happen to not be using context
  // propagation.
  jvmArgs("-Dio.opentelemetry.context.enableStrictContext=${rootProject.findProperty("enableStrictContext") ?: true}")
  // TODO(anuraaga): Have agent map unshaded to shaded.
  jvmArgs("-Dio.opentelemetry.javaagent.shaded.io.opentelemetry.context.enableStrictContext=${rootProject.findProperty("enableStrictContext") ?: true}")

  // Disable default resource providers since they cause lots of output we don't need.
  jvmArgs("-Dotel.java.disabled.resource.providers=$resourceClassesCsv")

  val trustStore = project(":testing-common").file("src/misc/testing-keystore.p12")
  // Work around payara not working when this is set for some reason.
  // Don't set for camel as we have tests that interact with AWS and need normal trustStore
  if (project.name != "jaxrs-2.0-payara-testing" && project.description != "camel-2-20") {
    jvmArgumentProviders.add(KeystoreArgumentsProvider(trustStore))
  }

  // All tests must complete within 15 minutes.
  // This value is quite big because with lower values (3 mins) we were experiencing large number of false positives
  timeout.set(Duration.ofMinutes(15))

  retry {
    // You can see tests that were retried by this mechanism in the collected test reports and build scans.
    if (System.getenv().containsKey("CI") || rootProject.hasProperty("retryTests")) {
      maxRetries.set(5)
    }
  }

  reports {
    junitXml.isOutputPerTestCase = true
  }

  testLogging {
    exceptionFormat = TestExceptionFormat.FULL
    showStandardStreams = true
  }
}

class KeystoreArgumentsProvider(
  @InputFile
  @PathSensitive(PathSensitivity.RELATIVE)
  val trustStore: File
) : CommandLineArgumentProvider {
  override fun asArguments(): Iterable<String> = listOf(
    "-Djavax.net.ssl.trustStore=${trustStore.absolutePath}",
    "-Djavax.net.ssl.trustStorePassword=testing"
  )
}

afterEvaluate {
  // gradle.startParameter.projectProperties对构建启动参数的访问，当设置-PtestJavaVersion时执行JavaVersion::toVersion
  // 将通过-PtestJavaVersion指定的具体的java版本转换为JavaVersion对象
  val testJavaVersion = gradle.startParameter.projectProperties["testJavaVersion"]?.let(JavaVersion::toVersion)
  // 如果在启动Gradle构建时使用了-PtestJavaVM=openj9，则返回true，否则返回false
  val useJ9 = gradle.startParameter.projectProperties["testJavaVM"]?.run { this == "openj9" }?: false
  tasks.withType<Test>().configureEach {
    if (testJavaVersion != null) {
      // javaLauncher用于指定构建任务中使用的 Java 启动器
      javaLauncher.set(
        // javaToolchains用于配置和管理不同的Java Toolchain，
        javaToolchains.launcherFor {
          // 指定要使用的 Java 语言版本
          languageVersion.set(JavaLanguageVersion.of(testJavaVersion.majorVersion))
          // 指定要使用的 JVM 实现
          implementation.set(if (useJ9) JvmImplementation.J9 else JvmImplementation.VENDOR_SPECIFIC)
        }
      )
      isEnabled = isEnabled && isJavaVersionAllowed(testJavaVersion)
    } else {
      // We default to testing with Java 11 for most tests, but some tests don't support it, where we change
      // the default test task's version so commands like `./gradlew check` can test all projects regardless
      // of Java version.
      if (!isJavaVersionAllowed(DEFAULT_JAVA_VERSION) && otelJava.maxJavaVersionForTests.isPresent) {
        javaLauncher.set(
          javaToolchains.launcherFor {
            languageVersion.set(JavaLanguageVersion.of(otelJava.maxJavaVersionForTests.get().majorVersion))
          }
        )
      }
    }
  }
}

// CodeNarc插件用于静态代码分析，特别是针对Groovy代码
codenarc {
  // 指定 CodeNarc 使用的配置文件，该文件定义了代码风格和规则
  configFile = rootProject.file("buildscripts/codenarc.groovy")
}

// 用于静态代码分析，以确保代码符合特定的编码标准和风格指南
checkstyle {
  // 指定 Checkstyle 使用的配置文件，该文件定义了代码风格和规则
  configFile = rootProject.file("buildscripts/checkstyle.xml")
  // this version should match the version of google_checks.xml used as basis for above configuration
  // 指定 Checkstyle 工具的版本
  toolVersion = "10.12.4"
  // 设置允许的最大警告数量
  maxWarnings = 0
}

// dependencyCheck用于进行依赖安全性扫描，检查项目中使用的库是否存在已知的安全漏洞
dependencyCheck {
  // 指定在扫描时跳过errorprone、checkstyle和annotationProcessor这三个配置
  skipConfigurations = listOf("errorprone", "checkstyle", "annotationProcessor")
  // 指定一个XML文件，用于抑制已知的误报或特定的安全漏洞
  suppressionFile = "buildscripts/dependency-check-suppressions.xml"
  // 用于设置构建失败的临界 CVSS（通用漏洞评分系统）分数
  failBuildOnCVSS = 7.0f // fail on high or critical CVE
}

// idea是一个Gradle插件，用于生成与IntelliJ IDEA兼容的项目文件。它允许根据Gradle构建脚本生成.iml文件和其他项目配置文件
idea {
  module {
    // 表示在生成的IDEA项目中，不自动下载依赖的Javadoc文档，可以加快项目同步速度
    isDownloadJavadoc = false
    // 表示在生成的IDEA项目中，不自动下载依赖的源代码，可以加快项目同步速度
    isDownloadSources = false
  }
}

// 当项目名称为bootstrap、javaagent、library-autoconfigure、testing其中之一时，将group设置为io.opentelemetry.dummy.父目录名称
// 该分组不会在配置中的任何地方使用，但需要确保它对于每个检测都是唯一的
when (projectDir.name) {
  "bootstrap", "javaagent", "library", "library-autoconfigure", "testing" -> {
    // We don't use this group anywhere in our config, but we need to make sure it is unique per
    // instrumentation so Gradle doesn't merge projects with same name due to a bug in Gradle.
    // https://github.com/gradle/gradle/issues/847
    // In otel.publish-conventions, we set the maven group, which is what matters, to the correct
    // value.
    // 该分组不会在配置中的任何地方使用，但需要确保它对于每个检测都是唯一的
    group = "io.opentelemetry.dummy.${projectDir.parentFile.name}"
  }
}

// 将项目中引用的远程仓库已发布的版本，替换为本地项目模块代码，编译开发调试
configurations.configureEach {
  // resolutionStrategy用于定义如何解析依赖关系
  resolutionStrategy {
    // While you might think preferProjectModules would do this, it doesn't. If this gets hard to
    // manage, we could consider having the io.opentelemetry.instrumentation add information about
    // what modules they add to reference generically.
    // 通过dependencySubstitution指定依赖替换规则
    dependencySubstitution {
      // substitute 配置是依赖替换的一种机制，通常用于模块依赖管理，允许你在构建过程中用本地项目替换外部模块依赖，可以在不改变构建脚本中依赖声明的情况下使用本地的模块版本
      // module中指定的是一个maven坐标，即希望替换的外部模块依赖，using中指定用来替换外部模块的本地项目，project(":instrumentation-api") 指的是Gradle多项目构建中的一个子项目
      // 可以在本地开发和测试时使用本地项目的代码，而不是依赖于已经发布的版本
      substitute(module("io.opentelemetry.instrumentation:opentelemetry-instrumentation-api")).using(project(":instrumentation-api"))
      substitute(module("io.opentelemetry.instrumentation:opentelemetry-instrumentation-api-semconv")).using(project(":instrumentation-api-semconv"))
      substitute(module("io.opentelemetry.instrumentation:opentelemetry-instrumentation-annotations")).using(project(":instrumentation-annotations"))
      substitute(module("io.opentelemetry.instrumentation:opentelemetry-instrumentation-annotations-support")).using(
        project(":instrumentation-annotations-support")
      )
      substitute(module("io.opentelemetry.javaagent:opentelemetry-javaagent-bootstrap")).using(project(":javaagent-bootstrap"))
      substitute(module("io.opentelemetry.javaagent:opentelemetry-javaagent-extension-api")).using(project(":javaagent-extension-api"))
      substitute(module("io.opentelemetry.javaagent:opentelemetry-javaagent-tooling")).using(project(":javaagent-tooling"))
      substitute(module("io.opentelemetry.javaagent:opentelemetry-agent-for-testing")).using(project(":testing:agent-for-testing"))
      substitute(module("io.opentelemetry.javaagent:opentelemetry-testing-common")).using(project(":testing-common"))
      substitute(module("io.opentelemetry.javaagent:opentelemetry-muzzle")).using(project(":muzzle"))
      substitute(module("io.opentelemetry.javaagent:opentelemetry-javaagent")).using(project(":javaagent"))
    }

    // The above substitutions ensure dependencies managed by this BOM for external projects refer to this repo's projects here.
    // Excluding the bom as well helps ensure if we miss a substitution, we get a resolution failure instead of using the
    // wrong version.
    // 上述替换可确保此BOM管理的外部项目的依赖项在此处引用此存储库的项目。排除bom也有助于确保如果我们错过替换，我们会得到解析失败，而不是使用错误的版本。
    // 排除io.opentelemetry.instrumentation:opentelemetry-instrumentation-bom-alpha
    exclude("io.opentelemetry.instrumentation", "opentelemetry-instrumentation-bom-alpha")
  }
}
