import ru.vyarus.gradle.plugin.animalsniffer.AnimalSniffer

plugins {
  `java-library`

  // 检查编译生成的字节码是否与特定的Java API版本或其他API兼容
  id("ru.vyarus.animalsniffer")
}

dependencies {
  add("signature", "com.toasttab.android:gummy-bears-api-21:0.3.0:coreLib@signature")
}

animalsniffer {
  sourceSets = listOf(java.sourceSets.main.get())
}
tasks.withType<AnimalSniffer> {
  // always having declared output makes this task properly participate in tasks up-to-date checks
  reports.text.required.set(true)
}
