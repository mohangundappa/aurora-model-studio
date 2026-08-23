create table initiatives (
  id uuid primary key default gen_random_uuid(),
  client_id uuid not null,
  requirement_id uuid not null,
  include_candidates boolean not null default false,
  client_baseline_duration_millis bigint,
  created_at timestamptz not null default now(),
  unique (client_id, id),
  foreign key (client_id, requirement_id)
    references discovery_requirements(client_id, id)
);

create table initiative_stage_attempts (
  id uuid primary key default gen_random_uuid(),
  client_id uuid not null,
  initiative_id uuid not null,
  stage varchar(60) not null,
  attempt integer not null,
  status varchar(30) not null,
  started_at timestamptz,
  completed_at timestamptz,
  machine_duration_millis bigint not null default 0,
  human_wait_duration_millis bigint not null default 0,
  blockers jsonb not null default '[]'::jsonb,
  feasibility_checks jsonb not null default '[]'::jsonb,
  artifact_ids jsonb not null default '[]'::jsonb,
  created_at timestamptz not null default now(),
  unique (client_id, initiative_id, stage, attempt),
  unique (client_id, id),
  foreign key (client_id, initiative_id)
    references initiatives(client_id, id)
);

create table initiative_gate_decisions (
  id uuid primary key default gen_random_uuid(),
  client_id uuid not null,
  initiative_id uuid not null,
  stage_attempt_id uuid not null,
  stage varchar(60) not null,
  decision varchar(20) not null check (decision in ('APPROVE','REJECT','RETURN')),
  actor varchar(200) not null,
  actor_verified boolean not null default false,
  reason text,
  created_at timestamptz not null default now(),
  foreign key (client_id, initiative_id)
    references initiatives(client_id, id),
  foreign key (client_id, stage_attempt_id)
    references initiative_stage_attempts(client_id, id)
);

create table initiative_events (
  id bigserial primary key,
  client_id uuid not null,
  initiative_id uuid not null,
  stage varchar(60) not null,
  from_status varchar(30),
  to_status varchar(30) not null,
  actor varchar(200) not null,
  reason text,
  artifact_ids jsonb not null default '[]'::jsonb,
  at timestamptz not null default now(),
  foreign key (client_id, initiative_id)
    references initiatives(client_id, id)
);

create index initiative_lookup_idx on initiatives(client_id, created_at desc);
create index initiative_stage_lookup_idx on initiative_stage_attempts(client_id, initiative_id, stage, attempt desc);
create index initiative_event_lookup_idx on initiative_events(client_id, initiative_id, at);

create or replace function reject_initiative_append_only() returns trigger language plpgsql as $$
begin
  raise exception 'initiative records are append-only';
end;
$$;

create trigger initiative_events_append_only
  before update or delete on initiative_events
  for each row execute function reject_initiative_append_only();

create trigger initiative_gate_decisions_append_only
  before update or delete on initiative_gate_decisions
  for each row execute function reject_initiative_append_only();

create or replace function require_human_initiative_gate() returns trigger language plpgsql as $$
begin
  if coalesce(current_setting('aurora.initiative_gate_actor', true), '') <> 'human' then
    raise exception 'initiative gate decisions require the human gate API';
  end if;
  if new.actor_verified then
    raise exception 'initiative actors are self-declared and unverified';
  end if;
  return new;
end;
$$;

create trigger initiative_gate_human_guard
  before insert on initiative_gate_decisions
  for each row execute function require_human_initiative_gate();
