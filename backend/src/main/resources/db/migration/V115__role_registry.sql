-- §4 extraction — role registry (Auth Gap-5-A).
--
-- Set.of("USER","TENANT","ADMIN") is duplicated at AdminUserService:213
-- (all roles) and AdminUserInviteController:42 (invitable subset — ADMIN
-- excluded). Adding a new role (e.g. REPORTING) today means editing both
-- plus @PreAuthorize spots throughout the codebase.
--
-- V115 adds a minimal role table + is_invitable flag so the two
-- duplicated allow-lists collapse into one DB-driven list. The
-- @PreAuthorize sites are left alone — that's a Spring Security
-- expression-resolver change out-of-scope here; the audit's
-- role_permission table is deferred until there's an actual
-- permission-vs-role distinction to model.

DO $$
BEGIN
    IF to_regclass('public.role') IS NULL THEN
        CREATE TABLE role (
            code         VARCHAR(32) PRIMARY KEY,
            name         VARCHAR(80),
            -- TRUE when AdminUserInviteController.mint() will accept it as
            -- an invite target. ADMIN defaults to FALSE — admin accounts
            -- stay out-of-band only.
            is_invitable BOOLEAN NOT NULL DEFAULT TRUE,
            created_at   TIMESTAMP NOT NULL DEFAULT NOW(),
            updated_at   TIMESTAMP NOT NULL DEFAULT NOW()
        );
        CREATE INDEX IF NOT EXISTS ix_role_is_invitable
            ON role (is_invitable) WHERE is_invitable = TRUE;
    END IF;

    INSERT INTO role (code, name, is_invitable) VALUES
        ('USER',   'Operator',           TRUE),
        ('TENANT', 'Tenant (customer)',  TRUE),
        ('ADMIN',  'Administrator',      FALSE)
    ON CONFLICT (code) DO UPDATE
        SET name         = EXCLUDED.name,
            is_invitable = EXCLUDED.is_invitable,
            updated_at   = NOW();
END $$;
