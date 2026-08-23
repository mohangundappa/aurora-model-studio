create extension if not exists vector;

create table knowledge_embeddings (
  id uuid primary key default gen_random_uuid(),
  client_id uuid not null,
  knowledge_object_id uuid not null,
  embedding vector(32) not null,
  embedding_provider varchar(120) not null,
  created_at timestamptz not null default now(),
  unique (client_id, knowledge_object_id),
  foreign key (client_id, knowledge_object_id)
    references knowledge_objects(client_id, id)
);

create index knowledge_embeddings_embedding_idx
  on knowledge_embeddings using hnsw (embedding vector_cosine_ops);

create table discovery_requirements (
  id uuid primary key default gen_random_uuid(),
  client_id uuid not null,
  requirement jsonb not null,
  created_at timestamptz not null default now(),
  unique (client_id, id)
);

create table discovery_runs (
  id uuid primary key default gen_random_uuid(),
  client_id uuid not null,
  requirement_id uuid not null,
  include_candidates boolean not null default false,
  weights jsonb not null,
  embedding_provider varchar(120) not null,
  result jsonb not null,
  created_at timestamptz not null default now(),
  unique (client_id, id),
  foreign key (client_id, requirement_id)
    references discovery_requirements(client_id, id)
);

create or replace function reject_discovery_mutation() returns trigger language plpgsql as $$
begin
  raise exception 'discovery records are append-only';
end;
$$;

create trigger discovery_requirements_append_only
  before update or delete on discovery_requirements
  for each row execute function reject_discovery_mutation();

create trigger discovery_runs_append_only
  before update or delete on discovery_runs
  for each row execute function reject_discovery_mutation();
