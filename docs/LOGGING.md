# Logs and Elastic

The API logs through Logback. By default the console shows a readable line with the request id, trace/span ids and
the catalog read source. With `TESTDATA_LOG_FORMAT=ecs` every line is one JSON object in
[Elastic Common Schema](https://www.elastic.co/guide/en/ecs/current/index.html) (Spring Boot structured logging),
which both Elastic and Google Cloud Logging read field by field.

| Variable | Effect |
|----------|--------|
| `TESTDATA_LOG_FORMAT` | Console format: unset = readable text; `ecs`, `logstash` or `gelf` = JSON (`logging.structured.format.console`) |
| `TESTDATA_LOG_ENVIRONMENT` | ECS `service.environment`, e.g. `local`, `cloud`, `production` (unset = left out) |
| `LOGGING_FILE_NAME` + `LOGGING_STRUCTURED_FORMAT_FILE=ecs` | Spring Boot's own: also write an ECS JSON file, console unchanged (used by the local stack below) |

Fields on each JSON line:

| Field | From |
|-------|------|
| `@timestamp`, `message`, `log.level`, `log.logger`, `process.thread.name`, `error.*` | Logback event (ECS) |
| `service.name` (`testdata-studio`), `service.environment` | `spring.application.name`, `TESTDATA_LOG_ENVIRONMENT` |
| `requestId` | `RequestCorrelationFilter` (`X-Request-Id`, echoed to the caller) |
| `tenantId` | `TenantFilter`: the workspace the request runs in (the built-in Local workspace id `00000000-0000-0000-0000-000000000001` without login) |
| `trace.id`, `span.id` | Micrometer / OpenTelemetry (MDC `traceId`, `spanId`, renamed to ECS names) |
| `studioReadSource` | `redis` or `db` while the Redis spec cache reads |
| `severity` | `CloudLoggingSeverity`: Cloud Logging's level names (`WARNING`, …), so Cloud Logging shows the right severity |

Configuration: `logging.structured.*` in `testdata-generator-api/src/main/resources/application.yml`; test:
`StructuredLoggingConfigTest`.

## Ways to get the logs into Elastic

| | **A. Cloud Logging → sink → Pub/Sub → Elastic** (recommended) | B. Elastic APM / EDOT (OpenTelemetry) Java agent | C. Logback appender writing to Elasticsearch |
|---|---|---|---|
| How | App writes JSON to stdout. Cloud Run sends it to Cloud Logging; a Logs Router sink copies the API's entries to a Pub/Sub topic; Elastic Agent (Custom Google Pub/Sub Logs integration) or Google's Dataflow "Pub/Sub to Elasticsearch" template moves them to Elastic | `-javaagent` in the image sends logs (and traces, metrics) over OTLP/APM to Elastic | An appender in the JVM batches log events and posts them to Elasticsearch |
| Elastic credentials | **Not in the app.** The Pub/Sub reader identity and the Elastic key live with the shipper (Fleet or Secret Manager) | In the Cloud Run service (secret token / API key) | In the Cloud Run service |
| Reliability | Cloud Logging captures stdout even when the app crashes; Pub/Sub keeps 7 days if Elastic or the shipper is down; logs stay in Cloud Logging (30 days) as a fallback | In-memory buffer: lost when Elastic is unreachable or the instance stops. Cloud Run throttles CPU between requests (min-instances 0), so background export stalls | Same as B, and a slow cluster can back up into request threads; nothing logged before the appender starts |
| Cost (small service) | Cloud Logging: first 50 GiB/month free; sink routing free; Pub/Sub: first 10 GiB/month free. Shipper: Elastic Agent on an e2-micro/small VM (a few $/month, or the agent you already run) or Dataflow (one streaming worker, roughly $50–80/month) | Nothing on GCP; adds agent overhead to the 1 GiB instance | Nothing on GCP |
| Change to the app | JSON format only (done) | New image layer, agent config, secrets | New dependency and code |
| Local dev | Same JSON, read from a file by Filebeat | Agent locally too | Appender locally too |

**Recommendation: A.** It keeps the app free of Elastic credentials and dependencies, survives Elastic outages, and
costs close to nothing at this size. For the last hop:

- **Elastic Agent** (`SHIPPER=agent`) when the cluster is self-managed, or to keep cost lowest: the agent pulls
  from Pub/Sub, so the cluster needs no inbound access from Google.
- **Dataflow** (`SHIPPER=dataflow`) on Elastic Cloud when you want nothing to run yourself: fully managed by
  Google, the Elastic API key sits in Secret Manager, at the price of a streaming worker.

Traces can follow later on their own: the API already exports OTLP traces (`OTEL_EXPORTER_OTLP_*`), which Elastic's
APM / OTLP endpoint accepts; that's independent of this log path.

**The UI service (`testdata-studio-ui`)** is nginx serving the built app and proxying `/api/` to the API. Its
container logs are access lines for static files; every API call it proxies is already logged by the API with a
request id. Not worth shipping by default. `INCLUDE_UI=true` on the sink script adds only its Cloud Run request logs
with status ≥ 500 (for example a failing proxy).

## Local: Elasticsearch + Kibana + Filebeat

Files: [`examples/elastic/docker-compose.yml`](examples/elastic/docker-compose.yml),
[`examples/elastic/filebeat.yml`](examples/elastic/filebeat.yml). Needs Docker Desktop. Security is off and every
port is bound to `127.0.0.1`: for your machine only, with no credentials anywhere.

1. Run the API with an ECS log file next to the readable console (from the repo root, PowerShell):

   ```powershell
   $env:LOGGING_FILE_NAME = "$PWD\logs\testdata-studio.json"
   $env:LOGGING_STRUCTURED_FORMAT_FILE = "ecs"
   $env:TESTDATA_LOG_ENVIRONMENT = "local"
   mvn -pl testdata-generator-api -am spring-boot:run
   ```

   Use an absolute path: `spring-boot:run` starts in `testdata-generator-api`. `logs/` is git-ignored.

2. Start the stack:

   ```powershell
   docker compose -f docs/examples/elastic/docker-compose.yml up -d
   ```

   If the log folder is elsewhere, set `STUDIO_LOG_DIR` (absolute path) first. `ELASTIC_VERSION` picks the image
   version (default 8.18.0).

3. Open Kibana at http://localhost:5601 → **Discover** → create a data view on `filebeat-*` with timestamp
   `@timestamp`. Try `log.level : "WARN"`, `tenantId : <workspace id>`, or a request id from an `X-Request-Id` response
   header.

Stop with `docker compose -f docs/examples/elastic/docker-compose.yml down` (add `-v` to drop the indexed logs).

For the API running in the root `docker-compose.yml`, set the same two `LOGGING_*` variables on the
`testdata-studio` service with a file under a mounted folder, and point `STUDIO_LOG_DIR` at that folder.

## Cloud Run: sink → Pub/Sub → Elastic

The deploy workflow already sets `TESTDATA_LOG_FORMAT=ecs` and `TESTDATA_LOG_ENVIRONMENT=cloud` (repository
variables override them; `TESTDATA_LOG_FORMAT=text` keeps the readable pattern). Cloud Logging then stores each line
as `jsonPayload` with the right severity.

1. **GCP side** (once, Cloud Shell or a signed-in gcloud):
   [`examples/elastic/gcp-log-sink.sh`](examples/elastic/gcp-log-sink.sh) creates topic `testdata-studio-logs`
   (plus `-errors`), subscription `testdata-studio-logs-elastic` (7-day retention), the Logs Router sink for the
   `testdata-studio` service's stdout/stderr, the publisher grant for the sink's writer identity, and a
   `elastic-log-reader` service account that can only read that subscription.

   ```bash
   PROJECT_ID=<project> SHIPPER=agent bash docs/examples/elastic/gcp-log-sink.sh
   ```

   For Dataflow, first store the Elastic API key (encoded form) in Secret Manager, never in the repo, then:

   ```bash
   read -rs KEY && printf %s "$KEY" | gcloud secrets create elastic-api-key --data-file=- --project=<project>
   PROJECT_ID=<project> SHIPPER=dataflow ELASTIC_URL=<Cloud ID or https URL> bash docs/examples/elastic/gcp-log-sink.sh
   ```

   Check the Dataflow template's parameters against
   [Google's page](https://cloud.google.com/dataflow/docs/guides/templates/provided/pubsub-to-elasticsearch) for
   the template version you run.

2. **Elastic side** (once): [`examples/elastic/elastic-setup.sh`](examples/elastic/elastic-setup.sh) installs the
   `testdata-studio-cloud-logging` ingest pipeline and an index template for the data stream
   `logs-gcp.pubsub-testdata`. The pipeline unwraps the Cloud Logging entry so `message`, `log.level`, `requestId`,
   `tenantId` and `trace.id` sit at the root, and keeps Cloud Run metadata under `gcp.*` (`gcp.cloud_run.revision`,
   `gcp.severity`, `gcp.http_request`). The script ends with a pipeline simulation on a sample entry.

   ```bash
   export ELASTIC_URL=https://<your-elasticsearch-endpoint>
   export ELASTIC_API_KEY=<encoded key with manage_pipeline and manage_index_templates>
   bash docs/examples/elastic/elastic-setup.sh
   ```

3. **Shipper.** With `SHIPPER=agent`: in Kibana **Fleet**, add the *Custom Google Pub/Sub Logs* integration to an
   agent policy with the project, topic and subscription above, *Create subscription* off, dataset `gcp.pubsub`,
   namespace `testdata`. Give it the `elastic-log-reader` identity: run the agent on a GCE VM as that service
   account, or paste a key into the integration's credentials field (the script prints the command; delete the key
   file afterwards). With `SHIPPER=dataflow` the script has started the job already.

4. **Check:** call the API, then in Kibana open a data view on `logs-gcp.pubsub-testdata*`. In Cloud Logging, the
   sink's filter shows the same entries; `gcloud pubsub subscriptions pull testdata-studio-logs-elastic --limit=1`
   (without `--auto-ack`) peeks at one waiting message.

Cost control: logs are stored in Cloud Logging's `_Default` bucket as well as routed. An exclusion filter on the
`_Default` sink (for example DEBUG lines) lowers ingestion without affecting the Elastic sink.
