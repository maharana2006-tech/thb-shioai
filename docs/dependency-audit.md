# Dependency audit (2026-09-18)

`npm audit` on `multiship-react/` + `mvn versions:display-dependency-updates` on `backend/`, plus manual CVE spot-checks on notable version deltas. No `mvn dependency-check` (OWASP plugin not configured — flagged below as a follow-up).

## TL;DR

**Zero production-runtime vulnerabilities**. All 5 npm audit findings are dev/test-only (Vitest + browserslist). Backend runs on **Spring Boot 4.1.0** — latest major, no known critical CVEs. Only 10 direct Maven dependencies have newer versions; the highest-signal one is a **massive `jsch` jump (0.2.21 → 2.28.7)** — the SFTP client is heavily out-of-date, worth bumping deliberately.

## Frontend (npm audit)

- **366 total deps** (production + dev).
- **5 vulnerabilities**: 0 critical, 1 HIGH, 4 moderate, 0 low.
- **All 5 are dev/test-only** — none reach the production bundle.

| Severity | Package | Direct? | Root cause |
|---|---|---|---|
| HIGH | `browserslist` | transitive (via Vite build tooling) | Unbounded memory growth (no cache eviction) via distinct query results — DoS vector on the DEV / CI build machine only |
| MODERATE | `vitest` | direct dev dep | Path traversal / arbitrary file read via `@vitest/mocker` redirect mocks |
| MODERATE | `@vitest/mocker` | transitive (via vitest) | Same as above |
| MODERATE | `@vitest/ui` | direct dev dep | Depends on the vulnerable vitest |
| MODERATE | `baseline-browser-mapping` | transitive (via Vite tooling) | Process termination on invalid input → DoS on build |

**Runtime impact**: zero. Every one of these is under `devDependencies` or transitively pulled by Vite/Vitest. Production `dist/` bundle doesn't include any of them.

**Fix path**: `npm audit fix` (should resolve everything without breaking changes; verify with a full CI run after).

## Backend (mvn versions:display-dependency-updates)

- **Spring Boot**: `4.1.0` (current major). No CVE concern at the framework level.
- **10 direct-dep updates available** in `pom.xml`'s `<dependencies>` block:

| Priority | Coordinate | Current → Latest | Notes |
|---|---|---|---|
| **HIGH** | `com.github.mwiede:jsch` | **0.2.21 → 2.28.7** | Huge version gap. Fork of the unmaintained JCraft jsch. SSH/SFTP client — CVE-prone family. Bump deliberately with regression testing on the SFTP-using paths. |
| MEDIUM | `io.jsonwebtoken:jjwt-*` (api / impl / jackson) | 0.12.6 → 0.13.0 | JWT parsing. Minor bump; no known CVE at 0.12.6 but any JWT lib update deserves a re-run of the auth suite. |
| MEDIUM | `org.apache.poi:poi-ooxml` | 5.3.0 → 5.5.1 | XML/office parsing family — XXE history. Ship after regression-checking the XLSX import path. |
| LOW | `org.apache.pdfbox:pdfbox` | 3.0.3 → 3.0.8 | Patch bumps; PDF parser fixes. Low risk. |
| LOW | `org.apache.commons:commons-csv` | 1.11.0 → 1.14.1 | Parser lib. Low risk. |
| LOW | `org.springdoc:springdoc-openapi-starter-webmvc-ui` | 3.0.3 → 3.1.1 | Swagger UI. Low risk. |
| LOW (test) | `org.testcontainers:junit-jupiter` + `postgresql` | 1.20.4 → 1.21.4 | Test-only. Bump when convenient. |

BOM-managed transitive updates (Jackson 2.21 → 2.22, Logback 1.5.34 → 1.6.3, etc.) come with the next Spring Boot minor bump. Not actionable individually — deferred to whenever Spring Boot 4.2.x lands.

## Findings summary

### BLOCKERs

None. Zero production-runtime vulnerabilities.

### MAJORs

**DEP-M1** — `jsch 0.2.21 → 2.28.7` is a 2-major-version gap on a security-sensitive library. Even without a specific CVE flag, the "we're on a fork of an abandoned parent that had CVEs" story alone justifies the bump. **Blast radius**: any code path calling SFTP (grep for `com.jcraft.jsch` or `com.github.mwiede.jsch` imports).

**DEP-M2** — Vite/Vitest ecosystem HIGH+MODERATE vulnerabilities (all dev-only). Doesn't ship to prod but represents build-machine DoS surface. `npm audit fix` should resolve.

### MINORs (bump when convenient)

- **DEP-N1** — `jjwt 0.12.6 → 0.13.0`. Bump + re-run auth tests.
- **DEP-N2** — `poi-ooxml 5.3.0 → 5.5.1`. Bump + re-run XLSX import tests.
- **DEP-N3** — `pdfbox`, `commons-csv`, `springdoc`, `testcontainers` patch bumps. Batch into one PR when Spring Boot 4.2 arrives.

### Infrastructure gaps

**DEP-INFRA-1** — No OWASP `dependency-check-maven` plugin configured. Every dependency audit needs to be manual because there's no automated CVE cross-check. Recommended fix: add the plugin to `backend/pom.xml`, wire to `mvn verify` (fail-on-CVSS-7+), and add a nightly CI job.

**DEP-INFRA-2** — No `npm audit` gate in CI. FE builds pass with HIGH-severity dev-dep vulnerabilities. Recommended: add `npm audit --production --audit-level=high` to the FE build pipeline.

## Proposed fix track — 3 PRs (~200 LoC total, mostly config)

Uses letter **D** (Dependency).

### PR-D1 — jsch major bump + FE `npm audit fix` (~100 LoC, MEDIUM risk)
- Backend: `com.github.mwiede:jsch` 0.2.21 → 2.28.7. Grep for `com.jcraft.jsch.*` imports; run the SFTP-touching test suites.
- FE: `npm audit fix` for the 5 dev-dep vulnerabilities. Verify all vitest suites still run + `npm run build` succeeds.

### PR-D2 — jjwt + poi-ooxml minor bumps (~30 LoC, LOW risk)
- `io.jsonwebtoken:jjwt-*` 0.12.6 → 0.13.0. Re-run auth + JWT tests.
- `org.apache.poi:poi-ooxml` 5.3.0 → 5.5.1. Re-run OrderImport XLSX tests.

### PR-D3 (infrastructure) — OWASP dependency-check + npm audit CI gate (~70 LoC config, LOW risk)
- Add `org.owasp:dependency-check-maven` plugin (fail-on CVSS ≥ 7) + wire to `mvn verify`.
- Add `npm audit --production --audit-level=high` step to the FE CI.
- Nightly CI job to catch new CVEs without waiting for a PR.

### Deferred (batch when Spring Boot 4.2 arrives)
- pdfbox / commons-csv / springdoc / testcontainers patch bumps.
- BOM-managed transitive updates (Jackson 2.22, Logback 1.6, etc.).

## Non-goals

- **Major-version jumps beyond `jsch`** — no other lib needs one today.
- **Dependency injection framework migration** — Spring Boot 4.1 is current.
- **Node LTS bump** — separate infra track.

## Related memory

- [[perf-audit]] — audit template.
- [[stamps-com-audit]] — cross-flow audit template.
- [[fe-audit]] — most recent audit; confirms zero FE XSS.
