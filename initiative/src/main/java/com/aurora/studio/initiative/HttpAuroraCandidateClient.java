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

  public HttpAuroraCandidateClient(
      ObjectMapper mapper,
      @Value("${studio.handoff.aurora-base-url:http://localhost:8080}") String baseUrl) {
    this.mapper = mapper;
    this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    this.baseUrl = baseUrl.replaceAll("/+$", "");
  }

  @Override
  public Registration register(String name, Map<String, Object> payload, String packageHash) {
    String endpoint = endpoint(name);
    try {
      HttpRequest request =
          HttpRequest.newBuilder(URI.create(endpoint))
              .timeout(Duration.ofSeconds(10))
              .header("Content-Type", "application/json")
              .header("Idempotency-Key", packageHash)
              .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(payload)))
              .build();
      HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != 201) {
        return new Registration(false, response.statusCode(), null, null, "AURORA_REJECTED");
      }
      JsonNode body = mapper.readTree(response.body());
      String candidateId = body.hasNonNull("candidateId") ? body.get("candidateId").asText() : null;
      String status = body.hasNonNull("status") ? body.get("status").asText() : null;
      if (candidateId == null || !"AWAITING_WEIGHTS".equals(status)) {
        return new Registration(false, 201, candidateId, status, "AURORA_RESPONSE_INVALID");
      }
      return new Registration(true, 201, candidateId, status, null);
    } catch (IOException | InterruptedException | RuntimeException exception) {
      if (exception instanceof InterruptedException) Thread.currentThread().interrupt();
      return new Registration(false, null, null, null, "AURORA_UNREACHABLE");
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
