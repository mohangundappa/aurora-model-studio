package com.aurora.studio.initiative;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;

class HttpAuroraCandidateClientTest {
  @Test
  void acceptsOnlyTheAwaitingWeightsSuccessContractAndSendsIdempotencyKey() throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
    try {
      server.createContext(
          "/api/models/model/candidates",
          exchange -> {
            assertThat(exchange.getRequestHeaders().getFirst("Idempotency-Key"))
                .isEqualTo("package-hash");
            assertThat(exchange.getRequestHeaders().getFirst("X-Aurora-Studio-Token"))
                .isEqualTo("studio-token");
            assertThat(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8))
                .contains("\"packageHash\":\"package-hash\"");
            byte[] response =
                "{\"candidateId\":\"candidate-1\",\"status\":\"AWAITING_WEIGHTS\"}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(201, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
          });
      server.start();

      HttpAuroraCandidateClient client =
          new HttpAuroraCandidateClient(
              new ObjectMapper(),
              "http://localhost:" + server.getAddress().getPort(),
              "studio-token");

      AuroraCandidateClient.Registration registration =
          client.register("model", Map.of("packageHash", "package-hash"), "package-hash");

      assertThat(registration.successful()).isTrue();
      assertThat(registration.responseStatus()).isEqualTo(201);
      assertThat(registration.candidateId()).isEqualTo("candidate-1");
      assertThat(registration.candidateStatus()).isEqualTo("AWAITING_WEIGHTS");
    } finally {
      server.stop(0);
    }
  }

  @Test
  void containsAnUnreachableAuroraAsAStableFailureCode() {
    HttpAuroraCandidateClient client =
        new HttpAuroraCandidateClient(new ObjectMapper(), "http://127.0.0.1:1", "studio-token");

    AuroraCandidateClient.Registration registration =
        client.register("model", Map.of("packageHash", "package-hash"), "package-hash");

    assertThat(registration.successful()).isFalse();
    assertThat(registration.failureCode()).isEqualTo("AURORA_UNREACHABLE");
  }

  @Test
  void classifiesMalformedSuccessBodyAsResponseInvalid() throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
    try {
      server.createContext(
          "/api/models/model/candidates",
          exchange -> {
            exchange.sendResponseHeaders(201, 0);
            exchange.getResponseBody().write("{not json".getBytes(StandardCharsets.UTF_8));
            exchange.close();
          });
      server.start();

      HttpAuroraCandidateClient client =
          new HttpAuroraCandidateClient(
              new ObjectMapper(),
              "http://localhost:" + server.getAddress().getPort(),
              "studio-token");

      AuroraCandidateClient.Registration registration =
          client.register("model", Map.of("packageHash", "package-hash"), "package-hash");

      assertThat(registration.successful()).isFalse();
      assertThat(registration.responseStatus()).isEqualTo(201);
      assertThat(registration.failureCode()).isEqualTo("AURORA_RESPONSE_INVALID");
    } finally {
      server.stop(0);
    }
  }

  @Test
  void classifiesAuthenticationResponsesAsRejectedWithStatus() throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
    try {
      server.createContext(
          "/api/models/model/candidates",
          exchange -> {
            exchange.sendResponseHeaders(401, 0);
            exchange.close();
          });
      server.createContext(
          "/api/models/unconfigured/candidates",
          exchange -> {
            exchange.sendResponseHeaders(503, 0);
            exchange.close();
          });
      server.start();

      HttpAuroraCandidateClient client =
          new HttpAuroraCandidateClient(
              new ObjectMapper(),
              "http://localhost:" + server.getAddress().getPort(),
              "studio-token");

      AuroraCandidateClient.Registration unauthorized =
          client.register("model", Map.of("packageHash", "package-hash"), "package-hash");

      assertThat(unauthorized.successful()).isFalse();
      assertThat(unauthorized.responseStatus()).isEqualTo(401);
      assertThat(unauthorized.failureCode()).isEqualTo("AURORA_REJECTED");

      AuroraCandidateClient.Registration unavailable =
          client.register("unconfigured", Map.of("packageHash", "package-hash"), "package-hash");

      assertThat(unavailable.successful()).isFalse();
      assertThat(unavailable.responseStatus()).isEqualTo(503);
      assertThat(unavailable.failureCode()).isEqualTo("AURORA_REJECTED");
    } finally {
      server.stop(0);
    }
  }

  @Test
  void refusesAnonymousRegistrationWhenTokenIsNotConfigured() {
    HttpAuroraCandidateClient client =
        new HttpAuroraCandidateClient(new ObjectMapper(), "http://127.0.0.1:1", "");

    AuroraCandidateClient.Registration registration =
        client.register("model", Map.of("packageHash", "package-hash"), "package-hash");

    assertThat(registration.failureCode()).isEqualTo("AURORA_NOT_CONFIGURED");
  }
}
