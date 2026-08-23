# Experiment design and governed handoff

Model Studio's `EXPERIMENT_DESIGN` stage turns governed requirements,
observables, approved targeting, and approved features into a bounded
experiment definition. It validates one control and at least one treatment,
unique nonblank variant names, positive integer allocations totaling 100,
positive minimum exposures, and Aurora's 120-code-point variant-name limit.

Minimum exposures are calculated deterministically with a two-proportion
calculation. The package names the baseline conversion rate, minimum
detectable effect, alpha, and power used by that calculation. Missing governed
inputs produce `UNKNOWN`, not a plausible default, and leave the stage
`AWAITING_APPROVAL` until a human explicitly accepts the named unknown checks.
When variants are omitted, the deterministic 50/50 split is labeled
`allocationSource: DEFAULT`; requirement-declared variants are labeled
`allocationSource: REQUIREMENT`. A decision rule includes the governed
effect, alpha, power, and per-variant exposure threshold; it is `UNKNOWN`
when those inputs are not governed.

`HANDOFF` is a separate human-gated stage. It requires completed targeting and
feature design, approved referenced features, accepted feasibility and
experiment unknowns, required observables, and no open blocking knowledge
conflicts. Generated `EXTRACTED` feature candidates and the live
loyalty-tenure conflict therefore block the handoff.

An approved handoff transfers an immutable, content-hashed design package to
Aurora using:

```text
POST /api/models/{name}/candidates
→ 201 { candidateId, status: "AWAITING_WEIGHTS" }
```

The package hash is the idempotency key and every attempt is persisted.
Transport or remote failures are contained and never create a local fake
registration. The package contains **no trained model, no weights, and no
evaluation**. It does not claim `TESTED`; Model Studio never trains a model.
Client MLOps supplies weights and evaluation later, under a separate human
controlled process.

The reset script uses a deliberately scripted actor and reason to accept the
seeded unknown checks so the complete walkthrough can reach the handoff
preconditions. Those approvals are demo actions, not evidence of a real human
review.
