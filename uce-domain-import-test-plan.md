# UCE Domain Import Test And Profiling Plan

## 1. Goal

Validate that UCE can import the BioFID domain-annotated taxa data through the new DUUI importer with Podman-compatible services, then profile the import path enough to choose sane runtime settings for a full run.

Correctness and optimization are separate phases:

1. **Correctness first**
   - PostgreSQL/AGE schema is usable.
   - UCE services survive repeated calls without leaking connections or failing under normal contention.
   - Domain annotations are imported into AGE with stable identity.
   - Existing relational UCE output remains semantically equivalent to the canonical importer output.
   - Failed or corrupt documents are recorded/skipped according to import policy, not allowed to poison later documents.

2. **Process optimization second**
   - Measure where time is spent.
   - Determine useful thread counts and virtual/platform-thread split.
   - Identify service bottlenecks before the full run.
   - Keep instrumentation removable and low-risk.

## 2. Input Data

Current relevant export paths from `BIOfidPipeline/src/test/java/Processing.java`:

| Path | Role | Current Count |
| --- | --- | ---: |
| `/mnt/bioFID/TTLabExports/2026_04_18` | raw source export | `24268` `.bz2` |
| `/mnt/bioFID/TTLabExports/2026_04_18_sort` | sorted/prepared XMI | `24244` `.xmi.gz` + `TypeSystem.xml.gz` |
| `/mnt/bioFID/TTLabExports/2026_04_18_basic` | basic preprocessing output | `24227` `.xmi.gz` + `TypeSystem.xml.gz` |
| `/mnt/bioFID/TTLabExports/2026_04_18_taxa` | taxa preprocessing output | `504` `.xmi.gz` + `TypeSystem.xml.gz` |
| `/mnt/bioFID/TTLabExports/2026_05_14_taxa_domains` | domain annotator output | currently partial |
| `/mnt/bioFID/TTLabExports/2026_05_14_taxa_domains_sample_check` | two-document domain sample | `2` `.xmi.gz` + `TypeSystem.xml.gz` |

Taxa input validation found:

| File | Count |
| --- | ---: |
| `/mnt/bioFID/TTLabExports/2026_05_14_taxa_domains.valid-files.txt` | `491` |
| `/mnt/bioFID/TTLabExports/2026_05_14_taxa_domains.invalid-files.txt` | `8` |

The UCE import correctness tests should start from `2026_05_14_taxa_domains_sample_check`, then a small deterministic subset of `2026_05_14_taxa_domains`, then the full valid domain-annotated taxa set.

## 3. Podman Compose Setup

UCE must run with Podman-compatible Compose. The existing root `docker-compose.yaml` already defines:

| Service | Compose Service | Profile |
| --- | --- | --- |
| PostgreSQL/AGE | `uce-postgresql-db` | `db`, `local`, `remotekc` |
| Importer | `uce-importer` | `import` |
| Web | `uce-web` | `local`, `remote`, `remotekc`, `remotedb`, `ssh`, `proxy` |
| Keycloak | `uce-keycloak-auth` | `local`, `remotedb`, `ssh`, `proxy` |
| Fuseki/SPARQL | expected through `fuseki` profile | `fuseki` |
| RAG/Python service | `uce-rag-service` | `rag` |

The first import harness should use a reduced profile set:

```bash
podman compose --profile db --profile fuseki up -d
```

Then importer-only runs should use:

```bash
podman compose --profile import run --rm uce-importer
```

The `.env` for test runs should be generated separately from the normal dev `.env`:

```text
.env.importer
```

Important runtime variables:

| Variable | Purpose |
| --- | --- |
| `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USER`, `DB_PASSWORD` | PostgreSQL connection |
| `POSTGRES_DATA_HOST_PATH` | isolated test database volume |
| `IMPORTER_CORPORA_HOST_PATH` | host input folder mounted to `/app/input` |
| `IMPORTER_IMPORT_SRC` | importer source path inside container |
| `IMPORTER_THREADS` | importer-level worker count |
| `IMPORTER_NUMBER` | importer id |
| `IMPORTER_DATABASE_HOST_PATH` | optional SQL helper path |
| `UCE_CONFIG_PATH` | full UCE config JSON |
| `SPARQL_*` config/env values | Fuseki behavior if enabled |

## 4. Correctness Test Harness

### 4.1 Service Health Tests

These tests validate services before the importer is allowed to run.

| Service | Test | Success Criteria |
| --- | --- | --- |
| PostgreSQL | connect, run `select 1`, verify expected extensions | connection succeeds, required schema/extensions available |
| AGE graph | create/read test graph transaction | node/edge upsert works, duplicate upsert is idempotent |
| Hibernate/UCE DB | initialize `PostgresqlDataInterface_Impl` and run a bounded query | sessions close, retries do not leak connections |
| SPARQL/Fuseki | ping endpoint and run a simple `ASK` query | timeout/retry config works |

MinIO and RAG are deliberately out of the first correctness harness. They stay disabled until PostgreSQL/AGE, Fuseki, and importer-domain correctness are stable.

### 4.2 Import Correctness Tests

Run in increasing size:

1. **Two-document smoke import**
   - Input: `/mnt/bioFID/TTLabExports/2026_05_14_taxa_domains_sample_check`
   - Goal: verify pipeline wiring, schema creation, and minimal AGE materialization.

2. **Small deterministic import**
   - Input: a copied subset from `2026_05_14_taxa_domains.valid-files.txt`
   - Suggested sizes: `10`, `25`, `50`
   - Goal: catch duplicate domain identity, repeated journal/volume/issue/page associations, and batch postprocessing effects.

3. **Full valid taxa domain import**
   - Input: all valid domain-annotated taxa documents.
   - Goal: complete run without corrupt input files.

### 4.3 Correctness Indicators

| Aspect | Indicator | Evaluation |
| --- | --- | --- |
| Document import | expected document count | DB document rows match imported valid docs |
| Duplicate handling | repeated import of same sample | no duplicate stable domains, duplicate document behavior follows policy |
| AGE domain identity | `Article`, `Journal`, `Volume`, `Issue`, `Page` nodes | same IDs merge, no duplicate nodes for same domain id |
| AGE associations | `Membership`, `Sequence` edges | edge count matches XMI association count modulo idempotent merge |
| Annotation extraction | UCE relational annotation tables | non-domain annotation output remains equivalent to canonical importer |
| Page identity | page domain IDs | no bare page-number identity; IDs are article/page scoped |
| Failure behavior | corrupt files and failed documents | failures are recorded, later documents continue |
| Idempotence | import same sample twice | stable rows/domains are not duplicated incorrectly |
| Deletion readiness | imported domain scope can be queried | documents/domains/operations can be selected by import/corpus/document scope |

### 4.4 Required Service Tests

Concrete tests to add before full import:

| Test Class | Scope |
| --- | --- |
| `PostgresqlServiceRobustnessTest` | connection pool blocking/retry, transient SQL retry, no session leaks |
| `AgeGraphServiceDomainTest` | domain node merge, association merge, repeated import idempotence |
| `JenaSparqlServiceRobustnessTest` | timeout/retry behavior, service unavailable behavior |
| `DUUIImporterSampleImportTest` | two-document domain import end-to-end |
| `DUUIImporterRepeatedImportTest` | repeated sample import and duplicate policy |
| `DUUIImporterFailurePolicyTest` | corrupt/missing document behavior |

## 5. Profiling And Metrics

Instrumentation should be surface-level and easily removable. The first pass should avoid deep invasive metrics.

### 5.1 Instrumentation Options

| Tool | Use | Why |
| --- | --- | --- |
| JFR | low-overhead JVM runtime profile | captures CPU, allocation, blocking, file I/O, socket I/O, thread scheduling |
| Lightweight annotations | mark importer procedures/scopes | removable and close to the code paths we care about |
| DUUI event/operation events | import-stage status and errors | aligns with `UCEOperation` and existing DUUI task/event model |
| Hikari metrics/logging | connection pool pressure | identifies pool exhaustion vs normal blocking |
| PostgreSQL views | DB bottlenecks | `pg_stat_activity`, locks, slow queries |
| Podman stats | container CPU/memory/I/O | validates host/container resource pressure |

### 5.2 Annotation Shape

Use minimal annotations such as:

```java
@UCEProfiled("extract-document")
@UCEProfiled("persist-document")
@UCEProfiled("persist-age-graph")
@UCEProfiled("postprocess-document")
```

These annotations should only mark method scopes. Runtime collection can be AOP, reflection wrapper, DUUI lambda wrapper, or explicit utility later. If removed, importer semantics must not change.

### 5.3 Service-Concurrency Timing Model

Plain latency is not enough. Every service call should be understood as multiple intervals:

| Interval | Meaning | Why It Matters |
| --- | --- | --- |
| caller queue wait | time a DUUI task waits before it can even call the service | shows pipeline-side backpressure and whether too many tasks were scheduled |
| pool/resource wait | time waiting for a service-owned resource, e.g. JDBC connection, HTTP slot, SPARQL permit | distinguishes saturation from slow service execution |
| request serialization time | time preparing payload/query/body | important for large JCas/XMI/AGE writes |
| service execution time | time spent inside PostgreSQL/AGE or Fuseki | actual backend latency |
| response deserialization time | time transforming result back into Java objects | catches large result/object mapping overhead |
| retry/backoff time | time consumed by transient failures | separates robustness overhead from normal work |
| total wall time | end-to-end time seen by the caller | what the pipeline experiences |

For PostgreSQL this means separating:

- Hikari connection acquisition wait.
- transaction duration.
- SQL execution duration.
- lock wait / deadlock retry.
- Hibernate flush/dirty-check overhead.

For AGE graph writes this means separating:

- graph statement construction.
- connection acquisition.
- Cypher execution.
- merge/idempotence overhead.
- duplicate-domain and duplicate-edge checks.

For Fuseki/SPARQL this means separating:

- request construction.
- HTTP connection wait.
- remote query/update execution.
- response parsing.
- retry/backoff.

### 5.4 Tail Latency And Saturation Indicators

Use averages only as a rough baseline. The useful indicators are:

| Indicator | Meaning | Evaluation |
| --- | --- | --- |
| p50 latency | normal call behavior | baseline service cost |
| p90 latency | moderate contention | early warning for saturation |
| p95/p99 latency | tail behavior | determines whether full imports stall unpredictably |
| max latency | worst visible pause | inspect alongside logs/JFR |
| queue depth | pending DUUI/service work | should be bounded or explained |
| active resources | active DB connections / HTTP requests / SPARQL requests | should stay within configured limits |
| resource wait ratio | wait time divided by total time | high ratio means concurrency is too high or pool too small |
| retry ratio | retried calls divided by total calls | high ratio means service instability or overload |
| timeout count | hard failures by service | should be zero in correctness tests |
| blocked virtual/platform thread time | scheduler/backpressure problem | JFR and scoped metrics |

### 5.5 Important Indicators

| Indicator | Why It Matters | How To Measure |
| --- | --- | --- |
| docs/sec | overall throughput | importer event counters and wall clock |
| per-stage latency | finds slow import steps | `UCEProfiled` scope durations with p50/p90/p99 |
| service wait time | detects contention before service execution | service-owned timing around pool/semaphore acquisition |
| DB connection wait time | detects pool pressure | Hikari/JFR/JDBC timing |
| DB transaction time | detects long transactions | service timing and PostgreSQL views |
| AGE write time | graph materialization bottleneck | profiled `persist-age-graph` scope split into build/wait/execute |
| SPARQL time | ontology/taxonomy bottleneck | profiled SPARQL calls split into wait/execute/parse |
| error rate | robustness | UCEOperation/DUUIEvent errors per stage |
| retry count | transient instability | service retry counters/logs |
| blocked thread time | bad concurrency config | JFR thread park/block events |
| heap allocation rate | memory pressure | JFR allocation and GC events |
| GC pause time | JVM capacity | JFR GC events |
| open DB connections | pool sizing | Hikari stats / PostgreSQL activity |
| row/domain/edge counts | correctness | DB and AGE queries after import |

## 6. Configuration Parameters To Test

### 6.1 Importer Runtime

| Parameter | Values |
| --- | --- |
| `IMPORTER_THREADS` | `1`, `2`, `4`, `8` |
| DUUI executor thread kind | platform, virtual, mixed |
| DUUI document-stage parallelism | conservative, balanced, aggressive |
| DUUI task retry policy | none, bounded retry |
| failed-document policy | fail-fast, record-and-continue |

### 6.2 Service Capacity

| Parameter | Values |
| --- | --- |
| PostgreSQL pool max size | `4`, `8`, `16` |
| PostgreSQL connection timeout | `30s`, `60s` |
| PostgreSQL retry attempts | `1`, `3`, `5` |
| PostgreSQL retry backoff | `100ms`, `250ms`, `1000ms` |
| SPARQL retry attempts | `1`, `3` |

### 6.3 JVM

| Parameter | Values |
| --- | --- |
| heap | `4g`, `8g`, `16g` depending node capacity |
| GC | default G1 first |
| JFR | off, on with low-overhead profile |
| virtual thread use | off/on for I/O-heavy stages |

## 7. Planned Test Matrix

Start small. Do not run the full cross product initially.

### 7.1 Correctness Matrix

| Run | Input | Threads | Services | Expected Result |
| --- | --- | ---: | --- | --- |
| C1 | two-doc sample | `1` | PostgreSQL/AGE only | exact import, AGE domains/edges present |
| C2 | two-doc sample repeated | `1` | PostgreSQL/AGE only | idempotent stable domains |
| C3 | 10 valid docs | `1` | PostgreSQL/AGE only | no duplicate domain identity |
| C4 | 10 valid docs | `2` | PostgreSQL/AGE only | same output as C3 |
| C5 | 50 valid docs | `4` | PostgreSQL/AGE + enabled optional services | no pool leaks, no failed docs |

### 7.2 Profiling Matrix

Only after C1-C5 pass:

| Run | Input | Threads | DB Pool | Thread Kind | JFR | Purpose |
| --- | --- | ---: | ---: | --- | --- | --- |
| P1 | 50 docs | `1` | `4` | platform | on | baseline |
| P2 | 50 docs | `4` | `8` | platform | on | normal platform scaling |
| P3 | 50 docs | `4` | `8` | virtual for I/O scopes | on | I/O-bound benefit check |
| P4 | 50 docs | `8` | `8` | virtual for I/O scopes | on | connection pool pressure check |
| P5 | 100 docs | best from P1-P4 | tuned | tuned | on | pre-full-run validation |

### 7.3 Full Run Gate

The full taxa domain import is allowed only if:

- C1-C5 pass.
- P5 has no connection pool exhaustion.
- All failed documents are explainable and recorded.
- Repeated import behavior is correct on the sample.
- AGE node/edge counts are stable across repeated sample imports.

## 8. Evaluation Queries

After each correctness run:

| Query Area | Question |
| --- | --- |
| PostgreSQL documents | How many documents were imported? |
| PostgreSQL annotations | Are expected annotation tables populated? |
| AGE domains | How many `Article`, `Journal`, `Volume`, `Issue`, `Page` domains exist? |
| AGE associations | How many `Membership` and `Sequence` edges exist? |
| AGE duplicate check | Are there duplicate nodes with the same domain id? |
| Import operations | Which stages completed/failed/retried? |
| Error log | Are corrupt or skipped files recorded explicitly? |
| Connection usage | Did active DB connections return to idle after import? |

## 9. Immediate Implementation Steps

1. Add Podman test `.env.importer` template for importer runs.
2. Add a Podman Compose helper document/command set.
3. Add service health tests.
4. Add two-document DUUI importer integration test.
5. Add AGE domain/association assertion helpers.
6. Add removable `@UCEProfiled` annotation and minimal timing collector.
7. Add JFR launch profile for importer container.
8. Run C1-C5.
9. Run P1-P5.
10. Start full valid taxa domain import only after the gates pass.
