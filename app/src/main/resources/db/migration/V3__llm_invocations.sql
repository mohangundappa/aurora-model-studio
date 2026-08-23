create table llm_invocations (
  id uuid primary key default gen_random_uuid(),
  client_id uuid not null,
  task_id varchar(200) not null,
  provider varchar(80) not null,
  model varchar(160) not null,
  prompt_template_id varchar(200) not null,
  prompt_template_version varchar(80) not null,
  prompt_hash varchar(128) not null,
  schema_id varchar(200) not null,
  input_tokens integer not null default 0,
  output_tokens integer not null default 0,
  cost numeric(14,8) not null default 0,
  latency_millis bigint not null default 0,
  retry_count integer not null default 0,
  outcome varchar(30) not null check (outcome in ('OK','REFUSED','SCHEMA_INVALID','FAILED')),
  recorded_at timestamptz not null default now(),
  unique (client_id, id)
);
create index llm_invocations_task_idx on llm_invocations(client_id, task_id, recorded_at);

alter table knowledge_objects
  add column llm_invocation_id uuid,
  add constraint knowledge_objects_llm_invocation_fk
    foreign key (client_id, llm_invocation_id)
    references llm_invocations(client_id, id);

create or replace function reject_llm_invocation_mutation() returns trigger language plpgsql as $$
begin
  raise exception 'llm invocations are append-only';
end;
$$;

create trigger llm_invocations_append_only
  before update or delete on llm_invocations
  for each row execute function reject_llm_invocation_mutation();
