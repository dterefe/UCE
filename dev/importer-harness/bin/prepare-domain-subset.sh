#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
COUNT="${1:?usage: prepare-domain-subset.sh <count|all> [target]}"
SOURCE="${UCE_DOMAIN_FULL_SOURCE:-/mnt/bioFID/TTLabExports/2026_05_14_taxa_domains}"
VALID_LIST="${UCE_DOMAIN_VALID_LIST:-/mnt/bioFID/TTLabExports/2026_05_14_taxa_domains.valid-files.txt}"
TARGET="${2:-$ROOT/.dev/importer-harness/corpora/domain-${COUNT}}"

if [[ ! -d "$SOURCE" ]]; then
  echo "Source does not exist: $SOURCE" >&2
  exit 1
fi

rm -rf "$TARGET"
mkdir -p "$TARGET/input"

if [[ -f "$VALID_LIST" ]]; then
  if [[ "$COUNT" == "all" ]]; then
    mapfile -t FILES < <(sed '/^[[:space:]]*$/d' "$VALID_LIST")
  else
    mapfile -t FILES < <(sed '/^[[:space:]]*$/d' "$VALID_LIST" | head -n "$COUNT")
  fi
else
  if [[ "$COUNT" == "all" ]]; then
    mapfile -t FILES < <(find "$SOURCE" -type f -name '*.xmi.gz' | sort)
  else
    mapfile -t FILES < <(find "$SOURCE" -type f -name '*.xmi.gz' | sort | head -n "$COUNT")
  fi
fi

if [[ "${#FILES[@]}" -eq 0 ]]; then
  echo "No input files found in $SOURCE" >&2
  exit 1
fi

for file in "${FILES[@]}"; do
  if [[ "$file" != /* ]]; then
    file="$SOURCE/$file"
  fi
  if [[ ! -f "$file" ]]; then
    echo "Listed file does not exist: $file" >&2
    exit 1
  fi
  rel="${file#"$SOURCE"/}"
  mkdir -p "$TARGET/input/$(dirname "$rel")"
  cp "$file" "$TARGET/input/$rel"
done

type_system="$(find "$SOURCE" -maxdepth 1 -type f -name 'TypeSystem.xml.gz' -print -quit)"
if [[ -n "${type_system:-}" ]]; then
  cp "$type_system" "$TARGET/TypeSystem.xml.gz"
fi

cat > "$TARGET/corpusConfig.json" <<'JSON'
{
  "name": "biofid-domain-subset",
  "author": "Text Technology Lab",
  "language": "de-DE",
  "description": "Importer harness corpus for BioFID domain-annotated taxa data.",
  "annotations": {
    "annotatorMetadata": false,
    "uceMetadata": true,
    "logicalLinks": false,
    "OCRPage": true,
    "OCRParagraph": true,
    "OCRBlock": true,
    "OCRLine": true,
    "taxon": {
      "annotated": true,
      "biofidOnthologyAnnotated": true
    },
    "srLink": false,
    "lemma": false,
    "namedEntity": true,
    "geoNames": true,
    "sentence": true,
    "time": true,
    "sentiment": false,
    "emotion": false,
    "wikipediaLink": false,
    "completeNegation": false,
    "cue": false,
    "event": false,
    "focus": false,
    "scope": false,
    "unifiedTopic": false,
    "pages": true,
    "images": true,
    "permissions": false,
    "biofidDomains": true,
    "uceDomains": true
  },
  "s3": {
    "uploadXmi": false
  },
  "other": {
    "availableOnFrankfurtUniversityCollection": false,
    "includeKeywordDistribution": false,
    "enableEmbeddings": false,
    "enableRAGBot": false,
    "enableS3Storage": false
  }
}
JSON

echo "Prepared ${#FILES[@]} domain XMI files in $TARGET"
