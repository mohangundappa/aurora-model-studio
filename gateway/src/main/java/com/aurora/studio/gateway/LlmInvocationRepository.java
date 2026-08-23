package com.aurora.studio.gateway;

import com.aurora.studio.common.ClientContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class LlmInvocationRepository {
  private final JdbcTemplate jdbc;
  private final ObjectMapper mapper;

  public LlmInvocationRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
    this.jdbc = jdbc;
    this.mapper = mapper;
  }

  public UUID record(LlmRequest request, LlmResult result, String provider, String model) {
    return jdbc.queryForObject(
        "insert into llm_invocations(client_id,task_id,provider,model,prompt_template_id,prompt_template_version,prompt_hash,schema_id,input_tokens,output_tokens,cost,latency_millis,retry_count,outcome,recorded_at) values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,now()) returning id",
        UUID.class,
        ClientContext.require(),
        request.taskId(),
        provider,
        model,
        request.promptTemplateId(),
        request.promptTemplateVersion(),
        request.promptHash(),
        String.valueOf(request.responseSchema().getOrDefault("$id", request.taskId())),
        result.inputTokens(),
        result.outputTokens(),
        result.cost(),
        result.latencyMillis(),
        result.retryCount(),
        result.outcome().name());
  }

  public String json(Object value) {
    try {
      return mapper.writeValueAsString(value);
    } catch (JsonProcessingException exception) {
      throw new IllegalArgumentException("invalid invocation payload", exception);
    }
  }
}
