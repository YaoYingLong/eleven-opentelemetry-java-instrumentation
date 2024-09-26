plugins {
  id("io.opentelemetry.instrumentation.javaagent-testing")

  id("otel.java-conventions")
}

evaluationDependsOn(":testing:agent-for-testing")

dependencies {
  annotationProcessor("com.google.auto.service:auto-service")
  compileOnly("com.google.auto.service:auto-service")

  testImplementation("org.testcontainers:testcontainers")
}

configurations.configureEach {
  if (name.endsWith("testruntimeclasspath", ignoreCase = true)) {
    // Added by agent, don't let Gradle bring it in when running tests.
    exclude(module = "javaagent-bootstrap")
  }
}
