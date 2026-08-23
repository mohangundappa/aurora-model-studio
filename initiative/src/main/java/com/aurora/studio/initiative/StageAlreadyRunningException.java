package com.aurora.studio.initiative;

public class StageAlreadyRunningException extends RuntimeException {
  public StageAlreadyRunningException() {
    super("Stage is already running or awaiting approval");
  }
}
