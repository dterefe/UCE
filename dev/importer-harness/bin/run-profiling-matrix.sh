#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
cd "$ROOT"

FIFTY="$ROOT/.dev/importer-harness/corpora/domain-50"
HUNDRED="$ROOT/.dev/importer-harness/corpora/domain-100"

dev/importer-harness/bin/prepare-domain-subset.sh 50 "$FIFTY"
dev/importer-harness/bin/prepare-domain-subset.sh 100 "$HUNDRED"

dev/importer-harness/bin/run-profile.sh P1 "$FIFTY" 1 4 reset
dev/importer-harness/bin/run-profile.sh P2 "$FIFTY" 4 8 reset
dev/importer-harness/bin/run-profile.sh P3 "$FIFTY" 4 8 reset
dev/importer-harness/bin/run-profile.sh P4 "$FIFTY" 8 8 reset
dev/importer-harness/bin/run-profile.sh P5 "$HUNDRED" 4 8 reset
