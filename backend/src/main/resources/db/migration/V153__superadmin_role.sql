-- SUPERADMIN — a role above ADMIN. Its JWT carries ROLE_SUPERADMIN +
-- ROLE_ADMIN + ROLE_USER (see JwtAuthenticationFilter), so it inherits every
-- admin capability. Not invitable: assigned by SQL / another superadmin, never
-- through the public invite flow.
INSERT INTO public.role (code, name, is_invitable)
VALUES ('SUPERADMIN', 'Super administrator', FALSE)
ON CONFLICT (code) DO NOTHING;
