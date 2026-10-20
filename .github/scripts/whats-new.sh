#!/usr/bin/env bash
# Prints a "## What's new" markdown list for the commits since the previous release tag.
# Each commit contributes its "- " bullet lines (continuation lines joined); commits without
# bullets contribute their subject. Attribution trailers are skipped. The app reads this
# section to show what changed in the update card.
set -euo pipefail
prev=$(git describe --tags --abbrev=0 --match 'v*' HEAD^ 2>/dev/null || true)
range=${prev:+$prev..}HEAD
echo "## What's new"
echo
git log --no-merges --format='%x1e%s%x1f%b' "$range" | awk '
  BEGIN { RS = "\x1e"; FS = "\x1f" }
  NF == 0 { next }
  {
    subject = $1; n = split($2, lines, "\n"); out = ""; cur = ""
    for (i = 1; i <= n; i++) {
      l = lines[i]
      if (l ~ /^(Co-Authored-By|Claude-Session|Signed-off-by):/) continue
      if (l ~ /^- /) { if (cur != "") out = out cur "\n"; cur = l }
      else if (cur != "" && l ~ /^  +[^ ]/) { sub(/^ +/, "", l); cur = cur " " l }
      else if (cur != "") { out = out cur "\n"; cur = "" }
    }
    if (cur != "") out = out cur "\n"
    if (out == "") out = "- " subject "\n"
    printf "%s", out
  }'
