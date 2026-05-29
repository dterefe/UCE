#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
cd "$ROOT"

SAMPLE="$ROOT/.dev/importer-harness/corpora/domain-sample"
TEN="$ROOT/.dev/importer-harness/corpora/domain-10"
FIFTY="$ROOT/.dev/importer-harness/corpora/domain-50"

dev/importer-harness/bin/prepare-domain-sample.sh 2 "$SAMPLE"
dev/importer-harness/bin/prepare-domain-subset.sh 10 "$TEN"
dev/importer-harness/bin/prepare-domain-subset.sh 50 "$FIFTY"

dev/importer-harness/bin/run-profile.sh C1 "$SAMPLE" 1 4 reset
dev/importer-harness/bin/run-profile.sh C2 "$SAMPLE" 1 4 reuse
dev/importer-harness/bin/run-profile.sh C3 "$TEN" 1 4 reset
dev/importer-harness/bin/run-profile.sh C4 "$TEN" 2 4 reset
dev/importer-harness/bin/run-profile.sh C5 "$FIFTY" 4 8 reset
