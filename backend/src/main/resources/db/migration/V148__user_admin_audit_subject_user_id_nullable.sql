-- BUG-050 — INVITE_SENT audit rows have no subject user_id (the invitee
-- doesn't exist as a users row until the invite is consumed). Drop the
-- NOT NULL constraint on subject_user_id so recordInviteSent can persist.
DO $$
BEGIN
    IF to_regclass('public.user_admin_audit') IS NOT NULL THEN
        ALTER TABLE user_admin_audit ALTER COLUMN subject_user_id DROP NOT NULL;
    END IF;
END $$;
