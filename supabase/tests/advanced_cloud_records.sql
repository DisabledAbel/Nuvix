-- Isolated SQL regression suite. Every fixture is rolled back; no hosted project is required.
begin;
do $$ begin
  if not (select relrowsecurity and relforcerowsecurity from pg_class where oid='public.nuvix_advanced_records'::regclass) then
    raise exception 'Advanced RLS must be enabled and forced';
  end if;
  if exists (select 1 from pg_proc where oid in (
    'public.nuvix_merge_advanced_records(jsonb)'::regprocedure,
    'public.nuvix_guard_advanced_record()'::regprocedure,
    'public.nuvix_advanced_payload_key(public.nuvix_advanced_records)'::regprocedure
  ) and (prosecdef or not ('search_path=""' = any(proconfig)))) then
    raise exception 'Advanced functions must use invoker security and an empty search_path';
  end if;
  if has_table_privilege('authenticated','public.nuvix_advanced_records','DELETE') then
    raise exception 'Physical advanced deletion must not be granted';
  end if;
end $$;
insert into auth.users(id,email) values
 ('20000000-0000-0000-0000-000000000001','advanced-a@example.invalid'),
 ('20000000-0000-0000-0000-000000000002','advanced-b@example.invalid');

create function pg_temp.record(kind text, key text, payload jsonb default '{}'::jsonb)
returns jsonb language sql as $$
  select jsonb_build_object('schema_version',1,'kind',kind,'record_key',key,'updated_at_ms',2000,
    'device_id','00000000-0000-0000-0000-000000000001','deleted',false,
    'text_value',null,'bool_value',null,'int_value',null,'entries','[]'::jsonb) || payload
$$;
create function pg_temp.reject_record(payload jsonb)
returns void language plpgsql as $$
begin
  begin
    perform public.nuvix_merge_advanced_records(jsonb_build_array(payload));
  exception when invalid_parameter_value or check_violation or numeric_value_out_of_range or
                 invalid_text_representation or not_null_violation or insufficient_privilege then return;
  end;
  raise exception 'Invalid advanced payload accepted: %', payload;
end $$;
set local role authenticated;
select set_config('request.jwt.claims','{"sub":"20000000-0000-0000-0000-000000000001"}',true);
-- All supported kinds and setting/shelf allowlists have real typed storage.
select public.nuvix_merge_advanced_records(jsonb_build_array(
 pg_temp.record('group_name','00000000-0000-0000-0000-000000000010','{"text_value":"News"}'),
 pg_temp.record('group_rank','00000000-0000-0000-0000-000000000010','{"int_value":2}'),
 pg_temp.record('group_members','00000000-0000-0000-0000-000000000010',jsonb_build_object('entries',jsonb_build_array(repeat('b',64),repeat('a',64)))),
 pg_temp.record('channel_hidden',repeat('a',64),'{"bool_value":true}'),
 pg_temp.record('channel_name',repeat('a',64),'{"text_value":""}'),
 pg_temp.record('channel_order',repeat('a',64),'{"int_value":12}'),
 pg_temp.record('channel_favourite_state',repeat('a',64),'{"bool_value":false}'),
 pg_temp.record('movie_favourite_state',repeat('a',64),'{"bool_value":false}'),
 pg_temp.record('series_favourite_state',repeat('a',64),'{"bool_value":true}'),
 pg_temp.record('app_order','apps','{"entries":["com.example.missing","com.example.installed"]}'),
 pg_temp.record('home_shelf','continue','{"bool_value":false}'),
 pg_temp.record('home_shelf','recent','{"bool_value":true}'),
 pg_temp.record('home_shelf','favourites','{"bool_value":true}'),
 pg_temp.record('home_shelf','on_now','{"bool_value":false}'),
 pg_temp.record('home_shelf','movies','{"bool_value":true}'),
 pg_temp.record('home_shelf','series','{"bool_value":true}'),
 pg_temp.record('home_shelf','apps','{"bool_value":true}'),
 pg_temp.record('setting','theme_mode','{"text_value":"DARK"}'),
 pg_temp.record('setting','subtitles_enabled','{"bool_value":true}'),
 pg_temp.record('setting','channel_layout','{"text_value":"GRID"}'),
 pg_temp.record('setting','startup_destination','{"text_value":"APPS"}')
));
do $$ begin
  if (select count(*) from public.nuvix_advanced_records) <> 21 then raise exception 'Missing supported records'; end if;
  if (select entries from public.nuvix_advanced_records where kind='group_members') <> array[repeat('b',64),repeat('a',64)] then
    raise exception 'Group member order changed';
  end if;
  if (select entries from public.nuvix_advanced_records where kind='app_order') <> array['com.example.missing','com.example.installed'] then
    raise exception 'Desired app order changed';
  end if;
end $$;
-- Replay preserves stable row identity and payload. Independent fields remain independent.
create temporary table replay_before as select id,kind,record_key,updated_at_ms,text_value,bool_value,int_value,entries from public.nuvix_advanced_records;
select public.nuvix_merge_advanced_records(jsonb_build_array(pg_temp.record('setting','theme_mode','{"text_value":"DARK"}')));
do $$ begin
  if exists ((select * from replay_before) except (select id,kind,record_key,updated_at_ms,text_value,bool_value,int_value,entries from public.nuvix_advanced_records)) then
    raise exception 'Replay changed existing state';
  end if;
end $$;
select public.nuvix_merge_advanced_records(jsonb_build_array(pg_temp.record('group_name','00000000-0000-0000-0000-000000000010','{"text_value":"Renamed","updated_at_ms":3000}')));
do $$ begin
  if (select int_value from public.nuvix_advanced_records where kind='group_rank') <> 2 or
     (select cardinality(entries) from public.nuvix_advanced_records where kind='group_members') <> 2 then
    raise exception 'Independent group edit was lost';
  end if;
end $$;
-- Strict JSON scalar types, required fields, bounds, exact key allowlists, and payload slots.
select pg_temp.reject_record('null');
select pg_temp.reject_record('[]');
select pg_temp.reject_record('{}');
select pg_temp.reject_record(pg_temp.record('unknown','apps'));
select pg_temp.reject_record(pg_temp.record('setting','provider_url','{"text_value":"https://secret.invalid"}'));
select pg_temp.reject_record(pg_temp.record('setting','theme_mode','{"text_value":"DARK","provider_url":"https://secret.invalid"}'));
select pg_temp.reject_record(pg_temp.record('setting','theme_mode','{"text_value":"DARK","owner_id":"20000000-0000-0000-0000-000000000002"}'));
select pg_temp.reject_record(pg_temp.record('setting','theme_mode','{"text_value":"DARK","access_token":"secret"}'));
select pg_temp.reject_record(pg_temp.record('setting','theme_mode','{"schema_version":2,"text_value":"DARK"}'));
select pg_temp.reject_record(pg_temp.record('setting','theme_mode','{"schema_version":"1","text_value":"DARK"}'));
select pg_temp.reject_record(pg_temp.record('setting','theme_mode','{"text_value":"DARK","updated_at_ms":"2000"}'));
select pg_temp.reject_record(pg_temp.record('setting','theme_mode','{"text_value":"DARK","updated_at_ms":1.5}'));
select pg_temp.reject_record(pg_temp.record('setting','theme_mode','{"text_value":"DARK","updated_at_ms":0}'));
select pg_temp.reject_record(pg_temp.record('setting','theme_mode',jsonb_build_object('text_value','DARK','updated_at_ms',(extract(epoch from clock_timestamp())*1000)::bigint+600000)));
select pg_temp.reject_record(pg_temp.record('setting','theme_mode','{"text_value":"DARK","device_id":"bogus"}'));
select pg_temp.reject_record(pg_temp.record('setting','theme_mode','{"text_value":"DARK","device_id":"00000000-0000-0000-0000-00000000000A"}'));
select pg_temp.reject_record(pg_temp.record('setting','theme_mode','{"text_value":"DARK","deleted":null}'));
select pg_temp.reject_record(pg_temp.record('setting','theme_mode','{"text_value":"DARK","deleted":true}'));
select pg_temp.reject_record(pg_temp.record('setting','theme_mode','{"text_value":"DARK","bool_value":true}'));
select pg_temp.reject_record(pg_temp.record('setting','theme_mode','{"text_value":"DARK","entries":["com.example.app"]}'));
select pg_temp.reject_record(pg_temp.record('setting','theme_mode','{"text_value":"future-theme"}'));
select pg_temp.reject_record(pg_temp.record('setting','channel_layout','{"text_value":"TABLE"}'));
select pg_temp.reject_record(pg_temp.record('setting','startup_destination','{"text_value":"LAUNCHER_ROLE"}'));
select pg_temp.reject_record(pg_temp.record('setting','subtitles_enabled','{"bool_value":"true"}'));
select pg_temp.reject_record(pg_temp.record('home_shelf','secret','{"bool_value":true}'));
select pg_temp.reject_record(pg_temp.record('home_shelf','recent','{"bool_value":1}'));
select pg_temp.reject_record(pg_temp.record('group_name','local-room-id','{"text_value":"News"}'));
select pg_temp.reject_record(pg_temp.record('group_name','00000000-0000-0000-0000-000000000010','{"text_value":""}'));
select pg_temp.reject_record(pg_temp.record('group_name','00000000-0000-0000-0000-000000000010',jsonb_build_object('text_value',repeat('x',121))));
select pg_temp.reject_record(pg_temp.record('group_name','00000000-0000-0000-0000-000000000010',jsonb_build_object('text_value',E'bad\nname')));
select pg_temp.reject_record(pg_temp.record('group_name','00000000-0000-0000-0000-000000000010','{"deleted":true,"text_value":"leak"}'));
select pg_temp.reject_record(pg_temp.record('channel_hidden','provider-id','{"bool_value":true}'));
select pg_temp.reject_record(pg_temp.record('channel_order',repeat('a',64),'{"int_value":1000001}'));
select pg_temp.reject_record(pg_temp.record('channel_order',repeat('a',64),'{"int_value":-1}'));
select pg_temp.reject_record(pg_temp.record('channel_order',repeat('a',64),'{"int_value":"2"}'));
select pg_temp.reject_record(pg_temp.record('channel_order',repeat('a',64),'{"int_value":2.1}'));
select pg_temp.reject_record(pg_temp.record('group_members','00000000-0000-0000-0000-000000000010','{"entries":["https://secret.invalid"]}'));
select pg_temp.reject_record(pg_temp.record('group_members','00000000-0000-0000-0000-000000000010',jsonb_build_object('entries',jsonb_build_array(repeat('a',64),repeat('a',64)))));
select pg_temp.reject_record(pg_temp.record('group_members','00000000-0000-0000-0000-000000000010','{"entries":[null]}'));
select pg_temp.reject_record(pg_temp.record('group_members','00000000-0000-0000-0000-000000000010','{"entries":[["nested"]]}'));
select pg_temp.reject_record(pg_temp.record('group_members','00000000-0000-0000-0000-000000000010',jsonb_build_object('entries',(select jsonb_agg(lpad(i::text,64,'0')) from generate_series(1,2001) as i))));
select pg_temp.reject_record(pg_temp.record('app_order','apps','{"entries":["com.example.app","com.example.app"]}'));
select pg_temp.reject_record(pg_temp.record('app_order','apps','{"entries":["https://secret.invalid"]}'));
select pg_temp.reject_record(pg_temp.record('app_order','apps','{"entries":["bad..package"]}'));
select pg_temp.reject_record(pg_temp.record('app_order','apps','{"entries":["com.1invalid"]}'));
select pg_temp.reject_record(pg_temp.record('app_order','apps',jsonb_build_object('entries',jsonb_build_array('com.'||repeat('x',252)))));
select pg_temp.reject_record(pg_temp.record('app_order','apps',jsonb_build_object('entries',(select jsonb_agg('com.example.app'||i) from generate_series(1,201) as i))));
do $$ begin
  begin perform public.nuvix_merge_advanced_records(null); raise exception 'Null SQL batch accepted'; exception when invalid_parameter_value then null; end;
  begin perform public.nuvix_merge_advanced_records('{}'); raise exception 'Object batch accepted'; exception when invalid_parameter_value then null; end;
  begin perform public.nuvix_merge_advanced_records((select jsonb_agg(pg_temp.record('setting','theme_mode','{"text_value":"DARK"}')) from generate_series(1,201)));
    raise exception 'Oversized batch accepted'; exception when invalid_parameter_value then null; end;
  begin
    perform public.nuvix_merge_advanced_records(jsonb_build_array(pg_temp.record('channel_name',repeat('c',64),'{"text_value":"Atomic"}'),'{"token":"invalid"}'::jsonb));
    raise exception 'Invalid batch accepted';
  exception when invalid_parameter_value then null; end;
  if exists(select 1 from public.nuvix_advanced_records where record_key=repeat('c',64)) then raise exception 'Batch was not atomic'; end if;
end $$;
-- Invalid stale payloads must still fail instead of disappearing through a conflict no-op.
select pg_temp.reject_record(pg_temp.record('channel_order',repeat('a',64),'{"int_value":1000001,"updated_at_ms":1}'));
select pg_temp.reject_record(pg_temp.record('group_name','00000000-0000-0000-0000-000000000010',
    jsonb_build_object('text_value',repeat('x',121),'updated_at_ms',1)));
-- Time, UUID, then payload determine the winner, including metadata collisions and direct writes.
select public.nuvix_merge_advanced_records(jsonb_build_array(pg_temp.record('setting','theme_mode','{"text_value":"LIGHT"}')));
select public.nuvix_merge_advanced_records(jsonb_build_array(pg_temp.record('setting','theme_mode','{"text_value":"DARK"}')));
do $$ begin
  if (select text_value from public.nuvix_advanced_records where kind='setting' and record_key='theme_mode') <> 'LIGHT' then raise exception 'Payload tie order failed'; end if;
end $$;
select public.nuvix_merge_advanced_records(jsonb_build_array(pg_temp.record('setting','theme_mode','{"text_value":"DARK","device_id":"00000000-0000-0000-0000-000000000002"}')));
update public.nuvix_advanced_records set text_value='SYSTEM',updated_at_ms=1000 where kind='setting' and record_key='theme_mode';
do $$ begin
  if (select text_value from public.nuvix_advanced_records where kind='setting' and record_key='theme_mode') <> 'DARK' then raise exception 'UUID/stale direct conflict failed'; end if;
  begin update public.nuvix_advanced_records set bool_value=true where kind='setting' and record_key='theme_mode';
    raise exception 'Direct invalid typed write accepted'; exception when invalid_parameter_value then null; end;
  begin update public.nuvix_advanced_records set entries=null,updated_at_ms=1 where kind='setting' and record_key='theme_mode';
    raise exception 'Direct stale null array accepted'; exception when invalid_parameter_value then null; end;
  begin update public.nuvix_advanced_records set deleted=null,updated_at_ms=1 where kind='setting' and record_key='theme_mode';
    raise exception 'Direct stale null boolean accepted'; exception when invalid_parameter_value then null; end;
  begin update public.nuvix_advanced_records set owner_id='20000000-0000-0000-0000-000000000002';
    raise exception 'Owner reassignment accepted'; exception when insufficient_privilege then null; end;
  begin update public.nuvix_advanced_records set record_key='apps';
    raise exception 'Record identity reassignment accepted'; exception when insufficient_privilege then null; end;
  begin delete from public.nuvix_advanced_records;
    raise exception 'Physical deletion accepted'; exception when insufficient_privilege then null; end;
end $$;
-- Even an older tombstone defeats a newer live revision. A new UUID is required to recreate a group.
select public.nuvix_merge_advanced_records(jsonb_build_array(pg_temp.record('group_name','00000000-0000-0000-0000-000000000010','{"deleted":true,"updated_at_ms":1000}')));
select public.nuvix_merge_advanced_records(jsonb_build_array(pg_temp.record('group_name','00000000-0000-0000-0000-000000000010','{"text_value":"Resurrect","updated_at_ms":9000}')));
update public.nuvix_advanced_records set deleted=false,text_value='Direct resurrection',updated_at_ms=10000 where kind='group_name';
do $$ begin
  if not (select deleted and text_value is null from public.nuvix_advanced_records where kind='group_name') then raise exception 'Group resurrected'; end if;
end $$;
-- A second owner cannot see or mutate any of A's rows; direct writes cannot forge owner_id.
select set_config('request.jwt.claims','{"sub":"20000000-0000-0000-0000-000000000002"}',true);
do $$ declare affected bigint; begin
  if (select count(*) from public.nuvix_advanced_records) <> 0 then raise exception 'Cross-account read leaked'; end if;
  update public.nuvix_advanced_records set bool_value=false where kind='channel_hidden';
  get diagnostics affected = row_count;
  if affected <> 0 then raise exception 'Cross-account update succeeded'; end if;
  begin
    insert into public.nuvix_advanced_records(owner_id,kind,record_key,updated_at_ms,device_id,bool_value)
    values ('20000000-0000-0000-0000-000000000001','channel_hidden',repeat('d',64),2000,'00000000-0000-0000-0000-000000000001',true);
    raise exception 'Cross-account insertion succeeded'; exception when insufficient_privilege then null; end;
  begin delete from public.nuvix_advanced_records where owner_id='20000000-0000-0000-0000-000000000001';
    raise exception 'Cross-account deletion allowed'; exception when insufficient_privilege then null; end;
end $$;
select public.nuvix_merge_advanced_records(jsonb_build_array(pg_temp.record('setting','theme_mode','{"text_value":"SYSTEM"}')));
do $$ begin
  if (select count(*) from public.nuvix_advanced_records) <> 1 then raise exception 'Own record missing'; end if;
end $$;
select set_config('request.jwt.claims','{}',true);
do $$ begin
  begin perform public.nuvix_merge_advanced_records('[]'); raise exception 'Missing principal accepted'; exception when insufficient_privilege then null; end;
end $$;
set local role anon;
do $$ begin
  begin perform * from public.nuvix_advanced_records; raise exception 'Anon SELECT allowed'; exception when insufficient_privilege then null; end;
  begin perform public.nuvix_merge_advanced_records('[]'); raise exception 'Anon RPC allowed'; exception when insufficient_privilege then null; end;
  begin insert into public.nuvix_advanced_records(owner_id,kind,record_key,updated_at_ms,device_id,bool_value)
    values ('20000000-0000-0000-0000-000000000001','channel_hidden',repeat('d',64),2000,'00000000-0000-0000-0000-000000000001',true);
    raise exception 'Anon INSERT allowed'; exception when insufficient_privilege then null; end;
  begin update public.nuvix_advanced_records set bool_value=true; raise exception 'Anon UPDATE allowed'; exception when insufficient_privilege then null; end;
end $$;
rollback;
