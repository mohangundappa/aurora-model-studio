create or replace function migrate_implementation_attributes(existing_attributes jsonb)
returns jsonb
language sql
immutable
as $$
  select
    existing_attributes - 'languageOrKind'
    || case
      when position(' ' in coalesce(existing_attributes->>'languageOrKind', '')) > 0
        then jsonb_build_object(
          'language', split_part(existing_attributes->>'languageOrKind', ' ', 1),
          'implementationKind',
          substring(
            existing_attributes->>'languageOrKind'
            from position(' ' in existing_attributes->>'languageOrKind') + 1
          )
        )
      when existing_attributes ? 'languageOrKind'
        and existing_attributes->>'languageOrKind' <> 'source'
        then jsonb_build_object('language', existing_attributes->>'languageOrKind')
      when lower(coalesce(existing_attributes->>'sourceTraceability', '')) like '%.java'
        then jsonb_build_object('language', 'Java')
      when lower(coalesce(existing_attributes->>'sourceTraceability', '')) like '%.yaml'
        or lower(coalesce(existing_attributes->>'sourceTraceability', '')) like '%.yml'
        then jsonb_build_object('language', 'YAML')
      else '{}'::jsonb
    end
$$;

create temporary table implementation_migration_map (
  old_id uuid primary key,
  new_id uuid not null
) on commit drop;

do $$
declare
  old_object record;
  new_id uuid;
  new_version integer;
begin
  for old_object in
    select *
    from knowledge_objects
    where knowledge_type = 'IMPLEMENTATION'
      and lifecycle_status = 'APPROVED'
      and attributes ? 'languageOrKind'
  loop
    new_id := gen_random_uuid();
    select coalesce(max(version), 0) + 1
      into new_version
      from knowledge_objects
      where client_id = old_object.client_id
        and knowledge_key = old_object.knowledge_key;

    update knowledge_objects
    set lifecycle_status = 'SUPERSEDED',
        effective_to = now()
    where client_id = old_object.client_id
      and id = old_object.id;

    insert into knowledge_objects (
      id,
      client_id,
      knowledge_key,
      version,
      knowledge_type,
      name,
      business_domain,
      business_use_case,
      business_description,
      canonical_taxonomy,
      client_taxonomy,
      tags,
      lifecycle_status,
      effective_from,
      effective_to,
      confidence,
      confidence_breakdown,
      quality_assessment,
      extracted_at,
      extracted_by,
      reviewed_at,
      reviewed_by,
      approved_at,
      approved_by,
      approval_comments,
      attributes,
      synthetic,
      created_at,
      llm_invocation_id
    )
    values (
      new_id,
      old_object.client_id,
      old_object.knowledge_key,
      new_version,
      old_object.knowledge_type,
      old_object.name,
      old_object.business_domain,
      old_object.business_use_case,
      old_object.business_description,
      old_object.canonical_taxonomy,
      old_object.client_taxonomy,
      old_object.tags,
      'APPROVED',
      coalesce(old_object.effective_from, now()),
      null,
      old_object.confidence,
      old_object.confidence_breakdown,
      old_object.quality_assessment,
      old_object.extracted_at,
      old_object.extracted_by,
      old_object.reviewed_at,
      old_object.reviewed_by,
      old_object.approved_at,
      old_object.approved_by,
      old_object.approval_comments,
      migrate_implementation_attributes(old_object.attributes),
      old_object.synthetic,
      old_object.created_at,
      old_object.llm_invocation_id
    );

    insert into implementation_migration_map(old_id, new_id)
    values (old_object.id, new_id);

    insert into knowledge_audit (
      client_id,
      knowledge_object_id,
      from_status,
      to_status,
      actor,
      comment
    )
    values (
      old_object.client_id,
      old_object.id,
      'APPROVED',
      'SUPERSEDED',
      'knowledge-schema-migration',
      'Separated implementation language and kind fields'
    );

    insert into knowledge_audit (
      client_id,
      knowledge_object_id,
      from_status,
      to_status,
      actor,
      comment
    )
    values (
      old_object.client_id,
      new_id,
      null,
      'APPROVED',
      'knowledge-schema-migration',
      'Migrated approved implementation with separate language and kind fields'
    );
  end loop;
end
$$;

update knowledge_objects
set attributes = migrate_implementation_attributes(attributes)
where knowledge_type = 'IMPLEMENTATION'
  and attributes ? 'languageOrKind';

create temporary table implementation_evidence_migration_map (
  old_id uuid primary key,
  new_id uuid not null
) on commit drop;

insert into implementation_evidence_migration_map(old_id, new_id)
select evidence.id, gen_random_uuid()
from knowledge_evidence evidence
join implementation_migration_map objects
  on objects.old_id = evidence.knowledge_object_id;

insert into knowledge_evidence (
  id,
  client_id,
  knowledge_object_id,
  source_system,
  source_type,
  source_uri,
  source_version,
  excerpt,
  extraction_certainty,
  recorded_at
)
select evidence_map.new_id,
       evidence.client_id,
       objects.new_id,
       evidence.source_system,
       evidence.source_type,
       evidence.source_uri,
       evidence.source_version,
       evidence.excerpt,
       evidence.extraction_certainty,
       evidence.recorded_at
from knowledge_evidence evidence
join implementation_evidence_migration_map evidence_map
  on evidence_map.old_id = evidence.id
join implementation_migration_map objects
  on objects.old_id = evidence.knowledge_object_id;

insert into knowledge_field_provenance (
  id,
  client_id,
  knowledge_object_id,
  field_name,
  field_value,
  provenance,
  citation_evidence_id,
  citation_excerpt,
  extraction_certainty,
  created_at
)
select gen_random_uuid(),
       provenance.client_id,
       objects.new_id,
       provenance.field_name,
       provenance.field_value,
       provenance.provenance,
       coalesce(evidence_map.new_id, provenance.citation_evidence_id),
       provenance.citation_excerpt,
       provenance.extraction_certainty,
       provenance.created_at
from knowledge_field_provenance provenance
join implementation_migration_map objects
  on objects.old_id = provenance.knowledge_object_id
left join implementation_evidence_migration_map evidence_map
  on evidence_map.old_id = provenance.citation_evidence_id;

insert into knowledge_relationships (
  client_id,
  from_object_id,
  relationship_type,
  to_object_id,
  evidence_id
)
select relationships.client_id,
       coalesce(from_map.new_id, relationships.from_object_id),
       relationships.relationship_type,
       coalesce(to_map.new_id, relationships.to_object_id),
       coalesce(evidence_map.new_id, relationships.evidence_id)
from knowledge_relationships relationships
left join implementation_migration_map from_map
  on from_map.old_id = relationships.from_object_id
left join implementation_migration_map to_map
  on to_map.old_id = relationships.to_object_id
left join implementation_evidence_migration_map evidence_map
  on evidence_map.old_id = relationships.evidence_id
where from_map.old_id is not null
   or to_map.old_id is not null
on conflict do nothing;

update knowledge_conflicts
set status = 'RESOLVED',
    resolved_at = coalesce(resolved_at, now()),
    resolved_by = coalesce(resolved_by, 'knowledge-schema-migration'),
    values = values || jsonb_build_object(
      'resolutionReason',
      'Legacy languageOrKind field mixed language and implementation kind; conflict re-derived'
    )
where field = 'languageOrKind'
  and status = 'OPEN';

drop function migrate_implementation_attributes(jsonb);
