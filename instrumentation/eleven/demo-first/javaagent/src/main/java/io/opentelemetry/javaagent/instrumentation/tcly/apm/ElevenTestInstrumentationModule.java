package io.opentelemetry.javaagent.instrumentation.tcly.apm;

import static java.util.Collections.singletonList;

import com.google.auto.service.AutoService;
import io.opentelemetry.javaagent.extension.instrumentation.InstrumentationModule;
import io.opentelemetry.javaagent.extension.instrumentation.TypeInstrumentation;
import java.util.List;

@AutoService(InstrumentationModule.class)
public class ElevenTestInstrumentationModule extends InstrumentationModule {

  public ElevenTestInstrumentationModule() {
    super("eleven", "eleven-utils");
  }

  @Override
  public List<TypeInstrumentation> typeInstrumentations() {
    return singletonList(new ElevenTestInstrumentation());
  }
}
