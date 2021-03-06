/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.javaagent.instrumentation.elasticsearch.transport.v5_3;

import static io.opentelemetry.javaagent.instrumentation.elasticsearch.transport.ElasticsearchTransportClientTracer.tracer;
import static java.util.Collections.singletonList;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;
import io.opentelemetry.javaagent.tooling.InstrumentationModule;
import io.opentelemetry.javaagent.tooling.TypeInstrumentation;
import java.util.List;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.elasticsearch.action.Action;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.ActionRequest;
import org.elasticsearch.action.ActionResponse;

/** Beginning in version 5.3.0, DocumentRequest was renamed to DocWriteRequest. */
@AutoService(InstrumentationModule.class)
public class Elasticsearch53TransportClientInstrumentationModule extends InstrumentationModule {
  public Elasticsearch53TransportClientInstrumentationModule() {
    super("elasticsearch-transport", "elasticsearch-transport-5.3", "elasticsearch");
  }

  @Override
  public List<TypeInstrumentation> typeInstrumentations() {
    return singletonList(new AbstractClientInstrumentation());
  }

  public static class AbstractClientInstrumentation implements TypeInstrumentation {
    @Override
    public ElementMatcher<TypeDescription> typeMatcher() {
      // If we want to be more generic, we could instrument the interface instead:
      // .and(safeHasSuperType(named("org.elasticsearch.client.ElasticsearchClient"))))
      return named("org.elasticsearch.client.support.AbstractClient");
    }

    @Override
    public Map<? extends ElementMatcher<? super MethodDescription>, String> transformers() {
      return singletonMap(
          isMethod()
              .and(named("execute"))
              .and(takesArgument(0, named("org.elasticsearch.action.Action")))
              .and(takesArgument(1, named("org.elasticsearch.action.ActionRequest")))
              .and(takesArgument(2, named("org.elasticsearch.action.ActionListener"))),
          Elasticsearch53TransportClientInstrumentationModule.class.getName()
              + "$ElasticsearchTransportClientAdvice");
    }
  }

  public static class ElasticsearchTransportClientAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnter(
        @Advice.Argument(0) Action action,
        @Advice.Argument(1) ActionRequest actionRequest,
        @Advice.Local("otelSpan") Span span,
        @Advice.Local("otelScope") Scope scope,
        @Advice.Argument(value = 2, readOnly = false)
            ActionListener<ActionResponse> actionListener) {

      span = tracer().startSpan(null, action);
      scope = tracer().startScope(span);

      tracer().onRequest(span, action.getClass(), actionRequest.getClass());
      actionListener = new TransportActionListener<>(actionRequest, actionListener, span);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void stopSpan(
        @Advice.Thrown Throwable throwable,
        @Advice.Local("otelSpan") Span span,
        @Advice.Local("otelScope") Scope scope) {
      scope.close();

      if (throwable != null) {
        tracer().endExceptionally(span, throwable);
      }
    }
  }
}
