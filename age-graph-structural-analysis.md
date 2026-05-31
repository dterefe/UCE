# AGE Graph Structural Analysis

## Core Question

Would making AGE a minimal node-edge store, while delegating complete feature/annotation storage elsewhere, definitively solve both goals?

- Maximum import throughput.
- Very fast runtime subgraph querying, traversing, and merging.

Short answer: **it is the right structural direction, but not a complete solution by itself**.

A thin graph is necessary because AGE is a poor place to store large annotation payloads, arbitrary feature JSON, and frequently rewritten metadata. But a thin graph only stays fast and correct if the delegated storage is designed as indexed, queryable projection storage, not just a generic blob dump.

The target should be:

```text
AGE graph:
  identity + topology + small routing keys

PostgreSQL relational/projection tables:
  searchable/filterable domain and annotation facts

Object/blob storage:
  full serialized annotation feature payloads, only loaded on demand
```

## Current UCE Graph Shape

Current `AgeGraphService` uses one vertex label and one edge label:

```text
FeatureStructure(uid, uimaType, corpusId, documentId, name, uri, metadata, features)
Association(uid, uimaType, corpusId, documentId, name, metadata, features)
```

It also maintains registry tables:

```text
uce_age_domain_node_registry(uid, age_id)
uce_age_association_edge_registry(uid, age_id)
```

The registry approach is good. It avoids expensive AGE `MERGE` by UID and lets the service resolve existing AGE ids through normal PostgreSQL indexes.

The expensive part is that graph elements still carry too much payload:

- `metadata`
- `features`
- sometimes large or arbitrary JSON
- fields that belong to display/search/runtime projections rather than topology

For current domain nodes this may be acceptable at small scale. For `UCEAnnotation` it becomes structurally wrong: annotation volume is high, features are heterogeneous, and import throughput will be dominated by JSON construction, agtype conversion, storage bloat, and update pressure.

## AGE Strengths And Weaknesses

AGE is good for:

- Explicit domain identity.
- Deduplicated domain nodes.
- Direct domain-to-domain associations.
- Traversals from known seeds.
- Subgraph expansion.
- Runtime scoping through graph neighborhoods.
- Merging equivalent domains when identity is stable.

AGE is not good for:

- Full annotation feature storage.
- High-volume per-token or per-span payloads.
- Wide JSON property maps.
- Text search inside arbitrary features.
- Frequent mutation of large property maps.
- Serving tabular annotation payloads directly.
- Being the only source of truth for all imported data.

The graph should answer:

```text
What things exist?
How are they connected?
Which stable identity do they have?
Which corpus/document/page/import scope do they belong to?
Where do I fetch the full payload if I need it?
```

It should not answer:

```text
What is every feature value of every annotation?
What is every table row derived from every annotation?
What is every serialized feature structure?
```

## Thin Graph Model

### Node Minimum

Each AGE node should carry only stable graph/navigation fields:

```text
uid
kind/type
corpusId
documentId
pageId or pageUid, if applicable
payloadRef, optional
label/displayName, optional and short
```

For graph performance, the absolute minimum is:

```text
uid
uimaType/domainType
scope keys
```

`name` can stay only if it is small and used for display. `metadata` and `features` should not be primary graph properties.

### Edge Minimum

Each AGE edge should carry:

```text
uid
associationType
corpusId
documentId
payloadRef, optional
```

Edge features, operation details, annotation details, and arbitrary metadata should be delegated.

### Registry Tables Stay Mandatory

The registry tables are not an implementation detail; they are the identity backbone:

```text
uid -> AGE graph id
```

They make import idempotent and let PostgreSQL do what it is good at: indexed uniqueness and bulk conflict handling.

## Delegated Storage Model

Delegating storage elsewhere is only a solution if it is split by access pattern.

### 1. Projection Tables

Projection tables should hold fields needed for runtime filtering, display, counts, and correctness checks.

Example:

```text
uce_domain_projection
  uid primary key
  uima_type
  corpus_id
  document_id
  page_uid
  label
  uri
  payload_ref
  import_id_first_seen
  import_id_last_seen
  updated_at

uce_association_projection
  uid primary key
  uima_type
  corpus_id
  document_id
  left_uid
  right_uid
  label
  payload_ref
```

These tables should be indexed for:

- `uid`
- `corpus_id`
- `document_id`
- `uima_type`
- `(corpus_id, uima_type)`
- `(left_uid)`
- `(right_uid)`
- `(corpus_id, document_id)`

This allows runtime APIs to list, search, paginate, and count without repeatedly extracting agtype properties from AGE.

### 2. Annotation Fact Tables

If `UCEAnnotation` returns later, it should not mean "put full annotations into AGE".

Use a projected annotation table:

```text
uce_annotation_projection
  uid primary key
  uima_type
  corpus_id
  document_id
  page_uid
  begin_offset
  end_offset
  covered_text_hash
  covered_text_preview
  payload_ref
```

Possible indexes:

- `(corpus_id, document_id)`
- `(corpus_id, uima_type)`
- `(document_id, begin_offset, end_offset)`
- `(page_uid)`
- `covered_text_hash`

This keeps annotation operations fast while preserving identity.

### 3. Payload Storage

Full feature payloads should be stored outside AGE:

```text
uce_feature_payload
  payload_ref primary key
  payload_json jsonb
  payload_hash
  created_at
```

or, if payloads become too large:

```text
payload_ref -> object storage / compressed blob table
```

The payload ref is then stored in projection tables and optionally in AGE if needed for direct lookup.

## Import Throughput Analysis

### Current Bottleneck Pattern

The current service already does the right first step:

- Batch deduplication in Java.
- `COPY` into temp stage tables.
- Registry-based insert/update.
- Avoiding Cypher `MERGE` for bulk import.

Remaining bottlenecks:

- agtype property construction includes full `metadata` and `features`.
- AGE rows become wide.
- Updates rewrite entire AGE property maps.
- Runtime queries repeatedly extract agtype properties.
- Annotation-scale persistence would multiply this cost heavily.
- Operation nodes currently include detailed status data in graph properties.

### Why Thin Graph Helps

Thin graph import reduces:

- bytes copied into staging tables
- agtype conversion cost
- heap pressure in Java string builders
- database row width
- WAL volume
- update cost
- cache churn
- query extraction overhead

For high-volume imports, graph writes become closer to:

```text
insert uid/type/scope node
insert uid/type/scope edge
```

instead of:

```text
serialize arbitrary feature map
escape JSON
convert JSON to agtype
rewrite graph property maps
extract agtype for runtime views
```

### Why Thin Graph Alone Is Not Enough

If all payload is delegated to unindexed blobs, runtime becomes slow and awkward:

- listing nodes still needs labels/types
- filtering by type/scope still needs indexes
- annotation browsing needs span indexes
- correctness checks need counts and uniqueness constraints
- graph traversal results need display payloads

So the solution is not "AGE thin, everything else opaque".

The solution is:

```text
AGE thin topology
+ relational projections for runtime access
+ blob/json payload store for full fidelity
```

## Runtime Query Analysis

### Current Runtime APIs

The current `DomainApi` asks `AgeGraphService` for:

- domain type summaries
- node pages
- associated nodes
- scope previews
- ego graphs

These currently read directly from AGE tables and extract agtype fields such as:

- `uid`
- `uimaType`
- `corpusId`
- `documentId`
- `name`
- `uri`
- `metadata`
- `features`

This is convenient but not ideal for fast deployment queries.

### Better Runtime Path

Runtime should split into two operations:

1. Use AGE only for graph traversal.
2. Hydrate node/edge display and payload from projection tables.

Example:

```text
seed uid
  -> registry resolves AGE id
  -> AGE traverses neighbors
  -> AGE returns uid list / edge uid list
  -> projection tables hydrate labels, types, counts, payload refs
```

This keeps traversals graph-native while avoiding property extraction as the main API surface.

### Fast Subgraph Expansion

For fast subgraphs:

- AGE stores adjacency.
- Registry tables map uid to AGE ids.
- Projection tables map uid to display/runtime facts.
- The API hydrates in bulk:

```sql
SELECT * FROM uce_domain_projection WHERE uid = ANY(?)
SELECT * FROM uce_association_projection WHERE uid = ANY(?)
```

This is more predictable than pulling wide agtype properties during traversal.

## Correctness And Identity

### Domain Identity

Domain identity must remain in the domain id / uid:

```text
<uimaType>:<domainId>
```

AGE deduplication should be driven by this UID.

This is correct for:

- `UCEImport`
- `UCECorpus`
- `UCEDocument`
- `UCEPage`
- `UCEView`
- `UCEType`
- BioFID `Article`
- BioFID `Journal`
- BioFID `Issue`
- BioFID `Volume`
- BioFID `Page`
- BioFID `Collection`

### Annotation Identity

For future `UCEAnnotation`, identity must be explicit and stable enough for the intended semantics.

A weak first version could use:

```text
uimaType + documentDomainId + begin + end + coveredTextHash + selected feature hash
```

But this must stay outside AGE as the annotation projection UID. AGE may contain annotation nodes only if the UI truly needs annotation-level graph traversal.

### Edge Identity

Association identity must also be explicit:

```text
associationType + leftUid + rightUid + semantic role
```

For repeated imports, the same association should merge, not duplicate.

If an association is import-specific, include `importId`.
If it is conceptually stable, do not include `importId`.

This distinction matters for correctness.

## What Should Stay In AGE

Keep in AGE:

- stable domain nodes
- stable association edges
- enough scope keys for coarse filtering
- minimal labels/types for emergency inspection
- optional payload refs

Good AGE domains:

- Corpus/document/page hierarchy.
- BioFID publication hierarchy.
- Import-to-corpus/run relationships.
- Document-to-view/type summaries if they are useful for navigation.
- Domain-to-domain associations from UIMA `Association` annotations.

Questionable in AGE:

- `UCEOperation` as one node per procedural step if very high volume.
- `UCEAnnotation` as one node per annotation if the goal is only storage/search.

Bad in AGE:

- arbitrary feature maps for every annotation.
- full metadata JSON.
- large serialized feature structures.
- token-level spans unless graph traversal over tokens is a real runtime feature.

## UCEAnnotation Strategy

### Recommended First Implementation

Do not store every `UCEAnnotation` as an AGE node by default.

Instead:

```text
uce_annotation_projection stores annotation identity/span/type/payload_ref
uce_feature_payload stores full feature JSON
AGE stores only aggregate UCEType and selected domain associations
```

This supports:

- high import throughput
- exact annotation retrieval
- document/page annotation browsing
- counts by type
- later graph projection if needed

### Optional Graph Projection

If runtime needs graph traversal from a domain to annotations:

```text
Domain -> UCEAnnotation
UCEAnnotation -> UCEType
UCEAnnotation -> UCEPage
```

should be selectively enabled per annotation type, not globally.

Examples worth projecting:

- taxa
- named entities
- geonames
- user-curated annotations

Examples probably not worth projecting:

- tokens
- POS
- dependency edges
- sentence-level helper structures unless needed by the UI

## Import Architecture Recommendation

### Current Service Shape To Preserve

Keep:

- service-owned robustness
- registry tables
- staged COPY
- batch upserts
- blocking connection acquisition
- idempotent UID semantics

### Structural Change

Change `AgeGraphService.DomainNode` and `AssociationEdge` from payload-heavy records to topology records.

Current:

```java
DomainNode(uid, uimaType, corpusId, documentId, name, uri, metadata, featuresJson)
AssociationEdge(uid, uimaType, corpusId, documentId, leftUid, rightUid, name, metadata, featuresJson)
```

Target:

```java
DomainNode(uid, uimaType, corpusId, documentId, pageUid, label, payloadRef)
AssociationEdge(uid, uimaType, corpusId, documentId, leftUid, rightUid, label, payloadRef)
```

Then persist the rest through projection/payload services.

### Bulk Write Flow

Per import/document flush:

```text
1. collect domains and associations
2. deduplicate by uid
3. write projection rows with ON CONFLICT
4. write payload rows by hash/ref
5. write thin AGE nodes/edges using registry-stage COPY
```

This is still direct execution through robust services. It is not a giant post-import batch.

## Runtime Architecture Recommendation

### API Query Flow

```text
types/counts:
  projection table aggregate

node search:
  projection table search

association traversal:
  AGE traversal by age ids
  hydrate uid results from projection table

ego graph:
  AGE traversal
  bulk hydrate nodes/edges

full annotation inspection:
  projection table -> payload_ref -> payload store
```

### Why This Is Faster

- AGE does topology only.
- PostgreSQL B-tree indexes handle identity, type, scope, and pagination.
- JSONB/blob payloads are fetched only when needed.
- UI does not pay for annotation payloads during graph traversal.

## Migration Plan

### Phase 1: Thin AGE Properties

Remove from AGE properties:

- `metadata`
- `features`
- large per-node payload fields

Keep:

- `uid`
- `uimaType`
- `corpusId`
- `documentId`
- `name` only if small
- `payloadRef` if available

### Phase 2: Projection Tables

Add:

```text
uce_domain_projection
uce_association_projection
```

Move runtime list/search/count APIs to these tables where possible.

### Phase 3: Payload Store

Add:

```text
uce_feature_payload
```

Store full features by stable hash/ref.

### Phase 4: Annotation Projection

Add:

```text
uce_annotation_projection
```

Do not write all annotations into AGE by default.

### Phase 5: Selective Annotation Graphing

Add config:

```text
graph.project.annotation.types = [...]
```

Only selected annotation types become AGE nodes/edges.

## Evaluation Criteria

### Import Metrics

Measure:

- documents/sec
- graph nodes/sec
- graph edges/sec
- projection rows/sec
- payload rows/sec
- AGE write time
- projection write time
- payload write time
- DB connection wait
- transaction duration
- skipped/retried writes

Success target:

```text
AGE write time should be a small fraction of document import time.
AGE should not dominate p95/p99 stage latency.
```

### Runtime Metrics

Measure:

- node search latency
- type summary latency
- one-hop traversal latency
- two-hop ego graph latency
- hydration latency
- payload fetch latency

Success target:

```text
graph traversal returns uid topology quickly
projection hydration remains bounded and indexed
payload loading is explicit and on demand
```

## Final Recommendation

Do not treat "store everything elsewhere and keep AGE empty" as an absolute solution.

Use this split:

```text
AGE:
  graph identity and topology

Projection tables:
  runtime-indexed facts

Payload store:
  full serialized feature data
```

This is the structure that best matches UCE:

- high-throughput imports
- idempotent repeated imports
- correct domain identity preservation
- fast graph traversal
- fast runtime filtering/search
- future support for `UCEAnnotation` without making AGE unusable

The decisive rule should be:

```text
If a field is needed to traverse or merge graph identity, it can live in AGE.
If a field is needed to search, sort, paginate, count, or display, it belongs in projection tables.
If a field is needed only for full fidelity reconstruction, it belongs in payload storage.
```
