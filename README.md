# Aurora Model Studio

Aurora Model Studio is a governed, provider-neutral development workspace for
turning a marketing requirement into an evidence-backed and human-approved
model design package. It imports a real Aurora Intelligence checkout in place,
maintains versioned knowledge with provenance, discovers and reuses existing
features and implementations, and records conflicts, confidence, lifecycle
decisions, and tenant-scoped audit history.

The current loop is live:

```text
requirement
  → knowledge backfill and grounded extraction
  → retrieval and scorecard-based discovery/reuse
  → deterministic data-feasibility checks
  → targeting and feature design
  → bounded experiment design
  → named human approval
  → content-hashed handoff to Aurora Intelligence
```

The provider-neutral LLM gateway supports a deterministic adapter by default,
which is suitable for offline demos and CI. OpenAI is opt-in through
`studio.llm.provider=openai` and `OPENAI_API_KEY`; provider calls, prompts,
schemas, outcomes, and costs are recorded in append-only invocation history.
Model-assisted descriptions and proposals remain evidence-grounded candidates:
validators and human gates, not the model, decide what is trusted.

The handoff creates an immutable design package for Aurora Intelligence. It is
not model training and does not claim weights, evaluation, expected lift, or a
causal result. `CANDIDATE_BUILD` is permanently out of scope. Aurora receives
the package as a candidate awaiting client-trained weights; client MLOps owns
training, evaluation, deployment, serving, monitoring, and rollback of any
real model version. Governance actors in this local showcase are
self-declared and unverified. Aurora Hotels is fictional.

[Platform map](docs/model-studio-platform.md) explains the standalone Model Studio layers, lifecycle, interfaces, and external seams.
[Layer mapping](docs/layer-mapping.md) maps each diagram box and lifecycle stage to its implementation or gap.
[Agent boundary ADR](docs/adr/0001-agent-boundary.md) describes where AI agents fit and where verdicts stay deterministic.

Aurora Intelligence is the separate runtime that turns events into signals,
context, decisions, experiments, and measured value. The two products meet
through the live HTTP handoff described in the
[Aurora Hotels capability guide](https://github.com/mohangundappa/aurora-intelligence/blob/main/docs/capability-guide.md).

## Run locally

Requirements: Java 21, Maven, Docker, and PostgreSQL support for tests.

```bash
mvn -B verify
docker compose up --build -d
```

The PostgreSQL and application ports are `5433` and `8081`. Every API request needs
`X-Aurora-Client`; the local demo clients are UUIDs ending in `...0001` and `...0002`.
Actors are self-declared local-demo values, not authenticated identities.
The database gate trigger is defense in depth against accidental machine writes:
because the API marker is a session setting, a database-capable actor could forge
that marker. Gate rows still force `actor_verified` to remain false.

To recreate the showcase corpus and seed both initiatives from a clean database:

```bash
scripts/reset-demo.sh
```

The reset removes and recreates the Compose database volume, then runs the importer,
structural extraction, synthetic extraction, curated demo approval, curated approvals,
embedding backfill, and initiative seeding. Initiatives are created through the same
API service path used by `POST /api/initiatives`; the script never inserts initiative
rows directly. Set `AURORA_REPO` to use a different Aurora Intelligence checkout.

To backfill the real Aurora Intelligence checkout:

```bash
mvn -pl app -am package
java -jar app/target/app-0.1.0-SNAPSHOT.jar \
  --aurora-repo /home/ubuntu/repos/aurora-intelligence
```

Pass `--import` without `--aurora-repo` to use the default
`/home/ubuntu/repos/aurora-intelligence` path.

The importer reads artifacts in place and stores source paths and the resolved Git
commit as evidence. It never copies Aurora source files into this repository.
