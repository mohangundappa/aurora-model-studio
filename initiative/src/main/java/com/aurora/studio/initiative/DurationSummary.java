package com.aurora.studio.initiative;

public record DurationSummary(
    long machineDurationMillis,
    long humanWaitDurationMillis,
    Long clientBaselineDurationMillis,
    Long deliveryTimeReductionMillis,
    boolean comparisonClientDeclared,
    String comparisonNote) {}
