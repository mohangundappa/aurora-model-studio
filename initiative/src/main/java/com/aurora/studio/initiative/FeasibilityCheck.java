package com.aurora.studio.initiative;

import java.util.UUID;

public record FeasibilityCheck(String name, String status, UUID artifactId, String reason) {}
