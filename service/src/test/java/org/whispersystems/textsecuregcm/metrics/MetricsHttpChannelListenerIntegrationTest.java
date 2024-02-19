/*
 * Copyright 2024 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.whispersystems.textsecuregcm.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.common.net.HttpHeaders;
import io.dropwizard.core.Application;
import io.dropwizard.core.Configuration;
import io.dropwizard.core.setup.Environment;
import io.dropwizard.testing.junit5.DropwizardAppExtension;
import io.dropwizard.testing.junit5.DropwizardExtensionsSupport;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import java.io.IOException;
import java.net.URI;
import java.security.Principal;
import java.time.Duration;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.stream.Stream;
import javax.annotation.Priority;
import javax.security.auth.Subject;
import javax.servlet.DispatcherType;
import javax.ws.rs.GET;
import javax.ws.rs.InternalServerErrorException;
import javax.ws.rs.NotAuthorizedException;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Priorities;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.client.Client;
import javax.ws.rs.container.ContainerRequestContext;
import javax.ws.rs.container.ContainerRequestFilter;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Response;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.HttpChannel;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.util.component.Container;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.WebSocketListener;
import org.eclipse.jetty.websocket.client.ClientUpgradeRequest;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.eclipse.jetty.websocket.server.config.JettyWebSocketServletContainerInitializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.whispersystems.textsecuregcm.filters.RemoteAddressFilter;
import org.whispersystems.textsecuregcm.storage.ClientReleaseManager;
import org.whispersystems.websocket.WebSocketResourceProviderFactory;
import org.whispersystems.websocket.configuration.WebSocketConfiguration;
import org.whispersystems.websocket.setup.WebSocketEnvironment;

@ExtendWith(DropwizardExtensionsSupport.class)
class MetricsHttpChannelListenerIntegrationTest {

  private static final TrafficSource TRAFFIC_SOURCE = TrafficSource.HTTP;
  private static final MeterRegistry METER_REGISTRY = mock(MeterRegistry.class);
  private static final Counter COUNTER = mock(Counter.class);
  private static final AtomicReference<CompletableFuture<Void>> LISTENER_FUTURE_REFERENCE = new AtomicReference<>();

  private static final DropwizardAppExtension<Configuration> EXTENSION = new DropwizardAppExtension<>(
      MetricsHttpChannelListenerIntegrationTest.TestApplication.class);

  @AfterEach
  void teardown() {
    reset(METER_REGISTRY);
    reset(COUNTER);
  }

  @ParameterizedTest
  @MethodSource
  @SuppressWarnings("unchecked")
  void testSimplePath(String requestPath, String expectedTagPath, String expectedResponse, int expectedStatus)
      throws Exception {

    final CompletableFuture<Void> listenerCompleteFuture = new CompletableFuture<>();
    LISTENER_FUTURE_REFERENCE.set(listenerCompleteFuture);

    final ArgumentCaptor<Iterable<Tag>> tagCaptor = ArgumentCaptor.forClass(Iterable.class);
    when(METER_REGISTRY.counter(anyString(), any(Iterable.class)))
        .thenAnswer(a -> MetricsHttpChannelListener.REQUEST_COUNTER_NAME.equals(a.getArgument(0, String.class))
            ? COUNTER
            : mock(Counter.class))
        .thenReturn(COUNTER);

    Client client = EXTENSION.client();

    final Supplier<String> request = () -> client.target(
            String.format("http://localhost:%d%s", EXTENSION.getLocalPort(), requestPath))
        .request()
        .header(HttpHeaders.USER_AGENT, "Signal-Android/4.53.7 (Android 8.1)")
        .get(String.class);

    switch (expectedStatus) {
      case 200: {
        final String response = request.get();
        assertEquals(expectedResponse, response);
        break;
      }
      case 401: {
        assertThrows(NotAuthorizedException.class, request::get);
        break;
      }
      case 500: {
        assertThrows(InternalServerErrorException.class, request::get);
        break;
      }
      default: {
        fail("unexpected status");
      }
    }

    listenerCompleteFuture.get(1000, TimeUnit.MILLISECONDS);

    verify(METER_REGISTRY).counter(eq(MetricsHttpChannelListener.REQUEST_COUNTER_NAME), tagCaptor.capture());
    verify(COUNTER).increment();

    final Iterable<Tag> tagIterable = tagCaptor.getValue();
    final Set<Tag> tags = new HashSet<>();

    for (final Tag tag : tagIterable) {
      tags.add(tag);
    }

    assertEquals(5, tags.size());
    assertTrue(tags.contains(Tag.of(MetricsHttpChannelListener.PATH_TAG, expectedTagPath)));
    assertTrue(tags.contains(Tag.of(MetricsHttpChannelListener.METHOD_TAG, "GET")));
    assertTrue(tags.contains(Tag.of(MetricsHttpChannelListener.STATUS_CODE_TAG, String.valueOf(expectedStatus))));
    assertTrue(
        tags.contains(Tag.of(MetricsHttpChannelListener.TRAFFIC_SOURCE_TAG, TRAFFIC_SOURCE.name().toLowerCase())));
    assertTrue(tags.contains(Tag.of(UserAgentTagUtil.PLATFORM_TAG, "android")));
  }


  @Nested
  class WebSocket {

    private WebSocketClient client;

    @BeforeEach
    void setUp() throws Exception {
      client = new WebSocketClient();
      client.start();
    }

    @AfterEach
    void tearDown() throws Exception {
      client.stop();
    }

    @Test
    void testWebSocketUpgrade() throws Exception {
      final ClientUpgradeRequest upgradeRequest = new ClientUpgradeRequest();
      upgradeRequest.setHeader(HttpHeaders.USER_AGENT, "Signal-Android/4.53.7 (Android 8.1)");

      final ArgumentCaptor<Iterable<Tag>> tagCaptor = ArgumentCaptor.forClass(Iterable.class);
      when(METER_REGISTRY.counter(anyString(), any(Iterable.class)))
          .thenAnswer(a -> MetricsHttpChannelListener.REQUEST_COUNTER_NAME.equals(a.getArgument(0, String.class))
              ? COUNTER
              : mock(Counter.class))
          .thenReturn(COUNTER);

      final CompletableFuture<Void> connectionComplete = new CompletableFuture<>();

      client.connect(new WebSocketListener() {
                       @Override
                       public void onWebSocketConnect(final Session session) {
                         session.close(1000, "OK");
                         connectionComplete.complete(null);
                       }
                     },
          URI.create(String.format("ws://localhost:%d%s", EXTENSION.getLocalPort(), "/v1/websocket")), upgradeRequest);

      connectionComplete.get(1, TimeUnit.SECONDS);

      verify(METER_REGISTRY).counter(eq(MetricsHttpChannelListener.REQUEST_COUNTER_NAME), tagCaptor.capture());
      verify(COUNTER).increment();

      final Iterable<Tag> tagIterable = tagCaptor.getValue();
      final Set<Tag> tags = new HashSet<>();

      for (final Tag tag : tagIterable) {
        tags.add(tag);
      }

      assertEquals(5, tags.size());
      assertTrue(tags.contains(Tag.of(MetricsHttpChannelListener.PATH_TAG, "/v1/websocket")));
      assertTrue(tags.contains(Tag.of(MetricsHttpChannelListener.METHOD_TAG, "GET")));
      assertTrue(tags.contains(Tag.of(MetricsHttpChannelListener.STATUS_CODE_TAG, String.valueOf(101))));
      assertTrue(
          tags.contains(Tag.of(MetricsHttpChannelListener.TRAFFIC_SOURCE_TAG, TRAFFIC_SOURCE.name().toLowerCase())));
      assertTrue(tags.contains(Tag.of(UserAgentTagUtil.PLATFORM_TAG, "android")));
    }
  }

  static Stream<Arguments> testSimplePath() {
    return Stream.of(
        Arguments.of("/v1/test/hello", "/v1/test/hello", "Hello!", 200),
        Arguments.of("/v1/test/greet/friend", "/v1/test/greet/{name}",
            String.format(TestResource.GREET_FORMAT, "friend"), 200),
        Arguments.of("/v1/test/greet/unauthorized", "/v1/test/greet/{name}", null, 401),
        Arguments.of("/v1/test/greet/exception", "/v1/test/greet/{name}", null, 500)
    );
  }

  public static class TestApplication extends Application<Configuration> {

    @Override
    public void run(final Configuration configuration,
        final Environment environment) throws Exception {

      final MetricsHttpChannelListener metricsHttpChannelListener = new MetricsHttpChannelListener(
          METER_REGISTRY,
          mock(ClientReleaseManager.class),
          Set.of("/v1/websocket")
      );

      metricsHttpChannelListener.configure(environment);
      environment.lifecycle().addEventListener(new TestListener(LISTENER_FUTURE_REFERENCE));

      environment.servlets().addFilter("RemoteAddressFilter", new RemoteAddressFilter(true))
          .addMappingForUrlPatterns(EnumSet.of(DispatcherType.REQUEST), false, "/*");

      environment.jersey().register(new TestResource());
      environment.jersey().register(new TestAuthFilter());

      // WebSocket set up
      final WebSocketConfiguration webSocketConfiguration = new WebSocketConfiguration();

      WebSocketEnvironment<TestPrincipal> webSocketEnvironment = new WebSocketEnvironment<>(environment,
          webSocketConfiguration, Duration.ofMillis(1000));

      webSocketEnvironment.jersey().register(new TestResource());

      JettyWebSocketServletContainerInitializer.configure(environment.getApplicationContext(), null);

      WebSocketResourceProviderFactory<TestPrincipal> webSocketServlet = new WebSocketResourceProviderFactory<>(
          webSocketEnvironment, TestPrincipal.class, webSocketConfiguration,
          RemoteAddressFilter.REMOTE_ADDRESS_ATTRIBUTE_NAME);

      environment.servlets().addServlet("WebSocket", webSocketServlet)
          .addMapping("/v1/websocket");
    }
  }

  @Priority(Priorities.AUTHENTICATION)
  static class TestAuthFilter implements ContainerRequestFilter {

    @Override
    public void filter(final ContainerRequestContext requestContext) throws IOException {
      if (requestContext.getUriInfo().getPath().contains("unauthorized")) {
        throw new WebApplicationException(Response.Status.UNAUTHORIZED);
      }
    }
  }

  /**
   * A simple listener to signal that {@link HttpChannel.Listener} has completed its work, since its onComplete() is on
   * a different thread from the one that sends the response, creating a race condition between the listener and the
   * test assertions
   */
  static class TestListener implements HttpChannel.Listener, Container.Listener, LifeCycle.Listener {

    private final AtomicReference<CompletableFuture<Void>> completableFutureAtomicReference;

    TestListener(AtomicReference<CompletableFuture<Void>> completableFutureAtomicReference) {

      this.completableFutureAtomicReference = completableFutureAtomicReference;
    }

    @Override
    public void onComplete(final Request request) {
      completableFutureAtomicReference.get().complete(null);
    }

    @Override
    public void beanAdded(final Container parent, final Object child) {
      if (child instanceof Connector connector) {
          connector.addBean(this);
      }
    }

    @Override
    public void beanRemoved(final Container parent, final Object child) {

    }

  }

  @Path("/v1/test")
  public static class TestResource {

    static final String GREET_FORMAT = "Hello, %s!";


    @GET
    @Path("/hello")
    public String testGetHello() {
      return "Hello!";
    }

    @GET
    @Path("/greet/{name}")
    public String testGreetByName(@PathParam("name") String name, @Context ContainerRequestContext context) {

      if ("exception".equals(name)) {
        throw new InternalServerErrorException();
      }

      return String.format(GREET_FORMAT, name);
    }
  }

  public static class TestPrincipal implements Principal {

    // Principal implementation

    @Override
    public String getName() {
      return null;
    }

    @Override
    public boolean implies(final Subject subject) {
      return false;
    }
  }

}
