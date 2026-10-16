-- Runs only in disposable test infrastructure; every fixture is rolled back.
begin;
insert into auth.users(id,email) values
 ('41000000-0000-0000-0000-000000000001','home-a@example.invalid'),
 ('41000000-0000-0000-0000-000000000002','home-b@example.invalid');
set local role authenticated;
select set_config('request.jwt.claims','{"sub":"41000000-0000-0000-0000-000000000001"}',true);
select public.nuvix_merge_advanced_records('[
 {"schema_version":1,"kind":"home_order","record_key":"shelves_v1","entries":["apps","continue","group:00000000-0000-0000-0000-000000000001"],"updated_at_ms":1000,"device_id":"00000000-0000-0000-0000-000000000001"},
 {"schema_version":1,"kind":"home_layout","record_key":"presentation_v1","entries":["v1","layout=CINEMA","movie=LARGE","series=SMALL","channel=MEDIUM","corners=ROUND","metadata=false","background=DYNAMIC_ARTWORK","featured=CONTINUE"],"updated_at_ms":1000,"device_id":"00000000-0000-0000-0000-000000000001"},
 {"schema_version":1,"kind":"home_group","record_key":"group:00000000-0000-0000-0000-000000000001","bool_value":false,"updated_at_ms":1000,"device_id":"00000000-0000-0000-0000-000000000001"},
 {"schema_version":1,"kind":"home_shelf","record_key":"recent","bool_value":false,"updated_at_ms":1000,"device_id":"00000000-0000-0000-0000-000000000001"}
]');
do $$
declare invalid jsonb;
begin
 if (select count(*) from public.nuvix_advanced_records where owner_id=auth.uid()) <> 4 then
   raise exception 'Home records or legacy visibility lost';
 end if;
 for invalid in select value from jsonb_array_elements('[
  {"kind":"home_order","record_key":"shelves_v1","entries":["apps","apps"]},
  {"kind":"home_order","record_key":"shelves_v1","entries":["future_shelf"]},
  {"kind":"home_order","record_key":"shelves_v1","entries":["https://provider?token=secret"]},
  {"kind":"home_order","record_key":"shelves_v2","entries":[]},
  {"kind":"home_order","record_key":"shelves_v1","entries":[],"text_value":"secret"},
  {"kind":"home_layout","record_key":"presentation_v1","entries":["v1","layout=COMPACT"]},
  {"kind":"home_layout","record_key":"presentation_v1","entries":["v2","layout=CINEMA","movie=LARGE","series=SMALL","channel=MEDIUM","corners=ROUND","metadata=false","background=DYNAMIC_ARTWORK","featured=CONTINUE"]},
  {"kind":"home_layout","record_key":"presentation_v1","entries":["v1","layout=HUGE","movie=LARGE","series=SMALL","channel=MEDIUM","corners=ROUND","metadata=false","background=DYNAMIC_ARTWORK","featured=CONTINUE"]},
  {"kind":"home_group","record_key":"group:INVALID","bool_value":false}
 ]') loop
   begin
     perform public.nuvix_merge_advanced_records(jsonb_build_array(invalid ||
       '{"schema_version":1,"updated_at_ms":2000,"device_id":"00000000-0000-0000-0000-000000000001"}'::jsonb));
     raise exception 'Invalid home payload accepted: %', invalid;
   exception when invalid_parameter_value then null;
   end;
 end loop;
 begin
   insert into public.nuvix_advanced_records(owner_id,kind,record_key,updated_at_ms,device_id,entries)
   values(auth.uid(),'home_order','shelves_v1',2000,'00000000-0000-0000-0000-000000000001',array_fill('apps'::text,array[161]));
   raise exception 'Oversized home order accepted';
 exception when invalid_parameter_value then null;
 end;
 -- A later-arriving stale revision cannot overwrite the complete order.
 perform public.nuvix_merge_advanced_records('[{"schema_version":1,"kind":"home_order","record_key":"shelves_v1","entries":["movies"],"updated_at_ms":999,"device_id":"00000000-0000-0000-0000-000000000002"}]');
 if (select entries from public.nuvix_advanced_records where owner_id=auth.uid() and kind='home_order') <> array['apps','continue','group:00000000-0000-0000-0000-000000000001'] then
   raise exception 'Stale order revision overwrote winner';
 end if;
 perform public.nuvix_merge_advanced_records('[{"schema_version":1,"kind":"home_order","record_key":"shelves_v1","entries":["movies"],"updated_at_ms":1000,"device_id":"00000000-0000-0000-0000-000000000002"}]');
 perform public.nuvix_merge_advanced_records('[{"schema_version":1,"kind":"home_order","record_key":"shelves_v1","entries":["series"],"updated_at_ms":1000,"device_id":"00000000-0000-0000-0000-000000000001"}]');
 if (select entries from public.nuvix_advanced_records where owner_id=auth.uid() and kind='home_order') <> array['movies'] then
   raise exception 'Home order device tie-break failed';
 end if;
 perform public.nuvix_merge_advanced_records('[{"schema_version":1,"kind":"home_order","record_key":"shelves_v1","entries":["series"],"updated_at_ms":1000,"device_id":"00000000-0000-0000-0000-000000000002"}]');
 if (select entries from public.nuvix_advanced_records where owner_id=auth.uid() and kind='home_order') <> array['series'] then
   raise exception 'Home order whole-list payload tie-break failed';
 end if;
end $$;
select set_config('request.jwt.claims','{"sub":"41000000-0000-0000-0000-000000000002"}',true);
do $$ begin
 if exists(select 1 from public.nuvix_advanced_records where kind in ('home_order','home_layout','home_group','home_shelf')) then
   raise exception 'Cross-account home records visible';
 end if;
 begin
   insert into public.nuvix_advanced_records(owner_id,kind,record_key,updated_at_ms,device_id,entries)
   values('41000000-0000-0000-0000-000000000001','home_order','shelves_v1',3000,'00000000-0000-0000-0000-000000000002',array['movies']);
   raise exception 'Unauthorized home order write accepted';
 exception when insufficient_privilege then null;
 end;
end $$;
rollback;
