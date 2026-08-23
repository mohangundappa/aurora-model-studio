package com.aurora.studio.knowledge;

import com.aurora.studio.common.ClientContext;
import com.aurora.studio.common.ClientId;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class ClientScopeFilter extends OncePerRequestFilter {
  private final Set<UUID> knownClients;

  public ClientScopeFilter(
      @Value("${studio.clients:00000000-0000-0000-0000-000000000001}") String clients) {
    knownClients =
        java.util.Arrays.stream(clients.split(","))
            .map(String::trim)
            .map(UUID::fromString)
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    String header = request.getHeader("X-Aurora-Client");
    if (header == null || header.isBlank()) {
      response.sendError(HttpStatus.BAD_REQUEST.value(), "X-Aurora-Client header is required");
      return;
    }
    try {
      UUID clientId = ClientId.parse(header).value();
      if (!knownClients.contains(clientId)) {
        response.sendError(HttpStatus.BAD_REQUEST.value(), "Unknown Aurora client");
        return;
      }
      ClientContext.set(clientId);
      try {
        filterChain.doFilter(request, response);
      } finally {
        ClientContext.clear();
      }
    } catch (IllegalArgumentException exception) {
      response.sendError(HttpStatus.BAD_REQUEST.value(), exception.getMessage());
    }
  }
}
