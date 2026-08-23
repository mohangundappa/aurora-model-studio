package com.aurora.studio.gateway;

public interface LlmGateway {
  LlmResult complete(LlmRequest request);
}
