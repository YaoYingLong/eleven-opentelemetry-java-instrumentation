/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.javaagent.instrumentation.hystrix;

import static java.util.Collections.singletonList;

import com.google.auto.service.AutoService;
import io.opentelemetry.javaagent.extension.instrumentation.InstrumentationModule;
import io.opentelemetry.javaagent.extension.instrumentation.TypeInstrumentation;
import java.util.List;

@AutoService(InstrumentationModule.class)
public class HystrixInstrumentationModule extends InstrumentationModule {

  public HystrixInstrumentationModule() {
    super("hystrix", "hystrix-1.4");
  }

  @Override
  public boolean isHelperClass(String className) {
    return className.equals("rx.__OpenTelemetryTracingUtil");
  }

  @Override
  public boolean isIndyModule() {
    // rx.__OpenTelemetryTracingUtil is used for accessing a package private field
    return false;
  }

  @Override
  public List<TypeInstrumentation> typeInstrumentations() {
    return singletonList(new HystrixCommandInstrumentation());
  }
}
