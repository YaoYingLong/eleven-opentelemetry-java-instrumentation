import com.diffplug.gradle.spotless.SpotlessExtension

plugins {
  id("com.diffplug.spotless")
}

spotless {
  java {
    googleJavaFormat()
    licenseHeaderFile(
      rootProject.file("buildscripts/spotless.license.java"),
      "(package|import|public|// Includes work from:)"
    )
    toggleOffOn()
    target("src/**/*.java")
  }
  plugins.withId("groovy") {
    groovy {
      licenseHeaderFile(
        rootProject.file("buildscripts/spotless.license.java"),
        "(package|import|(?:abstract )?class)"
      )
      endWithNewline()
    }
  }
  plugins.withId("scala") {
    scala {
      scalafmt()
      licenseHeaderFile(
        rootProject.file("buildscripts/spotless.license.java"),
        "(package|import|public)"
      )
      target("src/**/*.scala")
    }
  }
  plugins.withId("org.jetbrains.kotlin.jvm") {
    kotlin {
      // not sure why it's not using the indent settings from .editorconfig
      ktlint().editorConfigOverride(
        mapOf(
          "indent_size" to "2",
          "continuation_indent_size" to "2",
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
      licenseHeaderFile(
        rootProject.file("buildscripts/spotless.license.java"),
        "(package|import|class|// Includes work from:)"
      )
    }
  }
  kotlinGradle {
    // not sure why it's not using the indent settings from .editorconfig
    ktlint().editorConfigOverride(
      mapOf(
        "indent_size" to "2",
        "continuation_indent_size" to "2",
        "max_line_length" to "160",
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
      indentWithSpaces()
      // 删除行尾多余的空白字符
      trimTrailingWhitespace()
      // 确保每个文件末尾都有一个空行
      endWithNewline()
    }
    predeclareDeps()
  }

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
