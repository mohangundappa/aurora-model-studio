package com.aurora.studio.extraction;

import java.util.List;
import java.util.Map;

public record StructuralFact(
    String identifier,
    String kind,
    String name,
    Map<String, Object> inputs,
    List<String> referencedTables,
    List<String> referencedColumns,
    String sourcePath,
    String sourceHash,
    String excerpt) {}
