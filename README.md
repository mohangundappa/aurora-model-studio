# Aurora Model Studio

Aurora Model Studio is the governed enterprise-knowledge foundation for accelerating
model development from a requirement to an approved candidate model. Phase 1 records
versioned models, features, data assets, implementations, experiments, standards,
evidence, relationships, conflicts, confidence, and lifecycle decisions.

It is not a live AI gateway, an LLM product, a vector or graph search system, a
production model-serving system, or a replacement for client MLOps. Phase 1 contains
no AI at all. Production deployment, monitoring, feature serving, and operational
ownership remain client MLOps responsibilities.

Aurora Intelligence remains the runtime that turns signals into decisions and measured
value. Model Studio and Aurora Intelligence meet through a future HTTP handoff contract;
the handoff is documented but not implemented in phase 1. Aurora Hotels is fictional.

## Run locally

Requirements: Java 21, Maven, Docker, and PostgreSQL support for tests.

```bash
mvn -B verify
docker compose up --build -d
```

The PostgreSQL and application ports are `5433` and `8081`. Every API request needs
`X-Aurora-Client`; the local demo clients are UUIDs ending in `...0001` and `...0002`.
Actors are self-declared local-demo values, not authenticated identities.

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
