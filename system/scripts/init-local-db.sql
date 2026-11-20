-- One-time setup. Run as the postgres user:  psql -U postgres -f scripts/init-local-db.sql
-- (skip the first line if the 'returnright' database already exists)
CREATE DATABASE returnright;          -- bootRun (profile: local)
CREATE DATABASE returnright_test;     -- shared integration-test DB (wiped on every test run)
CREATE DATABASE returnright_dashtest; -- DashboardIntegrationTest (fixed fixture)
CREATE DATABASE returnright_seedtest; -- SeedDataLoaderIntegrationTest
