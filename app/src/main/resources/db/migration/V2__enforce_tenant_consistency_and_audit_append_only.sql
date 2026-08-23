alter table knowledge_objects
  add constraint knowledge_objects_client_id_id_unique unique (client_id, id);

alter table knowledge_evidence
  drop constraint knowledge_evidence_knowledge_object_id_fkey,
  add constraint knowledge_evidence_object_fk
    foreign key (client_id, knowledge_object_id)
    references knowledge_objects(client_id, id),
  add constraint knowledge_evidence_client_id_id_unique unique (client_id, id);

alter table knowledge_relationships
  drop constraint knowledge_relationships_from_object_id_fkey,
  drop constraint knowledge_relationships_to_object_id_fkey,
  drop constraint knowledge_relationships_evidence_id_fkey,
  add constraint knowledge_relationships_from_fk
    foreign key (client_id, from_object_id)
    references knowledge_objects(client_id, id),
  add constraint knowledge_relationships_to_fk
    foreign key (client_id, to_object_id)
    references knowledge_objects(client_id, id),
  add constraint knowledge_relationships_evidence_fk
    foreign key (client_id, evidence_id)
    references knowledge_evidence(client_id, id);

alter table knowledge_conflicts
  drop constraint knowledge_conflicts_knowledge_object_id_fkey,
  add constraint knowledge_conflicts_object_fk
    foreign key (client_id, knowledge_object_id)
    references knowledge_objects(client_id, id);

alter table knowledge_audit
  drop constraint knowledge_audit_knowledge_object_id_fkey,
  add constraint knowledge_audit_object_fk
    foreign key (client_id, knowledge_object_id)
    references knowledge_objects(client_id, id);

create or replace function reject_audit_mutation() returns trigger language plpgsql as $$
begin
  raise exception 'knowledge audit is append-only';
end;
$$;

create trigger knowledge_audit_append_only
  before update or delete on knowledge_audit
  for each row execute function reject_audit_mutation();
