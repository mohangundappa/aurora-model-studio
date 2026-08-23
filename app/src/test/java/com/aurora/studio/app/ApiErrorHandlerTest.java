package com.aurora.studio.app;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aurora.studio.common.ResourceNotFoundException;
import com.aurora.studio.common.ValidationException;
import com.aurora.studio.initiative.CreateInitiativeRequest;
import com.aurora.studio.initiative.InitiativeController;
import com.aurora.studio.initiative.InitiativeService;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ApiErrorHandlerTest {
  private final InitiativeService service = mock(InitiativeService.class);
  private final MockMvc mockMvc =
      MockMvcBuilders.standaloneSetup(new InitiativeController(service))
          .setControllerAdvice(new ApiErrorHandler())
          .build();

  @Test
  void unknownStageHasJsonMessageListingValidStages() throws Exception {
    mockMvc
        .perform(
            post("/api/initiatives/{id}/stages/UNKNOWN/run", UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON))
        .andExpect(status().isBadRequest())
        .andExpect(
            jsonPath("$.error").value(org.hamcrest.Matchers.containsString("REQUIREMENT_INTAKE")));
  }

  @Test
  void malformedUuidHasJsonErrorWithoutEchoingPath() throws Exception {
    mockMvc
        .perform(
            post("/api/initiatives/not-a-uuid/stages/DATA_FEASIBILITY/run")
                .contentType(MediaType.APPLICATION_JSON))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("Invalid path or query parameter"));
  }

  @Test
  void wrongContentTypeHasJsonError() throws Exception {
    mockMvc
        .perform(post("/api/initiatives").contentType(MediaType.TEXT_PLAIN).content("{}"))
        .andExpect(status().isUnsupportedMediaType())
        .andExpect(jsonPath("$.error").value("Content-Type must be application/json"));
  }

  @Test
  void validationAndNotFoundRemainDistinct() throws Exception {
    when(service.create(any(CreateInitiativeRequest.class)))
        .thenThrow(new ValidationException("clientBaselineDurationMillis must not be negative"));
    mockMvc
        .perform(
            post("/api/initiatives")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"requirementId\":\"00000000-0000-0000-0000-000000000001\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("clientBaselineDurationMillis must not be negative"));

    when(service.get(any(UUID.class)))
        .thenThrow(new ResourceNotFoundException("Initiative was not found"));
    mockMvc
        .perform(get("/api/initiatives/{id}", UUID.randomUUID()))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error").value("Initiative was not found"));
  }
}
