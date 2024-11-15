package io.opentelemetry.javaagent.bootstrap;

import org.junit.jupiter.api.Test;
import java.io.File;
import java.lang.reflect.Constructor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * @author by YingLong on 2024/11/15
 */
public class AgentClassLoaderJavaTest {


  @Test
  public void test() throws ClassNotFoundException {
    String userDir = System.getProperty("user.dir");
    String rootPath = userDir.substring(0, userDir.lastIndexOf(File.separator));
    String agentPath = rootPath + "/javaagent/build/libs/opentelemetry-javaagent-1.31.0.jar";
    File javaagentFile = new File(agentPath);
    if (!javaagentFile.exists()) {
      System.out.println("Could not find opentelemetry-javaagent.jar");
      return;
    }
    AgentClassLoader loader = new AgentClassLoader(javaagentFile, "inst/", false);
    {
      Class<?> aClass = loader.loadClass("java.io.File");
      // 通过Bootstrap ClassLoader加载
      assertNull(aClass.getClassLoader());
    }
    {
      Class<?> aClass = loader.loadClass("io.opentelemetry.javaagent.tooling.AgentVersion");
      // 通过Bootstrap ClassLoader加载
      assertEquals(aClass.getClassLoader().getClass().getName(), "io.opentelemetry.javaagent.bootstrap.AgentClassLoader");
    }
    {
      Class<?> aClass = loader.loadClass("io.opentelemetry.javaagent.extension.matcher.Utils");
      // 通过Bootstrap ClassLoader加载
      assertEquals(aClass.getClassLoader().getClass().getName(), "io.opentelemetry.javaagent.bootstrap.AgentClassLoader");
    }
    {
      Class<?> aClass = loader.loadClass("io.opentelemetry.instrumentation.api.internal.ClassNames");
      // 通过Bootstrap ClassLoader加载
      assertEquals(aClass.getClassLoader().getClass().getName(), "io.opentelemetry.javaagent.bootstrap.AgentClassLoader");
    }
  }



}
