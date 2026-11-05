-- Operator-only: psql "$CORE_DATABASE_URL" -v target_user='usr_example' -f scripts/provision-admin.sql
-- Existing account, no password/role supplied by public registration. Re-login after provisioning.
\set ON_ERROR_STOP on
BEGIN;
CREATE TEMP TABLE admin_provision_target ON COMMIT DROP AS
    SELECT user_id,account_status FROM identity.accounts WHERE user_id=:'target_user' FOR UPDATE;
DO $$ BEGIN
    IF NOT EXISTS(SELECT 1 FROM admin_provision_target WHERE account_status='ACTIVE') THEN
        RAISE EXCEPTION 'An existing ACTIVE account is required';
    END IF;
END $$;
INSERT INTO identity.admin_audit(actor_id,target_user_id,action,reason,result)
    SELECT 'operator:'||CURRENT_USER,a.user_id,'PROVISION_ADMIN','Explicit operator provisioning','ADMIN'
    FROM identity.accounts a JOIN admin_provision_target t USING(user_id) WHERE a.account_role<>'ADMIN';
UPDATE identity.sessions SET revoked_at_utc=CURRENT_TIMESTAMP
    WHERE user_id IN (SELECT user_id FROM admin_provision_target) AND revoked_at_utc IS NULL;
UPDATE identity.accounts SET account_role='ADMIN' WHERE user_id IN (SELECT user_id FROM admin_provision_target);
COMMIT;
