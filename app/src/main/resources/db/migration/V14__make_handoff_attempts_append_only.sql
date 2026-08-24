create or replace function prevent_initiative_handoff_attempt_mutation()
returns trigger
language plpgsql
as $$
begin
  raise exception 'initiative handoff attempts are append-only';
end;
$$;

create trigger initiative_handoff_attempts_append_only
before update or delete on initiative_handoff_attempts
for each row execute function prevent_initiative_handoff_attempt_mutation();
