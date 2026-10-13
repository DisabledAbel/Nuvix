-- Optional Nuvix sync. This schema has no provider, URL, arbitrary settings, or credential column.
create table public.nuvix_sync_records (
  id bigint generated always as identity unique not null,
  owner_id uuid not null references auth.users(id) on delete cascade,
  record_key text not null check (record_key ~ '^[a-f0-9]{64}$'),
  kind text not null check (kind in ('channel_favourite', 'movie_favourite', 'series_favourite', 'movie_progress', 'episode_progress')),
  profile_key text not null default repeat('0', 64) check (profile_key ~ '^[a-f0-9]{64}$'),
  position_ms bigint not null default 0 check (position_ms between 0 and 604800000),
  duration_ms bigint not null default 0 check (duration_ms between 0 and 604800000),
  updated_at_ms bigint not null check (updated_at_ms > 0),
  device_id uuid not null,
  primary key (owner_id, kind, profile_key, record_key),
  check (duration_ms = 0 or position_ms <= duration_ms),
  check (
    (kind like '%_favourite' and profile_key = repeat('0', 64) and position_ms = 0 and duration_ms = 0)
    or (kind like '%_progress' and profile_key <> repeat('0', 64))
  )
);

-- Owner prefix supports the explicit owner filter + pagination used by the client.
create index nuvix_sync_records_owner_cursor_idx on public.nuvix_sync_records (owner_id, id);
alter table public.nuvix_sync_records enable row level security;
alter table public.nuvix_sync_records force row level security;
revoke all on public.nuvix_sync_records from anon, public;
grant select, insert, update, delete on public.nuvix_sync_records to authenticated;
grant usage, select on sequence public.nuvix_sync_records_id_seq to authenticated;

create policy nuvix_records_select on public.nuvix_sync_records for select to authenticated
  using ((select auth.uid()) = owner_id);
create policy nuvix_records_insert on public.nuvix_sync_records for insert to authenticated
  with check ((select auth.uid()) = owner_id);
create policy nuvix_records_update on public.nuvix_sync_records for update to authenticated
  using ((select auth.uid()) = owner_id) with check ((select auth.uid()) = owner_id);
create policy nuvix_records_delete on public.nuvix_sync_records for delete to authenticated
  using ((select auth.uid()) = owner_id);

-- SECURITY INVOKER preserves RLS. Ownership comes from Auth, never from a supplied JSON field.
-- Comparison happens inside ON CONFLICT to remain correct under concurrent device writes.
create function public.nuvix_merge_records(records jsonb)
returns void language plpgsql security invoker set search_path = '' as $$
declare
  item jsonb;
  allowed text[] := array['record_key','kind','profile_key','position_ms','duration_ms','updated_at_ms','device_id'];
begin
  if auth.uid() is null then raise exception 'Authentication required' using errcode = '42501'; end if;
  if jsonb_typeof(records) <> 'array' or jsonb_array_length(records) > 200 then
    raise exception 'Expected at most 200 records' using errcode = '22023';
  end if;
  for item in select value from jsonb_array_elements(records) loop
    if jsonb_typeof(item) <> 'object' then raise exception 'Invalid record' using errcode = '22023'; end if;
    if exists (select 1 from jsonb_object_keys(item) as k where not (k = any(allowed))) then
      raise exception 'Unknown cloud field' using errcode = '22023';
    end if;
    if (item->>'updated_at_ms')::bigint > (extract(epoch from clock_timestamp()) * 1000)::bigint + 300000 then
      raise exception 'Record clock is ahead' using errcode = '22023';
    end if;
    insert into public.nuvix_sync_records (owner_id, record_key, kind, profile_key, position_ms, duration_ms, updated_at_ms, device_id)
    values (auth.uid(), item->>'record_key', item->>'kind', item->>'profile_key',
      (item->>'position_ms')::bigint, (item->>'duration_ms')::bigint,
      (item->>'updated_at_ms')::bigint, (item->>'device_id')::uuid)
    on conflict (owner_id, kind, profile_key, record_key) do update
      set position_ms = excluded.position_ms, duration_ms = excluded.duration_ms,
          updated_at_ms = excluded.updated_at_ms, device_id = excluded.device_id
      where (excluded.updated_at_ms, excluded.device_id) >
        (nuvix_sync_records.updated_at_ms, nuvix_sync_records.device_id);
  end loop;
end;
$$;
revoke execute on function public.nuvix_merge_records(jsonb) from public, anon;
grant execute on function public.nuvix_merge_records(jsonb) to authenticated;
