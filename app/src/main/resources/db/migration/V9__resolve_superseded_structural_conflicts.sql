update knowledge_conflicts
set status = 'RESOLVED',
    resolved_at = coalesce(resolved_at, now()),
    resolved_by = coalesce(resolved_by, 'extraction-rederivation'),
    values = values || '{"resolutionReason":"Superseded by extraction re-derivation; structural placeholder removed"}'::jsonb
where status = 'OPEN'
  and (
    values::text like '%source-defined%'
    or values::text like '%Structurally parsed%'
    or values::text like '%guest%'
  );
