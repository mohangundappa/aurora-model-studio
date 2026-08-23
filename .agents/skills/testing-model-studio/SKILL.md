---
name: testing-model-studio
description: How to run and adversarially test Aurora Model Studio (Spring Boot knowledge API on :8081 + Postgres on :5433) over HTTP and SQL, including the CLI importer/extraction runs, tenant gating, candidate opt-in, append-only tables and prompt-injection probes. No frontend exists, so never test this repo through a browser.
---

# Testing Aurora Model Studio

There is **no UI**. Everything is a Spring Boot HTTP API plus two CLI-style runs, so test with
`curl` + `psql` and do **not** record a screencast.

## Bringing up / reusing the stack

```bash
docker compose up --build -d           # app :8081, postgres :5433
curl -s -H "X-Aurora-Client: 00000000-0000-0000-0000-000000000001" http://localhost:8081/actuator/health
```

- If a stack is already running with a seeded corpus, **reuse it**; rebuilding is slow and the
  corpus is the fixture. Never truncate `knowledge_objects` / `knowledge_audit` / `llm_invocations`
  without asking — audit and invocation tables are append-only by trigger and cannot be repaired.
- Maven Central rate limits: build with `MAVEN_MIRROR_URL=https://repo.huaweicloud.com/repository/maven/`.
- `psql` is usually **not** on the host. Use:
  `docker exec aurora-model-studio-postgres-1 psql -U aurora -d aurora_studio -c "..."`
- `/actuator/health` also goes through the client filter, so an unauthenticated health probe
  returns 400, not 200. Always send the header.

## Client header contract

Every request needs `X-Aurora-Client: <configured uuid>`. Configured clients live in
`app/src/main/resources/application.yml` (`studio.clients`); locally
`...0001` is the seeded tenant and `...0002` is empty — use `...0002` as a scratch tenant so you can
create/mutate objects without touching the seeded corpus.

Note `UUID.fromString` is lenient: `0-0-0-0-1` and a uuid with trailing whitespace are both accepted
as `...0001`. Expect that, and treat it as a contract-laxness finding rather than a leak.

## Running the CLI paths

The jar is a **web app**, so a CLI run collides with the container on 8081 and never exits by itself:

```bash
cd /home/ubuntu/repos/aurora-model-studio
timeout 200 java -jar app/target/app-0.1.0-SNAPSHOT.jar --server.port=0 \
  --extract --aurora-repo /path/to/aurora-intelligence      # importer THEN extraction
timeout 150 java -jar app/target/app-0.1.0-SNAPSHOT.jar --server.port=0 --extract-synthetic
```

- `--server.port=0` is required, `timeout` is expected to kill it after the run prints
  (`exit=124` is normal; grep the log for `Extracted`/`Imported`).
- Passing `--aurora-repo` **always** runs `AuroraBackfillImporter` first, even with `--extract`.
  The importer is not defensive about the tree it reads (`Files.list` on the signals dir with no
  filtering), so an unexpected subdirectory or non-YAML file there aborts the whole run before
  extraction gets to execute. If a run dies with `IOException: Is a directory`, that is why.

## Probing extraction / prompt injection safely

Never modify `aurora-intelligence`. Clone it to `/tmp` and add adversarial artifacts there:

```bash
git clone -q /home/ubuntu/repos/aurora-intelligence /tmp/aurora-injection
```

- Same HEAD ⇒ existing artifacts hash identically and come back as `unchanged`, so only your probe
  shows up as a candidate. Delete the clone afterwards.
- Declared roots/patterns are in `extraction/.../ExtractionSourceSelection.java`; recognised shapes
  are in `StructuralParser.recognizedYaml/Models/Markdown/Java`. A feature needs `inputs` plus
  `calculationType`/`explanationTemplate`; an experiment needs `id`+`variants`+`primaryOutcomeEvent`
  or `name`+`guard`.
- To exercise **extraction** rather than the importer, put the probe in a root the importer does not
  read (e.g. `experiments/src/main/resources/experiments/`). Anything under
  `signals/src/main/resources/signals/` is claimed by the importer first, and the resulting row will
  be importer-created — a materially different code path with a different governance-field policy.
- Extraction drops model/source-supplied governance fields (`ExtractionService.GOVERNANCE_FIELDS`);
  the importer copies the raw source YAML into `attributes` verbatim, so attacker-controlled
  `lifecycleStatus`/`confidence`/`approvedBy` keys can survive there even though the authoritative
  columns stay `EXTRACTED` / service-derived confidence / null approvers. Check both.

## Useful verification SQL

```sql
select client_id, synthetic, lifecycle_status, count(*) from knowledge_objects group by 1,2,3;
select count(*) from knowledge_objects where knowledge_key like 'extracted:%';   -- must be 0
select outcome, count(*) invocations, count(k.id) linked from llm_invocations i
  left join knowledge_objects k on k.llm_invocation_id=i.id group by outcome;    -- REFUSED must link 0
update knowledge_audit set actor='x';   -- must ERROR: knowledge audit is append-only
update llm_invocations set cost=0;      -- must ERROR: llm invocations are append-only
```

`llm_invocations` stores only `prompt_hash` (no prompt/response payload columns), so "prompts are
redacted" is structurally true — there is nothing to grep for a leak.

## Candidate opt-in gotchas

Default `GET /api/knowledge` returns only `APPROVED`; with an all-`EXTRACTED` corpus an empty list is
**correct**. `includeCandidates=true` is the opt-in, and Spring coerces `1`/`yes`/`TRUE` to true.
Routes that take no `includeCandidates` parameter (`/{id}/evidence`, `/{id}/impact`) are the place to
look for candidate exposure without opt-in, and `/api/knowledge/governance-rules` is the place to
look for nullable-parameter SQL typing 500s (`could not determine data type of parameter $2`) — check
`docker logs aurora-model-studio-app-1` for the root cause whenever you get a 500.

## Phase-4 initiative API (nine-stage Model Development Initiative)

Endpoints (all need the `X-Aurora-Client` header):
`POST /api/initiatives` (body `{"requirementId":…,"includeCandidates":true,"clientBaselineDurationMillis":null}`),
`GET /api/initiatives`, `GET /api/initiatives/{id}`,
`POST /api/initiatives/{id}/stages/{STAGE}/run`,
`POST /api/initiatives/{id}/stages/{STAGE}/decision` (body `{"decision":"APPROVE|REJECT|RETURN","actor":…,"reason":…,"acceptedUnknownChecks":[…]}`).

Mechanics that cost real time and are easy to get wrong:

- With an all-`EXTRACTED` corpus you must pass `"includeCandidates": true` when creating an
  initiative, otherwise knowledge discovery sees nothing and every feasibility check is UNKNOWN.
- Stages must be run in order; the JSON shape is `stages[].attempts[]` (there is no `stage.attempt`).
  Only `REUSE_DECISION`, `DATA_FEASIBILITY` and `HANDOFF` are gated; `TARGETING_DESIGN`,
  `FEATURE_DESIGN`, `EXPERIMENT_DESIGN`, `HANDOFF` are `NOT_IMPLEMENTED` and `CANDIDATE_BUILD` is
  `OUT_OF_SCOPE`, so a "full walkthrough" legitimately stops after `DATA_FEASIBILITY`.
- `APPROVE` on an `AWAITING_APPROVAL` feasibility stage requires `acceptedUnknownChecks` to equal the
  attempt's UNKNOWN check names *exactly* (as a set); read them from
  `stages[].attempts[].feasibilityChecks[?status=UNKNOWN].name` first. Partial/empty/extra lists are
  refused, and (because `IllegalArgumentException` is mapped to 404 in `InitiativeController`)
  validation refusals come back as **HTTP 404 with an `{"error":…}` body**, not 400 — don't read that
  as "initiative not found".
- Creating fixtures: `POST /api/discovery/requirements` accepts a full `ModelRequirement` JSON;
  `constraints.requiredFeatures` drives `MISSING_REQUIRED_FEATURE` / `OPEN_CONFLICT` blockers and
  `requiredObservables` drives `MISSING_TARGET_OBSERVABLE:<NAME>` blockers, which is how you prove
  the blocker text is derived and not a hardcoded literal.
- Conflicts cannot be created through the knowledge HTTP API at all (`detectConflicts` is only
  reachable from `addEvidence` / `linkGovernedArtifacts`, i.e. the extraction and importer paths). To
  exercise the `OPEN_CONFLICT` feasibility branch, create your own FEATURE object via
  `POST /api/knowledge` and insert a `knowledge_conflicts` row for it directly
  (`conflict_class='BLOCKING'` blocks, `'DIVERGENT_DESCRIPTION'` must not) — never on seeded objects.
- Approval order is `submit-review` then `approve`; approving an `EXTRACTED` object is a 409
  lifecycle error, so a "missing required fields"/"no evidence" refusal only shows up once the object
  is `PENDING_REVIEW`. There is no API path back to `EXTRACTED`, so pick throwaway objects.
- The gate trigger `require_human_initiative_gate` trusts the session GUC
  `aurora.initiative_gate_actor`; `select set_config('aurora.initiative_gate_actor','human',true)` in
  the same transaction as a raw `insert` forges a gate row. `actor_verified=true` is still refused.
- Known rough edges to re-probe: concurrent `run` calls on the same stage 500 with
  `DuplicateKeyException` on `initiative_stage_attempts_..._key`; `actor`/`reason` longer than
  varchar(200) or containing `\u0000` 500 on insert. Always check
  `docker logs aurora-model-studio-app-1 --since 2m` after any 500.
