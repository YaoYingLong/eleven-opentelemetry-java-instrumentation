import io.opentelemetry.javaagent.muzzle.generation.ClasspathByteBuddyPlugin
import io.opentelemetry.javaagent.muzzle.generation.ClasspathTransformation
import net.bytebuddy.ClassFileVersion
import net.bytebuddy.build.gradle.ByteBuddySimpleTask
import net.bytebuddy.build.gradle.Transformation

plugins {
  `java-library`
}

/**
 * Starting from version 1.10.15, ByteBuddy gradle plugin transformation task autoconfiguration is
 * hardcoded to be applied to javaCompile task. This causes the dependencies to be resolved during
 * an afterEvaluate that runs before any afterEvaluate specified in the build script, which in turn
 * makes it impossible to add dependencies in afterEvaluate. Additionally the autoconfiguration will
 * attempt to scan the entire project for tasks which depend on the compile task, to make each task
 * that depends on compile also depend on the transformation task. This is an extremely inefficient
 * operation in this project to the point of causing a stack overflow in some environments.
 *
 * 为了避免自动配置的所有问题，此插件手动配置 ByteBuddy 转换任务。这也使得它可以应用于Java以外的源语言。
 * 转换任务配置为在编译任务和类任务之间运行，假设没有其他任务直接依赖于编译任务，而是其他任务依赖于类任务。
 * 与 ByteBuddy 插件在 1.10.14 之前的版本中的工作方式相反，这会更改编译任务输出目录，从 1.10.15 开始，
 * 该插件不允许源目录和目标目录相同。然后转换任务写入编译任务的原始输出目录。
 *
 * <p>To avoid all the issues with autoconfiguration, this plugin manually configures the ByteBuddy
 * transformation task. This also allows it to be applied to source languages other than Java. The
 * transformation task is configured to run between the compile and the classes tasks, assuming no
 * other task depends directly on the compile task, but instead other tasks depend on classes task.
 * Contrary to how the ByteBuddy plugin worked in versions up to 1.10.14, this changes the compile
 * task output directory, as starting from 1.10.15, the plugin does not allow the source and target
 * directories to be the same. The transformation task then writes to the original output directory
 * of the compile task.
 */

val LANGUAGES = listOf("java", "scala", "kotlin")
val pluginName = "io.opentelemetry.javaagent.tooling.muzzle.generation.MuzzleCodeGenerationPlugin"

/*
 * configurations.creating用于定义一个自定义的依赖组时，特定的任务需要一组特定的依赖，或者需要在构建生命周期中以某种方式隔离这些依赖
 * - isCanBeResolved为true表示，configurations被消费者使用，将其管理的一组dependencies解析为文件
 * - isCanBeConsumed为true表示，configurations被生产者使用，公开其管理的artifacts及其dependencies以供其他项目使用
 * - extendsFrom表示子configurations继承父configurations的dependencies
 */
val codegen by configurations.creating {
  isCanBeConsumed = false
  isCanBeResolved = true
}

// 在io.opentelemetry.instrumentation.base.gradle.kts中有定义
val sourceSet = sourceSets.main.get()
// 当sourceSet.output.resourcesDir不为空时，执行codegen.plus(project.files(sourceSet.output.resourcesDir))
val inputClasspath = (sourceSet.output.resourcesDir?.let { codegen.plus(project.files(it)) }?: codegen)
  .plus(sourceSet.output.dirs) // needed to support embedding shadowed modules into instrumentation
  .plus(configurations.runtimeClasspath.get())

val languageTasks = LANGUAGES.map { language ->
  if (fileTree("src/${sourceSet.name}/${language}").isEmpty) {
    return@map null
  }
  val compileTaskName = sourceSet.getCompileTaskName(language)
  if (!tasks.names.contains(compileTaskName)) {
    return@map null
  }
  val compileTask = tasks.named(compileTaskName)
  createLanguageTask(compileTask, "byteBuddy${language.replaceFirstChar(Char::titlecase)}")
}.filterNotNull()

tasks {
  named(sourceSet.classesTaskName) {
    dependsOn(languageTasks)
  }
}

fun createLanguageTask(
  compileTaskProvider: TaskProvider<*>, name: String): TaskProvider<*> {
  return tasks.register<ByteBuddySimpleTask>(name) {
    setGroup("Byte Buddy")
    outputs.cacheIf { true }
    classFileVersion = ClassFileVersion.JAVA_V8
    var transformationClassPath = inputClasspath
    val compileTask = compileTaskProvider.get()
    if (compileTask is AbstractCompile) {
      val classesDirectory = compileTask.destinationDirectory.asFile.get()
      val rawClassesDirectory: File = File(classesDirectory.parent, "${classesDirectory.name}raw")
        .absoluteFile
      dependsOn(compileTask)
      compileTask.destinationDirectory.set(rawClassesDirectory)
      source = rawClassesDirectory
      target = classesDirectory
      classPath = compileTask.classpath.plus(rawClassesDirectory)
      transformationClassPath = transformationClassPath.plus(files(rawClassesDirectory))
      dependsOn(compileTask, sourceSet.processResourcesTaskName)
    }

    transformations.add(createTransformation(transformationClassPath, pluginName))
  }
}

fun createTransformation(classPath: FileCollection, pluginClassName: String): Transformation {
  return ClasspathTransformation(classPath, pluginClassName).apply {
    plugin = ClasspathByteBuddyPlugin::class.java
  }
}
