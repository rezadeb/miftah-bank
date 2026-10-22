-- Monitoring user for the Dynatrace PostgreSQL extension.
-- Safe to run more than once. Run it as the "miftah" (superuser) user:
--
--   kubectl -n miftah-bank exec -i deployment/postgres -- psql -U miftah -d postgres -f - < db/monitoring-user.sql
--
-- The password below is only a placeholder. Set the real one afterwards (do not commit it):
--
--   kubectl -n miftah-bank exec -it deployment/postgres -- psql -U miftah -d postgres
--   ALTER USER dynatrace WITH PASSWORD '<your password>';
--
-- Source: PostgreSQL extension, "Database user and grants" (Dynatrace Docs, checked 2026-10-22).
-- Needs the Postgres container to start with shared_preload_libraries=pg_stat_statements
-- (already set in k8s/postgres.yaml).

DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'dynatrace') THEN
    CREATE ROLE dynatrace WITH LOGIN PASSWORD 'CHANGE_ME' INHERIT;
  END IF;
END
$$;

-- Once per instance: read-only access to the monitoring views.
GRANT pg_monitor TO dynatrace;

-- Once per monitored database. "postgres" is the database used in the extension endpoint.
\connect postgres
CREATE SCHEMA IF NOT EXISTS dynatrace;
GRANT USAGE ON SCHEMA dynatrace TO dynatrace;
CREATE EXTENSION IF NOT EXISTS pg_stat_statements SCHEMA public;

-- The application database (needed when you turn on database discovery for it).
\connect miftahbank
CREATE SCHEMA IF NOT EXISTS dynatrace;
GRANT USAGE ON SCHEMA dynatrace TO dynatrace;
CREATE EXTENSION IF NOT EXISTS pg_stat_statements SCHEMA public;
