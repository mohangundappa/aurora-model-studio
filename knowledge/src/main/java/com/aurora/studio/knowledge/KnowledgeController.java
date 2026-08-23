package com.aurora.studio.knowledge;

import java.util.List;
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
@RequestMapping("/api/knowledge")
public class KnowledgeController {
  private final KnowledgeService service;

  public KnowledgeController(KnowledgeService service) {
    this.service = service;
  }

  @PostMapping
  public KnowledgeObject create(
      @RequestBody KnowledgeService.Draft draft,
      @RequestParam(defaultValue = "local-demo-actor") String actor) {
    return service.create(draft, actor);
  }

  @PostMapping("/{id}/submit-review")
  public KnowledgeObject submit(
      @PathVariable UUID id,
      @RequestParam(defaultValue = "local-demo-actor") String actor,
      @RequestParam(required = false) String comment) {
    return service.submitForReview(id, actor, comment);
  }

  @PostMapping("/{id}/approve")
  public KnowledgeObject approve(
      @PathVariable UUID id,
      @RequestParam(defaultValue = "local-demo-actor") String actor,
      @RequestParam(required = false) String comment) {
    return service.approve(id, actor, comment);
  }

  @PostMapping("/{id}/deprecate")
  public KnowledgeObject deprecate(
      @PathVariable UUID id,
      @RequestParam(defaultValue = "local-demo-actor") String actor,
      @RequestParam(required = false) String comment) {
    return service.deprecate(id, actor, comment);
  }

  @GetMapping
  public List<KnowledgeObject> search(
      @RequestParam(required = false) String type,
      @RequestParam(required = false) String domain,
      @RequestParam(required = false) String useCase,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String tag,
      @RequestParam(required = false) String text,
      @RequestParam(defaultValue = "false") boolean includeCandidates) {
    return service.search(type, domain, useCase, status, tag, text, includeCandidates);
  }

  @GetMapping("/{id}")
  public KnowledgePackage get(
      @PathVariable UUID id, @RequestParam(defaultValue = "false") boolean includeCandidates) {
    return service.get(id, includeCandidates);
  }

  @GetMapping("/{id}/evidence")
  public List<KnowledgeEvidence> evidence(
      @PathVariable UUID id, @RequestParam(defaultValue = "false") boolean includeCandidates) {
    return service.getSourceEvidence(id, includeCandidates);
  }

  @GetMapping("/governance-rules")
  public List<KnowledgePackage> governanceRules(
      @RequestParam(required = false) String enforcementPoint) {
    return service.governanceRules(enforcementPoint);
  }

  @GetMapping("/{id}/impact")
  public KnowledgeService.Impact impact(
      @PathVariable UUID id,
      @RequestParam(defaultValue = "2") int depth,
      @RequestParam(defaultValue = "false") boolean includeCandidates) {
    return service.analyzeImpact(id, depth, includeCandidates);
  }

  @ExceptionHandler(KnowledgeNotFoundException.class)
  @ResponseStatus(HttpStatus.NOT_FOUND)
  public ErrorResponse notFound(RuntimeException exception) {
    return new ErrorResponse(exception.getMessage());
  }

  @ExceptionHandler(KnowledgeConflictException.class)
  @ResponseStatus(HttpStatus.CONFLICT)
  public ErrorResponse conflict(RuntimeException exception) {
    return new ErrorResponse(exception.getMessage());
  }

  @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
  @ResponseStatus(HttpStatus.BAD_REQUEST)
  public ErrorResponse badRequest(RuntimeException exception) {
    return new ErrorResponse(exception.getMessage());
  }

  public record ErrorResponse(String error) {}
}
