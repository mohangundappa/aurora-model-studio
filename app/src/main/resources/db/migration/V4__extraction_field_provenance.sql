create table knowledge_field_provenance (
  id uuid primary key default gen_random_uuid(),
  client_id uuid not null,
  knowledge_object_id uuid not null,
  field_name varchar(160) not null,
  field_value jsonb not null,
  provenance varchar(40) not null check (
    provenance in ('EVIDENCE_BACKED','ADAPTED','AI_GENERATED_HYPOTHESIS')
  ),
  citation_evidence_id uuid,
  citation_excerpt text,
  extraction_certainty numeric(6,5) not null check (extraction_certainty between 0 and 1),
  created_at timestamptz not null default now(),
  foreign key (client_id, knowledge_object_id)
    references knowledge_objects(client_id, id),
  foreign key (client_id, citation_evidence_id)
    references knowledge_evidence(client_id, id)
);
create index knowledge_field_provenance_object_idx
  on knowledge_field_provenance(client_id, knowledge_object_id);
