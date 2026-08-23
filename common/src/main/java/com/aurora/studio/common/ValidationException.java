package com.aurora.studio.common;

public class ValidationException extends IllegalArgumentException {
  public ValidationException(String message) {
    super(message);
  }
}
