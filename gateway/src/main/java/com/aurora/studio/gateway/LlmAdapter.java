package com.aurora.studio.gateway;

public interface LlmAdapter {
  LlmAdapterResponse complete(LlmRequest request);
}
