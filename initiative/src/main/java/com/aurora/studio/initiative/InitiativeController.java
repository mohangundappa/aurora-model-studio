package com.aurora.studio.initiative;

import com.aurora.studio.common.ResourceNotFoundException;
import com.aurora.studio.common.ValidationException;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/initiatives")
public class InitiativeController {
  private final InitiativeService service;

  public InitiativeController(InitiativeService service) {
    this.service = service;
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public Initiative create(@RequestBody CreateInitiativeRequest request) {
    return service.create(request);
  }

  @GetMapping
  public List<Initiative> list() {
    return service.list();
  }

  @GetMapping("/{id}")
  public Initiative get(@PathVariable UUID id) {
    return service.get(id);
  }

  @PostMapping("/{id}/stages/{stage}/run")
  public Initiative run(@PathVariable UUID id, @PathVariable InitiativeStage stage) {
    return service.runStage(id, stage);
  }

  @PostMapping("/{id}/stages/{stage}/decision")
  public Initiative decide(
      @PathVariable UUID id,
      @PathVariable InitiativeStage stage,
      @RequestBody GateDecisionRequest request) {
    return service.decide(id, stage, request);
  }

  @ExceptionHandler(ResourceNotFoundException.class)
  @ResponseStatus(HttpStatus.NOT_FOUND)
  public ErrorResponse notFound(ResourceNotFoundException exception) {
    return new ErrorResponse(exception.getMessage());
  }

  @ExceptionHandler(ValidationException.class)
  @ResponseStatus(HttpStatus.BAD_REQUEST)
  public ErrorResponse validation(ValidationException exception) {
    return new ErrorResponse(exception.getMessage());
  }

  @ExceptionHandler(StageAlreadyRunningException.class)
  @ResponseStatus(HttpStatus.CONFLICT)
  public ErrorResponse conflict(StageAlreadyRunningException exception) {
    return new ErrorResponse(exception.getMessage());
  }

  @ExceptionHandler(IllegalStateException.class)
  @ResponseStatus(HttpStatus.BAD_REQUEST)
  public ErrorResponse badRequest(IllegalStateException exception) {
    return new ErrorResponse(exception.getMessage());
  }

  public record ErrorResponse(String error) {}
}
