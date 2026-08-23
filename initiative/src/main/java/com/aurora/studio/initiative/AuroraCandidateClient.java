package com.aurora.studio.initiative;

import java.util.Map;

public interface AuroraCandidateClient {
  Registration register(String name, Map<String, Object> payload, String packageHash);

  default String endpoint(String name) {
    return "/api/models/" + name + "/candidates";
  }

  record Registration(
      boolean successful,
      Integer responseStatus,
      String candidateId,
      String candidateStatus,
      String failureCode) {}
}
