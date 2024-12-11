import com.diffplug.gradle.spotless.SpotlessExtension

plugins {
  id("com.diffplug.spotless")
}

spotless {
  java {
    // 使用 Google Java Format
    googleJavaFormat()
    // 在项目中src/**/*.java匹配的每个文件中包含../buildscripts/spotless.license.java文件内容对应的Licence头
    licenseHeaderFile(
      rootProject.file("buildscripts/spotless.license.java"),
      "(package|import|public|// Includes work from:)"
    )
    // 允许你在代码中临时关闭和重新开启格式化检查
    toggleOffOn()
    target("src/**/*.java")
  }
  // 如果项目中包含了groovy插件
  plugins.withId("groovy") {
    groovy {
      // 在项目中src/**/*.java匹配的每个文件中包含../buildscripts/spotless.license.java文件内容对应的Licence头
      licenseHeaderFile(
        rootProject.file("buildscripts/spotless.license.java"),
        "(package|import|(?:abstract )?class)"
      )
      endWithNewline()
    }
  }

  // 如果项目中包含了scala插件
  plugins.withId("scala") {
    scala {
      scalafmt()
      // 在项目中src/**/*.java匹配的每个文件中包含../buildscripts/spotless.license.java文件内容对应的Licence头
      licenseHeaderFile(
        rootProject.file("buildscripts/spotless.license.java"),
        "(package|import|public)"
      )
      target("src/**/*.scala")
    }
  }
  // 如果项目中包含了org.jetbrains.kotlin.jvm插件
  plugins.withId("org.jetbrains.kotlin.jvm") {
    kotlin {
      // not sure why it's not using the indent settings from .editorconfig
      ktlint().editorConfigOverride(
        mapOf(
          // 设置缩进大小为2
          "indent_size" to "2",
          // 设置续行缩进大小为2
          "continuation_indent_size" to "2",
          // 设置最大行长度为160
          "max_line_length" to "160",
          "ktlint_standard_no-wildcard-imports" to "disabled",
          "ktlint_standard_package-name" to "disabled",
          // ktlint does not break up long lines, it just fails on them
          "ktlint_standard_max-line-length" to "disabled",
          // ktlint makes it *very* hard to locate where this actually happened
          "ktlint_standard_trailing-comma-on-call-site" to "disabled",
          // depends on ktlint_standard_wrapping
          "ktlint_standard_trailing-comma-on-declaration-site" to "disabled",
          // also very hard to find out where this happens
          "ktlint_standard_wrapping" to "disabled"
        )
      )
      // 在项目中src/**/*.java匹配的每个文件中包含../buildscripts/spotless.license.java文件内容对应的Licence头
      licenseHeaderFile(
        rootProject.file("buildscripts/spotless.license.java"),
        "(package|import|class|// Includes work from:)"
      )
    }
  }
  kotlinGradle {
    // not sure why it's not using the indent settings from .editorconfig
    // editorConfigOverride方法允许在不依赖.editorconfig文件的情况下，直接在Gradle配置中覆盖或指定ktlint的格式化规则
    // 不希望在项目中维护多个配置文件的情况特别有用
    ktlint().editorConfigOverride(
      mapOf(
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
        "ktlint_standard_wrapping" to "disabled",
        // we use variable names like v1_10Deps
        "ktlint_standard_property-naming" to "disabled"
      )
    )
  }
}

// Use root declared tool deps to avoid issues with high concurrency.
// see https://github.com/diffplug/spotless/tree/main/plugin-gradle#dependency-resolution-modes
if (project == rootProject) {
  spotless {
    // 为特定文件类型定义自定义格式化规则
    format("misc") {
      target(
        ".gitignore",
        ".gitattributes",
        ".gitconfig",
        ".editorconfig",
        "**/*.md",
        "**/*.sh",
        "**/*.dockerfile",
        "**/gradle.properties"
      )
      // 指定代码缩进时使用空格的数量，不带参数时，Spotless会使用默认的空格数量进行缩进，默认值为4个空格
      indentWithSpaces()
      // 删除行尾多余的空白字符
      trimTrailingWhitespace()
      // 确保每个文件末尾都有一个空行
      endWithNewline()
    }
    // 通过提前声明Spotless插件所需的依赖来减少构建时的依赖解析开销
    predeclareDeps()
  }

  // 访问名为spotlessPredeclare的扩展
  with(extensions["spotlessPredeclare"] as SpotlessExtension) {
    java {
      googleJavaFormat()
    }
    scala {
      scalafmt()
    }
    kotlin {
      ktlint()
    }
    kotlinGradle {
      ktlint()
    }
  }
}
