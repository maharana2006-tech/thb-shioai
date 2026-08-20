# Incident 2026-08-19 — pg_dump files exposed on public repo

## What happened
Merge commit `ae93b44` ("Merge branch 'security/audit-fixes-sprint51' into dev")
pulled four PostgreSQL `pg_dump` files into `origin/dev`'s tree on a **public**
GitHub repo. The files had been introduced earlier by commits `3d6378c`
(2026-08-16) and `b99dad8` (2026-08-18) and contained live row data — bcrypt
password hashes, encrypted carrier API credentials, PII.

## What's exposed
- **7 users** with `key_hash` (bcrypt password hashes)
- **7 clients** (customer identifiers)
- **7 carrier accounts** in `carrier_account_ref` (encrypted-at-rest, but if
  `SECRETS_ENCRYPTION_KEY` was in the same DB dump they're effectively plaintext)
- **12 label batches** · **44 order lines** · **55 shipping-service rows**
- Adjacent PII (recipient addresses, tracking numbers, phone numbers)

## Files removed (commit `d95e982`)
- `multiship_db_backup_20260721_145252.sql` (blob `28e9f646…`)
- `multiship_db_backup_20260817_145556.sql` (blob `8749e80f…`)
- `multiship_db_seed_20260817_155136.sql` (blob `1511bbe2…`)
- `multiship_db_dump.sql` (blob `dfb56d17…`)

Plus a `.gitignore` addition (`multiship_db_*.sql`, `*.pg_dump`, `*_pgdump.sql`)
to prevent recurrence.

## What's still exposed
Blobs remain reachable via commit history and via two other branches we've
opted to keep for now (`jagannath_dev`, `security/audit-fixes-sprint51`), both
of which still carry the raw `.sql` files at HEAD. Anyone can:

```bash
git clone -b security/audit-fixes-sprint51 https://github.com/maharana2006-tech/thb-shioai
# → gets the raw dumps
```

Plus anyone who already cloned the repo between 2026-07-21 and now has their
own copy — outside GitHub's control.

## This kit — how to use it

Run in **priority order**. Each file is standalone.

| # | File | Owner | Priority | Notes |
|---|---|---|---|---|
| 01 | `01-force-password-reset.sql` | DBA | TODAY | Nulls password hashes; sends everyone through /auth/password/forgot |
| 02 | `02-invalidate-all-sessions.sql` | DBA | TODAY | Bumps `token_version` → active JWTs die instantly (cheaper than rotating `JWT_SECRET`) |
| 03 | `03-rotate-encryption-key.md` | DBA + ops | THIS WEEK | Rotate `SECRETS_ENCRYPTION_KEY` + re-encrypt carrier creds |
| 04 | `04-carrier-credential-rotation.md` | Carrier admin | TODAY | Rotate keys in each carrier's developer portal |
| 05 | `05-user-breach-notification.md` | Comms / legal | GDPR 72h window | Draft email + timing/legal notes |
| 06 | `06-audit-log-queries.sql` | DBA | THIS WEEK | Investigate suspicious activity 2026-07-21 → now |
| 07 | `07-github-hardening.md` | Repo owner | THIS WEEK | Settings changes to prevent recurrence |
| — | `install-pre-commit-hook.sh` | Every dev | ONE-TIME | Blocks committing `pg_dump` files locally |

## Timeline reconstruction
- **2026-07-21 19:41** — `multiship_db_backup_20260721_145252.sql` + `multiship_db_dump.sql` created on disk
- **2026-08-16 07:55:27** — Commit `3d6378c` ("test(clientspage-filters)…") added them to `security/audit-fixes-sprint51`
- **2026-08-18 18:28:39** — Commit `b99dad8` ("new changes") added the Aug-17 dumps
- **2026-08-19** — Merge `ae93b44` pulled `security/audit-fixes-sprint51` → `dev`; blobs now on the public `dev` branch tip
- **2026-08-19 (later)** — Commit `d95e982` removed the 4 files from `dev`'s tree + added gitignore

## Ownership
- Overall: repo owner
- DB work (SQL, re-encryption): DBA / backend lead
- Carrier portals: whoever holds the accounts
- Comms: legal-reviewed template in `05`
