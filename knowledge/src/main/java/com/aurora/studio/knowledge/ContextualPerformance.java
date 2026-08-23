package com.aurora.studio.knowledge;

public record ContextualPerformance(
    Long sampleSize, Double metricValue, String metric, String context) {}
