-- Data-driven custom roles. A custom role has a base_role (ADMIN / USER /
-- TENANT) that is its backend security floor — its JWT is granted the base
-- role's authorities, so all existing @PreAuthorize rules keep working — plus a
-- curated set of module keys (role_module) that decide which pages show in the
-- UI. Built-in roles (SUPERADMIN / ADMIN / USER / TENANT) leave base_role NULL
-- and keep their hard-coded behaviour.
ALTER TABLE public.role ADD COLUMN IF NOT EXISTS base_role VARCHAR(32);

CREATE TABLE IF NOT EXISTS public.role_module (
    role_code  VARCHAR(32) NOT NULL REFERENCES public.role(code) ON DELETE CASCADE,
    module_key VARCHAR(64) NOT NULL,
    PRIMARY KEY (role_code, module_key)
);

CREATE INDEX IF NOT EXISTS ix_role_module_role ON public.role_module (role_code);
