update knowledge_conflicts
set status = 'RESOLVED',
    resolved_at = coalesce(resolved_at, now()),
    resolved_by = coalesce(resolved_by, 'extraction-rederivation'),
    values = values || '{"resolutionReason":"Superseded by extraction re-derivation; structural placeholder removed"}'::jsonb
where status = 'OPEN'
  and (
    values #>> '{current,value}' = 'source-defined'
    or values #>> '{other,value}' = 'source-defined'
    or values #>> '{current,value}' like 'Structurally parsed signal %'
    or values #>> '{other,value}' like 'Structurally parsed signal %'
  );
