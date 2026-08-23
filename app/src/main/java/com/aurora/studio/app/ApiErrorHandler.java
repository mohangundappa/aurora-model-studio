package com.aurora.studio.app;

import com.aurora.studio.initiative.InitiativeStage;
import java.util.Arrays;
import java.util.stream.Collectors;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
public class ApiErrorHandler {
  @ExceptionHandler(MethodArgumentTypeMismatchException.class)
  @ResponseStatus(HttpStatus.BAD_REQUEST)
  public ErrorResponse typeMismatch(MethodArgumentTypeMismatchException exception) {
    if (InitiativeStage.class.equals(exception.getRequiredType())) {
      return new ErrorResponse(
          "stage must be one of: "
              + Arrays.stream(InitiativeStage.values())
                  .map(Enum::name)
                  .collect(Collectors.joining(", ")));
    }
    return new ErrorResponse("Invalid path or query parameter");
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  @ResponseStatus(HttpStatus.BAD_REQUEST)
  public ErrorResponse malformedBody(HttpMessageNotReadableException exception) {
    return new ErrorResponse("Malformed request body");
  }

  @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
  @ResponseStatus(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
  public ErrorResponse unsupportedMediaType(HttpMediaTypeNotSupportedException exception) {
    return new ErrorResponse("Content-Type must be application/json");
  }

  public record ErrorResponse(String error) {}
}
