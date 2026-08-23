update initiative_stage_attempts
set status = 'OUT_OF_SCOPE'
where stage = 'CANDIDATE_BUILD'
  and status = 'NOT_IMPLEMENTED';
