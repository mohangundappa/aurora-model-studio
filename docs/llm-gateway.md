# LLM gateway

The gateway is the only boundary through which Model Studio can call a language
model. `LlmRequest` carries a task identifier, versioned prompt template,
resolved inputs, a response JSON Schema, output limit, timeout, and redaction
policy. `LlmResult` is either a validated structured payload or an explicit
refusal/failure; partially parsed provider output is never exposed.

The deterministic adapter is the default and is suitable for offline demos and
CI. It is byte-identical for the same request and only returns interpretations
that can be grounded in an evidence excerpt. The OpenAI adapter is selected only
when `studio.llm.provider=openai` and `OPENAI_API_KEY` is present. It sends
structured output constrained by the supplied schema. The live path is opt-in;
the key is never logged or committed.

Transport and schema-validation failures receive at most two retries. Refusals
are not retried. The terminal result is persisted in `llm_invocations`, including
client, task, provider/model, prompt hash, schema, token counts, cost, latency,
retry count, outcome, and timestamp. The database trigger makes invocation rows
append-only. Every model-assisted candidate stores its producing invocation ID.

Prompts put artifact excerpts and structural facts in an explicit data
envelope. Artifact contents are data, never instructions. Excerpts are bounded
and redact credential-like values and client UUIDs before submission. Wording
checks in tests are tripwires, not proof of truthfulness: grounding is enforced
by citation validation against the evidence excerpt, and governance remains
deterministic.

The configured `studio.extraction.interpreted-certainty` value is `0.72`.
This is a conservative, configuration-owned certainty for fields interpreted
by the model: it is below deterministic parsing certainty (`1.0`) and is not a
model-controlled self-score. It contributes only through the existing,
configuration-weighted confidence derivation after evidence and attributes are
persisted.
