#!/bin/bash
# Downloads the latest block lists (StevenBlack hosts) into the app's assets at build time.
#   block_ads.txt   - ad, tracker, malware and pop-up domains
#   block_adult.txt - adult and gambling domains (not already in the ads list)
set -euo pipefail
OUT=app/src/main/assets
mkdir -p "$OUT"
TMP=$(mktemp -d)

curl -fsSL https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts -o "$TMP/base"
curl -fsSL https://raw.githubusercontent.com/StevenBlack/hosts/master/alternates/gambling-porn/hosts -o "$TMP/full"

extract() {
  awk '($1=="0.0.0.0" || $1=="127.0.0.1") && $2 != "" {print tolower($2)}' "$1" \
    | grep -v -E '^(0\.0\.0\.0|localhost|localhost\.localdomain|local|broadcasthost|ip6-.*)$' \
    | LC_ALL=C sort -u
}

extract "$TMP/base" > "$OUT/block_ads.txt"
extract "$TMP/full" | LC_ALL=C comm -23 - "$OUT/block_ads.txt" > "$OUT/block_adult.txt"

wc -l "$OUT/block_ads.txt" "$OUT/block_adult.txt"
# Fail the build if a list came out suspiciously small (download problem).
[ "$(wc -l < "$OUT/block_ads.txt")" -gt 10000 ]
[ "$(wc -l < "$OUT/block_adult.txt")" -gt 10000 ]
