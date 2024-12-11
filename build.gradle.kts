import java.time.Duration

plugins {
  id("idea")

  id("io.github.gradle-nexus.publish-plugin")
  // conventions模块中定义的插件，用于代码格式化和样式检查
  id("otel.spotless-conventions")
}

apply(from = "version.gradle.kts")

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

  transitionCheckOptions {
    // We have many artifacts so Maven Central takes a long time on its compliance checks. This sets
    // the timeout for waiting for the repository to close to a comfortable 50 minutes.
    maxRetries.set(300)
    delayBetween.set(Duration.ofSeconds(10))
  }
}

description = "OpenTelemetry instrumentations for Java"

if (project.findProperty("skipTests") as String? == "true") {
  subprojects {
    tasks.withType<Test>().configureEach {
      enabled = false
    }
  }
}

tasks {
  val listTestsInPartition by registering {
    group = "Help"
    description = "List test tasks in given partition"

    // total of 4 partitions (see modulo 4 below)
    var testPartition = (project.findProperty("testPartition") as String?)?.toInt()
    if (testPartition == null) {
      throw GradleException("Test partition must be specified")
    } else if (testPartition < 0 || testPartition >= 4) {
      throw GradleException("Invalid test partition")
    }

    val partitionTasks = ArrayList<Test>()
    var testPartitionCounter = 0
    subprojects {
      // relying on predictable ordering of subprojects
      // (see https://docs.gradle.org/current/dsl/org.gradle.api.Project.html#N14CB4)
      // since we are splitting these tasks across different github action jobs
      val enabled = testPartitionCounter++ % 4 == testPartition
      if (enabled) {
        tasks.withType<Test>().configureEach {
          partitionTasks.add(this)
        }
      }
    }

    doLast {
      File("test-tasks.txt").printWriter().use { writer ->
        partitionTasks.forEach { task ->
          var taskPath = task.project.path + ":" + task.name
          // smoke tests are run separately
          // :instrumentation:test runs all instrumentation tests
          if (taskPath != ":smoke-tests:test" && taskPath != ":instrumentation:test") {
            writer.println(taskPath)
          }
        }
      }
    }

    // disable all tasks to stop build
    subprojects {
      tasks.configureEach {
        enabled = false
      }
    }
  }
}

if (gradle.startParameter.taskNames.any { it.equals("listTestsInPartition") }) {
  // disable all tasks to stop build
  project.tasks.configureEach {
    if (this.name != "listTestsInPartition") {
      enabled = false
    }
  }
}
