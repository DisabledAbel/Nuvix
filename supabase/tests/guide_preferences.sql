-- Disposable PostgreSQL only. Fixtures and assertions roll back together.
begin;
insert into auth.users(id,email) values
 ('42000000-0000-0000-0000-000000000001','guide-a@example.invalid'),
 ('42000000-0000-0000-0000-000000000002','guide-b@example.invalid');
set local role authenticated;
select set_config('request.jwt.claims','{"sub":"42000000-0000-0000-0000-000000000001"}',true);
select public.nuvix_merge_advanced_records('[
 {"schema_version":1,"kind":"guide_setting","record_key":"guide_layout","text_value":"COMPACT","updated_at_ms":1000,"device_id":"00000000-0000-0000-0000-000000000001"},
 {"schema_version":1,"kind":"guide_setting","record_key":"guide_density","text_value":"WIDE","updated_at_ms":1000,"device_id":"00000000-0000-0000-0000-000000000001"},
 {"schema_version":1,"kind":"guide_setting","record_key":"guide_default_filter","text_value":"PROVIDER","updated_at_ms":1000,"device_id":"00000000-0000-0000-0000-000000000001"},
 {"schema_version":1,"kind":"guide_setting","record_key":"guide_sort","text_value":"CUSTOM","updated_at_ms":1000,"device_id":"00000000-0000-0000-0000-000000000001"},
 {"schema_version":1,"kind":"guide_setting","record_key":"guide_show_artwork","bool_value":false,"updated_at_ms":1000,"device_id":"00000000-0000-0000-0000-000000000001"},
 {"schema_version":1,"kind":"guide_setting","record_key":"guide_show_progress","bool_value":true,"updated_at_ms":1000,"device_id":"00000000-0000-0000-0000-000000000001"},
 {"schema_version":1,"kind":"guide_setting","record_key":"guide_remember_position","bool_value":true,"updated_at_ms":1000,"device_id":"00000000-0000-0000-0000-000000000001"},
 {"schema_version":1,"kind":"setting","record_key":"theme_mode","text_value":"DARK","updated_at_ms":1000,"device_id":"00000000-0000-0000-0000-000000000001"}
]');
do $$
declare invalid jsonb;
begin
 if (select count(*) from public.nuvix_advanced_records where kind='guide_setting') <> 7 then
   raise exception 'Portable guide preferences not retained';
 end if;
 -- Older clients query an explicit kind allowlist, so these settings never reach their strict decoder.
 if (select count(*) from public.nuvix_advanced_records where kind='setting') <> 1 then
   raise exception 'Guide kinds leaked into legacy compatible settings';
 end if;
 for invalid in select value from jsonb_array_elements('[
  {"kind":"guide_setting","record_key":"guide_layout","text_value":"HUGE"},
  {"kind":"guide_setting","record_key":"guide_density","text_value":"SECRET"},
  {"kind":"guide_setting","record_key":"guide_default_filter","text_value":"https://provider?token=secret"},
  {"kind":"guide_setting","record_key":"guide_sort","text_value":"LOCAL_CHANNEL_ID"},
  {"kind":"guide_setting","record_key":"guide_layout","bool_value":true},
  {"kind":"guide_setting","record_key":"guide_layout","text_value":"STANDARD","entries":["provider"]},
  {"kind":"guide_setting","record_key":"guide_layout","text_value":"STANDARD","bool_value":false},
  {"kind":"guide_setting","record_key":"guide_layout","text_value":"STANDARD","deleted":true},
  {"kind":"guide_setting","record_key":"guide_layout","text_value":"STANDARD","provider_password":"secret"},
  {"kind":"guide_setting","record_key":"guide_show_artwork","text_value":"true"},
  {"kind":"guide_setting","record_key":"guide_show_progress","int_value":1},
  {"kind":"guide_setting","record_key":"guide_remember_position"},
  {"kind":"guide_setting","record_key":"guide_filter_group_id","text_value":"1"},
  {"kind":"guide_setting","record_key":"guide_filter_provider_id","text_value":"1"},
  {"kind":"guide_setting","record_key":"guide_position_channel_id","int_value":1},
  {"kind":"guide_setting","record_key":"guide_position_time_ms","text_value":"1000"},
  {"kind":"guide_setting","record_key":"guide_artwork_url","text_value":"https://provider"},
  {"kind":"setting","record_key":"guide_layout","text_value":"STANDARD"}
 ]') loop
   begin
     perform public.nuvix_merge_advanced_records(jsonb_build_array(invalid ||
       '{"schema_version":1,"updated_at_ms":2000,"device_id":"00000000-0000-0000-0000-000000000001"}'::jsonb));
     raise exception 'Unsafe guide payload accepted: %', invalid;
   exception when invalid_parameter_value then null;
   end;
 end loop;
 -- Direct REST updates pass the same validator, including malformed stale writes.
 begin
   update public.nuvix_advanced_records set text_value='https://provider?token=secret',updated_at_ms=999
     where kind='guide_setting' and record_key='guide_layout';
   raise exception 'Malformed stale direct guide write accepted';
 exception when invalid_parameter_value then null;
 end;
 begin
   update public.nuvix_advanced_records set bool_value=true
     where kind='guide_setting' and record_key='guide_layout';
   raise exception 'Extra direct guide payload accepted';
 exception when invalid_parameter_value then null;
 end;
 perform public.nuvix_merge_advanced_records('[{"schema_version":1,"kind":"guide_setting","record_key":"guide_layout","text_value":"EXPANDED","updated_at_ms":999,"device_id":"00000000-0000-0000-0000-000000000002"}]');
 if (select text_value from public.nuvix_advanced_records where kind='guide_setting' and record_key='guide_layout') <> 'COMPACT' then
   raise exception 'Stale guide preference overwrote winner';
 end if;
 perform public.nuvix_merge_advanced_records('[{"schema_version":1,"kind":"guide_setting","record_key":"guide_layout","text_value":"EXPANDED","updated_at_ms":1000,"device_id":"00000000-0000-0000-0000-000000000002"}]');
 perform public.nuvix_merge_advanced_records('[{"schema_version":1,"kind":"guide_setting","record_key":"guide_layout","text_value":"COMPACT","updated_at_ms":1000,"device_id":"00000000-0000-0000-0000-000000000002"}]');
 if (select text_value from public.nuvix_advanced_records where kind='guide_setting' and record_key='guide_layout') <> 'EXPANDED' then
   raise exception 'Guide device or payload tie-break failed';
 end if;
end $$;
select set_config('request.jwt.claims','{"sub":"42000000-0000-0000-0000-000000000002"}',true);
do $$ begin
 if exists(select 1 from public.nuvix_advanced_records where kind='guide_setting') then
   raise exception 'Cross-account guide preferences visible';
 end if;
 update public.nuvix_advanced_records set text_value='STANDARD' where kind='guide_setting';
 if found then raise exception 'Cross-account guide update accepted'; end if;
 begin
   insert into public.nuvix_advanced_records(owner_id,kind,record_key,updated_at_ms,device_id,text_value)
   values('42000000-0000-0000-0000-000000000001','guide_setting','guide_layout',3000,'00000000-0000-0000-0000-000000000002','STANDARD');
   raise exception 'Unauthorized direct guide write accepted';
 exception when insufficient_privilege then null;
 end;
end $$;
set local role anon;
do $$ begin
 begin
   perform public.nuvix_merge_advanced_records('[]');
   raise exception 'Anonymous guide RPC access accepted';
 exception when insufficient_privilege then null;
 end;
 begin
   perform 1 from public.nuvix_advanced_records where kind='guide_setting';
   raise exception 'Anonymous guide read access accepted';
 exception when insufficient_privilege then null;
 end;
end $$;
rollback;
