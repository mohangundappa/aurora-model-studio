# Future candidate-model handoff

Phase 5 may add an HTTP `POST` from Aurora Model Studio to Aurora Intelligence that
registers a candidate model as `TESTED` in Aurora Intelligence's model registry. The
payload is expected to include:

- model name and model version
- feature names
- weights and bias
- initiative ID
- approved knowledge versions used
- experiment evidence
- self-declared approver

Aurora Intelligence currently has no receiving endpoint for this contract. No handoff
code or client is implemented in phase 1, and the Aurora Intelligence repository is not
changed by this project. Production deployment remains a human decision in Aurora's
lifecycle. The receiving runtime, deployment controls, monitoring, and rollback remain
client MLOps responsibilities.
