# 07 — GitHub settings to prevent recurrence

Repo owner action items. All under **Settings** on
https://github.com/maharana2006-tech/thb-shioai/settings.

## 1. Enable secret scanning + push protection (free for public repos)

Settings → **Code security and analysis** → enable:

- ✅ **Secret scanning** — catches API tokens, SSH keys, cloud provider
  keys, private keys in the repo *after* they're pushed.
- ✅ **Push protection** — blocks pushes that would introduce known-
  secret patterns *before* they hit the remote. Would not have caught
  the pg_dump files (they're raw SQL, no obvious secret pattern), but
  covers the more common footgun.
- ✅ **Dependency review**
- ✅ **Code scanning** (CodeQL) — one-click enable, no config
  needed. Runs on every PR + weekly.

## 2. Add branch protection to `dev`

Settings → **Branches** → **Add branch protection rule** → pattern `dev`:

- ✅ Require a pull request before merging
- ✅ Require at least 1 approving review
- ✅ Dismiss stale reviews when new commits are pushed
- ✅ Require status checks to pass (`backend-ci`, `frontend-ci`)
- ✅ Require branches to be up to date before merging
- ✅ Do not allow bypassing the above settings (even for admins) —
  make this **strict**; the emergency-remediation commit `d95e982`
  bypassed via direct-push, but future emergencies should still go
  through PR (with a break-glass reviewer).

Note: the direct-push we used for the incident response would have
been blocked by this rule. That's fine — we can use `--admin` on
`gh pr merge` for future emergency situations, but the rule stays
on so accidents can't slip through.

## 3. Add a CODEOWNERS file

Create `.github/CODEOWNERS`:
```
# Any commits touching secrets / crypto / DB migrations get a security review.
/backend/src/main/resources/db/migration/  @maharana2006-tech
/backend/src/main/java/com/multiship/backend/config/CryptoService.java  @maharana2006-tech
/backend/src/main/java/com/multiship/backend/config/JwtService.java  @maharana2006-tech
/backend/src/main/java/com/multiship/backend/config/SecurityConfig.java  @maharana2006-tech
/.env.example  @maharana2006-tech
/.gitignore  @maharana2006-tech

# Anything at repo root — catches pg_dump / random binaries.
/*.sql  @maharana2006-tech
/*.pg_dump  @maharana2006-tech
```

Combined with branch protection's "Require review from Code Owners",
any PR touching those paths requires your approval.

## 4. Repo visibility

The repo is currently **public**. Options:

- **Keep public**: this is fine for genuinely-OSS projects, but every
  commit + branch is world-readable. The Aug-19 incident happened
  because a private-branch merge exposed private data to a public
  branch.
- **Switch to private**: Settings → **General** → "Change visibility"
  → Private. Kills the "anyone can clone" attack surface entirely.
  You lose OSS-community discovery + integrations that require public
  (like Dependabot on some tiers, though Dependabot works on private
  too for most cases).

**Recommendation**: unless this is being marketed as OSS, private is
the safer default. You can always re-open specific slices as separate
public repos if needed.

## 5. Enable Dependabot

Settings → **Code security and analysis** → **Dependabot alerts** + updates.
Auto-PRs for dependency upgrades with known CVEs. We already found (via
the dep-vulnerability audit) that both backend + frontend are on modern
versions; Dependabot keeps that current.

## 6. Historical: what could have caught the pg_dump commit?

- **Push protection with a custom pattern** — GitHub Enterprise Cloud
  supports custom secret-scanning patterns; you could add a regex for
  pg_dump file signature (`-- PostgreSQL database dump` in the file
  header). Free public repos don't have this; you'd need paid or the
  local pre-commit hook (see `install-pre-commit-hook.sh` in this dir).
- **Pre-commit hook** — see the install script. Runs locally on
  every dev's machine; blocks the commit before it hits any remote.
