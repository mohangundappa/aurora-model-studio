---
name: testing-model-studio
description: Run Aurora Model Studio API and CLI workflows against local PostgreSQL, with tenant isolation, ingestion lineage, deterministic design gates, and handoff package verification.
---

# Testing Aurora Model Studio

Check the revision before assuming there is a frontend. The modular-architecture revision
5fc7be1 has **no UI**: use HTTP, CLI stdout, and PostgreSQL evidence, not an artificial screencast.

## Runtime setup

- Prefer an existing healthy runtime and preserve its corpus.
- Blueprint default: `docker compose up --build -d` (Studio :8081, PostgreSQL :5433).
- If the Docker Maven build is unavailable but a current host jar already exists:
  `java -jar app/target/app-0.1.0-SNAPSHOT.jar --server.port=8081`.
  Verify jar freshness/revision rather than silently testing stale bytecode.
- Local database is `aurora_studio`, user/password `aurora`.
  Use `docker exec aurora-model-studio-postgres-1 psql -U aurora -d aurora_studio`.
- Health also requires a header:
  `curl -H 'X-Aurora-Client: 00000000-0000-0000-0000-000000000001' http://localhost:8081/actuator/health`.
- Maven mirror settings may already exist at `/home/ubuntu/maven-mirror-settings.xml`.
  Use `-s <settings>` when needed; don't rerun builds merely as runtime evidence.
- For old/new runtime comparisons, export the old source outside the checkout, build only the needed
  jar with `-Dmaven.test.skip=true -Dspotless.skip=true`, and use separate newly named databases.
  Never reset/truncate an existing corpus to reproduce a bug.

## Clients and CLI

Configured local clients are ...0001 and ...0002. Use ...0002 for API scratch objects after checking
its existing contents. Every HTTP request needs a configured canonical UUID in X-Aurora-Client.
Missing, malformed and unknown clients return 400; cross-tenant resource IDs return 404.
The shorthand `0-0-0-0-1` is rejected on this revision.

The importer/extractor CLI hardcodes tenant ...0001:

```sh
java -jar app/target/app-0.1.0-SNAPSHOT.jar --server.port=0 \
  --extract --aurora-repo /path/to/scratch-source
java -jar app/target/app-0.1.0-SNAPSHOT.jar --server.port=0 --extract-synthetic
```

- `--aurora-repo` always runs the importer before extraction.
- CLI is a web application and does not self-exit. Use an ephemeral server port and terminate the
  process only after the `Imported commit` / `Extracted ...` completion marker. Timeout exit 124
  is expected if using `timeout`, but it is not proof that extraction completed.
- For isolation, pass `--spring.datasource.url=jdbc:postgresql://localhost:5433/<new-test-db>`.
- Reuse `app/src/test/resources/aurora-fixture` by copying it outside the checkout. It contains all
  paths the importer expects. Plain copied sources report commit `unresolved`, so use content hashes
  and saved source files as evidence. Never modify the sibling aurora-intelligence repository.
- Snapshot **relationships as well as object/evidence counts** before the next CLI run. The combined
  importer/extractor pipeline may need an additional run to link the extracted latest versions even
  when it reports no new objects. Compare source/target IDs and versions, not just knowledge keys.
- Source-governance probes must be added explicitly to the copied YAML; the plain fixture does not
  contain the lifecycleStatus field that the integration test appends dynamically. Check authoritative
  EXTRACTED/null approvedBy, absent top-level governance fields, and importer `sourceDeclared`.

## API workflow

Routes: `POST /api/knowledge`, `POST /api/discovery/requirements`,
`POST /api/discovery/runs`, `POST /api/initiatives`,
`GET /api/initiatives[/{id}]`,
`POST /api/initiatives/{id}/stages/{STAGE}/run`,
`POST /api/initiatives/{id}/stages/{STAGE}/decision`.

- Knowledge defaults to APPROVED only. Opt in via `includeCandidates=true` for candidate list,
  detail, evidence and impact. Candidate detail/evidence/impact without opt-in return 404.
- Set `includeCandidates` when creating a discovery run or initiative if its corpus is EXTRACTED.
- Initiative reads have `stages[].attempts[]`, `gateDecisions`, `events`, and `durations`.
- Sequence: REQUIREMENT_INTAKE (completed at creation), KNOWLEDGE_DISCOVERY, REUSE_DECISION,
  DATA_FEASIBILITY, TARGETING_DESIGN, FEATURE_DESIGN, EXPERIMENT_DESIGN, HANDOFF.
  CANDIDATE_BUILD remains OUT_OF_SCOPE and is skipped by the sequence.
- Reuse and handoff await human decisions. Feasibility/design stages with UNKNOWN checks also await
  approval; known-valid targeting/feature/experiment stages can complete without a gate.
- Decision body:
  `{"decision":"APPROVE","actor":"local-human-unverified","reason":"explicit acceptance","acceptedUnknownChecks":[]}`.
  For gated UNKNOWNs, read check names from the latest attempt and send their exact set. Empty,
  partial or extra names return 400 without writing a decision. Identities remain explicitly
  unverified; the known orchestrator identity cannot self-approve.
- RETURN sets a gated attempt back to PENDING; REJECT blocks progress. Rerunning a rejected attempt
  creates another attempt, preserving prior history.

## Deterministic design fixtures

Use a BOOKING_COMPLETED requirement, 30d horizon and a raw_events DATA_ASSET whose attributes include:
`tableName`, `primaryKey=event_id`, `eventTime=event_time`, `history`, `grain`,
`observables=["BOOKING_COMPLETED"]`, and governed columns event_id, session_id, event_time, event_name.

- Deterministic targeting produces two drafts: the first leaks BOOKING_COMPLETED into the cohort and
  is REJECTED; the second can pass. Verify both persisted drafts and the invocation ID.
- Feature design produces recent-session-engagement. It remains EXTRACTED, with native
  generation-record evidence and four AI_GENERATED_HYPOTHESIS provenance fields.
  Do not insert evidence merely to approve this generated feature: it already has evidence.
- Review/approval order is submit-review then approve, using the knowledge API.
- An approved near-duplicate feature is REUSE rather than another candidate.
- Omitted sample inputs produce named UNKNOWN checks and null exposures. For constraints
  baselineConversionRate=.1, minimumDetectableEffect=.02, alpha=.05, power=.8, deterministic minimum
  exposures are 3841 per variant. Invalid conversion rates/allocations block experiment design.
- These validators do not run training or execute generated SQL against live customer data.

## Handoff

Unapproved referenced features block handoff before a package/outbound result is created.
Approved features permit an AWAITING_APPROVAL package. Read package content/hash from
`initiative_handoff_packages`; there is no separate package HTTP endpoint.
To test stale package refusal, add explicitly labeled evidence to a scratch referenced object after
the package was presented, then approve. Expect PACKAGE_CHANGED_SINCE_APPROVAL and no outbound attempt.
Fresh approval without receiver token yields PROVIDER_FAILED / AURORA_NOT_CONFIGURED, not registration.
Read `stages[].attempts[].handoffAttempts[]` and verify its hash matches the approved package.

### Devin Secrets Needed

- Local API/CLI testing: none; documented development credentials suffice.
- Real outbound registration: configured receiver URL (`STUDIO_HANDOFF_AURORA_BASE_URL`) and
  `STUDIO_HANDOFF_AURORA_TOKEN` matching receiver `AURORA_CANDIDATES_STUDIO_TOKEN`.
  Do not claim outbound success from a local failure or a mock. Real success requires HTTP 201,
  a nonblank candidateId, and AWAITING_WEIGHTS from Aurora Intelligence.
