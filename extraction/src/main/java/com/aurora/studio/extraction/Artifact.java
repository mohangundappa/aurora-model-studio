package com.aurora.studio.extraction;

import java.nio.file.Path;

public record Artifact(
    Path path, String kind, String name, String excerpt, StructuralFact structuralFact) {}
