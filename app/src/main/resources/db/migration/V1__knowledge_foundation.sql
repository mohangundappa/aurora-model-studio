create extension if not exists pgcrypto;

create table knowledge_objects (
  id uuid primary key default gen_random_uuid(),
  client_id uuid not null,
  knowledge_key varchar(240) not null,
  version integer not null,
  knowledge_type varchar(40) not null check (knowledge_type in ('MODEL','FEATURE','DATA_ASSET','IMPLEMENTATION','EXPERIMENT','STANDARD')),
  name varchar(300) not null,
  business_domain varchar(200) not null,
  business_use_case varchar(300) not null,
  business_description text not null,
  canonical_taxonomy jsonb not null default '{}'::jsonb,
  client_taxonomy jsonb not null default '{}'::jsonb,
  tags text[] not null default '{}',
  lifecycle_status varchar(40) not null check (lifecycle_status in ('EXTRACTED','PENDING_REVIEW','APPROVED','SUPERSEDED','DEPRECATED')),
  effective_from timestamptz,
  effective_to timestamptz,
  confidence numeric(6,5) not null check (confidence between 0 and 1),
  confidence_breakdown jsonb not null,
  quality_assessment jsonb not null default '{}'::jsonb,
  extracted_at timestamptz not null default now(),
  extracted_by varchar(200) not null,
  reviewed_at timestamptz,
  reviewed_by varchar(200),
  approved_at timestamptz,
  approved_by varchar(200),
  approval_comments text,
  attributes jsonb not null,
  synthetic boolean not null,
  created_at timestamptz not null default now(),
  unique (client_id, knowledge_key, version)
);
create unique index knowledge_one_approved_per_key
  on knowledge_objects (client_id, knowledge_key)
  where lifecycle_status = 'APPROVED';
create index knowledge_lookup_idx on knowledge_objects (client_id, knowledge_type, lifecycle_status);
create index knowledge_key_idx on knowledge_objects (client_id, knowledge_key, version desc);

create table knowledge_evidence (
  id uuid primary key default gen_random_uuid(),
  client_id uuid not null,
  knowledge_object_id uuid not null references knowledge_objects(id),
  source_system varchar(120) not null,
  source_type varchar(80) not null,
  source_uri text not null,
  source_version varchar(240) not null,
  excerpt text not null,
  extraction_certainty numeric(6,5) not null check (extraction_certainty between 0 and 1),
  recorded_at timestamptz not null default now()
);
create index knowledge_evidence_object_idx on knowledge_evidence (client_id, knowledge_object_id);

create table knowledge_relationships (
  id uuid primary key default gen_random_uuid(),
  client_id uuid not null,
  from_object_id uuid not null references knowledge_objects(id),
  relationship_type varchar(40) not null,
  to_object_id uuid not null references knowledge_objects(id),
  evidence_id uuid references knowledge_evidence(id),
  created_at timestamptz not null default now(),
  check (from_object_id <> to_object_id)
);
create index knowledge_relationship_from_idx on knowledge_relationships (client_id, from_object_id);
create index knowledge_relationship_to_idx on knowledge_relationships (client_id, to_object_id);
alter table knowledge_relationships
  add constraint knowledge_relationship_unique unique (client_id, from_object_id, relationship_type, to_object_id);

create table knowledge_conflicts (
  id uuid primary key default gen_random_uuid(),
  client_id uuid not null,
  knowledge_object_id uuid not null references knowledge_objects(id),
  field varchar(160) not null,
  values jsonb not null,
  status varchar(30) not null check (status in ('OPEN','RESOLVED')),
  detected_at timestamptz not null default now(),
  resolved_at timestamptz,
  resolved_by varchar(200)
);
create index knowledge_conflict_object_idx on knowledge_conflicts (client_id, knowledge_object_id, status);

create table knowledge_audit (
  id bigserial primary key,
  client_id uuid not null,
  knowledge_object_id uuid not null references knowledge_objects(id),
  from_status varchar(40),
  to_status varchar(40) not null,
  actor varchar(200) not null,
  comment text,
  at timestamptz not null default now()
);
create index knowledge_audit_object_idx on knowledge_audit (client_id, knowledge_object_id, at);

create or replace function protect_approved_knowledge() returns trigger language plpgsql as $$
begin
  if tg_op = 'DELETE' then
    if old.lifecycle_status <> 'EXTRACTED' then
      raise exception 'knowledge object % cannot be deleted from lifecycle status %', old.id, old.lifecycle_status;
    end if;
    return old;
  end if;
  if old.lifecycle_status = 'APPROVED' then
    if new.lifecycle_status not in ('SUPERSEDED','DEPRECATED')
       or new.client_id <> old.client_id
       or new.knowledge_key <> old.knowledge_key
       or new.version <> old.version
       or new.knowledge_type <> old.knowledge_type
       or new.name <> old.name
       or new.business_domain <> old.business_domain
       or new.business_use_case <> old.business_use_case
       or new.business_description <> old.business_description
       or new.attributes <> old.attributes
       or new.synthetic <> old.synthetic
       or new.confidence <> old.confidence
       or new.confidence_breakdown <> old.confidence_breakdown then
      raise exception 'approved knowledge object % is immutable', old.id;
    end if;
  end if;
  return new;
end;
$$;
create trigger knowledge_approved_guard
  before update or delete on knowledge_objects
  for each row execute function protect_approved_knowledge();
