# DUUI Importer Runtime Configuration

The DUUI UCE importer does not manage database, S3, SPARQL, embedding, or post-processing capacity with pipeline-local semaphores. Service robustness is owned by the services themselves.

## Importer-Level Inputs

The importer still accepts the normal import inputs:

```bash
java -jar importer.jar \
  -importSrc /path/to/corpus \
  -importerNumber 1 \
  -numThreads 8 \
  -casView _InitialView
```

`-numThreads` controls DUUI document task parallelism. It does not define service capacity.

## Service-Level Configuration

Service capacity and blocking behavior must be configured at the service boundary.

PostgreSQL/Hibernate/Hikari is configured through `uce.common`:

```properties
postgresql.pool.connection.timeout.ms=30000
postgresql.pool.minimum.idle=2
postgresql.pool.maximum.size=10
postgresql.pool.idle.timeout.ms=600000
postgresql.pool.max.lifetime.ms=1800000
postgresql.pool.leak.detection.threshold.ms=60000
```

The importer calls `PostgresqlDataInterface_Impl`, `S3StorageService`, `JenaSparqlService`, `LexiconService`, and `EmbeddingService` directly. If a service has finite capacity, it must block, queue, retry, or fail according to its own configuration and policy.

## Rule

Pipeline stages describe import semantics. They do not own service pool management.
