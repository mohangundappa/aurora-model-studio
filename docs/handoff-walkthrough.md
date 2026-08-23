# Presenter walkthrough: governed handoff

This is a live walkthrough. Keep the Model Studio repository as the current
directory and run the commands in one shell so that `C`, `API`, and
`REUSE_ID` remain available.

The UUIDs and SHA-256 hash shown below are from an observed run. A clean reset
creates new UUIDs, and the package hash can therefore differ; the statuses,
blockers, fields, and response codes are the assertions for the walkthrough.

## 1. Start the receiving service and Model Studio

Aurora's receiving branch is in the separate
`/home/ubuntu/repos/aurora-intelligence` repository. Start that stack on the
host, then start Model Studio:

```bash
cd /home/ubuntu/repos/aurora-intelligence
git checkout devin/1787514903-model-studio-candidate-registration
docker compose up -d

cd /home/ubuntu/repos/aurora-model-studio
docker compose up --build -d
```

Model Studio runs in a container, while Aurora is reached through the
host-published port. `localhost:8080` is the Model Studio container itself,
not the host. The original route was observed to fail from inside the
container:

```text
Connecting to localhost (localhost)|::1|:8080... failed: Connection refused.
Connecting to localhost (localhost)|127.0.0.1|:8080... failed: Connection refused.
```

The working route resolves `host.docker.internal` to `172.17.0.1` and reached
Aurora's health endpoint with HTTP `200`. `docker-compose.yml` supplies the
Linux host-gateway mapping and makes the URL overridable:

```yaml
STUDIO_HANDOFF_AURORA_BASE_URL: ${STUDIO_HANDOFF_AURORA_BASE_URL:-http://host.docker.internal:8080}
STUDIO_HANDOFF_AURORA_TOKEN: ${STUDIO_HANDOFF_AURORA_TOKEN:-aurora-model-studio-demo-token}
extra_hosts:
  - "host.docker.internal:host-gateway"
```

The default favors the topology used here: Model Studio in Compose and Aurora
on the host. Set `STUDIO_HANDOFF_AURORA_BASE_URL` when both services run in a
different network topology. The demo token must match Aurora's
`AURORA_CANDIDATES_STUDIO_TOKEN`; it protects the candidate write seam only.

Set the API variables and confirm the application:

```bash
C='00000000-0000-0000-0000-000000000001'
API='http://localhost:8081'
curl -sS -H "X-Aurora-Client: $C" "$API/actuator/health"
```

Observed output:

```json
{"status":"UP"}
```

## 2. Reset: begin with refusal

Run the reset exactly as shipped. Do not approve a generated feature in the
reset script:

```bash
cd /home/ubuntu/repos/aurora-model-studio
./scripts/reset-demo.sh
```

The reset ended with:

```text
Reset assertions passed: two initiatives, documented feasibility states, and loyalty-tenure conflict.
```

Locate the seeded reuse initiative and show the two states that matter:

```bash
REUSE_ID="$(curl -sS -H "X-Aurora-Client: $C" "$API/api/initiatives" |
  jq -r '[.[] | select(.requirement.constraints.modelName=="booking-intent")][0].id')"
echo "REUSE_ID=$REUSE_ID"
curl -sS -H "X-Aurora-Client: $C" "$API/api/initiatives/$REUSE_ID" |
  jq '{
    handoff: ([.stages[] | select(.stage=="HANDOFF")][0] |
      {status, blockers:.attempts[-1].blockers,
       handoffAttempts:.attempts[-1].handoffAttempts}),
    experimentDesign: ([.stages[] | select(.stage=="EXPERIMENT_DESIGN")][0] |
      {status,
       unknowns:[.attempts[-1].feasibilityChecks[] |
         select(.status=="UNKNOWN") | .name],
       decisionRule:.attempts[-1].drafts[0].payload.decisionRule})
  }'
```

Observed output:

```text
REUSE_ID=9f02763e-6079-408e-9b4b-5bcb17f66b60
```

```json
{
  "handoff": {
    "status": "BLOCKED",
    "blockers": [
      "FEATURE_NOT_APPROVED:feature:generated:recent-session-engagement"
    ],
    "handoffAttempts": []
  },
  "experimentDesign": {
    "status": "COMPLETED",
    "unknowns": [
      "sample-size-baselineConversionRate",
      "sample-size-minimumDetectableEffect",
      "sample-size-alpha",
      "sample-size-power",
      "minimum-exposures"
    ],
    "decisionRule": "UNKNOWN"
  }
}
```

The reset accepts the experiment and feasibility unknowns with a deliberately
scripted actor so the walkthrough can reach the handoff. That is not human
review. The unknowns remain recorded because the corpus contains no governed
baseline conversion rate (and therefore no governed inputs from which to
derive a sample size or decision rule).

The refusal is the opening beat: the generated feature is still an extracted
candidate, so there is no outbound attempt and nothing has been silently
registered.

## 3. Approve the generated feature live

Use the feature named by the blocker, not a hard-coded feature ID. The seeded
corpus also contains an approved `feature:booking-intent` baseline; only the
generated feature named by the blocker needs live approval.

```bash
BLOCKER="$(curl -sS -H "X-Aurora-Client: $C" "$API/api/initiatives/$REUSE_ID" |
  jq -r '[.stages[] | select(.stage=="HANDOFF")][0].attempts[-1].blockers[] |
    select(startswith("FEATURE_NOT_APPROVED:"))' | head -n1)"
FEATURE_KEY="${BLOCKER#FEATURE_NOT_APPROVED:}"
FEATURE_ID="$(curl -sS -H "X-Aurora-Client: $C" \
  "$API/api/knowledge?includeCandidates=true" |
  jq -r --arg key "$FEATURE_KEY" \
    '[.[] | select(.knowledgeKey==$key and .lifecycleStatus=="EXTRACTED")][0].id')"
ACTOR='Maya Chen'
REASON='Live presenter approval of generated feature for governed handoff'

curl -sS -X POST -H "X-Aurora-Client: $C" --get \
  --data-urlencode "actor=$ACTOR" \
  --data-urlencode "comment=$REASON" \
  "$API/api/knowledge/$FEATURE_ID/submit-review" |
  jq '{id,knowledgeKey,lifecycleStatus}'

curl -sS -X POST -H "X-Aurora-Client: $C" --get \
  --data-urlencode "actor=$ACTOR" \
  --data-urlencode "comment=$REASON" \
  "$API/api/knowledge/$FEATURE_ID/approve" |
  jq '{id,knowledgeKey,lifecycleStatus,approvedBy,approvalComments}'
```

Observed output:

```json
{
  "id": "1bd17af3-808b-42c0-a9ce-ca784bd8d532",
  "knowledgeKey": "feature:generated:recent-session-engagement",
  "lifecycleStatus": "PENDING_REVIEW"
}
{
  "id": "1bd17af3-808b-42c0-a9ce-ca784bd8d532",
  "knowledgeKey": "feature:generated:recent-session-engagement",
  "lifecycleStatus": "APPROVED",
  "approvedBy": "Maya Chen",
  "approvalComments": "Live presenter approval of generated feature for governed handoff"
}
```

This is the deliberate human gate: the presenter has now approved the
generated feature with a named actor and a reason.

## 4. Create and approve the handoff

First run the stage. This creates and records the immutable package, but does
not send it yet:

```bash
curl -sS -X POST -H "X-Aurora-Client: $C" \
  -H 'Content-Type: application/json' -d '{}' \
  "$API/api/initiatives/$REUSE_ID/stages/HANDOFF/run" |
  jq '{
    status,
    handoff: ([.stages[] | select(.stage=="HANDOFF")][0] |
      {status, blockers:.attempts[-1].blockers,
       handoffAttempts:.attempts[-1].handoffAttempts,
       artifacts:.attempts[-1].artifacts})
  }'
```

Observed output:

```json
{
  "status": "ACTIVE",
  "handoff": {
    "status": "AWAITING_APPROVAL",
    "blockers": [],
    "handoffAttempts": [],
    "artifacts": [
      {
        "type": "HANDOFF_PACKAGE",
        "id": "26bcf6a0-7209-4d8c-9c41-94a6162d3126",
        "synthetic": false
      }
    ]
  }
}
```

Approve the recorded package and send it:

```bash
curl -sS -X POST -H "X-Aurora-Client: $C" \
  -H 'Content-Type: application/json' \
  -d '{"decision":"APPROVE","actor":"Maya Chen",
       "reason":"Live approval to send the immutable design package to Aurora"}' \
  "$API/api/initiatives/$REUSE_ID/stages/HANDOFF/decision" |
  jq '{
    handoff: ([.stages[] | select(.stage=="HANDOFF")][0] |
      {status, blockers:.attempts[-1].blockers,
       handoffAttempts:[.attempts[-1].handoffAttempts[] |
         {packageHash,responseStatus,candidateId,candidateStatus,outcome,
          failureCode,requestSummaryPackageHash:.requestSummary.packageHash}]})
  }'
```

Observed output:

```json
{
  "handoff": {
    "status": "COMPLETED",
    "blockers": [],
    "handoffAttempts": [
      {
        "packageHash": "e6a9bf2d7645cd6974c1bd6af24b707e674ff04e94e87fea09ae6716f9b5ac2b",
        "responseStatus": 201,
        "candidateId": "06b4605f-951f-4e82-a580-7d36ba657bae",
        "candidateStatus": "AWAITING_WEIGHTS",
        "outcome": "REGISTERED",
        "failureCode": null,
        "requestSummaryPackageHash": "e6a9bf2d7645cd6974c1bd6af24b707e674ff04e94e87fea09ae6716f9b5ac2b"
      }
    ]
  }
}
```

The package hash is also the outbound `Idempotency-Key`. The matching hash in
`requestSummaryPackageHash` shows what was sent. The `201` response is a
candidate registration, not a trained-model registration.

## 5. Replay the same package

Run the handoff again and approve the new attempt:

```bash
curl -sS -X POST -H "X-Aurora-Client: $C" \
  -H 'Content-Type: application/json' -d '{}' \
  "$API/api/initiatives/$REUSE_ID/stages/HANDOFF/run" >/dev/null

curl -sS -X POST -H "X-Aurora-Client: $C" \
  -H 'Content-Type: application/json' \
  -d '{"decision":"APPROVE","actor":"Maya Chen",
       "reason":"Replay approved to demonstrate idempotent handoff"}' \
  "$API/api/initiatives/$REUSE_ID/stages/HANDOFF/decision" |
  jq '[.stages[] | select(.stage=="HANDOFF")][0].attempts |
    map(select(.status=="COMPLETED") | .handoffAttempts[] |
      {packageHash,responseStatus,candidateId,candidateStatus,outcome,failureCode})'
```

Observed output:

```json
[
  {
    "packageHash": "e6a9bf2d7645cd6974c1bd6af24b707e674ff04e94e87fea09ae6716f9b5ac2b",
    "responseStatus": 201,
    "candidateId": "06b4605f-951f-4e82-a580-7d36ba657bae",
    "candidateStatus": "AWAITING_WEIGHTS",
    "outcome": "REGISTERED",
    "failureCode": null
  },
  {
    "packageHash": "e6a9bf2d7645cd6974c1bd6af24b707e674ff04e94e87fea09ae6716f9b5ac2b",
    "responseStatus": 201,
    "candidateId": "06b4605f-951f-4e82-a580-7d36ba657bae",
    "candidateStatus": "AWAITING_WEIGHTS",
    "outcome": "REGISTERED",
    "failureCode": null
  }
]
```

The second Model Studio attempt is still `COMPLETED`, not a provider failure.
Aurora's database uniqueness constraint returns the original candidate ID for
the replay.

## 6. Show the candidate versus the unchanged model registry

The candidate endpoint is the receiving seam. Filter by this walkthrough's
initiative so older demo candidates in a persistent Aurora database do not
obscure the result:

```bash
curl -sS http://localhost:8080/api/models/booking-intent/candidates |
  jq --arg id "$REUSE_ID" \
    'map(select(.studioInitiativeId==$id) |
      {candidateId,modelName,packageHash,studioInitiativeId,status,
       requirementId:.packageContent.requirementId,
       notIncluded:.packageContent.notIncluded})'
```

Observed output:

```json
[
  {
    "candidateId": "06b4605f-951f-4e82-a580-7d36ba657bae",
    "modelName": "booking-intent",
    "packageHash": "e6a9bf2d7645cd6974c1bd6af24b707e674ff04e94e87fea09ae6716f9b5ac2b",
    "studioInitiativeId": "9f02763e-6079-408e-9b4b-5bcb17f66b60",
    "requirementId": "4aced7ef-0e6c-4e87-8217-b6cb70456d0b",
    "status": "AWAITING_WEIGHTS",
    "notIncluded": [
      "trained model",
      "weights",
      "evaluation",
      "expected lift"
    ]
  }
]
```

Show the existing model versions immediately beside it:

```bash
curl -sS http://localhost:8080/api/models/booking-intent |
  jq 'map({modelName,version,status})'

curl -sS -X POST -H 'Content-Type: application/json' \
  -d '{"propertyViewed":1,"roomViewed":1,"rateViewed":1,"bookingStarted":1}' \
  http://localhost:8080/api/models/booking-intent/predict |
  jq '{modelName,modelVersion,score,explanation}'
```

Observed output:

```json
[
  {
    "modelName": "booking-intent",
    "version": "1.0",
    "status": "DEPLOYED"
  },
  {
    "modelName": "booking-intent",
    "version": "2.0",
    "status": "TESTED"
  }
]
```

```json
{
  "modelName": "booking-intent",
  "modelVersion": "1.0",
  "score": 100,
  "explanation": "Score uses deployed booking-intent version 1.0; feature contributions explain the result."
}
```

This contrast is the point of the phase. Aurora received an approved design
package as an `AWAITING_WEIGHTS` candidate. The package did not become a model
version, did not earn `TESTED`, and did not change serving: prediction still
uses deployed version `1.0`. There is no trained model, weights, evaluation,
expected lift, or causal claim in this handoff.

## 7. Presenter note: Aurora unreachable

This is a resilience note, not part of the successful path. It was tested with
a throwaway invalid URL and then restored. Recreate only the Model Studio app
with the bad URL:

```bash
STUDIO_HANDOFF_AURORA_BASE_URL='http://127.0.0.1:1' \
  docker compose up -d --force-recreate app
until curl -fsS -H "X-Aurora-Client: $C" "$API/actuator/health" >/dev/null 2>&1; do
  sleep 1
done
docker exec aurora-model-studio-app-1 printenv STUDIO_HANDOFF_AURORA_BASE_URL
```

Observed output:

```text
http://127.0.0.1:1
```

Run a new handoff attempt and approve it:

```bash
curl -sS -X POST -H "X-Aurora-Client: $C" \
  -H 'Content-Type: application/json' -d '{}' \
  "$API/api/initiatives/$REUSE_ID/stages/HANDOFF/run" >/dev/null

curl -sS -X POST -H "X-Aurora-Client: $C" \
  -H 'Content-Type: application/json' \
  -d '{"decision":"APPROVE","actor":"Maya Chen",
       "reason":"Throwaway unreachable-Aurora resilience check"}' \
  "$API/api/initiatives/$REUSE_ID/stages/HANDOFF/decision" |
  jq '[.stages[] | select(.stage=="HANDOFF")][0].attempts[-1] |
    {status,handoffAttempts:[.handoffAttempts[] |
      {packageHash,responseStatus,candidateId,candidateStatus,outcome,
       failureCode,failureMessage,endpoint}]}'
```

Observed output:

```json
{
  "status": "PROVIDER_FAILED",
  "handoffAttempts": [
    {
      "packageHash": "e6a9bf2d7645cd6974c1bd6af24b707e674ff04e94e87fea09ae6716f9b5ac2b",
      "responseStatus": null,
      "candidateId": null,
      "candidateStatus": null,
      "outcome": "PROVIDER_FAILED",
      "failureCode": "AURORA_UNREACHABLE",
      "failureMessage": "Aurora candidate registration failed",
      "endpoint": "http://127.0.0.1:1/api/models/booking-intent/candidates"
    }
  ]
}
```

This proves there is no faked success and no local candidate ID when Aurora
cannot be reached. Restore the documented working default and leave the app
running:

```bash
docker compose up -d --force-recreate app
until curl -fsS -H "X-Aurora-Client: $C" "$API/actuator/health" >/dev/null 2>&1; do
  sleep 1
done
docker exec aurora-model-studio-app-1 printenv STUDIO_HANDOFF_AURORA_BASE_URL
```

Observed restored value:

```text
http://host.docker.internal:8080
```

If the token is missing, Model Studio does not attempt an anonymous POST. The
persisted attempt is `PROVIDER_FAILED` with `failureCode` `AURORA_NOT_CONFIGURED`,
no response status, and no candidate ID. Restore
`STUDIO_HANDOFF_AURORA_TOKEN` to the documented demo value before the successful
walkthrough. In the live check, the attempt summary was:

```json
{
  "status": "PROVIDER_FAILED",
  "handoffAttempts": [
    {
      "responseStatus": null,
      "candidateId": null,
      "outcome": "PROVIDER_FAILED",
      "failureCode": "AURORA_NOT_CONFIGURED",
      "failureMessage": "Aurora candidate registration failed"
    }
  ]
}
```
