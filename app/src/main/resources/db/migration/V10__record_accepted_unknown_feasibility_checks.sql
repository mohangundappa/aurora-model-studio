alter table initiative_gate_decisions
  add column accepted_unknown_checks jsonb not null default '[]'::jsonb;
