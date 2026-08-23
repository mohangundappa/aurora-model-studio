package com.aurora.studio.discovery;

import java.util.UUID;

public record DiscoveryEvidence(UUID id, String sourceUri, String excerpt, boolean synthetic) {}
