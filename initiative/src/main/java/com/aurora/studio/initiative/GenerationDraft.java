package com.aurora.studio.initiative;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public record GenerationDraft(
    String kind,
    Map<String, Object> payload,
    String outcome,
    UUID invocationId,
    List<ValidatorVerdict> validatorVerdicts) {}
