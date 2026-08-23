package com.aurora.studio.initiative;

import java.util.UUID;

public record ArtifactReference(String type, UUID id, boolean synthetic) {}
