-- Additive protocol v1. Never stores URLs, credentials, provider IDs or arbitrary settings JSON.
do $$ begin
  if pg_catalog.to_regclass('public.nuvix_sync_records') is null then
    raise exception 'Apply the existing cloud_records migration before advanced_cloud_sync';
  end if;
end $$;

create table public.nuvix_advanced_records (
  id bigint generated always as identity unique not null,
  owner_id uuid not null references auth.users(id) on delete cascade,
  schema_version integer not null default 1 check (schema_version = 1),
  kind text not null,
  record_key text not null,
  updated_at_ms bigint not null check (updated_at_ms > 0),
  device_id uuid not null,
  deleted boolean not null default false,
  text_value text,
  bool_value boolean,
  int_value integer,
  entries text[] not null default '{}',
  primary key (owner_id, kind, record_key),
  check (char_length(record_key) between 1 and 64),
  check (text_value is null or (char_length(text_value) <= 120 and text_value collate "C" !~ '[[:cntrl:]]')),
  check (int_value is null or int_value between 0 and 1000000),
  check (cardinality(entries) <= 2000)
);
create index nuvix_advanced_records_owner_cursor_idx on public.nuvix_advanced_records (owner_id, id);
alter table public.nuvix_advanced_records enable row level security;
alter table public.nuvix_advanced_records force row level security;
revoke all on public.nuvix_advanced_records from public, anon, authenticated;
-- Deliberately no DELETE: removing a group or favourite is an explicit versioned record.
grant select, insert on public.nuvix_advanced_records to authenticated;
grant update (schema_version, updated_at_ms, device_id, deleted, text_value, bool_value, int_value, entries)
  on public.nuvix_advanced_records to authenticated;
grant usage, select on sequence public.nuvix_advanced_records_id_seq to authenticated;
create policy nuvix_advanced_select on public.nuvix_advanced_records for select to authenticated
  using ((select auth.uid()) = owner_id);
create policy nuvix_advanced_insert on public.nuvix_advanced_records for insert to authenticated
  with check ((select auth.uid()) = owner_id);
create policy nuvix_advanced_update on public.nuvix_advanced_records for update to authenticated
  using ((select auth.uid()) = owner_id) with check ((select auth.uid()) = owner_id);

-- Shared with the JVM protocol: compare UTF-8 bytes (C collation), integer padded to seven digits.
-- Each kind uses just one field, and array entries cannot contain commas.
create function public.nuvix_advanced_payload_key(record public.nuvix_advanced_records)
returns text language sql immutable security invoker set search_path = '' as $$
  select case
    when record.text_value is not null then record.text_value
    when record.bool_value is not null then case when record.bool_value then '1' else '0' end
    when record.int_value is not null then pg_catalog.lpad(record.int_value::text, 7, '0')
    else pg_catalog.array_to_string(record.entries, ',') end
$$;
revoke all on function public.nuvix_advanced_payload_key(public.nuvix_advanced_records) from public, anon;
grant execute on function public.nuvix_advanced_payload_key(public.nuvix_advanced_records) to authenticated;

-- Every direct REST INSERT/UPDATE is validated and receives the same monotonic conflict rule as RPC.
-- An UPDATE holds the row lock, so concurrent RPC and direct writes cannot bypass stale-write checks.
create function public.nuvix_guard_advanced_record()
returns trigger language plpgsql security invoker set search_path = '' as $$
declare
  expected text;
begin
  if auth.uid() is null or new.owner_id <> auth.uid() then
    raise exception 'Authentication and matching owner required' using errcode = '42501';
  end if;
  if tg_op = 'UPDATE' and (new.id, new.owner_id, new.kind, new.record_key)
       is distinct from (old.id, old.owner_id, old.kind, old.record_key) then
    raise exception 'Record identity is immutable' using errcode = '42501';
  end if;
  if new.schema_version is null or new.kind is null or new.record_key is null or
     new.updated_at_ms is null or new.device_id is null or new.deleted is null or new.entries is null then
    raise exception 'Missing required advanced field' using errcode = '22023';
  end if;
  if new.schema_version <> 1 or new.updated_at_ms <= 0 or
     new.updated_at_ms > (extract(epoch from clock_timestamp()) * 1000)::bigint + 300000 then
    raise exception 'Unsupported schema or record clock' using errcode = '22023';
  end if;
  -- Validate bounds before conflict comparison too: malformed stale writes must fail clearly.
  if char_length(new.record_key) not between 1 and 64 or
     (new.text_value is not null and (char_length(new.text_value) > 120 or new.text_value collate "C" ~ '[[:cntrl:]]')) or
     (new.int_value is not null and new.int_value not between 0 and 1000000) or
     cardinality(new.entries) > 2000 then
    raise exception 'Advanced record bounds exceeded' using errcode = '22023';
  end if;
  if new.kind in ('group_name','group_rank','group_members') then
    if new.record_key !~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$' then
      raise exception 'Expected a canonical group UUID' using errcode = '22023';
    end if;
  elsif new.kind in ('channel_hidden','channel_name','channel_order',
                    'channel_favourite_state','movie_favourite_state','series_favourite_state') then
    if new.record_key !~ '^[a-f0-9]{64}$' then
      raise exception 'Expected a content fingerprint' using errcode = '22023';
    end if;
  elsif new.kind = 'app_order' then
    if new.record_key <> 'apps' then raise exception 'Unsupported app key' using errcode = '22023'; end if;
  elsif new.kind = 'home_shelf' then
    if new.record_key not in ('continue','recent','favourites','on_now','movies','series','apps') then
      raise exception 'Unsupported home shelf' using errcode = '22023';
    end if;
  elsif new.kind = 'setting' then
    if new.record_key not in ('theme_mode','subtitles_enabled','channel_layout','startup_destination') then
      raise exception 'Unsupported setting' using errcode = '22023';
    end if;
  else
    raise exception 'Unsupported advanced kind' using errcode = '22023';
  end if;
  if new.deleted and new.kind <> 'group_name' then
    raise exception 'Only group names support deletion tombstones' using errcode = '22023';
  end if;
  expected := case
    when new.kind = 'group_name' and new.deleted then 'empty'
    when new.kind in ('group_name','channel_name') then 'text'
    when new.kind in ('group_rank','channel_order') then 'int'
    when new.kind in ('group_members','app_order') then 'entries'
    when new.kind = 'setting' and new.record_key <> 'subtitles_enabled' then 'text'
    else 'bool' end;
  if (new.text_value is not null) <> (expected = 'text') or
     (new.bool_value is not null) <> (expected = 'bool') or
     (new.int_value is not null) <> (expected = 'int') or
     (expected <> 'entries' and cardinality(new.entries) <> 0) then
    raise exception 'Unexpected or missing typed field' using errcode = '22023';
  end if;
  if new.kind = 'group_name' and not new.deleted and char_length(new.text_value) = 0 then
    raise exception 'Group name cannot be empty' using errcode = '22023';
  end if;
  if new.kind = 'setting' and (
    (new.record_key = 'theme_mode' and new.text_value not in ('SYSTEM','DARK','LIGHT')) or
    (new.record_key = 'channel_layout' and new.text_value not in ('GRID','LIST')) or
    (new.record_key = 'startup_destination' and new.text_value not in ('HOME','LIVE_TV','GUIDE','LAST_WATCHED','APPS'))
  ) then raise exception 'Unsupported setting value' using errcode = '22023'; end if;
  if array_ndims(new.entries) > 1 or array_lower(new.entries, 1) <> 1 or
     exists (select 1 from unnest(new.entries) as e where e is null) or
     cardinality(new.entries) <> (select count(distinct e collate "C") from unnest(new.entries) as e) then
    raise exception 'Entries must be a unique one-dimensional ordered list' using errcode = '22023';
  end if;
  if new.kind = 'group_members' and exists (select 1 from unnest(new.entries) as e where e !~ '^[a-f0-9]{64}$') then
    raise exception 'Invalid group member fingerprint' using errcode = '22023';
  end if;
  if new.kind = 'app_order' and (cardinality(new.entries) > 200 or exists (
    select 1 from unnest(new.entries) as e where char_length(e) > 255 or
      e collate "C" !~ '^[A-Za-z_][A-Za-z0-9_]*(\.[A-Za-z_][A-Za-z0-9_]*)+$'
  )) then raise exception 'Invalid application order' using errcode = '22023'; end if;
  if tg_op = 'UPDATE' then
    -- A deleted stable group identity can never become live again. Recreate with a new UUID.
    if old.deleted and not new.deleted then return old; end if;
    if not (new.deleted and not old.deleted) and
       (new.updated_at_ms, new.device_id, public.nuvix_advanced_payload_key(new) collate "C") <=
       (old.updated_at_ms, old.device_id, public.nuvix_advanced_payload_key(old) collate "C") then
      return old;
    end if;
  end if;
  return new;
end;
$$;
revoke all on function public.nuvix_guard_advanced_record() from public, anon;
grant execute on function public.nuvix_guard_advanced_record() to authenticated;
create trigger nuvix_advanced_guard before insert or update on public.nuvix_advanced_records
  for each row execute function public.nuvix_guard_advanced_record();

create function public.nuvix_merge_advanced_records(records jsonb)
returns void language plpgsql security invoker set search_path = '' as $$
declare
  item jsonb;
  field text;
  allowed text[] := array['schema_version','kind','record_key','updated_at_ms','device_id',
                          'deleted','text_value','bool_value','int_value','entries'];
begin
  if auth.uid() is null then raise exception 'Authentication required' using errcode = '42501'; end if;
  if records is null or jsonb_typeof(records) <> 'array' then
    raise exception 'Expected an array' using errcode = '22023';
  end if;
  if jsonb_array_length(records) > 200 or pg_column_size(records) > 33554432 then
    raise exception 'Advanced batch is too large' using errcode = '22023';
  end if;
  for item in select value from jsonb_array_elements(records) loop
    if jsonb_typeof(item) <> 'object' then raise exception 'Expected an object' using errcode = '22023'; end if;
    if exists (select 1 from jsonb_object_keys(item) as k where not (k = any(allowed))) then
      raise exception 'Unknown advanced field' using errcode = '22023';
    end if;
    foreach field in array array['schema_version','updated_at_ms'] loop
      if jsonb_typeof(item->field) is distinct from 'number' or (item->>field) !~ '^[0-9]+$' then
        raise exception 'Expected a required integer' using errcode = '22023';
      end if;
    end loop;
    foreach field in array array['kind','record_key','device_id'] loop
      if jsonb_typeof(item->field) is distinct from 'string' then
        raise exception 'Expected a required string' using errcode = '22023';
      end if;
    end loop;
    if item->>'device_id' !~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$' then
      raise exception 'Expected a canonical device UUID' using errcode = '22023';
    end if;
    if item ? 'deleted' and jsonb_typeof(item->'deleted') <> 'boolean' then
      raise exception 'Expected deleted boolean' using errcode = '22023';
    end if;
    if item ? 'text_value' and jsonb_typeof(item->'text_value') not in ('string','null') then
      raise exception 'Expected text value' using errcode = '22023';
    end if;
    if item ? 'bool_value' and jsonb_typeof(item->'bool_value') not in ('boolean','null') then
      raise exception 'Expected bool value' using errcode = '22023';
    end if;
    if item ? 'int_value' and jsonb_typeof(item->'int_value') <> 'null' and
       (jsonb_typeof(item->'int_value') <> 'number' or (item->>'int_value') !~ '^[0-9]+$') then
      raise exception 'Expected integer value' using errcode = '22023';
    end if;
    if item ? 'entries' then
      if jsonb_typeof(item->'entries') <> 'array' then
        raise exception 'Expected ordered entries array' using errcode = '22023';
      end if;
      if exists (select 1 from jsonb_array_elements(item->'entries') as e where jsonb_typeof(e) <> 'string') then
        raise exception 'Expected string entries' using errcode = '22023';
      end if;
    end if;
    insert into public.nuvix_advanced_records
      (owner_id,schema_version,kind,record_key,updated_at_ms,device_id,deleted,text_value,bool_value,int_value,entries)
    values (auth.uid(),(item->>'schema_version')::integer,item->>'kind',item->>'record_key',
      (item->>'updated_at_ms')::bigint,(item->>'device_id')::uuid,coalesce((item->>'deleted')::boolean,false),
      item->>'text_value',(item->>'bool_value')::boolean,(item->>'int_value')::integer,
      array(select jsonb_array_elements_text(coalesce(item->'entries','[]'::jsonb))))
    on conflict (owner_id,kind,record_key) do update set
      schema_version=excluded.schema_version,updated_at_ms=excluded.updated_at_ms,device_id=excluded.device_id,
      deleted=excluded.deleted,text_value=excluded.text_value,bool_value=excluded.bool_value,
      int_value=excluded.int_value,entries=excluded.entries;
  end loop;
end;
$$;
revoke all on function public.nuvix_merge_advanced_records(jsonb) from public, anon;
grant execute on function public.nuvix_merge_advanced_records(jsonb) to authenticated;
