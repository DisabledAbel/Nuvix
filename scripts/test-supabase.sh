#!/usr/bin/env bash
# Clean PostgreSQL, no hosted database URL, published port, or Supabase credentials.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
command -v docker >/dev/null || { echo 'Docker is required for isolated SQL validation.' >&2; exit 1; }
container="nuvix-sql-tests-$$"
image="postgres:16.15-alpine"
test_password="nuvix-isolated-test"
cleanup() { docker rm -f "$container" >/dev/null 2>&1 || true; }
trap cleanup EXIT
docker run --detach --name "$container" --env POSTGRES_PASSWORD="$test_password" "$image" >/dev/null
psql_test() {
  docker exec --interactive --env PGPASSWORD="$test_password" --env PGCONNECT_TIMEOUT=2 "$container" \
    psql -X -v ON_ERROR_STOP=1 --host 127.0.0.1 --port 5432 --username postgres --dbname postgres "$@"
}
# The image's temporary initialization server accepts Unix sockets, then stops.
# Wait for an authenticated query on the final TCP server using the same connection as the tests.
ready=false
for attempt in {1..60}; do
  if psql_test --quiet --tuples-only --command 'SELECT 1' >/dev/null 2>&1; then ready=true; break; fi
  sleep 1
done
if [[ "$ready" != true ]]; then
  echo 'PostgreSQL did not become ready for SQL validation.' >&2
  docker logs "$container" >&2
  exit 1
fi
psql_test < supabase/tests/support/bootstrap.sql
for migration in supabase/migrations/*.sql; do
  echo "Applying $migration"
  psql_test < "$migration"
  if [[ "$migration" == *20261013185918_cloud_records.sql ]]; then
    # Seed before the new migration, so accidental truncation/alteration cannot pass unnoticed.
    psql_test <<'SQL'
insert into auth.users(id,email) values ('99999999-0000-0000-0000-000000000001','migration-fixture@example.invalid');
set role authenticated;
select set_config('request.jwt.claims','{"sub":"99999999-0000-0000-0000-000000000001"}',false);
select public.nuvix_merge_records('[{"record_key":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","kind":"movie_progress","profile_key":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb","position_ms":123,"duration_ms":1000,"updated_at_ms":2000,"device_id":"00000000-0000-0000-0000-000000000001"}]');
SQL
  fi
done
psql_test <<'SQL'
do $$ begin
  if not exists (select 1 from public.nuvix_sync_records where owner_id='99999999-0000-0000-0000-000000000001'
    and kind='movie_progress' and position_ms=123 and duration_ms=1000 and updated_at_ms=2000) then
    raise exception 'Existing cloud record changed during migration';
  end if;
end $$;
-- Remove only the known synthetic compatibility fixture, preserving tests' rollback isolation.
delete from auth.users where id='99999999-0000-0000-0000-000000000001';
SQL
for sql_test in supabase/tests/*.sql; do
  echo "Testing $sql_test"
  psql_test < "$sql_test"
done

# Separate database connections really overlap on one logical row; the lower write arrives last.
psql_test <<'SQL'
insert into auth.users(id,email) values ('30000000-0000-0000-0000-000000000001','concurrent@example.invalid');
SQL
psql_test <<'SQL' &
begin;
set local role authenticated;
select set_config('request.jwt.claims','{"sub":"30000000-0000-0000-0000-000000000001"}',true);
select public.nuvix_merge_advanced_records('[{"schema_version":1,"kind":"setting","record_key":"theme_mode","text_value":"LIGHT","updated_at_ms":1000,"device_id":"00000000-0000-0000-0000-000000000001"}]');
select pg_sleep(1);
commit;
SQL
first_pid=$!
psql_test <<'SQL' &
begin;
set local role authenticated;
select set_config('request.jwt.claims','{"sub":"30000000-0000-0000-0000-000000000001"}',true);
select pg_sleep(0.2);
select public.nuvix_merge_advanced_records('[{"schema_version":1,"kind":"setting","record_key":"theme_mode","text_value":"DARK","updated_at_ms":1000,"device_id":"00000000-0000-0000-0000-000000000001"}]');
commit;
SQL
second_pid=$!
wait "$first_pid"
wait "$second_pid"
psql_test <<'SQL'
do $$ begin
  if (select text_value from public.nuvix_advanced_records where owner_id='30000000-0000-0000-0000-000000000001') <> 'LIGHT' then
    raise exception 'Concurrent merge did not retain deterministic payload winner';
  end if;
end $$;
SQL
echo 'Supabase migrations, RLS, typed validation and concurrent merges passed.'
