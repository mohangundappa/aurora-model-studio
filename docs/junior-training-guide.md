# Aurora Model Studio: junior team training guide

## 1. Learning goals

This guide teaches junior team members to **use and understand the application**.
“Training” here means training people. Importing knowledge does not fine-tune the
LLM, and Model Studio does not train predictive models.

By the end, a learner should be able to:

1. Explain what enters Model Studio and what leaves it.
2. Start a separate workshop instance and call its API.
3. Distinguish a candidate, its evidence, and an approved knowledge object.
4. Turn a business question into a structured requirement.
5. Interpret discovery, feasibility, and design results without overstating them.
6. Record an informed gate decision and inspect its history.
7. Explain why a blocked workflow or failed external handoff can be correct.
8. Locate the owning module when investigating a problem.

**Prerequisites:** basic terminal use and an introductory understanding of JSON.
No machine-learning background is needed. Java experience is needed only for
the optional developer exercise.

Read the [use-case guide](use-cases.md) first for the business context.

## 2. Trainer delivery plan

Suggested delivery: two instructor-led sessions of about 2.5 hours each, including
breaks. Installation can be completed before class.

| Session | Topic | Learner evidence |
| --- | --- | --- |
| 1: orientation, 20 minutes | Product boundaries and glossary | Explain “design package” in their own words |
| 1: setup and import, 40 minutes | Sections 4–5 | Health response and imported evidence |
| 1: review and discovery, 40 minutes | Sections 6–7 | Reviewed feature and a discovery explanation |
| 1: workflow and feasibility, 35 minutes | Sections 8–9 | Ordered stages and named unknowns |
| 2: targeting and features, 35 minutes | Section 10 | Rejected/accepted drafts and candidate provenance |
| 2: experiment and handoff, 40 minutes | Sections 11–12 | Sample-size explanation and honest delivery outcome |
| 2: failure exercise, 25 minutes | Section 13 | Missing-observable diagnosis |
| 2: assessment and teach-back, 35 minutes | Sections 15–16 | Independent walkthrough and review |

Pair learners: one operates the terminal and one reviews evidence. Swap roles
after discovery. Pause before every approval and ask the reviewer to explain
the evidence, the uncertainty, and the meaning of proceeding.

### Trainer preparation

- Use a current checkout containing the repeated-import fix and these guides.
- Provide Java 21, Maven, Docker with Compose, Bash, `curl` 7.76 or later, and `jq`.
  The commands assume Bash on Linux/macOS or WSL2; they are not PowerShell syntax.
- Give each learner a separate local database. The two configured client IDs are
  not a substitute for separate databases: the CLI imports into client `...0001`.
- Rehearse this guide on a fresh workshop database. An existing database may
  contain prior approvals and versions, which change the expected observations.
- Use the deterministic provider. No external AI credentials or Aurora receiver
  are needed for the core course.
- Keep the regular demo database and any sibling checkout untouched. The reset
  script deletes the Compose database volume and is not part of these exercises.

## 3. Concepts in plain language

| Term | Meaning | Example |
| --- | --- | --- |
| Requirement | A structured statement of the business problem | Predict booking completion within 30 days |
| Observable | An event or fact that represents the outcome | `BOOKING_COMPLETED` |
| Feature | An input definition that could help a prediction | Recent session engagement |
| Data asset | Governed metadata about a source of data | The declared columns of `raw_events` |
| Implementation | The code or logic behind an asset | A feature calculator |
| Knowledge key | The logical identity across versions | `feature:booking-intent` |
| Object ID | A UUID identifying one exact object version | Retrieved from the API, never copied from someone else's run |
| Evidence | A record explaining where knowledge came from | A source URI, source version, and excerpt |
| Provenance | How a particular field was obtained | Evidence-backed value or AI-generated hypothesis |
| Candidate | Knowledge that has not been approved | An `EXTRACTED` feature |
| Trusted | An approved object eligible for default retrieval | A reviewed feature with evidence |
| Confidence | A score based on evidence signals | It is not model accuracy |
| Synthetic | Explicitly labeled demonstration material | A synthetic estate record |
| Initiative | The workflow that develops one requirement | Discovery through handoff |
| Stage / attempt | A workflow step / one execution of that step | A retried feasibility check |
| Gate | A point requiring an explicit review decision | Approve reuse with a named actor and reason |
| `UNKNOWN` | Required information is not established | No machine-comparable history depth |
| Leakage | Future/outcome information entering the inputs | Selecting a cohort because its booking already completed |
| Point in time | What could have been known at the scoring time | Inputs strictly before the `as_of` timestamp |
| Package hash | A fingerprint of the exact design content | Approval and delivery refer to the same content |

Knowledge lifecycle:

```text
EXTRACTED → PENDING_REVIEW → APPROVED → SUPERSEDED
                 ↓             ↓
             DEPRECATED    DEPRECATED
```

The supported initiative progression is:

```text
REQUIREMENT_INTAKE → KNOWLEDGE_DISCOVERY → REUSE_DECISION
  → DATA_FEASIBILITY → TARGETING_DESIGN → FEATURE_DESIGN
  → EXPERIMENT_DESIGN → HANDOFF
```

You will also see `CANDIDATE_BUILD` in the nine-stage response. It remains
`OUT_OF_SCOPE`; it is not a step you can run.

## 4. Prepare an isolated workshop

Use **Terminal A** for setup, CLI imports, and then the running server.
Use **Terminal B** for API exercises. Run commands from the repository root
unless a step says otherwise.

### 4.1 Check tools and build

In Terminal A:

```bash
java -version
mvn -version
docker compose version
curl --version
jq --version
mvn -B verify
```

Expected: Java 21 and a successful Maven build. Docker must be available for
PostgreSQL integration tests. Check the test summaries; a build with skipped
database tests is not evidence that they ran.

### 4.2 Create a new database

```bash
docker compose up -d postgres
docker compose exec -T postgres pg_isready -U aurora
docker compose exec -T postgres createdb -U aurora aurora_training
```

Wait for `pg_isready` to report accepting connections before `createdb`.
If `createdb` says the database already exists, stop and ask the trainer to
allocate a fresh name. Do not drop or reuse an unknown database.
Replace `aurora_training` consistently in subsequent commands if assigned another
name. The credentials `aurora` / `aurora` are the repository's local development
credentials.

### 4.3 Prepare a fictional source fixture

The repository's small test fixture is enough for import exercises, but its
`raw_events` table declares only `id`. For the design labs, add explicit column
metadata to a **copy** of that fixture. No source SQL is executed against customer
data; this file is input for metadata extraction.

```bash
export LAB="$HOME/aurora-training"
export SOURCE="$LAB/source"
export DB_URL='jdbc:postgresql://localhost:5433/aurora_training'
export JAR="$PWD/app/target/app-0.1.0-SNAPSHOT.jar"
mkdir -p "$LAB"
```

Check whether `$SOURCE` exists. If it does, ask the trainer for a new lab folder
before copying, so a previous learner's files are preserved.

```bash
cp -R app/src/test/resources/aurora-fixture "$SOURCE"
cat > "$SOURCE/app/src/main/resources/db/migration/V1__initial_schema.sql" <<'SQL'
create table raw_events(
  event_id uuid primary key,
  session_id uuid not null,
  event_time timestamp not null,
  event_name text not null
);
create table derived_signals(id uuid);
create table decisions(id uuid);
create table experiment_exposures(id uuid);
create table experiment_outcomes(id uuid);
SQL
```

Keep the explicit column constraints in this fixture. The current importer
recognizes these column declarations with their constraints; omitting them can
leave columns out of the governed metadata and block targeting design.

All workshop source content is fictional. The ordinary importer/extractor route
can report `synthetic=false` for this fixture; that flag alone does not establish
that a source is genuine client history. Record the fixture origin in your lab
notes. The separate `--extract-synthetic` route is not needed here.

## 5. Lab: import, repeat, and inspect source knowledge

**Goal:** understand the difference between loading knowledge and approving it.

In Terminal A:

```bash
java -jar "$JAR" \
  --server.port=0 \
  "--spring.datasource.url=$DB_URL" \
  --studio.llm.provider=deterministic \
  --studio.discovery.embedding-provider=deterministic \
  --import --extract --aurora-repo "$SOURCE"
```

Wait for both `Imported commit ...` and `Extracted Aurora estate ...` completion
messages. Then press **Ctrl+C**. The CLI runs inside a web application and does
not exit by itself. `--server.port=0` assigns an ephemeral port so it does not
conflict with another server. A timeout or process exit alone does not prove
the import completed.

Run the same command once more. Expected: no new imported objects and no new
extracted candidates. Importer and extractor can create separate versions on
the first run; unchanged subsequent runs should converge.

For a SQL checkpoint in Terminal A:

```bash
docker compose exec -T postgres psql -U aurora -d aurora_training -c \
  "select 'objects' as kind, count(*) from knowledge_objects
   union all select 'evidence', count(*) from knowledge_evidence
   union all select 'relationships', count(*) from knowledge_relationships;"
```

Counts are helpful, but exact source/target version IDs are needed to detect
relationship drift. The repeat-import integration test covers that stronger
comparison. Do not treat stable counts as proof that endpoints stayed identical.

### Start the workshop API

After stopping the second CLI run, keep Terminal A running this command:

```bash
java -jar "$JAR" \
  --server.port=8091 \
  "--spring.datasource.url=$DB_URL" \
  --studio.llm.provider=deterministic \
  --studio.discovery.embedding-provider=deterministic \
  --studio.handoff.aurora-token=
```

Port **8091** is specific to this workshop; the usual application port is 8081.
The empty handoff token deliberately makes the last lab an offline refusal
exercise.

In Terminal B, from the repository root, initialize your session:

```bash
export LAB="$HOME/aurora-training"
export API='http://localhost:8091'
export C='00000000-0000-0000-0000-000000000001'
export ACTOR='Workshop reviewer'
set -o pipefail
api() { curl --fail-with-body -sS -H "X-Aurora-Client: $C" "$@"; }
api "$API/actuator/health" | jq .
```

Replace `Workshop reviewer` with your name before recording decisions. Names
remain self-declared and unverified; this is not a production login.
Keep Terminal B open so these variables and the helper remain defined.

Expected health response: `{"status":"UP"}`. If any later command fails, stop and
read the error before continuing; otherwise a missing ID can cause confusing
follow-on failures.

```bash
api "$API/api/knowledge" | jq 'length'
api "$API/api/knowledge?includeCandidates=true" > "$LAB/knowledge.json"
jq '.[] | {id,knowledgeKey,version,lifecycleStatus,trusted,synthetic}' \
  "$LAB/knowledge.json"
export FEATURE_ID="$(jq -er \
  '.[] | select(.knowledgeKey=="feature:booking-intent" and .version==1) | .id' \
  "$LAB/knowledge.json")"
api "$API/api/knowledge/$FEATURE_ID?includeCandidates=true" \
  > "$LAB/feature-package.json"
jq '{id,version,approvalStatus,trusted,evidence,fieldProvenance,relationships}' \
  "$LAB/feature-package.json"
```

**Expected:** the approved-only list starts empty in this fresh workshop.
Candidate mode shows imported/extracted objects. The selected imported feature
has evidence and is not trusted yet.

**Explain to your partner:** why can a source-backed object still require review?
Why might `booking-intent` have more than one version?

## 6. Lab: review an object and check tenant visibility

**Goal:** distinguish object approval from initiative approval.

Inspect the feature package before changing its state. For this fictional
workshop, the reviewer can approve the imported feature after explaining its
source and limitations.

```bash
api -X POST -G \
  --data-urlencode "actor=$ACTOR" \
  --data-urlencode "comment=Reviewed fictional booking fixture and its source evidence" \
  "$API/api/knowledge/$FEATURE_ID/submit-review" |
  jq '{id,lifecycleStatus}'

api -X POST -G \
  --data-urlencode "actor=$ACTOR" \
  --data-urlencode "comment=Approved for this isolated training exercise" \
  "$API/api/knowledge/$FEATURE_ID/approve" |
  jq '{id,lifecycleStatus,approvedBy}'

api "$API/api/knowledge/$FEATURE_ID" | jq '{id,approvalStatus,trusted}'
```

**Expected:** `PENDING_REVIEW`, then `APPROVED`, then `trusted:true`.
Approval does not merge the imported and extracted versions.

Use plain `curl` for this deliberate error so you can see the HTTP status:

```bash
curl -sS -w '\nHTTP %{http_code}\n' \
  -H 'X-Aurora-Client: 00000000-0000-0000-0000-000000000002' \
  "$API/api/knowledge/$FEATURE_ID"
```

**Expected:** 404 when accessing this object through the other configured client.
Both IDs are local demo clients; the header is a routing/context requirement,
not proof of authenticated access control.

Optional read: `api "$API/api/knowledge/$FEATURE_ID/impact?depth=2" | jq .`
Explain which exact versions depend on this object.

## 7. Lab: turn a business request into discovery

**Goal:** write a specific requirement and interpret the recommendation.

Scenario: prioritize outreach for eligible consented sessions with a booking
completion outcome within 30 days.

The numbers below are **declared workshop assumptions**, not measured conversion
rates or validated client experiment inputs.

```bash
cat > "$LAB/requirement.json" <<'JSON'
{
  "businessDomain": "customer intelligence",
  "businessUseCase": "workshop booking propensity",
  "predictionTarget": "BOOKING_COMPLETED",
  "observableDefinition": "BOOKING_COMPLETED",
  "population": "eligible consented sessions",
  "outcomeHorizon": "30d",
  "decisionLatency": "batch",
  "requiredAction": "prioritize outreach",
  "constraints": {
    "modelName": "workshop-booking-intent",
    "requiredFeatures": ["booking-intent"],
    "baselineConversionRate": 0.10,
    "minimumDetectableEffect": 0.02,
    "alpha": 0.05,
    "power": 0.80
  },
  "clientTaxonomy": {},
  "canonicalTaxonomy": {},
  "requiredObservables": ["BOOKING_COMPLETED"],
  "syntheticEvidenceAllowed": false
}
JSON

api -X POST -H 'Content-Type: application/json' \
  --data-binary "@$LAB/requirement.json" "$API/api/discovery/requirements" \
  > "$LAB/registered-requirement.json"
export REQUIREMENT_ID="$(jq -er '.id' "$LAB/registered-requirement.json")"

jq -n --arg id "$REQUIREMENT_ID" \
  '{requirementId:$id,includeCandidates:true}' > "$LAB/discovery-request.json"
api -X POST -H 'Content-Type: application/json' \
  --data-binary "@$LAB/discovery-request.json" "$API/api/discovery/runs" \
  > "$LAB/discovery.json"
jq '{id,includeCandidates,classification,reasonCodes,blockers,candidates}' \
  "$LAB/discovery.json"
```

Candidate mode is intentional because most of the workshop corpus has not been
approved. In an ordinary trusted-catalog workflow, use `includeCandidates:false`.
The `requiredFeatures` values are **feature names** here; the separate
`requiredKnowledgeKeys` constraint accepts logical knowledge keys.

**Expected:** a persisted discovery result explicitly identifies candidate mode.
Inspect the actual classification and gaps; this small corpus does not promise a
particular top candidate or a fully reusable model.

**Submit:** a three-sentence explanation of the recommendation, one evidence
source, and one reason the score is not predictive accuracy.

## 8. Lab: create an initiative and record reuse review

**Goal:** understand stages, predecessors, attempts, and gates.

```bash
api -X POST -H 'Content-Type: application/json' \
  --data-binary "@$LAB/discovery-request.json" "$API/api/initiatives" \
  > "$LAB/initiative-created.json"
export INITIATIVE_ID="$(jq -er '.id' "$LAB/initiative-created.json")"
jq '{id,actorIdentityVerified,stages:[.stages[] | {stage,status}]}' \
  "$LAB/initiative-created.json"

run_stage() {
  api -X POST "$API/api/initiatives/$INITIATIVE_ID/stages/$1/run"
}
show_stage() {
  api "$API/api/initiatives/$INITIATIVE_ID" |
    jq --arg stage "$1" '.stages[] | select(.stage==$stage) |
      {stage,status,currentAttempt,latest:.attempts[-1]}'
}
run_stage KNOWLEDGE_DISCOVERY > "$LAB/initiative-discovery.json"
run_stage REUSE_DECISION > "$LAB/reuse.json"
show_stage REUSE_DECISION
```

**Expected:** intake is already completed at creation; reuse becomes
`AWAITING_APPROVAL`. Discovery is recorded again as part of the initiative.

Read its findings before submitting:

```bash
jq -n --arg actor "$ACTOR" \
  '{decision:"APPROVE",actor:$actor,
    reason:"Reviewed reuse findings; continue the fictional workshop with stated gaps",
    acceptedUnknownChecks:[]}' > "$LAB/reuse-decision.json"
api -X POST -H 'Content-Type: application/json' \
  --data-binary "@$LAB/reuse-decision.json" \
  "$API/api/initiatives/$INITIATIVE_ID/stages/REUSE_DECISION/decision" \
  > "$LAB/reuse-approved.json"
show_stage REUSE_DECISION
```

**Expected:** reuse is `COMPLETED`, with an explicit gate decision. The application
cannot run a stage before its predecessor completes.

Other decision options are `RETURN` and `REJECT`. `RETURN` makes the gated attempt
pending; `REJECT` records rejection and prevents the next stage from starting.
Later attempts preserve history. Use a separate exercise initiative to practice
these decisions rather than disrupting this walkthrough.

## 9. Lab: distinguish missing data from unknown metadata

**Goal:** review uncertainty without changing it into a false success.

```bash
run_stage DATA_FEASIBILITY > "$LAB/feasibility.json"
show_stage DATA_FEASIBILITY
```

**Expected:** the fixture declares the booking observable, but metadata such as
numeric history depth, refresh cadence, and point-in-time availability is
incomplete. The stage should wait for approval of named `UNKNOWN` checks.
The exact names come from the response, not from this guide.

For each unknown, write what evidence would resolve it and who could provide
that evidence. For example, `history: retained in PostgreSQL tables` does not
establish that at least 30 days are available.

For this isolated workshop, the reviewer may explicitly accept these limitations
to demonstrate later stages. Build a draft decision:

```bash
jq --arg actor "$ACTOR" \
  '[.stages[] | select(.stage=="DATA_FEASIBILITY")][0].attempts[-1]
   | {decision:"APPROVE",actor:$actor,
      reason:"Training only: reviewed each unknown; no live data execution is claimed",
      acceptedUnknownChecks:
        ([.feasibilityChecks[] | select(.status=="UNKNOWN") | .name] | unique)}' \
  "$LAB/feasibility.json" > "$LAB/feasibility-decision.json"
jq . "$LAB/feasibility-decision.json"
```

The command prepares a decision; it does not replace the review. After the
reviewer has read every name and explained the reason:

```bash
api -X POST -H 'Content-Type: application/json' \
  --data-binary "@$LAB/feasibility-decision.json" \
  "$API/api/initiatives/$INITIATIVE_ID/stages/DATA_FEASIBILITY/decision" \
  > "$LAB/feasibility-approved.json"
show_stage DATA_FEASIBILITY
```

**Expected:** the stage is completed, while the original checks remain
`UNKNOWN` in its history. An incomplete or extra set of accepted names is
rejected. Known `FAIL` blockers cannot be overridden through this mechanism.

## 10. Lab: inspect targeting and generated features

**Goal:** separate proposal generation, validation, and object approval.

```bash
run_stage TARGETING_DESIGN > "$LAB/targeting.json"
show_stage TARGETING_DESIGN
```

With the prepared columns and deterministic provider, expect two retained
targeting drafts: one rejected and one accepted. Inspect `payload.cohortSql`,
`payload.labelSql`, `validatorVerdicts`, and `llmInvocationId` on each draft.

**Explain:** why is using `BOOKING_COMPLETED` to select the cohort a leakage
problem? What does `:as_of` separate? What evidence would be needed beyond these
metadata checks before executing the query on client data?

The prepared fixture should allow this stage to complete. If it is blocked or
awaiting approval, inspect its checks before proceeding. In particular, using
the unmodified fixture leaves the required columns undeclared. Do not blanket
approve a stage just to get past an unexpected result.

```bash
run_stage FEATURE_DESIGN > "$LAB/feature-design.json"
show_stage FEATURE_DESIGN
api "$API/api/knowledge?includeCandidates=true" > "$LAB/after-feature.json"
export GENERATED_ID="$(jq -er \
  '.[] | select(.knowledgeKey=="feature:generated:recent-session-engagement"
    and .lifecycleStatus=="EXTRACTED") | .id' "$LAB/after-feature.json")"
api "$API/api/knowledge/$GENERATED_ID?includeCandidates=true" \
  > "$LAB/generated-feature.json"
jq '{id,approvalStatus,trusted,evidence,fieldProvenance}' \
  "$LAB/generated-feature.json"
```

**Expected:** the feature stage completes, but the generated knowledge object is
still `EXTRACTED` and untrusted. It has generation evidence and fields marked
`AI_GENERATED_HYPOTHESIS`. Do not approve it yet: the next lab demonstrates the
handoff guard. In a reused database, an approved near-duplicate can produce a
`REUSE` outcome instead; that is another reason to start with a fresh database.

## 11. Lab: explain an experiment design

**Goal:** trace a numeric result to its assumptions.

```bash
run_stage EXPERIMENT_DESIGN > "$LAB/experiment.json"
show_stage EXPERIMENT_DESIGN
jq '[.stages[] | select(.stage=="EXPERIMENT_DESIGN")][0]
    .attempts[-1].drafts[0].payload' "$LAB/experiment.json"
```

**Expected with these inputs:** a completed experiment stage; default control and
treatment allocations of 50/50 labeled `allocationSource: DEFAULT`; and
`sampleSize.minimumExposuresPerVariant: 3841`.

Interpret the assumptions:

- Baseline `0.10` means a declared 10% starting conversion rate.
- Detectable effect `0.02` means a two-percentage-point absolute difference.
- Alpha `0.05` and power `0.80` are statistical design inputs.
- The calculation determines a proposed exposure threshold. It does not report
  actual exposures, measured lift, or a completed experiment.

If these sample inputs are omitted, the design records named unknowns and waits
for review. Invalid rates or allocations produce blockers.

**Submit:** the sample inputs, exposure threshold, and one sentence distinguishing
an experiment definition from experiment execution.

## 12. Lab: observe handoff refusal, then inspect the proposed package

**Goal:** understand feature approval, package approval, and external delivery.

### 12.1 Observe a governance refusal

```bash
run_stage HANDOFF > "$LAB/handoff-blocked.json"
show_stage HANDOFF
```

**Expected:** `BLOCKED` with
`FEATURE_NOT_APPROVED:feature:generated:recent-session-engagement` and no outbound
attempt. Completing feature design did not approve that knowledge object.

Review the generated feature with your partner. Its evidence establishes that it
was generated; it does not establish effectiveness. For the training exercise:

```bash
api -X POST -G \
  --data-urlencode "actor=$ACTOR" \
  --data-urlencode "comment=Reviewed generated hypothesis and limitations for training" \
  "$API/api/knowledge/$GENERATED_ID/submit-review" > "$LAB/generated-review.json"
api -X POST -G \
  --data-urlencode "actor=$ACTOR" \
  --data-urlencode "comment=Approved fictional workshop definition; no model performance claim" \
  "$API/api/knowledge/$GENERATED_ID/approve" > "$LAB/generated-approved.json"
run_stage HANDOFF > "$LAB/handoff-ready.json"
show_stage HANDOFF
```

**Expected:** a new handoff attempt is `AWAITING_APPROVAL`; the prior blocked
attempt remains in history. A `HANDOFF_PACKAGE` artifact references the stored
package. There is no separate package-download API.

### 12.2 Read the package before approving

In Terminal B, still at the repository root:

```bash
docker compose exec -T postgres psql -U aurora -d aurora_training \
  -v initiative_id="$INITIATIVE_ID" <<'SQL'
SELECT package_hash, jsonb_pretty(package)
FROM initiative_handoff_packages
WHERE initiative_id = :'initiative_id'::uuid
  AND client_id = '00000000-0000-0000-0000-000000000001'
ORDER BY created_at DESC;
SQL
```

Read the requirement, referenced designs and evidence, accepted uncertainty, and
the package exclusions. Save the displayed hash in your lab notes.
Do not modify referenced knowledge between reviewing the package and approving
it; a changed package requires a new review.

### 12.3 Record the offline delivery result

```bash
jq -n --arg actor "$ACTOR" \
  '{decision:"APPROVE",actor:$actor,
    reason:"Reviewed workshop package; demonstrate missing-receiver configuration handling",
    acceptedUnknownChecks:[]}' > "$LAB/handoff-decision.json"
api -X POST -H 'Content-Type: application/json' \
  --data-binary "@$LAB/handoff-decision.json" \
  "$API/api/initiatives/$INITIATIVE_ID/stages/HANDOFF/decision" \
  > "$LAB/handoff-result.json"
show_stage HANDOFF
```

**Expected:** `PROVIDER_FAILED` with `failureCode: AURORA_NOT_CONFIGURED`, no
candidate ID, and no outbound request, because the local token is deliberately
empty. The HTTP call can succeed while the recorded stage reports a delivery
failure: always inspect the body.

This completes the core workshop. A trainer can run a separate receiver
demonstration using the [handoff contract](handoff-contract.md), a current Aurora
Intelligence checkout, and its documented configuration. Real success requires
HTTP 201 from the receiver, a nonblank candidate ID, and `AWAITING_WEIGHTS`.
Neither response means that model weights were trained or deployed.

## 13. Independent exercise: diagnose an unsupported outcome

**Goal:** show that a specific blocker is more useful than an invented answer.

Create a second requirement; keep the original initiative unchanged:

```bash
jq '.businessUseCase = "workshop cancellation prevention"
    | .predictionTarget = "BOOKING_CANCELLED"
    | .observableDefinition = "BOOKING_CANCELLED"
    | .requiredObservables = ["BOOKING_CANCELLED"]
    | .constraints.modelName = "workshop-cancellation"' \
  "$LAB/requirement.json" > "$LAB/cancellation.json"
api -X POST -H 'Content-Type: application/json' \
  --data-binary "@$LAB/cancellation.json" "$API/api/discovery/requirements" \
  > "$LAB/cancellation-registered.json"
export CANCEL_ID="$(jq -er '.id' "$LAB/cancellation-registered.json")"
jq -n --arg id "$CANCEL_ID" \
  '{requirementId:$id,includeCandidates:true}' > "$LAB/cancellation-run.json"
api -X POST -H 'Content-Type: application/json' \
  --data-binary "@$LAB/cancellation-run.json" "$API/api/discovery/runs" \
  > "$LAB/cancellation-discovery.json"
jq '{classification,blockers,reasonCodes}' "$LAB/cancellation-discovery.json"
```

**Expected:** `MISSING_TARGET_OBSERVABLE:BOOKING_CANCELLED`.

Without copying an approval blindly, create a separate initiative for this
requirement, complete discovery, review the reuse findings, and run feasibility.
Explain why the missing outcome blocks it and what instrumentation/evidence is
needed. An exploratory reuse decision is not permission to ignore a subsequent
feasibility failure.

## 14. Troubleshooting

| Symptom | Likely explanation | Next action |
| --- | --- | --- |
| Connection refused on 8091 | Server not ready or not running | Inspect Terminal A and confirm the port |
| Port 5433 already used | A PostgreSQL service is already present | Ask the trainer to confirm the intended instance; do not kill an unknown service |
| Missing/invalid client returns 400 | Header absent, malformed, or unconfigured | Use the full configured UUID; health also requires the header |
| Candidate list appears empty | Default retrieval is approved-only | Explicitly use `includeCandidates=true` for candidate inspection |
| Candidate detail returns 404 | Candidate hidden, wrong client, or wrong ID | Check candidate mode, client header, and the saved list |
| Approval returns 409 | Illegal lifecycle transition | Submit an `EXTRACTED` object for review before approval |
| Approval says evidence is required | Candidate lacks supporting evidence | Investigate provenance; do not fabricate evidence to bypass the guard |
| Stage cannot start | Predecessor incomplete or current attempt awaiting review | Inspect stage status and finish the correct prior step |
| Unknown acceptance returns 400 | Missing, extra, or stale check names | Read the current attempt and explicitly review the exact set |
| All targeting drafts rejected | Missing columns, unsafe SQL, or other validator failure | Read each verdict; check the copied training DDL |
| `FEATURE_NOT_APPROVED` | Referenced feature is still a candidate | Review its evidence through the knowledge lifecycle |
| `PACKAGE_CHANGED_SINCE_APPROVAL` | Referenced content changed after package presentation | Run a fresh handoff attempt and review the new package |
| Handoff HTTP response is 200 but stage failed | HTTP transport and workflow status differ | Inspect `handoffAttempts` and `failureCode` |
| Import command stays open | CLI also starts a web server | Wait for completion markers, then Ctrl+C |
| Unexpected versions, approvals, or reuse | Old lab data or repeated creation commands | Check database and IDs; ask for a fresh lab instead of resetting shared data |
| Maven reports HTTP 429 | Dependency repository rate limit | Use the team's approved Maven mirror settings with `-s` |
| Testcontainers reports Docker API too old | Docker/client compatibility mismatch | On affected environments, the verified workaround is `-Dapi.version=1.44`; confirm compatibility with the trainer |

Retain the failing response, client ID, initiative ID, stage, latest attempt,
blockers, and relevant log excerpt when asking for help. Never paste credentials.
Keep workshop records until the trainer has assessed them; cleanup is a separate
trainer action. Ctrl+C stops the workshop Java server without deleting its data.

## 15. Assessment and answer key

### Practical submission

Provide a short lab note with:

- Your requirement ID, initiative ID, and client ID.
- One imported evidence example and the approved version's ID.
- Your discovery explanation, without a model-performance claim.
- Each accepted feasibility unknown and the evidence needed to resolve it.
- One rejected targeting draft and the validator reason.
- The generated feature's provenance and separate approval.
- Experiment inputs and the calculated exposure threshold.
- The package hash, final delivery outcome, and cancellation blocker.

### Trainer rubric

| Area | Points | What earns full credit |
| --- | ---: | --- |
| Product understanding | 15 | Clearly separates design, knowledge ingestion, and model execution |
| Environment and API use | 15 | Correct database, client header, IDs, and response interpretation |
| Knowledge governance | 20 | Explains source evidence, versions, lifecycle, and provenance |
| Discovery and feasibility | 20 | Justifies findings and accepts uncertainty explicitly |
| Design and handoff | 20 | Explains leakage, sample inputs, package approval, and delivery result |
| Diagnosis and teach-back | 10 | Independently explains the cancellation blocker and next action |

Suggested completion threshold: 80/100. Before sign-off, every learner must
correctly distinguish a candidate from approved knowledge, an accepted unknown
from a passed check, and a registered design from a trained model. Repeat the
relevant exercise when a learner cannot yet explain one of these distinctions.

### Knowledge check

1. **Does importing a source train the LLM?** No. It populates governed knowledge.
2. **Can a source declare itself approved?** No. Source declarations are metadata;
   Model Studio lifecycle approval is separate.
3. **Why is candidate search explicit?** To distinguish exploratory material from
   the approved-only default.
4. **Does accepting an unknown prove the data exists?** No. It records a review
   decision while preserving uncertainty.
5. **Does an accepted SQL draft prove a query will run?** No. Validation checks
   syntax and governed metadata; no warehouse query executes.
6. **Does a completed feature stage approve the generated feature?** No.
7. **What does 3,841 represent here?** A proposed per-variant exposure threshold
   derived from the declared workshop inputs.
8. **What does `AWAITING_WEIGHTS` mean?** The receiver has a design candidate;
   client model training and evaluation still follow.
9. **Why compare relationship IDs on repeated import?** Counts can stay the same
   while an edge points to a different version.
10. **Are reviewer names authenticated?** No. Local actors are explicitly
    unverified.

## 16. Developer orientation and next steps

Trace one request through controller → service → owning persistence contract.
Use [module architecture](module-architecture.md) for the full dependency graph.

| Where to look | What it owns |
| --- | --- |
| `common` | Client context, shared types, and errors |
| `gateway` | AI provider boundary, validation, invocation history |
| `knowledge` | Object lifecycle, evidence, relationships, persistence |
| `importer` | Reading supported Aurora artifacts |
| `extraction` | Parsing and grounded interpretation |
| `discovery` | Requirements, retrieval, deterministic ranking |
| `initiative` | Workflow facade, focused stage components, and gates |
| `app` | Application startup, CLI, configuration, migrations, integration tests |

**Optional coding exercise:** with a mentor, propose a small improvement to a
feasibility check. Identify the owning component, affected contract, meaningful
test, and expected user-visible outcome before editing code.
Do not inject another module's repository to take a shortcut.

Run the repository checks for code changes:

```bash
mvn -B spotless:check
mvn -B verify
```

The reactor includes Java compilation, unit tests, module-boundary tests, and
Docker-backed PostgreSQL integration tests when Docker is available.

**After the course:** shadow a reviewer on a real requirement, then independently
prepare a discovery and feasibility explanation for mentor review. Move to
external receiver demonstrations only after the learner can interpret the local
package and refusal results.

### Further reading

- [Use cases](use-cases.md): when the application is useful.
- [Knowledge model](knowledge-model.md): lifecycle, evidence, confidence, and impact.
- [Discovery](discovery.md): ranking and candidate visibility.
- [Targeting and features](targeting-feature-design.md): validator boundaries.
- [Experiment and handoff](experiment-design-and-handoff.md): experiment assumptions and delivery.
- [Handoff contract](handoff-contract.md): external receiver integration.
- [Module architecture](module-architecture.md): responsibilities and extension rules.
