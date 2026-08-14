# Flyway migration rollback reference

Sprint 51 AC-L6 — one-shot reversal snippets for every migration currently in
`backend/src/main/resources/db/migration`. This is an operator handbook, not
an application feature; the app itself does not run reversals.

Pair with `docs/flyway-fresh-db-guard-pattern.md` for the forward-migration
authoring rules. Every row here has been sanity-checked against the current
migration file it references — an addition here must ship in the same PR
as the migration.

## Reversal principles

- **DDL adds** (new column, new table, new index, new constraint) reverse
  cleanly with the matching `DROP ... IF EXISTS` — no data loss beyond the
  dropped structure.
- **Type widens** (VARCHAR(255) → VARCHAR(512)) reverse only if you also
  truncate any row whose value exceeds the narrower bound. Snippet is
  listed but the operator must verify data fits.
- **`ALTER COLUMN ... TYPE TIMESTAMPTZ USING ... AT TIME ZONE 'UTC'`**
  reverses to `TIMESTAMP` with the inverse `AT TIME ZONE 'UTC'` cast —
  lossless in both directions given the UTC assumption.
- **`UPDATE` backfills** (V3 `client_code` from username, V3 `expires_at`
  from `created_at + 365 days`) are best-effort and cannot be surgically
  reversed — mark such migrations "irreversible; restore from backup".
- Reversal snippets are always the LAST resort — Flyway's own `undo`
  feature is enterprise-only; the SOP is roll-forward with a new
  migration that inverts the change, not manual reversal.

## Rollback matrix

| Migration | Purpose | Reversal snippet | Notes |
|---|---|---|---|
| **V1**`__baseline.sql` | Empty baseline that anchors the Flyway version table. | `-- no-op` | Baseline exists to lock in `flyway_schema_history` at version 1; there is nothing to undo. |
| **V2**`__sprint49_tier0_tier1_columns.sql` | Adds `carrier_webhook_events.rejected/event_hash/duplicate`, creates `system_settings`, widens `carrier_config.client_secret` + `carrier_account_ref.client_secret` to VARCHAR(512). | `ALTER TABLE carrier_webhook_events DROP COLUMN IF EXISTS rejected, DROP COLUMN IF EXISTS event_hash, DROP COLUMN IF EXISTS duplicate;`<br>`DROP INDEX IF EXISTS idx_carrier_webhook_events_event_hash;`<br>`DROP TABLE IF EXISTS system_settings;`<br>`-- VARCHAR widens are irreversible without truncation:`<br>`ALTER TABLE carrier_config ALTER COLUMN client_secret TYPE VARCHAR(255);`<br>`ALTER TABLE carrier_account_ref ALTER COLUMN client_secret TYPE VARCHAR(255);` | The VARCHAR narrow will fail if any row has an enc:v1: ciphertext (all newly rotated secrets); truncate or delete those rows first. |
| **V3**`__sprint50_tier05_auth_schema.sql` | Adds `users.client_code`, `api_key.expires_at/rotated_from_id/last_rotated_at`, creates `user_invites` + `signup_attempts`, backfills `users.client_code` from username and `api_key.expires_at` from `created_at+365 days`. | `ALTER TABLE users DROP COLUMN IF EXISTS client_code;`<br>`DROP INDEX IF EXISTS idx_users_client_code;`<br>`ALTER TABLE api_key DROP COLUMN IF EXISTS expires_at, DROP COLUMN IF EXISTS rotated_from_id, DROP COLUMN IF EXISTS last_rotated_at;`<br>`DROP TABLE IF EXISTS user_invites;`<br>`DROP TABLE IF EXISTS signup_attempts;` | Irreversible for the two UPDATE backfills — no way to restore prior NULLs; use a DB backup. |
| **V4**`__sprint50_tier05_D_signup_verify.sql` | Adds `users.email_verified/email_verify_token/email_verify_expires_at`. | `ALTER TABLE users DROP COLUMN IF EXISTS email_verified, DROP COLUMN IF EXISTS email_verify_token, DROP COLUMN IF EXISTS email_verify_expires_at;`<br>`DROP INDEX IF EXISTS idx_users_email_verify_token;` | Legacy backfill (`DEFAULT TRUE`) is lost; unaffected users had NULL originally. |
| **V5**`__sprint50_tier05_E_scope_tightening.sql` | Adds `users.deactivated_at/deactivated_by`, creates `user_admin_audit`. | `ALTER TABLE users DROP COLUMN IF EXISTS deactivated_at, DROP COLUMN IF EXISTS deactivated_by;`<br>`DROP TABLE IF EXISTS user_admin_audit;` | Audit trail is lost — export before rollback if the trail matters. |
| **V6**`__sprint50_tier1_B_data_model.sql` | Widens `users.carrier_client_secret` to VARCHAR(512); adds `clients.default_currency/default_weight_unit/default_dim_unit/timezone/default_origin_country` + length CHECKs. | `ALTER TABLE users ALTER COLUMN carrier_client_secret TYPE VARCHAR(255);`<br>`ALTER TABLE clients DROP CONSTRAINT IF EXISTS chk_client_default_currency_len, DROP CONSTRAINT IF EXISTS chk_client_default_origin_len;`<br>`ALTER TABLE clients DROP COLUMN IF EXISTS default_currency, DROP COLUMN IF EXISTS default_weight_unit, DROP COLUMN IF EXISTS default_dim_unit, DROP COLUMN IF EXISTS timezone, DROP COLUMN IF EXISTS default_origin_country;` | The VARCHAR narrow on `carrier_client_secret` fails if any row contains the enc:v1: prefix + ciphertext (>255 chars); clear those first. |
| **V7**`__post_audit_indexes.sql` | Post-audit index additions across api_key + user_admin_audit + user_invites. | `DROP INDEX IF EXISTS <each index name>;` — see the migration file for the exact list. | Purely additive — reversal is trivially a DROP INDEX per line. |
| **V8**`__post_audit_foreign_keys.sql` | Adds `fk_api_key_client_code`, `fk_api_key_rotated_from`, `fk_user_invites_client_code` + `idx_user_invites_client_code`; sets `api_key.client_code NOT NULL` when no orphan rows. | `ALTER TABLE api_key DROP CONSTRAINT IF EXISTS fk_api_key_client_code, DROP CONSTRAINT IF EXISTS fk_api_key_rotated_from;`<br>`ALTER TABLE user_invites DROP CONSTRAINT IF EXISTS fk_user_invites_client_code;`<br>`DROP INDEX IF EXISTS idx_user_invites_client_code;`<br>`ALTER TABLE api_key ALTER COLUMN client_code DROP NOT NULL;` | The NOT NULL cannot be reversed if any code path relied on it since; check for downstream assumptions before dropping. |
| **V9**`__sprint51_t2_token_version.sql` | Adds `users.token_version BIGINT NOT NULL DEFAULT 0`. | `ALTER TABLE users DROP COLUMN IF EXISTS token_version;` | Trivially reversible; any currently-issued JWTs stop being validated against the bumped counter. |
| **V10**`__timestamptz.sql` | Widens a targeted set of naive TIMESTAMP columns to TIMESTAMPTZ assuming UTC (see the migration header for the exact column list). | For each converted column: `ALTER TABLE <table> ALTER COLUMN <col> TYPE TIMESTAMP USING <col> AT TIME ZONE 'UTC';` | Lossless because the forward migration also used `AT TIME ZONE 'UTC'` — the timezone offset is the identity transform when applied twice. Companion setting `spring.jpa.properties.hibernate.jdbc.time_zone=UTC` in `application.properties` must be removed if the columns are reverted. |
| **V11**`__auth_audit_fks.sql` | Adds `fk_user_admin_audit_subject_user`, `fk_user_invites_invited_by`, `chk_user_invites_role`. | `ALTER TABLE user_admin_audit DROP CONSTRAINT IF EXISTS fk_user_admin_audit_subject_user;`<br>`ALTER TABLE user_invites DROP CONSTRAINT IF EXISTS fk_user_invites_invited_by, DROP CONSTRAINT IF EXISTS chk_user_invites_role;` | Purely additive — reversal is trivial. |
| **V12** — reserved for the parallel backend-security PR (password change surface). | See that PR's rollback notes when it lands. | | Slot reserved so V13 does not collide across the three sprint-51-med worktrees. |
| **V13**`__cleanup_legacy_unique_constraints.sql` | Drops five legacy unique constraints (`shipping_service.uq_shipping_service_code`, `package_preset.uq_package_preset_name`, three `client_*_code_map.uq_client_*_code`) that were previously stripped at boot by `SchemaHealer`. | **Irreversible in a general way** — the original constraint definitions live in git history at the entity classes they used to be declared on. To restore any single one: `ALTER TABLE <t> ADD CONSTRAINT <name> UNIQUE (<original columns>);`, but only after deleting rows that violate the narrower key. | The whole point of dropping them was that the app now legitimately inserts rows that violate the old shape, so reversal likely requires a data cleanup first. |

## Rollback SOP

1. Take a `pg_dump` of the DB *before* running any reversal snippet. The
   snippets here are meant for surgical reversal on a canary/staging env
   or during a hotfix window, not for prod without a checkpoint.
2. Wrap the reversal in a `BEGIN;` / `COMMIT;` block so a mid-script
   failure rolls back cleanly.
3. After reversal, update `flyway_schema_history` to remove the version
   row so Flyway won't complain about a missing script on next boot:
   `DELETE FROM flyway_schema_history WHERE version = 'V<n>';`
4. Restart the app with `spring.flyway.baseline-on-migrate=true`
   (already set in application.properties) so a subsequent forward
   migration reconstructs the version chain.

## When to prefer roll-forward

Every migration in this table can be undone by shipping a NEW migration
with a higher version number that inverts the change (e.g. a V14 that
re-adds a dropped constraint). Prefer that path over manual reversal on
prod — it keeps `flyway_schema_history` intact and gives every
environment the same automatic upgrade behaviour on next deploy.
