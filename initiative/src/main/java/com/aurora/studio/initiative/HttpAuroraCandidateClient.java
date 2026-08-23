package com.aurora.studio.initiative;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class HttpAuroraCandidateClient implements AuroraCandidateClient {
  private final ObjectMapper mapper;
  private final HttpClient client;
  private final String baseUrl;
  private final String token;

  public HttpAuroraCandidateClient(
      ObjectMapper mapper,
      @Value("${studio.handoff.aurora-base-url:http://localhost:8080}") String baseUrl,
      @Value("${studio.handoff.aurora-token:}") String token) {
    this.mapper = mapper;
    this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    this.baseUrl = baseUrl.replaceAll("/+$", "");
    this.token = token;
  }

  @Override
  public Registration register(String name, Map<String, Object> payload, String packageHash) {
    if (token == null || token.isBlank()) {
      return new Registration(false, null, null, null, "AURORA_NOT_CONFIGURED");
    }
    String endpoint = endpoint(name);
    HttpResponse<String> response;
    try {
      HttpRequest request =
          HttpRequest.newBuilder(URI.create(endpoint))
              .timeout(Duration.ofSeconds(10))
              .header("Content-Type", "application/json")
              .header("Idempotency-Key", packageHash)
              .header("X-Aurora-Studio-Token", token)
              .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(payload)))
              .build();
      response = client.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != 201) {
        return new Registration(false, response.statusCode(), null, null, "AURORA_REJECTED");
      }
    } catch (IOException exception) {
      return new Registration(false, null, null, null, "AURORA_UNREACHABLE");
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      return new Registration(false, null, null, null, "AURORA_UNREACHABLE");
    }
    try {
      JsonNode body = mapper.readTree(response.body());
      String candidateId =
          body != null && body.hasNonNull("candidateId") ? body.get("candidateId").asText() : null;
      String status =
          body != null && body.hasNonNull("status") ? body.get("status").asText() : null;
      if (candidateId == null || candidateId.isBlank() || !"AWAITING_WEIGHTS".equals(status)) {
        return new Registration(false, 201, candidateId, status, "AURORA_RESPONSE_INVALID");
      }
      return new Registration(true, 201, candidateId, status, null);
    } catch (IOException | RuntimeException exception) {
      return new Registration(false, 201, null, null, "AURORA_RESPONSE_INVALID");
    }
  }

  @Override
  public String endpoint(String name) {
    return baseUrl + "/api/models/" + encode(name) + "/candidates";
  }

  private String encode(String value) {
    return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
  }
}
