#!/usr/bin/env bash
# Installs a repo-local pre-commit hook that blocks pg_dump files.
#
# Every dev on the repo should run this once. The hook lives in
# .git/hooks/pre-commit which is per-clone (git does not track hooks
# themselves — they're intentionally local so a malicious commit
# can't ship a malicious hook).
#
# Usage:
#     ./docs/incident-2026-08-19/install-pre-commit-hook.sh
#
# Uninstall:
#     rm .git/hooks/pre-commit
#
# What it blocks:
#   · multiship_db_*.sql at any depth (the exact 2026-08-19 exposure)
#   · *.pg_dump / *_pgdump.sql (generic pg_dump-style output)
#   · Any file at repo root ending in .sql (Flyway migrations are
#     under backend/src/main/resources/db/migration/ so they're
#     unaffected)

set -euo pipefail

REPO_ROOT="$(git rev-parse --show-toplevel)"
HOOK="$REPO_ROOT/.git/hooks/pre-commit"

if [ -e "$HOOK" ]; then
    echo "⚠️  $HOOK already exists. Not overwriting."
    echo "    Merge the check below into your existing hook manually:"
    echo "    ---"
fi

cat > "$HOOK.new" <<'HOOK_EOF'
#!/usr/bin/env bash
# Pre-commit hook installed by docs/incident-2026-08-19/install-pre-commit-hook.sh
# Blocks the "pg_dump accidentally committed" class of mistake.

blocked=""
while IFS= read -r file; do
    case "$file" in
        multiship_db_*.sql)          blocked="$blocked  $file (pg_dump filename pattern)\n" ;;
        *.pg_dump)                   blocked="$blocked  $file (.pg_dump extension)\n" ;;
        *_pgdump.sql)                blocked="$blocked  $file (_pgdump.sql suffix)\n" ;;
    esac

    # Also reject any .sql file at repo root — Flyway migrations live
    # under backend/src/main/resources/db/migration/, so a root-level
    # .sql is almost certainly ad-hoc.
    case "$file" in
        */*)  ;;                     # has a slash → not root
        *.sql) blocked="$blocked  $file (root-level .sql — Flyway migrations belong under backend/src/main/resources/db/migration/)\n" ;;
    esac
done < <(git diff --cached --name-only --diff-filter=ACM)

if [ -n "$blocked" ]; then
    echo ""
    echo "❌ Commit blocked — the following files match pg_dump / bare-SQL patterns:"
    echo ""
    printf "$blocked"
    echo ""
    echo "  Real pg_dump files carry password hashes, carrier credentials, and PII."
    echo "  See docs/incident-2026-08-19/ for the 2026-08-19 incident that motivated this."
    echo ""
    echo "  If you're 100% sure this file is safe, override with:"
    echo "      git commit --no-verify"
    exit 1
fi
HOOK_EOF

chmod +x "$HOOK.new"

if [ -e "$HOOK" ]; then
    echo ""
    cat "$HOOK.new"
    echo "    ---"
    echo "Left as $HOOK.new — merge manually then rm the .new suffix."
else
    mv "$HOOK.new" "$HOOK"
    echo "✅ Installed pre-commit hook at $HOOK"
    echo "   Test with:  touch multiship_db_test.sql && git add multiship_db_test.sql && git commit -m test"
    echo "   Cleanup:    git reset && rm multiship_db_test.sql"
fi
