# Flyway migration pattern — fresh-DB safety

## The problem

This codebase runs Flyway alongside Hibernate's `ddl-auto=update` (see `spring.jpa.hibernate.ddl-auto` in `application.properties`). The intent from Sprint 49 is that Hibernate owns the schema (creates tables from entity metadata) and Flyway records versions + adds ALTER-shaped changes on top.

But Spring Boot wires **Flyway as a dependency of `entityManagerFactory`**, so Flyway runs *before* Hibernate. On a truly fresh Postgres (empty DB, no tables), a migration that does anything beyond `ALTER TABLE IF EXISTS ...` will error because the target table doesn't exist yet — Hibernate hasn't run.

Concretely, three SQL patterns fail on a fresh DB even inside a migration marked "idempotent":

1. `CREATE INDEX ... ON <table>` — no `IF EXISTS` clause on the table reference; errors if table missing.
2. `UPDATE <table> SET ... WHERE ...` — errors if table missing.
3. `ALTER TABLE <table>` (without `IF EXISTS`) — errors if table missing.

The **CI smoke test** (`BackendApplicationTests`) hit this after Sprint 50 introduced backend-ci as the first fresh-DB context load. Fix in PR #128: the smoke test bypasses Flyway (`spring.flyway.enabled=false`, `ddl-auto=create-drop`) so context load can complete. That's a band-aid for the smoke test; it doesn't fix the migrations themselves.

## The pattern

Every migration statement whose target might not exist on a fresh DB must be **guarded by `to_regclass('<table>') IS NOT NULL`** (for existence) inside a `DO $$ BEGIN ... END $$` block, OR use the SQL-level `IF EXISTS` clause where the statement supports it.

### Pattern A — `IF EXISTS` clause (preferred when available)

```sql
-- ALTER TABLE supports IF EXISTS in Postgres.
ALTER TABLE IF EXISTS users ADD COLUMN IF NOT EXISTS client_code VARCHAR(50);
ALTER TABLE IF EXISTS api_key ADD COLUMN IF NOT EXISTS expires_at TIMESTAMP;
```

Works because ALTER TABLE IF EXISTS is a first-class Postgres feature that no-ops if the table is missing.

### Pattern B — `to_regclass` DO block (required for CREATE INDEX / UPDATE / ALTER COLUMN)

```sql
DO $$
BEGIN
    IF to_regclass('users') IS NOT NULL THEN
        CREATE INDEX IF NOT EXISTS idx_users_client_code
            ON users (UPPER(client_code));
        UPDATE users SET client_code = UPPER(username)
        WHERE role = 'TENANT' AND client_code IS NULL;
    END IF;
END $$;
```

`to_regclass('<name>')` returns the OID if the relation exists, NULL otherwise. Cheap catalog lookup, no locking.

### Pattern C — column-level guard for `ALTER COLUMN`

```sql
DO $$
BEGIN
    IF to_regclass('users') IS NOT NULL AND EXISTS (
            SELECT 1 FROM information_schema.columns
             WHERE table_name = 'users' AND column_name = 'carrier_client_secret') THEN
        ALTER TABLE users ALTER COLUMN carrier_client_secret TYPE VARCHAR(512);
    END IF;
END $$;
```

`ALTER COLUMN` doesn't accept `IF EXISTS`. Guard the whole statement in a DO block with both table + column checks.

## When the guard is NOT needed

- `CREATE TABLE IF NOT EXISTS ...` — always safe.
- `CREATE INDEX IF NOT EXISTS ... ON <newly-created-table-in-same-migration>` — the table was just CREATEd above, so it exists.
- Referencing `pg_constraint`, `information_schema`, `pg_catalog.*` — those are always present.

## Existing migrations status

| Migration | Fresh-DB safe? | Notes |
|---|---|---|
| V1 | ✓ | Empty baseline. |
| V2 | ✓ | Pre-existing PR #128 hotfix wrapped the one unsafe CREATE INDEX. |
| V3 | ✗ (**intentionally not patched**) | Ships in prod as of Sprint 50 Tier 0.5 PR A. Editing would break Flyway checksum validation on prod boot. |
| V4 | ✗ (**intentionally not patched**) | Same reason as V3. |
| V5 | ✓ | Every ALTER uses `IF EXISTS`; new table + index self-contained. |
| V6 | ✓ (**patched in PR I**) | Fresh migration; safe to edit since not yet in prod. |
| V7+ | must follow this pattern | New migrations MUST use Pattern A/B/C for any external-table reference. |

## Testing your migration

`MigrationsFreshDbIntegrationTest` in the integration test suite spins up a fresh Testcontainers Postgres and runs Flyway against it. New migrations that violate the pattern will fail this test. Guarded by `INTEGRATION_TESTS=1`:

```bash
INTEGRATION_TESTS=1 mvn test -Dtest=MigrationsFreshDbIntegrationTest
```

## Long-term direction

The "proper" fix is to make Flyway the sole schema authority — every table gets its `CREATE TABLE ...` inside a Flyway migration, and `spring.jpa.hibernate.ddl-auto` moves to `validate`. That requires either:

1. A `pg_dump --schema-only` of a verified prod database → replace `V1__baseline.sql`.
2. Manually consolidating current entity metadata into a new V1_1 migration.

Both are expensive one-time efforts. Until then, this DO-block guard pattern is the tactical fix: new migrations remain fresh-DB safe without a schema rewrite.
