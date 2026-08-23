alter table knowledge_conflicts
  add column if not exists conflict_class varchar(40) not null default 'BLOCKING';

alter table knowledge_conflicts
  add constraint knowledge_conflict_class_check
  check (conflict_class in ('BLOCKING', 'DIVERGENT_DESCRIPTION'));

update knowledge_conflicts
set conflict_class = 'DIVERGENT_DESCRIPTION'
where field = 'businessDefinition';

update knowledge_conflicts
set status = 'RESOLVED',
    resolved_at = coalesce(resolved_at, now()),
    resolved_by = coalesce(resolved_by, 'extraction-rederivation'),
    values = values || '{"resolutionReason":"Superseded by extraction re-derivation; structural placeholder removed"}'::jsonb
where status = 'OPEN'
  and values::text like '%source-defined%';

update knowledge_conflicts
set status = 'RESOLVED',
    resolved_at = coalesce(resolved_at, now()),
    resolved_by = coalesce(resolved_by, 'extraction-rederivation'),
    values = values || '{"resolutionReason":"Superseded by extraction re-derivation; structural placeholder removed"}'::jsonb
where status = 'OPEN'
  and values::text like '%guest%';
