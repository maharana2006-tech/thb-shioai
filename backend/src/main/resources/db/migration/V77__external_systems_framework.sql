-- V77 — External Systems framework (S1 of 5).
--
-- Protocol-agnostic connector framework for talking to systems OUTSIDE
-- multiship's own database: Oracle WMS (NDS), REST integrations (SAP),
-- SFTP, etc. Each connector is a Spring bean implementing the
-- ExternalSystemConnector SPI; connection rows here tell the registry
-- WHICH connector to dispatch to (system_type column) and pass a JSON
-- config blob whose shape is defined by that connector.
--
-- Three tables:
--   1. external_system_connection — one row per configured integration.
--   2. external_system_secret     — encrypted values keyed off the row +
--                                    a per-secret key name. All values
--                                    encrypted via CryptoService (AES-GCM).
--   3. external_system_client_login_override — per-tenant credential
--                                    overrides for connectors that need
--                                    them (e.g. NDS's default rule is
--                                    "username = clientCode" but a
--                                    specific client may need a different
--                                    login).
--
-- No connectors ship in this slice — the framework is standalone,
-- tables carry no consumers yet. S2 adds the NdsOracleConnector as
-- the first client of the framework.
--
-- Fresh-DB safe per docs/flyway-fresh-db-guard-pattern.md.

DO $$
BEGIN
    IF to_regclass('public.external_system_connection') IS NULL THEN
        CREATE TABLE public.external_system_connection (
            id             BIGSERIAL PRIMARY KEY,
            -- Human-readable identifier used by callers to look up the
            -- connection (e.g. "nds-default", "sap-prod"). Unique across
            -- the table so callers can hardcode names.
            name           VARCHAR(80)  NOT NULL,
            -- Connector discriminator: must match some registered
            -- ExternalSystemConnector's systemType() (e.g. "NDS_ORACLE",
            -- "SAP_REST"). Nothing here enforces the value is known —
            -- if a row's system_type has no matching connector, the
            -- registry logs a warn on startup and skips it. Uppercase
            -- convention.
            system_type    VARCHAR(50)  NOT NULL,
            -- Off-switch. Inactive rows are loaded but never dispatched
            -- to; toggling active=false is safer than deleting when
            -- rolling back a bad config.
            active         BOOLEAN      NOT NULL DEFAULT TRUE,
            -- Non-secret configuration as JSON. Shape is defined by the
            -- connector's configType() Jackson class — the framework
            -- itself never introspects this. Passwords / secrets DO NOT
            -- live here; use external_system_secret for those.
            config_json    TEXT         NOT NULL DEFAULT '{}',
            created_at     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
            updated_at     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
            updated_by     VARCHAR(200),
            CONSTRAINT uk_external_system_connection_name UNIQUE (name)
        );

        CREATE INDEX idx_external_system_connection_type
            ON public.external_system_connection (system_type);

        COMMENT ON TABLE public.external_system_connection IS
            'V77 — one row per configured external integration. system_type dispatches to a registered ExternalSystemConnector; config_json shape is defined per-connector.';
        COMMENT ON COLUMN public.external_system_connection.system_type IS
            'Uppercase connector discriminator (e.g. NDS_ORACLE, SAP_REST). Must match a registered ExternalSystemConnector.systemType().';
        COMMENT ON COLUMN public.external_system_connection.config_json IS
            'Non-secret config as JSON. Shape defined per-connector. Passwords / API tokens go in external_system_secret instead.';
    END IF;

    IF to_regclass('public.external_system_secret') IS NULL THEN
        CREATE TABLE public.external_system_secret (
            id              BIGSERIAL PRIMARY KEY,
            connection_id   BIGINT       NOT NULL,
            -- Per-connector secret name (e.g. "productionPassword",
            -- "oauthClientSecret"). The connector reads secrets by
            -- calling ExternalSystemConfigService.getSecret(connId, key).
            secret_key      VARCHAR(120) NOT NULL,
            -- AES-256-GCM ciphertext (12-byte nonce prepended, base64).
            -- Written / read through CryptoService — never plaintext at
            -- rest or in logs.
            encrypted_value TEXT         NOT NULL,
            updated_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
            updated_by      VARCHAR(200),
            CONSTRAINT fk_ess_connection
                FOREIGN KEY (connection_id)
                REFERENCES public.external_system_connection(id)
                ON DELETE CASCADE,
            CONSTRAINT uk_ess_connection_key UNIQUE (connection_id, secret_key)
        );

        COMMENT ON TABLE public.external_system_secret IS
            'V77 — encrypted secrets for external_system_connection rows. AES-256-GCM via CryptoService; shared SECRETS_ENCRYPTION_KEY.';
    END IF;

    IF to_regclass('public.external_system_client_login_override') IS NULL THEN
        CREATE TABLE public.external_system_client_login_override (
            id                 BIGSERIAL PRIMARY KEY,
            connection_id      BIGINT       NOT NULL,
            client_code        VARCHAR(64)  NOT NULL,
            username           VARCHAR(120) NOT NULL,
            -- AES-256-GCM ciphertext, same format as external_system_secret.
            encrypted_password TEXT         NOT NULL,
            updated_at         TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
            updated_by         VARCHAR(200),
            CONSTRAINT fk_esclo_connection
                FOREIGN KEY (connection_id)
                REFERENCES public.external_system_connection(id)
                ON DELETE CASCADE,
            CONSTRAINT uk_esclo_connection_client UNIQUE (connection_id, client_code)
        );

        COMMENT ON TABLE public.external_system_client_login_override IS
            'V77 — per-tenant credential overrides for connectors whose default rule doesn''t apply to a given client (e.g. an NDS client whose Oracle login differs from the "username=clientCode" convention).';
    END IF;
END
$$;
