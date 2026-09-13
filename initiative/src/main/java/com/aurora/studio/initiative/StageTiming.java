package com.aurora.studio.initiative;

import java.time.Duration;
import java.time.Instant;

final class StageTiming {
  private StageTiming() {}

  static long elapsed(Instant start, Instant end) {
    return Math.max(0, Duration.between(start, end).toMillis());
  }
}
