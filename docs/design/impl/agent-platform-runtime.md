# Agent platform runtime

**Status: TO BUILD, except for the existing `gateway` boundary named below.**
This specification adds a Java `agentplatform` module for tools, inbound
bounded-loop controls, attempt persistence and evidence-citation enforcement.
The Python agent service runs the graph; Java remains the boundary that
executes tools, proxies completions, owns the ledger and enforces verdict
controls. It does not add a new provider boundary, workflow state machine,
validator threshold or human approval path.

Realises [the agent platform rail](../cross-cutting-agent-platform.md), part of [the implementation specification index](README.md).

## 1. Scope

Build typed tool dispatch, fixed-budget inbound controls, append-only attempt
and tool-call records, and a deterministic control that prevents uncited agent
fields from satisfying a judge. `LlmGateway`, `GatewayService`, `LlmRequest`,
`LlmResult`, `LlmOutcome`, `EmbeddingProvider` and `llm_invocations` are
existing gateway capabilities. The module must never depend on `initiative`;
validators remain in `initiative` and are registered there as tools.

## 2. Module and package layout

Add `agentplatform` to the root Maven `<modules>` list. Add
`agentplatform/pom.xml` with these dependencies:

```text
com.aurora.studio:common:${project.version}
com.aurora.studio:gateway:${project.version}
spring-boot-starter-jdbc
jackson-databind
postgresql
spring-boot-starter-test (test)
org.testcontainers:postgresql (test)
org.testcontainers:junit-jupiter (test)
```

`initiative/pom.xml` then adds `com.aurora.studio:agentplatform` and the
existing `initiative` module registers its validator tools. Put proposed Java
files under
`agentplatform/src/main/java/com/aurora/studio/agentplatform/`:

```text
Tool.java, ToolCall.java, ToolResult.java, ToolSuccess.java, ToolRefusal.java
ToolRegistry.java, LoopBudget.java, LoopTermination.java
LoopStateSnapshot.java
AttemptLedger.java, AttemptLedgerRepository.java
AgentAttempt.java, ToolCallRecord.java
EvidenceCitation.java, EvidenceBoundValue.java
EvidenceCitationVerifier.java, AgentPlatformProperties.java
AgentPlatformInboundController.java
AgentToolDispatchRequest.java, AgentCompletionRequest.java
AgentAttemptAppendRequest.java, AgentToolCallAppendRequest.java
AgentCompletionResponse.java
```

Put `V15__agent_attempt_ledger.sql` and
`V16__capability_loop_state.sql` in
`app/src/main/resources/db/migration/`. This follows the current Flyway
location and avoids claiming that app does not package migrations.

## 3. Types

The following are new types. They deliberately use typed records rather than
`Map<String,Object>` for control state; JSON payloads remain at the boundary
where the existing gateway already uses them.

```java
public interface Tool<I, O> {
  String name();
  Class<I> inputType();
  Class<O> outputType();
  ToolResult<O> execute(ToolCall<I> call);
}

public interface ToolRegistry {
  void register(String capability, Tool<?, ?> tool);
  Optional<Tool<?, ?>> find(String capability, String toolName);
  ToolResult<?> execute(ToolCall<?> call);
}

public record ToolCall<I>(
    UUID callId,
    UUID loopId,
    int attempt,
    String capability,
    String toolName,
    I input,
    Instant requestedAt) {}

public sealed interface ToolResult<O>
    permits ToolSuccess, ToolRefusal {
  boolean successful();
  String code();
  String message();
  O value();
}

public record ToolSuccess<O>(O value) implements ToolResult<O> {
  public boolean successful() { return true; }
  public String code() { return "OK"; }
  public String message() { return ""; }
}

public record ToolRefusal<O>(String code, String message)
    implements ToolResult<O> {
  public boolean successful() { return false; }
  public O value() { return null; }
}

public record LoopBudget(
    int maxAttempts,
    int maxToolCalls,
    Duration attemptTimeout,
    Duration toolTimeout,
    Duration loopTimeout) {}

public enum LoopTermination {
  ACCEPTED,
  HUMAN_GATE_REQUIRED,
  BUDGET_EXHAUSTED,
  PROVIDER_REFUSED,
  PROVIDER_FAILED,
  TOOL_REFUSED,
  VALIDATION_FAILED
}

public interface AttemptLedger {
  UUID beginLoop(UUID initiativeId, UUID stageAttemptId, String capability);
  UUID appendAttempt(UUID loopId, AgentAttempt attempt);
  UUID appendToolCall(UUID loopId, UUID attemptId, ToolCallRecord call);
}

public interface AttemptLedgerRepository extends AttemptLedger {
  Optional<LoopStateSnapshot> findLoop(UUID clientId, UUID loopId);
}

public record AgentAttempt(
    int attempt,
    Map<String, Object> input,
    Map<String, Object> draft,
    List<Map<String, Object>> verdicts,
    String outcome,
    UUID llmInvocationId,
    Instant startedAt,
    Instant completedAt) {}

public record ToolCallRecord(
    UUID callId,
    String toolName,
    Map<String, Object> input,
    Map<String, Object> output,
    String outcome,
    String refusalCode,
    Instant startedAt,
    Instant completedAt) {}

public record AgentToolDispatchRequest(
    UUID loopId,
    UUID attemptId,
    int attempt,
    String capability,
    String toolName,
    JsonNode input) {}

public record AgentCompletionRequest(
    UUID loopId,
    UUID attemptId,
    String taskId,
    String promptTemplateId,
    String promptTemplateVersion,
    JsonNode responseSchema,
    String prompt,
    int maxOutputTokens,
    Duration timeout) {}

public record AgentAttemptAppendRequest(
    UUID loopId,
    int attempt,
    JsonNode input,
    JsonNode draft,
    JsonNode verdicts,
    String outcome,
    UUID llmInvocationId,
    Instant startedAt,
    Instant completedAt) {}

public record AgentToolCallAppendRequest(
    UUID loopId,
    UUID attemptId,
    UUID callId,
    String toolName,
    JsonNode input,
    JsonNode output,
    String outcome,
    String refusalCode,
    Instant startedAt,
    Instant completedAt) {}

public record AgentCompletionResponse(
    UUID invocationId,
    JsonNode response,
    String responseSchemaId) {}

public interface AgentPlatformInboundPort {
  ToolResult<?> dispatch(AgentToolDispatchRequest request);
  AgentCompletionResponse complete(AgentCompletionRequest request);
  UUID appendAttempt(AgentAttemptAppendRequest request);
  UUID appendToolCall(AgentToolCallAppendRequest request);
}

public record LoopStateSnapshot(
    UUID loopId,
    UUID initiativeId,
    UUID stageAttemptId,
    String capability,
    String status,
    LoopBudget budget,
    int attemptsUsed,
    int toolCallsUsed,
    UUID latestAttemptId,
    Instant startedAt,
    Instant completedAt) {}

public record EvidenceCitation(UUID evidenceId, String fieldPath, String excerpt) {}

public record EvidenceBoundValue<T>(
    String fieldPath, T value, EvidenceCitation citation) {}

public interface EvidenceCitationVerifier {
  Optional<String> refusalFor(
      UUID clientId, String capability, List<EvidenceBoundValue<?>> values);
}

@ConfigurationProperties(prefix = "aurora.agent")
public record AgentPlatformProperties(
    @Min(1) @Max(10) int maxAttempts,
    @Min(1) @Max(100) int maxToolCalls,
    Duration attemptTimeout,
    Duration toolTimeout,
    Duration loopTimeout) {}
```

`LlmRequest` construction follows the existing `finishTargeting` pattern:
each definition supplies its own task id, `promptTemplateId`,
`promptTemplateVersion`, response schema map, token limit, timeout,
`RedactionPolicy` and rendered prompt. The gateway records the schema `$id` as
`llm_invocations.schema_id`; the loop does not bypass `LlmGateway`.

## 4. Behaviour

`AgentPlatformInboundController` exposes the Java return direction for the
Python graph:

1. Authenticate `X-Aurora-Studio-Token` and resolve its client binding before
   deserialising a request. Ignore any client id in a request body; the token
   binding supplies the client scope.
2. For a tool dispatch, validate the capability, loop id, attempt id and
   recorded budget. Refuse with `TOOL_NOT_REGISTERED`,
   `CLIENT_SCOPE_MISMATCH` or `TOOL_BUDGET_EXCEEDED` before execution when
   applicable.
3. Execute the registered `Tool` in Java, apply
   `EvidenceCitationVerifier` to evidence-bearing outputs, and return the
   sealed `ToolSuccess` or `ToolRefusal` JSON shape.
4. For a completion, validate the attempt and budget, construct the existing
   `LlmRequest` from the request's prompt template, version, response schema,
   redaction policy and timeout, then call `LlmGateway.complete`. Return its
   invocation id and schema-validated response; Python never receives a
   provider key.
5. For an attempt or tool-call append, verify the token scope, loop linkage,
   monotonic attempt number and recorded budget, then insert the V15 row in a
   short transaction. Once the recorded budget is spent, Java refuses further
   appends even if a graph continues locally.
6. No inbound method writes `initiative_stage_attempts`,
   `initiative_events` or `initiative_gate_decisions`. The Python graph
   returns its outcome to Java, and `InitiativeService` alone maps it to a
   stage transition or human gate.

Tool execution and ledger writes are separately transactional. The gateway
call occurs outside the ledger transaction, and its invocation id is retained
in the following append. An append refusal is itself returned to Python and
terminates the graph rather than being bypassed or retried around.

## 5. Schema

`V15__agent_attempt_ledger.sql`:

```sql
create table agent_attempts (
  id uuid primary key default gen_random_uuid(),
  client_id uuid not null,
  loop_id uuid not null,
  capability varchar(80) not null,
  attempt integer not null check (attempt > 0),
  input jsonb not null,
  draft jsonb,
  verdicts jsonb not null default '[]'::jsonb,
  outcome varchar(40) not null check (outcome in (
    'DRAFTED','ACCEPTED','UNKNOWN','REJECTED','PROVIDER_REFUSED',
    'PROVIDER_FAILED','TOOL_REFUSED','BUDGET_EXHAUSTED')),
  llm_invocation_id uuid,
  started_at timestamptz not null,
  completed_at timestamptz,
  created_at timestamptz not null default now(),
  unique (client_id, id),
  unique (client_id, loop_id, attempt),
  foreign key (client_id, llm_invocation_id)
    references llm_invocations(client_id, id)
);

create table agent_tool_calls (
  id uuid primary key default gen_random_uuid(),
  client_id uuid not null,
  agent_attempt_id uuid not null,
  loop_id uuid not null,
  call_id uuid not null,
  tool_name varchar(160) not null,
  input jsonb not null,
  output jsonb,
  outcome varchar(30) not null check (outcome in ('OK','REFUSED','FAILED')),
  refusal_code varchar(80),
  started_at timestamptz not null,
  completed_at timestamptz,
  created_at timestamptz not null default now(),
  unique (client_id, id),
  unique (client_id, call_id),
  foreign key (client_id, agent_attempt_id)
    references agent_attempts(client_id, id)
);

create index agent_attempts_loop_idx
  on agent_attempts(client_id, loop_id, attempt);
create index agent_tool_calls_attempt_idx
  on agent_tool_calls(client_id, agent_attempt_id, created_at);

create or replace function reject_agent_platform_mutation()
returns trigger language plpgsql as $$
begin
  raise exception 'agent platform records are append-only';
end;
$$;

create trigger agent_attempts_append_only
before update or delete on agent_attempts
for each row execute function reject_agent_platform_mutation();
create trigger agent_tool_calls_append_only
before update or delete on agent_tool_calls
for each row execute function reject_agent_platform_mutation();
```

`V16__capability_loop_state.sql`:

```sql
create table capability_loop_state (
  id uuid primary key default gen_random_uuid(),
  client_id uuid not null,
  initiative_id uuid not null,
  stage_attempt_id uuid not null,
  capability varchar(80) not null,
  status varchar(30) not null check (status in (
    'RUNNING','ACCEPTED','HUMAN_GATE_REQUIRED','BUDGET_EXHAUSTED',
    'PROVIDER_REFUSED','PROVIDER_FAILED','TOOL_REFUSED')),
  budget jsonb not null,
  attempts_used integer not null default 0 check (attempts_used >= 0),
  tool_calls_used integer not null default 0 check (tool_calls_used >= 0),
  latest_attempt_id uuid,
  started_at timestamptz not null default now(),
  completed_at timestamptz,
  created_at timestamptz not null default now(),
  unique (client_id, id),
  unique (client_id, initiative_id, stage_attempt_id, capability),
  foreign key (client_id, initiative_id)
    references initiatives(client_id, id),
  foreign key (client_id, stage_attempt_id)
    references initiative_stage_attempts(client_id, id)
);

alter table agent_attempts
  add constraint agent_attempts_loop_fk
  foreign key (client_id, loop_id)
  references capability_loop_state(client_id, id);

create index capability_loop_stage_idx
  on capability_loop_state(client_id, initiative_id, stage_attempt_id);
```

`capability_loop_state` is a mutable current-state projection so Java can
atomically move `RUNNING` to a terminal status after Python returns. The
attempt and tool-call rows are the audit ledger and are immutable. Updates must
include the expected current status to prevent lost transitions; a future
migration may add an append-only state-event table. The LangGraph checkpointer
is working memory only and is not a substitute for this Java state or ledger.

## 6. HTTP contract

All routes are authenticated internal routes. They use the token-header
conventions from `java-python-seam.md`:

```text
X-Aurora-Studio-Token: <deployment token>
X-Aurora-Client: <client UUID>
Idempotency-Key: <stable append or completion key>
Content-Type: application/json
```

The client scope is derived from the authenticated token binding and checked
against `X-Aurora-Client`; it is never accepted from a JSON field.

| Route | Method | Request | Response |
| --- | --- | --- | --- |
| `/internal/agent/tools/dispatch` | POST | `AgentToolDispatchRequest` | `ToolSuccess` or `ToolRefusal` |
| `/internal/agent/completions` | POST | `AgentCompletionRequest` | `AgentCompletionResponse` |
| `/internal/agent/attempts` | POST | `AgentAttemptAppendRequest` | `{"id":"..."}` |
| `/internal/agent/tool-calls` | POST | `AgentToolCallAppendRequest` | `{"id":"..."}` |

| Condition | Status | Outcome |
| --- | ---: | --- |
| Valid authenticated dispatch or completion | 200 | Typed result |
| Valid ledger append | 201 | New row id |
| Missing or invalid token | 401/403 | No execution or persistence |
| Client header not bound to token | 403 | `CLIENT_SCOPE_MISMATCH` |
| Unknown capability/tool | 400 | `TOOL_NOT_REGISTERED` |
| Recorded budget exhausted | 409 | `ATTEMPT_BUDGET_EXCEEDED` or `TOOL_BUDGET_EXCEEDED` |
| Duplicate idempotency key with same body | 200 | Original result |
| Duplicate key with different body | 409 | `IDEMPOTENCY_KEY_REUSE` |
| Gateway refusal or failure | 502 | Bounded refusal; invocation remains audited |
| Attempted stage or gate write | 403 | No such route or persistence path |

## 7. Configuration

Add `AgentPlatformProperties` with prefix `aurora.agent`:

| Property | Type | Default | Validation |
| --- | --- | --- | --- |
| `max-attempts` | `int` | `3` | 1–10 |
| `max-tool-calls` | `int` | `12` | 1–100 |
| `attempt-timeout` | `Duration` | `PT30S` | positive, no more than 5 minutes |
| `tool-timeout` | `Duration` | `PT10S` | positive and no longer than attempt timeout |
| `loop-timeout` | `Duration` | `PT2M` | positive and no shorter than attempt timeout |

Three attempts allow initial draft plus two repairs; twelve tool calls allow
four tools per attempt without permitting unbounded autonomy.

## 8. Deterministic rules

| Identifier | Rule |
| --- | --- |
| `LOOP-BUDGET-POSITIVE` | All configured budgets are within the validated ranges. |
| `LOOP-ATTEMPT-BOUND` | Java refuses an attempt append once `max-attempts` is spent. |
| `LOOP-TOOL-BOUND` | Java refuses a tool dispatch or append once `max-tool-calls` is spent. |
| `LOOP-CAPABILITY-SCOPE` | A tool must be registered for the current capability. |
| `LOOP-CLIENT-SCOPE` | Every repository read and write uses the current client id. |
| `LOOP-SCHEMA-VALID` | Only an `LlmOutcome.OK` payload accepted by its response schema is decoded. |
| `LOOP-CITATION-REQUIRED` | A field used by a threshold needs a `knowledge_evidence` citation or recorded observation. |
| `LOOP-NO-MACHINE-APPROVAL` | Java inbound routes never write a gate decision or stage status. |

All seven rules in this table, including
`LOOP-BUDGET-POSITIVE`, `LOOP-ATTEMPT-BOUND`, `LOOP-TOOL-BOUND`,
`LOOP-CAPABILITY-SCOPE`, `LOOP-CLIENT-SCOPE`, `LOOP-CITATION-REQUIRED` and
`LOOP-NO-MACHINE-APPROVAL`, remain Java-enforced. The enforcement point for
`LOOP-CITATION-REQUIRED` is immediately before a deterministic judge receives
a draft. The hook verifies each `EvidenceBoundValue.citation` against
`knowledge_evidence(client_id, id)`, records the citation in
`agent_attempts.input` and `verdicts`, and converts missing or cross-client
citations to `UNKNOWN`. A tool result may be an equivalent recorded
observation only when its tool-call row contains the observation and the judge
declares that tool as an allowed evidence source.

## 9. Failure and refusal matrix

| Condition | Outcome | Persisted record | HTTP status |
| --- | --- | --- | --- |
| Unknown tool or capability | `TOOL_REFUSED` | `agent_tool_calls` and `agent_attempts` | 400 from capability route |
| Cross-client evidence id | `UNKNOWN` | Attempt with citation failure | 200 stage result; human gate required |
| Provider `REFUSED` | `PROVIDER_REFUSED` | `llm_invocations`, attempt and tool calls | 200 stage result with provider failure mapping |
| Provider `FAILED` or schema invalid | `PROVIDER_FAILED` | `llm_invocations` and attempt | 200 stage result with provider failure mapping |
| Judge returns FAIL | `REJECTED` or next repair | Attempt and verdicts | 200 stage result |
| Judge returns UNKNOWN | `HUMAN_GATE_REQUIRED` | Attempt and verdicts | 200 stage result |
| Budget or timeout exhausted | `BUDGET_EXHAUSTED` | Final attempt and loop state | 200 stage result; no approval |
| Python continues after an append refusal | `BUDGET_EXHAUSTED` or refusal | Refusal and prior rows unchanged | 409 from inbound route |
| Duplicate loop key | none | Existing rows unchanged | 409 |

## 10. Tests to write

Unit tests:

- `ToolRegistryRejectsUnregisteredCapability`: assert a refusal with
  `TOOL_NOT_REGISTERED`.
- `ToolResultIsSealedIntoSuccessOrRefusal`: assert both typed variants expose
  stable codes.
- `InboundAttemptAppendRefusesSpentBudget`: assert no append is accepted after
  the Java budget is exhausted.
- `InboundToolDispatchRefusesSpentToolBudget`: assert the next tool call is
  refused server-side.
- `InboundAppendPersistsStructuredVerdicts`: capture repository order and
  assert the append precedes the next graph request.
- `UncitedEvidenceBecomesUnknown`: assert no threshold pass is returned.
- `InboundAgentRoutesNeverWriteInitiativeState`: mock only the ledger and
  assert no stage or gate repository dependency exists.

`@SpringBootTest` tests:

- `AgentPlatformPropertiesBindAndValidateDefaults`.
- `GatewayInvocationIdentityIsStoredPerApplicationCall`.
- `InboundCompletionRoutesThroughGovernedGateway`.
- `CapabilityLoopStateAcceptsOnlyJavaReturnedTerminalOutcome`.

Testcontainers repository tests:

- `AgentAttemptAndToolCallAreTenantScoped`.
- `AgentAttemptAppendOnlyTriggerRejectsUpdateAndDelete`.
- `ToolCallAppendOnlyTriggerRejectsUpdateAndDelete`.
- `CapabilityLoopLinksToInitiativeStageAttempt`.
- `DuplicateAttemptNumberIsRejected`.

These follow the existing Mockito style in `GatewayServiceTest` and
`InitiativeServiceTest`, repository capture style in
`InitiativeRepositoryTest`, and PostgreSQL/Testcontainers style used by the
knowledge integration tests.

## 11. Acceptance criteria

- [ ] Root module and dependency direction are documented and implemented
      without an `initiative` dependency in `agentplatform`.
- [ ] Every provider call from the agent service uses the Java
      `LlmGateway.complete` route.
- [ ] Three application attempts and physical gateway retries are distinct.
- [ ] Every tool call, draft, verdict and refusal is replayable in Java.
- [ ] V15/V16 migrations have client-scoped composite keys and append-only
  protections.
- [ ] Uncited threshold inputs are persisted and resolve `UNKNOWN`.
- [ ] Inbound Java routes never write a gate decision or initiative stage
      status.
- [ ] All named unit, Spring and Testcontainers tests pass.

## 12. Open decisions

- **State history:** use an append-only state-event table in a follow-up
  migration, or permit controlled updates to `capability_loop_state`.
  Recommendation: append-only state events before production; LangGraph
  checkpoint state remains working memory only.
- **Observation evidence:** permit only tool-call observations or add a
  dedicated observation table. Recommendation: start with tool-call
  observations and add a table when Python execution is introduced.
