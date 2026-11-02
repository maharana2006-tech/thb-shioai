#!/usr/bin/env bash
# R2-BUG-005 — concurrent-duplicate retest probe.
#
# Fires N parallel POSTs to /api/v1/external/shipments with the SAME
# reference. The Redis SETNX in CarrierServiceImpl should let exactly
# one win (201) and the rest 409 LABEL_ALREADY_GENERATED. After V173,
# a Redis outage also still 409s via the DB UNIQUE on new shipments.
#
# Usage:
#   API_KEY=sk-... BASE_URL=http://localhost:8080 ./dup-ref-concurrent-probe.sh [count] [reference]
#
# count defaults to 3; reference defaults to a random one-shot.
#
# Expected output (Redis UP + fresh ref):
#   201 one succeeded
#   409 others blocked by Redis SETNX
#
# A result like "201 201" (two winners) means both Redis SETNX AND
# the DB floor missed — reopen R2-BUG-005.

set -u
: "${API_KEY:?API_KEY required (TEST key with SHIPMENTS scope)}"
: "${BASE_URL:=http://localhost:8080}"

COUNT="${1:-3}"
REF="${2:-R2-BUG-005-$(date +%s)-$RANDOM}"
CLIENT_CODE="${CLIENT_CODE:-MKL246}"
SHIP_METHOD="${SHIP_METHOD:-P80}"

body=$(cat <<JSON
{
  "clientCode": "$CLIENT_CODE",
  "reference": "$REF",
  "shipMethod": "$SHIP_METHOD",
  "shipTo": {
    "name": "R2BUG005 Recipient",
    "addressLine1": "350 5th Ave",
    "city": "New York",
    "state": "NY",
    "postalCode": "10118",
    "countryCode": "US"
  },
  "parcel": {"weight": 1, "length": 10, "width": 8, "height": 4}
}
JSON
)

echo "Firing $COUNT concurrent POSTs for reference '$REF' ..."
tmpdir=$(mktemp -d)
trap 'rm -rf "$tmpdir"' EXIT

for i in $(seq 1 "$COUNT"); do
  (
    curl -sS -o "$tmpdir/body-$i" -w "%{http_code}" \
      -H "X-API-Key: $API_KEY" \
      -H "Content-Type: application/json" \
      -H "Idempotency-Key: $REF-$i-$RANDOM" \
      "$BASE_URL/api/v1/external/shipments" \
      -d "$body" > "$tmpdir/status-$i"
  ) &
done
wait

echo
echo "Results:"
successes=0
conflicts=0
for i in $(seq 1 "$COUNT"); do
  code=$(cat "$tmpdir/status-$i")
  snippet=$(head -c 200 "$tmpdir/body-$i")
  printf "  attempt %d → HTTP %s   %s\n" "$i" "$code" "$snippet"
  case "$code" in
    201) successes=$((successes + 1)) ;;
    409) conflicts=$((conflicts + 1)) ;;
  esac
done

echo
if [ "$successes" -eq 1 ] && [ "$conflicts" -eq $((COUNT - 1)) ]; then
  echo "PASS — 1 × 201, $conflicts × 409. Dup-guard is working."
  exit 0
else
  echo "FAIL — expected 1 × 201 + $((COUNT - 1)) × 409, got $successes × 201 + $conflicts × 409."
  exit 1
fi
