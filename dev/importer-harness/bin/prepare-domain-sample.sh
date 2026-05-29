#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
COUNT="${1:-2}"
SOURCE="${UCE_DOMAIN_SAMPLE_SOURCE:-/mnt/bioFID/TTLabExports/2026_05_14_taxa_domains_sample_check}"
TARGET="${UCE_DOMAIN_SAMPLE_TARGET:-$ROOT/.dev/importer-harness/corpora/domain-sample}"

if [[ ! -d "$SOURCE" ]]; then
  echo "Source does not exist: $SOURCE" >&2
  exit 1
fi

rm -rf "$TARGET"
mkdir -p "$TARGET/input"

mapfile -t FILES < <(find "$SOURCE" -type f -name '*.xmi.gz' | sort | head -n "$COUNT")

if [[ "${#FILES[@]}" -eq 0 ]]; then
  echo "No .xmi.gz files found in $SOURCE" >&2
  exit 1
fi

for file in "${FILES[@]}"; do
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
  "name": "biofid-domain-sample",
  "author": "Text Technology Lab",
  "language": "de-DE",
  "description": "Importer harness corpus for BioFID domain-annotated sample data.",
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

echo "Prepared ${#FILES[@]} sample XMI files in $TARGET"
