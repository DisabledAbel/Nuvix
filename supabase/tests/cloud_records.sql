-- Run against an isolated local Supabase database after applying the migration.
-- Assertions raise on failure; fixture changes are rolled back.
begin;
insert into auth.users (id, email) values
  ('10000000-0000-0000-0000-000000000001', 'nuvix-test-a@example.invalid'),
  ('10000000-0000-0000-0000-000000000002', 'nuvix-test-b@example.invalid');

set local role authenticated;
select set_config('request.jwt.claims', '{"sub":"10000000-0000-0000-0000-000000000001","role":"authenticated"}', true);
select public.nuvix_merge_records(jsonb_build_array(jsonb_build_object(
  'record_key', repeat('a',64), 'kind','movie_progress', 'profile_key',repeat('b',64),
  'position_ms',100, 'duration_ms',1000, 'updated_at_ms',2000,
  'device_id','00000000-0000-0000-0000-000000000001')));
-- A stale high playback position must not replace a newer intentional rewind.
select public.nuvix_merge_records(jsonb_build_array(jsonb_build_object(
  'record_key', repeat('a',64), 'kind','movie_progress', 'profile_key',repeat('b',64),
  'position_ms',900, 'duration_ms',1000, 'updated_at_ms',1000,
  'device_id','00000000-0000-0000-0000-000000000002')));
do $$ begin
  if (select position_ms from public.nuvix_sync_records) <> 100 then raise exception 'Stale replay changed newer progress'; end if;
  begin
    update public.nuvix_sync_records set owner_id = '10000000-0000-0000-0000-000000000002';
    raise exception 'Owner reassignment was permitted';
  exception when insufficient_privilege then null; end;
  begin
    perform public.nuvix_merge_records(jsonb_build_array(jsonb_build_object('provider_url','https://secret.invalid')));
    raise exception 'Unexpected field was accepted';
  exception when invalid_parameter_value then null; end;
end $$;

-- The device UUID provides one deterministic winner when timestamps tie.
select public.nuvix_merge_records(jsonb_build_array(jsonb_build_object(
  'record_key', repeat('a',64), 'kind','movie_progress', 'profile_key',repeat('b',64),
  'position_ms',200, 'duration_ms',1000, 'updated_at_ms',2000,
  'device_id','00000000-0000-0000-0000-000000000002')));
select public.nuvix_merge_records(jsonb_build_array(jsonb_build_object(
  'record_key', repeat('a',64), 'kind','movie_progress', 'profile_key',repeat('b',64),
  'position_ms',300, 'duration_ms',1000, 'updated_at_ms',2000,
  'device_id','00000000-0000-0000-0000-000000000001')));
do $$ begin
  if (select position_ms from public.nuvix_sync_records) <> 200 then raise exception 'UUID conflict order is unstable'; end if;
  begin
    perform public.nuvix_merge_records(jsonb_build_array(jsonb_build_object(
      'record_key', repeat('a',64), 'kind','movie_progress', 'profile_key',repeat('b',64),
      'position_ms',-1, 'duration_ms',1000, 'updated_at_ms',3000,
      'device_id','00000000-0000-0000-0000-000000000001')));
    raise exception 'Negative progress was accepted';
  exception when check_violation then null; end;
end $$;

select set_config('request.jwt.claims', '{"sub":"10000000-0000-0000-0000-000000000002","role":"authenticated"}', true);
do $$ begin
  if (select count(*) from public.nuvix_sync_records) <> 0 then raise exception 'Cross-account read leaked'; end if;
  if (select count(*) from (select 1 from public.nuvix_sync_records where owner_id = '10000000-0000-0000-0000-000000000001') rows) <> 0 then
    raise exception 'Explicit owner filter bypassed RLS';
  end if;
  begin
    insert into public.nuvix_sync_records(owner_id,record_key,kind,profile_key,position_ms,duration_ms,updated_at_ms,device_id)
    values ('10000000-0000-0000-0000-000000000001',repeat('c',64),'movie_favourite',repeat('0',64),0,0,1000,'00000000-0000-0000-0000-000000000002');
    raise exception 'Cross-account insert was permitted';
  exception when insufficient_privilege then null; end;
end $$;
select public.nuvix_merge_records(jsonb_build_array(jsonb_build_object(
  'record_key', repeat('a',64), 'kind','movie_progress', 'profile_key',repeat('b',64),
  'position_ms',50, 'duration_ms',1000, 'updated_at_ms',3000,
  'device_id','00000000-0000-0000-0000-000000000002')));
do $$ begin
  if (select count(*) from public.nuvix_sync_records) <> 1 then raise exception 'Own account record missing'; end if;
end $$;

set local role anon;
do $$ begin
  begin
    perform * from public.nuvix_sync_records;
    raise exception 'Anon table access was permitted';
  exception when insufficient_privilege then null; end;
  begin
    perform public.nuvix_merge_records('[]'::jsonb);
    raise exception 'Anon RPC access was permitted';
  exception when insufficient_privilege then null; end;
end $$;
rollback;

-- Additive protocol/RLS/concurrency coverage lives in advanced_cloud_records.sql and
-- scripts/test-supabase.sh; this original suite continues exercising unchanged legacy behavior.
