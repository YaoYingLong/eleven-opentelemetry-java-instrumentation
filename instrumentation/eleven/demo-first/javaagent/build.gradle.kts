plugins {
  id("otel.javaagent-instrumentation")
}

repositories {
}

dependencies {
  compileOnly("com.google.auto.value:auto-value-annotations")
  annotationProcessor("com.google.auto.value:auto-value")
}
