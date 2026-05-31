# UCE Import Domain Graph And DUUI Importer Report

## 0. Correction Of The Model

The earlier version treated `UCECorpus`, `UCEDocument`, and related domains too much like a single canonical identity layer. That is too simple.

UCE needs to distinguish:

- **storage identity**: database primary keys and persisted implementation records
- **import identity**: one concrete import attempt with a concrete config, path, view, and runtime
- **conceptual equivalence**: two imported corpora/documents may be equivalent even if their concrete import identities differ
- **procedural state**: which importer step ran, failed, retried, skipped, or produced data
- **annotation identity**: which UIMA feature structures were observed and materialized

Therefore, the graph should not force “same corpus” to mean “same import.” It should allow two imports to be different but still equivalent.

Example:

```text
Import A:
  same corpus source
  default view
  spacy + gnfinder

Import B:
  same corpus source
  default view
  spacy + gnfinder + taxonerd
```

These should not collapse into one indistinguishable import result. But they also should not be treated as unrelated corpora. The graph should preserve both:

```text
UCECorpus(A) --equivalent_to--> UCECorpus(B)
UCEImport(A) --imports--> UCECorpus(A)
UCEImport(B) --imports--> UCECorpus(B)
```

The purpose of the UCE graph is therefore not only deduplication. It is controlled identity, equivalence, provenance, and recovery.

## 1. Domain Types To Add To UIMATypeSystem

These should be added to the local `UIMATypeSystem` Java project as real UIMA types, like the existing BIOFID domain annotations.

Package should likely be:

```text
org.texttechnologylab.annotation.uce
```

There are already Java model/artifact classes with overlapping simple names:

- `org.texttechnologylab.uce.common.models.imp.UCEImport`
- `org.texttechnologylab.uce.corpusimporter.pipeline.artifact.UCEImport`
- `org.texttechnologylab.uce.corpusimporter.pipeline.artifact.UCECorpus`
- `org.texttechnologylab.uce.corpusimporter.pipeline.artifact.UCEDocument`

That is not fatal. The UIMA type-system classes can still be:

```text
org.texttechnologylab.annotation.uce.UCEImport
org.texttechnologylab.annotation.uce.UCECorpus
org.texttechnologylab.annotation.uce.UCEDocument
org.texttechnologylab.annotation.uce.UCEPage
org.texttechnologylab.annotation.uce.UCEView
org.texttechnologylab.annotation.uce.UCEType
org.texttechnologylab.annotation.uce.UCEAnnotation
org.texttechnologylab.annotation.uce.UCEOperation
```

The package distinguishes them from database models and DUUI artifacts.

## 2. Core UCE Domains

### 2.1 UCEImport

`UCEImport` is a transient procedural domain for one import attempt.

It should capture:

- import id
- source path or upload origin
- corpus config path/content hash
- selected CAS view
- importer version
- DUUI pipeline version
- start/end timestamps
- status
- failure state
- retry count or attempt number

It should link to:

- `UCECorpus`
- `UCEView`
- every `UCEDocument` it touched
- every `UCEOperation` it executed
- every `UCEAnnotation` type/instance observed or produced

Important:

`UCEImport` is not the stable corpus identity. It is the run ledger.

### 2.2 UCECorpus

`UCECorpus` is a concrete imported corpus domain.

It should not pretend that one corpus identity can be decided by one simple field. Instead, it should capture the concrete import definition:

- corpus config identity-relevant fields
- corpus config full hash
- import source path or source identifier
- selected view
- annotation configuration
- preprocessing/input state

Equivalence between corpora should be modeled explicitly:

```text
UCECorpus --equivalent_to--> UCECorpus
```

This lets UCE say:

- these two imports are conceptually the same corpus
- but they were imported with different views
- or different annotation layers
- or different runtime settings
- or different source paths

### 2.3 UCEDocument

`UCEDocument` is a concrete imported document domain.

It should capture:

- original `DocumentMetaData.documentId`
- current numeric legacy `Document.documentId`, if needed
- source relative path
- source absolute path, if useful as runtime provenance
- source file hash
- CAS view
- document title
- language
- document content hash

Like corpora, documents may be equivalent without being identical concrete imports:

```text
UCEDocument --equivalent_to--> UCEDocument
```

Examples:

- same document imported from a different folder
- same document imported from a different view
- same document imported after additional annotations were added
- same document imported after OCR changes

### 2.4 UCEPage

`UCEPage` is a concrete page domain.

It should capture:

- parent `UCEDocument`
- page identifier
- page number
- begin/end offsets
- OCR page id if present
- source page id if present
- page content hash if available

Page number alone is not identity.

Correct shape:

```text
UCEPage:<documentIdentity>:<pageIdentity>
```

### 2.5 UCEView

`UCEView` is needed because import from a different CAS view can produce different output from the same corpus and same document.

It should capture:

- view name
- Sofa mime type
- view source relation, if known
- whether it was default view or explicitly selected

Relationships:

```text
UCEImport --uses_view--> UCEView
UCECorpus --has_view--> UCEView
UCEDocument --imported_from_view--> UCEView
```

### 2.6 UCEType

`UCEType` represents a UIMA feature-structure type recognized during import.

It is a domain because UIMA types are conceptual entities in UCE’s import universe. They can be associated with imports, corpora, documents, operations, and concrete annotations without pretending that a plain string is a graph node.

It should capture:

- fully qualified UIMA type name
- short/simple type name
- package
- supertype, if available
- whether it is span-based
- whether UCE knows how to persist it into relational storage
- optional target table name as metadata, not as an association target

Relationships:

```text
UCEImport --observed_type--> UCEType
UCECorpus --contains_type--> UCEType
UCEDocument --contains_type--> UCEType
UCEAnnotation --has_type--> UCEType
UCEOperation --observed_type--> UCEType
```

## 3. UCEAnnotation

### 3.1 Final Direction

`UCEAnnotation` should represent individual UIMA feature structures, not type names.

Type identity belongs to `UCEType`. A `UCEAnnotation` points to the relevant `UCEType`.

Each observed/imported feature structure can become:

```text
UCEAnnotation
```

with properties:

- UIMA type name
- begin/end, when span-based
- covered text, when span-based
- normalized value, when available
- feature hash
- source document identity
- source page identity, when resolvable
- optional implementation metadata, such as target table and persisted row id

### 3.2 Annotation Identity

For span annotations, identity can be weakly semantic:

```text
UCEAnnotation:<uimaType>:<documentIdentity>:<view>:<begin>:<end>:<valueHash>
```

For non-span feature structures:

```text
UCEAnnotation:<uimaType>:<documentIdentity>:<view>:<featureHash>
```

This allows repeated imports to resolve the same annotation when it is genuinely the same feature structure.

For example, a taxon annotation with the same:

- document identity
- view
- UIMA type
- span
- covered text/value
- feature payload

should resolve to the same `UCEAnnotation` vertex. A new import should add new provenance/operation edges to the existing annotation vertex instead of blindly creating an unrelated duplicate.

### 3.3 Type-Level Annotation Coverage

The type-level question is still important:

```text
Which annotation types exist in this import?
Which annotation types exist in this corpus?
Which annotation types exist in this document?
How many instances exist?
Which types were expected but missing?
```

This must not be modeled as associations to non-domain values.

Type-level coverage is modeled through `UCEType` domains and summary properties on the association.

Examples:

```text
UCEImport --observed_type {count=1832}--> UCEType(Token)
UCECorpus --contains_type {count=102}--> UCEType(Sentence)
UCEDocument --contains_type {count=4}--> UCEType(GnFinderTaxon)
```

Expected and missing types can be recorded as properties on `UCEOperation` or as domain-to-domain associations to `UCEType`:

```text
UCEOperation --expected_type--> UCEType
UCEOperation --missing_type--> UCEType
```

## 4. UCEOperation

`UCEOperation` is the procedural domain that captures stage/step execution.

This replaces vague procedural tracking with explicit operation domains.

Examples:

```text
load-corpus-config
ensure-corpus
discover-documents
load-jcas
select-view
extract-document
extract-pages
extract-sentences
extract-taxa
persist-document
persist-domain-graph
postprocess-document
embed-document
refresh-lexicon
refresh-logical-links
finalize-corpus
```

Each operation should capture:

- operation id
- operation name
- pipeline stage
- component id
- status
- start/end time
- duration
- attempt number
- retry count
- error type/message/stack summary
- input domain ids
- output domain ids
- whether it was skipped, completed, cancelled, failed, or retried

Operation statuses:

```text
PENDING
RUNNING
COMPLETED
SKIPPED
FAILED
CANCELED
RETRYING
DIRTY
```

Relationships:

```text
UCEImport --ran--> UCEOperation
UCEOperation --uses--> UCECorpus / UCEDocument / UCEPage / UCEType / UCEAnnotation
UCEOperation --creates_or_updates--> UCECorpus / UCEDocument / UCEPage / UCEType / UCEAnnotation
UCEOperation --failed_on--> UCEDocument
UCEOperation --observed_type--> UCEType
UCEOperation --observed--> UCEAnnotation
```

This gives UCE a way to answer:

- did lemma extraction finish for this document?
- did taxon extraction fail?
- did embeddings run?
- can this import resume?
- which exact step observed or imported this domain?
- which step must be retried?

## 5. Conceptual Equivalence

Identity and equivalence must be separate.

### 5.1 Corpus Equivalence

Two `UCECorpus` domains may be equivalent if they share enough identity-defining facts, even if concrete import details differ.

Equivalence bases:

- same configured corpus identity
- same normalized corpus config identity subset
- same source dataset id
- same source path after normalization
- same corpus content hash
- user-declared equivalence

Association:

```text
UCECorpus --equivalent_to {basis, confidence, declaredBy}--> UCECorpus
```

### 5.2 Document Equivalence

Two `UCEDocument` domains may be equivalent if they represent the same conceptual document.

Equivalence bases:

- same original `DocumentMetaData.documentId`
- same source hash
- same relative path under equivalent corpora
- same title/source metadata
- user-declared equivalence

Association:

```text
UCEDocument --equivalent_to {basis, confidence, declaredBy}--> UCEDocument
```

### 5.3 Why This Matters

If a corpus is imported twice with additional annotations, UCE should preserve:

```text
Import A produced Corpus A with annotation types X, Y.
Import B produced Corpus B with annotation types X, Y, Z.
Corpus A is equivalent to Corpus B by source/config identity.
```

That is better than either:

- collapsing them completely and losing procedural difference
- treating them as unrelated and losing conceptual sameness

## 6. Pipeline Case Taxonomy

### 6.1 Corpus Cases

| Case | Decision Needed |
|---|---|
| no matching corpus | create new `UCECorpus` |
| same concrete import identity | resume / skip / replace |
| equivalent conceptual corpus, different annotations | create new concrete `UCECorpus`, add equivalence |
| same source, different view | create new concrete `UCECorpus`, link shared/equivalent corpus |
| same name, different source/config | reject or require explicit equivalence |
| old DB corpus by name only | migrate to graph-backed decision |

### 6.2 Document Cases

| Case | Decision Needed |
|---|---|
| no matching document | import |
| same concrete document identity complete | skip |
| same concrete document identity incomplete | resume failed/missing operations |
| equivalent document with different view | import as separate concrete `UCEDocument`, link equivalence |
| equivalent document with additional annotations | import/update annotation graph, preserve equivalence |
| changed source hash | reject / replace / version |
| failed previous import | retry failed operations or rollback per policy |

### 6.3 Annotation Cases

| Case | Decision Needed |
|---|---|
| same annotation identity already exists | add import/operation provenance edge |
| same span/type but changed features | version, replace, or create distinct annotation |
| annotation expected but missing | mark operation incomplete/failed/dirty |
| annotation persisted outside AGE | store persistence details as properties on `UCEAnnotation` or `UCEOperation` |
| annotation deleted/replaced | mark graph state and cleanup implementation storage separately |

### 6.4 Operation Cases

| Case | Decision Needed |
|---|---|
| operation completed | do not repeat unless policy says force |
| operation failed | retry or abort |
| operation skipped | record why |
| operation partially materialized | cleanup/retry from checkpoint |
| operation dirty | recompute |
| operation canceled | resume/cancel/rollback by policy |

## 7. Config Surface

### 7.1 Corpus Identity And Equivalence

```yaml
identity:
  corpus:
    concrete:
      include:
        - corpusConfig.fullHash
        - source.normalizedPath
        - view.name
        - annotationConfig.hash
    equivalence:
      strategy: config-subset
      fields:
        - name
        - author
        - language
        - source.datasetId
      onEquivalentExisting: create-concrete-and-link
```

### 7.2 Document Identity And Equivalence

```yaml
identity:
  document:
    concrete:
      strategy:
        - documentMetaData.documentId
        - view.name
        - source.fileHash
    equivalence:
      strategy:
        - documentMetaData.documentId
        - source.fileHash
        - normalizedRelativePath
      onEquivalentExisting: create-concrete-and-link
```

### 7.3 Operation Policy

```yaml
operations:
  onCompleted: skip
  onFailed: retry
  onPartialMaterialization: cleanup-and-retry
  onCanceled: resume
  retry:
    maxAttempts: 3
    backoff: exponential
```

### 7.4 Annotation And Type Policy

```yaml
annotations:
  types:
    includeAllObservedTypes: true
    countPerImport: true
    countPerCorpus: true
    countPerDocument: true
  identity:
    spanBased:
      - uimaType
      - documentIdentity
      - view
      - begin
      - end
      - valueHash
    featureBased:
      - uimaType
      - documentIdentity
      - view
      - featureHash
  onExisting: attach-provenance
  onChangedFeatures: version
```

## 8. Current UCE Data Mutation Map

Important rule:

```text
Associations are only between domains.
```

Normal implementation objects are not graph association targets:

- Hibernate rows
- vector rows
- S3 objects
- logs
- SQL-derived tables
- external service resources

Those can be mentioned as properties, persistence metadata, or cleanup obligations, but not as graph nodes unless they are promoted to actual domain types. The current model does not promote them.

### 8.1 Import Logs

Current models:

- `org.texttechnologylab.uce.common.models.imp.UCEImport`
- `ImportLog`

Current problem:

- logs are tied to `importId`, but not to graph domains or exact operations.

Graph correction:

```text
UCEImport --ran--> UCEOperation
UCEOperation records log/procedure metadata as properties
```

### 8.2 Corpus

Current model:

- `Corpus`

Current problem:

- corpus lookup is name/config behavior, not graph-governed identity/equivalence.

Graph correction:

```text
UCEImport --imports--> UCECorpus
UCECorpus --equivalent_to--> UCECorpus
```

Implementation notes:

- the `Corpus` database row id can be stored as a property on `UCECorpus`
- the corpus config hash can be stored as a property on `UCECorpus`
- neither `Corpus` nor a config file path is an association target

### 8.3 Document And Cascaded Annotations

Current model:

- `Document`
- pages
- sentences
- named entities
- geonames
- taxa
- times
- lemmata
- sentiments
- emotions
- negations
- topics
- images
- permissions
- metadata

Current problem:

- rows are mostly document-scoped, but operation provenance is not preserved.
- duplicate/retry semantics are too coarse.

Graph correction:

```text
UCECorpus --contains--> UCEDocument
UCEDocument --contains--> UCEPage
UCEImport --observed_type--> UCEType
UCECorpus --contains_type--> UCEType
UCEDocument --contains_type--> UCEType
UCEAnnotation --has_type--> UCEType
UCEDocument --contains--> UCEAnnotation
UCEPage --contains--> UCEAnnotation
UCEOperation --observed--> UCEAnnotation
```

Implementation notes:

- database row ids can be stored as properties on the corresponding domain
- table names can be stored as properties on `UCEAnnotation`
- rows themselves are not domains

### 8.4 Embeddings

Current service:

- `EmbeddingService`

Current problem:

- vector tables are not cleaned by `deleteDocumentById`.
- embedding completion is not represented as graph state.

Graph correction:

```text
UCEOperation(embed-document) --uses--> UCEDocument
UCEOperation(embed-document) --updates--> UCEDocument
```

Implementation notes:

- embedding ids, chunk counts, and vector table details are operation/domain properties
- vector rows are not association targets
- completion/failure belongs to `UCEOperation`

### 8.5 S3

Current service:

- `S3StorageService`

Current problem:

- source XMI upload failure is only a warning.
- S3 object lifecycle is not tied to document replacement/deletion.

Graph correction:

```text
UCEOperation(upload-source-xmi) --uses--> UCEDocument
UCEOperation(upload-source-xmi) --updates--> UCEDocument
```

Implementation notes:

- S3 object name and upload status are properties
- S3 objects are not domains in this model

### 8.6 Lexicon / Logical Links / Geonames / Topic SQL

Current state:

- global or corpus-level refresh operations
- derived from relational annotation tables
- not represented as completed/dirty/failed graph state

Graph correction:

```text
UCEOperation(refresh-lexicon) --uses--> UCECorpus
UCEOperation(refresh-logical-links) --uses--> UCECorpus
UCEOperation(refresh-geonames) --uses--> UCECorpus
UCEOperation(refresh-topic-tables) --uses--> UCECorpus
```

Implementation notes:

- derived table names, refresh counts, and dirty flags are operation/corpus properties
- SQL tables are not domains

## 9. Completing DUUIImporter To Legacy Equivalence

The DUUI importer must become semantically and output-wise equivalent to the legacy `Importer`.

Current problem:

```text
DUUIImporter.xmiToDocument(...)
```

only creates basic `Document` state. That is not equivalent.

### 9.1 Required Legacy Extraction Parity

DUUI importer must implement the full canonical `XMIToDocument(...)` behavior:

- load/select JCas view exactly as legacy importer
- read `DocumentMetaData`
- preserve original document id separately
- apply legacy numeric id compatibility only where needed
- duplicate document decision by policy, not hardcoded skip only
- set mime type
- set PDF/image bytes
- set full text
- set cleaned full text
- set metadata title info
- optional S3 upload
- set UCEMetadata
- set sentences
- set named entities
- set geonames
- set sentiments
- set emotions
- set lemmata
- set semantic role labels
- set times
- set taxonomy
- set wiki links
- set complete negations
- set unified topics
- set logical links
- set pages
- set images
- set permissions
- preserve legacy import log behavior
- preserve legacy postprocessing behavior
- preserve batch and final corpus postprocessing behavior

### 9.2 Required DUUI Pipeline Shape

The importer should not be a flat method clone. It should split canonical semantics into DUUI operations:

```text
UCEImport
  runtime-prepare
  -> UCECorpus
       corpus-config
       ensure-corpus
       -> UCEDocument fork
            discover-document
            load-jcas
            select-view
            identify-document
            extract-document-core
            extract-annotations
            persist-document
            persist-age-graph
            postprocess-document
       corpus-finalize
  runtime-finalize
```

Each operation should create/update a `UCEOperation` graph node.

### 9.3 Required AGE Materialization

`persistDomainAssociationGraph(...)` must stop being a document-node placeholder.

It must materialize:

- `UCEImport`
- `UCECorpus`
- `UCEDocument`
- `UCEPage`
- `UCEView`
- `UCEType`
- `UCEAnnotation`
- `UCEOperation`
- BIOFID domain annotations
- BIOFID association annotations
- domain-to-domain associations between UCE operational domains and BIOFID conceptual domains

### 9.4 Required Output Equivalence Checks

For a test corpus, legacy and DUUI import should match:

- document count
- page count
- sentence count
- named entity count
- geoname count
- taxon counts by source
- metadata count
- image count
- permission count
- link count
- topic/negation counts when enabled
- postprocessed flag
- import log shape
- S3 behavior when enabled
- embedding behavior when enabled
- AGE graph materialization in addition to relational parity

## 10. Deletion / Replacement Semantics

### 10.1 Delete UCEDocument

Must use `UCEDocument` identity and operation provenance.

Cleanup targets:

- `Document`
- pages
- all document-owned annotations
- links involving document/annotations
- vector embedding storage cleanup
- S3 source object cleanup
- AGE operational subgraph
- BIOFID/domain associations scoped only to that document
- mark corpus-level derived operations dirty

### 10.2 Delete UCECorpus

Cleanup targets:

- all `UCEDocument` cleanup targets
- `Corpus`
- corpus metadata filters
- corpus-level derived tables
- AGE `UCECorpus` operational graph
- corpus-level equivalence edges, depending on policy
- import history retained or tombstoned by policy

### 10.3 Retry Failed Operation

Retry should not mean “run the whole import again blindly.”

It should mean:

```text
find failed UCEOperation
inspect domain inputs/outputs
cleanup partial outputs if needed
retry operation
update operation status
continue dependent operations
```

## 11. Implementation Notes For Type System

Add the domain classes to `UIMATypeSystem`:

```text
org.texttechnologylab.annotation.uce.UCEImport
org.texttechnologylab.annotation.uce.UCECorpus
org.texttechnologylab.annotation.uce.UCEDocument
org.texttechnologylab.annotation.uce.UCEPage
org.texttechnologylab.annotation.uce.UCEView
org.texttechnologylab.annotation.uce.UCEType
org.texttechnologylab.annotation.uce.UCEAnnotation
org.texttechnologylab.annotation.uce.UCEOperation
```

They should extend the same domain base used by BIOFID:

```text
org.texttechnologylab.annotation.domain.Domain
```

Operational relationships should use existing association classes where possible:

- `Membership`
- `Sequence`
- `Reference`
- `Equivalence`

If operation-specific association detail is too awkward as generic `Association.metadata`, add a UCE-specific association subtype later. Do not add it before the current model proves insufficient.

## 12. Current State Summary

### Existing Pieces

- legacy importer has full extraction semantics
- DUUI importer has pipeline skeleton
- AGE service has `MERGE`-based node/edge upsert
- BIOFID domain annotations exist
- BIOFID domain annotator creates conceptual bibliographic domains

### Missing Pieces

- UCE operational UIMA domain types
- `UCEType`
- `UCEAnnotation`
- `UCEOperation`
- UCE graph annotator inside DUUI import
- full legacy extraction parity in DUUI importer
- full AGE materialization from JCas domains/associations
- operation status tracking
- configurable identity/equivalence policy
- deletion/replacement/retry semantics over graph domains

### Correct Direction

```text
Do not collapse conceptual equivalence into concrete identity.
Do not treat imports as anonymous side effects.
Do not rely on DB primary keys as semantic identity.
Do not leave importer steps untraced.

Use UCEImport for procedural run metadata.
Use UCECorpus / UCEDocument / UCEPage for concrete imported domains.
Use UCEView to distinguish view-specific imports.
Use UCEType for UIMA feature-structure type domains.
Use UCEAnnotation for individual feature structures.
Use UCEOperation for pipeline/procedure/stage state.
Use AGE to preserve identity, equivalence, provenance, and recovery state.
Complete DUUIImporter until it matches legacy importer output exactly.
```
