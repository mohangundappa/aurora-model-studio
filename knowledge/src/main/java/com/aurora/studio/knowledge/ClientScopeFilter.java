package com.aurora.studio.knowledge;

import com.aurora.studio.common.ClientContext;
import com.aurora.studio.common.ClientId;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class ClientScopeFilter extends OncePerRequestFilter {
  private final Set<UUID> knownClients;
  private final ObjectMapper mapper;

  public ClientScopeFilter(
      @Value("${studio.clients:00000000-0000-0000-0000-000000000001}") String clients,
      ObjectMapper mapper) {
    this.mapper = mapper;
    knownClients =
        Arrays.stream(clients.split(","))
            .map(String::trim)
            .map(UUID::fromString)
            .collect(Collectors.toUnmodifiableSet());
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    String header = request.getHeader("X-Aurora-Client");
    if (header == null || header.isBlank()) {
      writeError(response, "X-Aurora-Client header is required");
      return;
    }
    try {
      UUID clientId = ClientId.parse(header).value();
      if (!knownClients.contains(clientId)) {
        writeError(response, "Unknown Aurora client");
        return;
      }
      ClientContext.set(clientId);
      try {
        filterChain.doFilter(request, response);
      } finally {
        ClientContext.clear();
      }
    } catch (IllegalArgumentException exception) {
      writeError(response, exception.getMessage());
    }
  }

  private void writeError(HttpServletResponse response, String message) throws IOException {
    response.setStatus(HttpStatus.BAD_REQUEST.value());
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    mapper.writeValue(response.getWriter(), Map.of("error", message));
  }
}
