create table initiative_handoff_packages (
  id uuid primary key default gen_random_uuid(),
  client_id uuid not null,
  initiative_id uuid not null,
  package_hash varchar(128) not null,
  package jsonb not null,
  created_at timestamptz not null default now(),
  unique (client_id, package_hash),
  foreign key (client_id, initiative_id) references initiatives(client_id, id)
);

create table initiative_handoff_attempts (
  id uuid primary key default gen_random_uuid(),
  client_id uuid not null,
  initiative_id uuid not null,
  stage_attempt_id uuid not null,
  package_hash varchar(128) not null,
  endpoint text not null,
  request_summary jsonb not null default '{}'::jsonb,
  response_status integer,
  candidate_id varchar(240),
  candidate_status varchar(80),
  outcome varchar(40) not null,
  failure_code varchar(120),
  failure_message varchar(500),
  started_at timestamptz not null,
  completed_at timestamptz not null,
  created_at timestamptz not null default now(),
  foreign key (client_id, initiative_id) references initiatives(client_id, id),
  foreign key (client_id, stage_attempt_id)
    references initiative_stage_attempts(client_id, id)
);

create index initiative_handoff_attempt_lookup
  on initiative_handoff_attempts(client_id, initiative_id, created_at);
