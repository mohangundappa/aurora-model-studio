alter table initiative_stage_attempts
  add column generation_drafts jsonb not null default '[]'::jsonb,
  add column drafts_generated integer not null default 0,
  add column drafts_rejected integer not null default 0,
  add column violated_checks jsonb not null default '[]'::jsonb;
