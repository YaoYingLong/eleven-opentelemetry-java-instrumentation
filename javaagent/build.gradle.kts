import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import com.github.jk1.license.filter.LicenseBundleNormalizer
import com.github.jk1.license.render.InventoryMarkdownReportRenderer

plugins {
  id("com.github.jk1.dependency-license-report")

  id("otel.java-conventions")
  id("otel.publish-conventions")
  id("io.opentelemetry.instrumentation.javaagent-shadowing")
}

description = "OpenTelemetry Javaagent"
group = "io.opentelemetry.javaagent"


/*
 * configurations.creating用于定义一个自定义的依赖组时，特定的任务需要一组特定的依赖，或者需要在构建生命周期中以某种方式隔离这些依赖
 * - isCanBeResolved为true表示，configurations被消费者使用，将其管理的一组dependencies解析为文件
 * - isCanBeConsumed为true表示，configurations被生产者使用，公开其管理的artifacts及其dependencies以供其他项目使用
 * - extendsFrom表示子configurations继承父configurations的dependencies
 */

// this configuration collects libs that will be placed in the bootstrap classloader
// 此配置收集将放置在Bootstrap类加载器中的libs
val bootstrapLibs by configurations.creating {
  // isCanBeResolved为true表示，configurations被消费者使用，将其管理的一组dependencies解析为文件
  isCanBeResolved = true
  // 若isCanBeConsumed为true表示，configurations被生产者使用，公开其管理的artifacts及其dependencies以供其他项目使用
  isCanBeConsumed = false
}
// this configuration collects only required instrumentations and agent machinery
// 此配置仅收集所需的插桩和代理机制
val baseJavaagentLibs by configurations.creating {
  isCanBeResolved = true
  isCanBeConsumed = false
}
// this configuration collects libs that will be placed in the agent classloader, isolated from the instrumented application code
// 此配置收集将放置在AgentClassloader中的库，这些库与插桩的应用程序代码隔离
val javaagentLibs by configurations.creating {
  isCanBeResolved = true
  isCanBeConsumed = false
  // 子configurations继承父configurations的dependencies
  extendsFrom(baseJavaagentLibs)
}

// exclude dependencies that are to be placed in bootstrap from agent libs - they won't be added to inst/
// 从代理库中排除要放置在引导程序中的依赖项 - 它们不会添加到/inst
listOf(baseJavaagentLibs, javaagentLibs).forEach {
  it.run {
    exclude("io.opentelemetry", "opentelemetry-api")
    exclude("io.opentelemetry", "opentelemetry-api-events")
    exclude("io.opentelemetry.semconv", "opentelemetry-semconv")
    // metrics advice API
    exclude("io.opentelemetry", "opentelemetry-extension-incubator")
  }
}

val licenseReportDependencies by configurations.creating {
  extendsFrom(bootstrapLibs)
  extendsFrom(baseJavaagentLibs)
}

dependencies {
  bootstrapLibs(project(":instrumentation-api"))
  // opentelemetry-api is an api dependency of :instrumentation-api, but opentelemetry-api-events is not
  bootstrapLibs("io.opentelemetry:opentelemetry-api-events")
  bootstrapLibs(project(":instrumentation-api-semconv"))
  bootstrapLibs(project(":instrumentation-annotations-support"))
  bootstrapLibs(project(":javaagent-bootstrap"))

  // extension-api contains both bootstrap packages and agent packages
  bootstrapLibs(project(":javaagent-extension-api")) {
    // exclude javaagent dependencies from the bootstrap classpath
    exclude("net.bytebuddy")
    exclude("org.ow2.asm")
    exclude("io.opentelemetry", "opentelemetry-sdk")
    exclude("io.opentelemetry", "opentelemetry-sdk-extension-autoconfigure")
    exclude("io.opentelemetry", "opentelemetry-sdk-extension-autoconfigure-spi")
  }
  baseJavaagentLibs(project(":javaagent-extension-api"))

  baseJavaagentLibs(project(":javaagent-tooling"))
  baseJavaagentLibs(project(":javaagent-internal-logging-application"))
  baseJavaagentLibs(project(":javaagent-internal-logging-simple", configuration = "shadow"))
  baseJavaagentLibs(project(":muzzle"))
  baseJavaagentLibs(project(":instrumentation:opentelemetry-api:opentelemetry-api-1.0:javaagent"))
  baseJavaagentLibs(project(":instrumentation:opentelemetry-api:opentelemetry-api-1.4:javaagent"))
  baseJavaagentLibs(project(":instrumentation:opentelemetry-instrumentation-api:javaagent"))
  baseJavaagentLibs(project(":instrumentation:opentelemetry-instrumentation-annotations-1.16:javaagent"))
  baseJavaagentLibs(project(":instrumentation:executors:javaagent"))
  baseJavaagentLibs(project(":instrumentation:internal:internal-application-logger:javaagent"))
  baseJavaagentLibs(project(":instrumentation:internal:internal-class-loader:javaagent"))
  baseJavaagentLibs(project(":instrumentation:internal:internal-eclipse-osgi-3.6:javaagent"))
  baseJavaagentLibs(project(":instrumentation:internal:internal-lambda:javaagent"))
  baseJavaagentLibs(project(":instrumentation:internal:internal-reflection:javaagent"))
  baseJavaagentLibs(project(":instrumentation:internal:internal-url-class-loader:javaagent"))

  // concurrentlinkedhashmap-lru and weak-lock-free are copied in to the instrumentation-api module
  licenseReportDependencies("com.googlecode.concurrentlinkedhashmap:concurrentlinkedhashmap-lru:1.4.2")
  licenseReportDependencies("com.blogspot.mydailyjava:weak-lock-free:0.18")
  licenseReportDependencies(project(":javaagent-internal-logging-simple")) // need the non-shadow versions

  testCompileOnly(project(":javaagent-bootstrap"))
  testCompileOnly(project(":javaagent-extension-api"))

  testImplementation("com.google.guava:guava")
  testImplementation("io.opentelemetry:opentelemetry-sdk")
  testImplementation("io.opentracing.contrib.dropwizard:dropwizard-opentracing:0.2.2")
}

val javaagentDependencies = dependencies

// collect all bootstrap and javaagent instrumentation dependencies
// 用于访问根项目的子项目instrumentation的所有子项目，目的是通过每个项目使用的不同的插件将子项目依赖放入到不同的依赖分组中
project(":instrumentation").subprojects {
  val subProj = this

  // 在instrumentation的子项目中应用了otel.javaagent-bootstrap插件时，执行代码块
  plugins.withId("otel.javaagent-bootstrap") {
    javaagentDependencies.run {
      // 举个例子这里其实就相当于：bootstrapLibs(project(:instrumentation:armeria-1.3:testing))
      add(bootstrapLibs.name, project(subProj.path))
    }
  }

  // 在instrumentation的子项目中应用了otel.javaagent-instrumentation插件时，执行代码块
  plugins.withId("otel.javaagent-instrumentation") {
    javaagentDependencies.run {
      // javaagentLibs.name值就是javaagentLibs
      // subProj.path获取子项目的路径，使得构建脚本可以将该子项目作为依赖添加到指定的配置中
      add(javaagentLibs.name, project(subProj.path))
    }
  }

  // 在instrumentation的子项目中应用了otel.sdk-extension插件时，执行代码块
  plugins.withId("otel.sdk-extension") {
    javaagentDependencies.run {
      add(javaagentLibs.name, project(subProj.path))
    }
  }
}

tasks {
  // 用于处理目录中的资源文件
  processResources {
    // 将源文件rootPath下的licenses目录下的文件放到META-INF/licenses下
    from(rootProject.file("licenses")) {
      into("META-INF/licenses")
    }
  }

  // 在Gradle的任务容器中注册一个新的ShadowJar任务，且委托给buildBootstrapLibs，任务在需要时才被实际配置
  // 这里其实将通过Bootstrap类加载器加载的代码单独打成一个jar包
  val buildBootstrapLibs by registering(ShadowJar::class) {
    configurations = listOf(bootstrapLibs)

    // exclude the agent part of the javaagent-extension-api; these classes will be added in relocate tasks
    // javaagent-extension-api项目即被bootstrapLibs依赖分组引入，也被baseJavaagentLibs依赖分组引入
    // extension目录下的代码需要放到/inst目录，所以需要排除，只将bootstrap目录下的代码打包到bootstrapLibs.jar中
    exclude("io/opentelemetry/javaagent/extension/**")

    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    archiveFileName.set("bootstrapLibs.jar")
  }

  // 将baseJavaagentLibs中依赖的项目单独打成一个baseJavaagentLibs-relocated.jar包
  val relocateBaseJavaagentLibs by registering(ShadowJar::class) {
    configurations = listOf(baseJavaagentLibs)

    // 从依赖中排除bootstrap相关的依赖
    excludeBootstrapClasses()

    duplicatesStrategy = DuplicatesStrategy.FAIL

    archiveFileName.set("baseJavaagentLibs-relocated.jar")
  }

  // 将instrumentation目录下的代码，单独打成一个javaagentLibs-relocated.jar包
  val relocateJavaagentLibs by registering(ShadowJar::class) {
    configurations = listOf(javaagentLibs)

    // 从依赖中排除bootstrap相关的依赖
    excludeBootstrapClasses()

    duplicatesStrategy = DuplicatesStrategy.FAIL

    archiveFileName.set("javaagentLibs-relocated.jar")
  }

  // Includes everything needed for OOTB experience
  // existing的作用是获取类型为ShadowJar的已注册任务的引用
  val shadowJar by existing(ShadowJar::class) {
    // 依赖buildBootstrapLibs任务打的jar包
    dependsOn(buildBootstrapLibs)
    // 把buildBootstrapLibs任务打的jar包文件拷贝到当前任务
    from(zipTree(buildBootstrapLibs.get().archiveFile))

    // 将instrumentation目录下的代码单独打成的jar包拷贝到当前任务
    dependsOn(relocateJavaagentLibs)
    // 将传入的jar的代码文件树放到inst目录下，且将所有的.class后缀改为.classdata后缀
    isolateClasses(relocateJavaagentLibs.get().archiveFile)

    duplicatesStrategy = DuplicatesStrategy.FAIL

    // 生成opentelemetry-javaagent-1.31.0.jar包，也是我们最终使用的包
    archiveClassifier.set("")

    // 设置manifest内容
    manifest {
      attributes(jar.get().manifest.attributes)
      attributes(
        "Main-Class" to "io.opentelemetry.javaagent.OpenTelemetryAgent",
        "Agent-Class" to "io.opentelemetry.javaagent.OpenTelemetryAgent",
        "Premain-Class" to "io.opentelemetry.javaagent.OpenTelemetryAgent",
        "Can-Redefine-Classes" to true,
        "Can-Retransform-Classes" to true,
      )
    }
  }

  // Includes only the agent machinery and required instrumentations
  val baseJavaagentJar by registering(ShadowJar::class) {
    // 依赖buildBootstrapLibs任务打的jar包
    dependsOn(buildBootstrapLibs)
    // 把buildBootstrapLibs任务打的jar包文件拷贝到当前任务
    from(zipTree(buildBootstrapLibs.get().archiveFile))

    // 将baseJavaagentLibs中依赖的项目单独打成一个baseJavaagentLibs-relocated.jar引入当前任务
    dependsOn(relocateBaseJavaagentLibs)
    // 将传入的jar的代码文件树放到inst目录下，且将所有的.class后缀改为.classdata后缀
    isolateClasses(relocateBaseJavaagentLibs.get().archiveFile)

    duplicatesStrategy = DuplicatesStrategy.FAIL

    // 生成opentelemetry-javaagent-1.31.0-base.jar包
    archiveClassifier.set("base")

    manifest {
      attributes(shadowJar.get().manifest.attributes)
    }
  }

  // opentelemetry-javaagent-1.31.0-dontuse.jar，就是对生成的jar包进行一个分类
  jar {
    // Empty jar that cannot be used for anything and isn't published.
    archiveClassifier.set("dontuse")
  }

  val baseJar by configurations.creating {
    isCanBeConsumed = true
    isCanBeResolved = false
  }

  // 将baseJavaagentJar文件添加到baseJar配置中，标记为项目的一个构建产物
  artifacts {
    add("baseJar", baseJavaagentJar)
  }

  // 执行assemble任务时，会先执行shadowJar和baseJavaagentJar任务
  assemble {
    dependsOn(shadowJar, baseJavaagentJar)
  }

  if (findProperty("removeJarVersionNumbers") == "true") {
    withType<AbstractArchiveTask>().configureEach {
      archiveVersion.set("")
    }
  }

  withType<Test>().configureEach {
    dependsOn(shadowJar)

    jvmArgs("-Dotel.javaagent.debug=true")

    jvmArgumentProviders.add(JavaagentProvider(shadowJar.flatMap { it.archiveFile }))

    testLogging {
      events("started")
    }
  }

  val cleanLicenses by registering(Delete::class) {
    delete(rootProject.file("licenses"))
  }

  val generateLicenseReportEnabled =
    gradle.startParameter.taskNames.any { it.equals("generateLicenseReport") }
  named("generateLicenseReport").configure {
    dependsOn(cleanLicenses)
    finalizedBy(":spotlessApply")
    // disable licence report generation unless this task is explicitly run
    // the files produced by this task are used by other tasks without declaring them as dependency
    // which gradle considers an error
    enabled = enabled && generateLicenseReportEnabled
  }
  if (generateLicenseReportEnabled) {
    project.parent?.tasks?.getByName("spotlessMisc")?.dependsOn(named("generateLicenseReport"))
  }

  // Because we reconfigure publishing to only include the shadow jar, the Gradle metadata is not correct.
  // Since we are fully bundled and have no dependencies, Gradle metadata wouldn't provide any advantage over
  // the POM anyways so in practice we shouldn't be losing anything.
  withType<GenerateModuleMetadata>().configureEach {
    enabled = false
  }
}

// Don't publish non-shadowed jar (shadowJar is in shadowRuntimeElements)
with(components["java"] as AdhocComponentWithVariants) {
  configurations.forEach {
    withVariantsFromConfiguration(configurations["apiElements"]) {
      skip()
    }
    withVariantsFromConfiguration(configurations["runtimeElements"]) {
      skip()
    }
  }
}

licenseReport {
  outputDir = rootProject.file("licenses").absolutePath

  renderers = arrayOf(InventoryMarkdownReportRenderer())

  configurations = arrayOf(licenseReportDependencies.name)

  excludeBoms = true

  excludeGroups = arrayOf(
    "io\\.opentelemetry\\.instrumentation",
    "io\\.opentelemetry\\.javaagent",
    "io\\.opentelemetry\\.dummy\\..*",
  )

  excludes = arrayOf(
    "io.opentelemetry:opentelemetry-bom-alpha",
    "opentelemetry-java-instrumentation:dependencyManagement",
  )

  filters = arrayOf(LicenseBundleNormalizer("$projectDir/license-normalizer-bundle.json", true))
}

// 将传入的jar的代码文件树放到inst目录下，且将所有的.class后缀改为.classdata后缀
fun CopySpec.isolateClasses(jar: Provider<RegularFile>) {
  // zipTree的作用是用于解压传入的jar文件，且将解压后的内容作为文件树返回
  from(zipTree(jar)) {
    // important to keep prefix "inst" short, as it is prefixed to lots of strings in runtime mem
    into("inst")
    rename("(^.*)\\.class\$", "\$1.classdata")
    // Rename LICENSE file since it clashes with license dir on non-case sensitive FSs (i.e. Mac)
    rename("""^LICENSE$""", "LICENSE.renamed")
    exclude("META-INF/INDEX.LIST")
    exclude("META-INF/*.DSA")
    exclude("META-INF/*.SF")
  }
}

// exclude bootstrap projects from javaagent libs - they won't be added to inst/
// 从依赖中排除bootstrap相关的依赖
fun ShadowJar.excludeBootstrapClasses() {
  dependencies {
    exclude(project(":instrumentation-api"))
    exclude(project(":instrumentation-api-semconv"))
    exclude(project(":instrumentation-annotations-support"))
    exclude(project(":javaagent-bootstrap"))
  }

  // exclude the bootstrap part of the javaagent-extension-api
  // dependencies中通过baseJavaagentLibs(project(":javaagent-extension-api"))，将javaagent-extension-api项目生成的代码放入baseJavaagentLibs分组中
  // 这里是将javaagent-extension-api项目中的bootstrap目录下的代码，从baseJavaagentLibs中排除掉，通过bootstrap classloader来加载
  exclude("io/opentelemetry/javaagent/bootstrap/**")
}

class JavaagentProvider(
  @InputFile
  @PathSensitive(PathSensitivity.RELATIVE)
  val agentJar: Provider<RegularFile>,
) : CommandLineArgumentProvider {
  override fun asArguments(): Iterable<String> = listOf(
    "-javaagent:${file(agentJar).absolutePath}",
  )
}
