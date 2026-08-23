package com.aurora.studio.discovery;

import com.aurora.studio.common.ResourceNotFoundException;
import com.aurora.studio.common.ValidationException;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/discovery")
public class DiscoveryController {
  private final DiscoveryService service;

  public DiscoveryController(DiscoveryService service) {
    this.service = service;
  }

  @PostMapping("/requirements")
  @ResponseStatus(HttpStatus.CREATED)
  public RequirementResponse register(@RequestBody ModelRequirement requirement) {
    return new RequirementResponse(service.register(requirement), requirement);
  }

  @PostMapping("/runs")
  @ResponseStatus(HttpStatus.CREATED)
  public DiscoveryRun run(@RequestBody RunRequest request) {
    return service.run(request.requirementId(), request.includeCandidates());
  }

  @GetMapping("/runs/{id}")
  public DiscoveryRun get(
      @PathVariable UUID id, @RequestParam(defaultValue = "false") boolean includeCandidates) {
    return service.get(id);
  }

  public record RequirementResponse(UUID id, ModelRequirement requirement) {}

  public record RunRequest(UUID requirementId, boolean includeCandidates) {}

  @ExceptionHandler(ResourceNotFoundException.class)
  @ResponseStatus(HttpStatus.NOT_FOUND)
  public java.util.Map<String, String> notFound(ResourceNotFoundException exception) {
    return java.util.Map.of("error", exception.getMessage());
  }

  @ExceptionHandler({
    ValidationException.class,
    IllegalArgumentException.class,
    IllegalStateException.class
  })
  @ResponseStatus(HttpStatus.BAD_REQUEST)
  public java.util.Map<String, String> error(RuntimeException exception) {
    return java.util.Map.of("error", exception.getMessage());
  }
}
