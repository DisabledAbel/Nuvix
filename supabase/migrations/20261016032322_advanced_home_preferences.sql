-- Forward-only extension: new kinds are invisible to older clients' explicit kind queries.
-- Existing v1 visibility, RPC ownership, RLS, grants and conflict rules remain intact.
-- Only identifiers and presentation enums are portable; no artwork or provider URLs.
create or replace function public.nuvix_guard_advanced_record()
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
  elsif new.kind = 'home_order' then
    if new.record_key <> 'shelves_v1' then raise exception 'Unsupported home order key' using errcode = '22023'; end if;
  elsif new.kind = 'home_layout' then
    if new.record_key <> 'presentation_v1' then raise exception 'Unsupported home layout key' using errcode = '22023'; end if;
  elsif new.kind = 'home_group' then
    if new.record_key !~ '^group:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$' then
      raise exception 'Invalid home group UUID' using errcode = '22023';
    end if;
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
    when new.kind in ('group_members','app_order','home_order','home_layout') then 'entries'
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
  if new.kind = 'home_order' and (cardinality(new.entries) > 160 or exists (
    select 1 from unnest(new.entries) as e where e not in ('continue','recent','favourites','on_now','movies','series','apps')
      and e !~ '^group:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
  )) then raise exception 'Invalid home shelf order' using errcode = '22023'; end if;
  if new.kind = 'home_layout' and (
    cardinality(new.entries) <> 9 or new.entries[1] <> 'v1' or
    new.entries[2] not in ('layout=CLASSIC','layout=COMPACT','layout=CINEMA') or
    new.entries[3] not in ('movie=SMALL','movie=MEDIUM','movie=LARGE') or
    new.entries[4] not in ('series=SMALL','series=MEDIUM','series=LARGE') or
    new.entries[5] not in ('channel=SMALL','channel=MEDIUM','channel=LARGE') or
    new.entries[6] not in ('corners=SQUARE','corners=SOFT','corners=ROUND') or
    new.entries[7] not in ('metadata=true','metadata=false') or
    new.entries[8] not in ('background=DEFAULT','background=MIDNIGHT','background=CHARCOAL','background=FOREST','background=OCEAN','background=DUSK','background=DYNAMIC_ARTWORK') or
    new.entries[9] not in ('featured=AUTOMATIC','featured=CONTINUE','featured=RECENT','featured=FAVOURITE','featured=DISABLED')
  ) then raise exception 'Invalid home presentation v1' using errcode = '22023'; end if;
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