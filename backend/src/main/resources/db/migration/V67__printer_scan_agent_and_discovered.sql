-- V67 - Printer auto-detect P1: printer_scan_agent + printer_discovered tables.
--
-- Backend hooks for the per-tenant Docker scan agent (multiship-lan-scanner)
-- described in docs/printer-auto-detect-design.md. Two tables:
--
--   printer_scan_agent
--     One row per tenant/agent enrollment. The api_key_hash is the SHA-256
--     of the raw agent key returned once at generate time (mirrors the
--     ApiKeyService pattern). scan_requested_at is the poll flag: an
--     admin's "Scan now" button sets it, the agent's next long-poll picks
--     it up and clears via a separate PUT. last_seen_at is refreshed on
--     every poll or discovered-POST for the Grafana staleness alert
--     deferred to P4.
--
--   printer_discovered
--     One row per (tenant, host, port) tuple. UPSERTED on every scan;
--     scan_seq bumps so the FE picker can filter to the most recent
--     scan only. raw_txt is the pipe-joined mDNS TXT dump for debugging
--     when the connection heuristic guesses wrong (e.g. IPP printer
--     announcing on port 9100).
--
-- Fresh-DB safe per docs/flyway-fresh-db-guard-pattern.md - the
-- to_regclass guards skip the CREATE when Hibernate already made the
-- tables from the entity classes on a truly fresh DB.

DO $$
BEGIN
    IF to_regclass('public.printer_scan_agent') IS NULL THEN
        CREATE TABLE public.printer_scan_agent (
            id                    BIGSERIAL PRIMARY KEY,
            tenant_code           VARCHAR(50)  NOT NULL,
            agent_id              VARCHAR(100) NOT NULL,
            hostname              VARCHAR(255),
            api_key_hash          VARCHAR(64)  NOT NULL,
            enrolled_at           TIMESTAMP    NOT NULL,
            enrolled_by           VARCHAR(120),
            last_seen_at          TIMESTAMP,
            scan_requested_at     TIMESTAMP,
            active                BOOLEAN      NOT NULL DEFAULT TRUE,
            revoked_at            TIMESTAMP,
            CONSTRAINT uk_scan_agent_tenant_agent UNIQUE (tenant_code, agent_id)
        );

        CREATE INDEX idx_scan_agent_tenant_active
            ON public.printer_scan_agent (tenant_code, active)
            WHERE active = TRUE;

        COMMENT ON TABLE public.printer_scan_agent IS
            'PR-Printer-P1: per-tenant enrollment of the multiship-lan-scanner Docker agent. api_key_hash is SHA-256 of the raw key returned once at generate time (mirrors ApiKeyService).';

        COMMENT ON COLUMN public.printer_scan_agent.scan_requested_at IS
            'Nudge flag set by admin "Scan now"; agent picks up on next poll and clears via PUT. Latency budget: 5s (poll interval).';
    END IF;

    IF to_regclass('public.printer_discovered') IS NULL THEN
        CREATE TABLE public.printer_discovered (
            id                BIGSERIAL PRIMARY KEY,
            tenant_code       VARCHAR(50)  NOT NULL,
            agent_id          VARCHAR(100) NOT NULL,
            host              VARCHAR(255) NOT NULL,
            port              INT          NOT NULL,
            name              VARCHAR(120),
            location          VARCHAR(160),
            connection_guess  VARCHAR(20),
            format_guess      VARCHAR(10),
            paper_guess       VARCHAR(20),
            queue_path        VARCHAR(160),
            raw_txt           TEXT,
            discovered_at     TIMESTAMP    NOT NULL,
            scan_seq          BIGINT       NOT NULL,
            CONSTRAINT uk_discovered_tenant_host_port UNIQUE (tenant_code, host, port)
        );

        CREATE INDEX idx_discovered_tenant_scan_seq
            ON public.printer_discovered (tenant_code, scan_seq DESC);

        COMMENT ON TABLE public.printer_discovered IS
            'PR-Printer-P1: latest scan snapshot per (tenant, host, port). Upserted on every agent POST. scan_seq bumps per scan so the FE picker filters to the most recent run.';

        COMMENT ON COLUMN public.printer_discovered.connection_guess IS
            'Heuristic RAW_9100 | IPP based on port probe. Admin confirms before persisting to the real printer table.';
    END IF;
END
$$;
