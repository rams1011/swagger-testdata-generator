#!/usr/bin/env bash
# Installs the ingest pipeline and index template for Studio logs from Cloud Run, then checks the pipeline on a
# sample Cloud Logging entry. Works against Elastic Cloud or a self-managed cluster.
#
#   export ELASTIC_URL=https://<deployment>.es.<region>.gcp.elastic-cloud.com     # no trailing slash
#   export ELASTIC_API_KEY=<encoded API key with manage_pipeline + manage_index_templates>
#   bash docs/examples/elastic/elastic-setup.sh
#
# The key comes from your shell only. Never write it into this repository.
set -euo pipefail

: "${ELASTIC_URL:?Set ELASTIC_URL (Elasticsearch endpoint)}"
: "${ELASTIC_API_KEY:?Set ELASTIC_API_KEY (encoded API key)}"
here="$(cd "$(dirname "$0")" && pwd)"

es() {
  local method="$1" path="$2"
  shift 2
  curl -fsS -X "$method" "${ELASTIC_URL%/}${path}" \
    -H "Authorization: ApiKey ${ELASTIC_API_KEY}" \
    -H "Content-Type: application/json" "$@"
  echo
}

echo "Ingest pipeline testdata-studio-cloud-logging"
es PUT /_ingest/pipeline/testdata-studio-cloud-logging --data-binary "@${here}/cloud-logging-pipeline.json"

echo "Index template testdata-studio-cloud-logging (data stream logs-gcp.pubsub-testdata)"
es PUT /_index_template/testdata-studio-cloud-logging --data-binary "@${here}/cloud-logging-index-template.json"

echo "Pipeline check on a sample entry (expect message, requestId, tenantId and gcp.cloud_run.service at the root):"
es POST /_ingest/pipeline/testdata-studio-cloud-logging/_simulate --data-binary @- <<'JSON'
{"docs": [{"_source": {"message": "{\"insertId\":\"abc\",\"logName\":\"projects/p/logs/run.googleapis.com%2Fstdout\",\"severity\":\"WARNING\",\"timestamp\":\"2026-11-19T10:00:00.123Z\",\"resource\":{\"type\":\"cloud_run_revision\",\"labels\":{\"project_id\":\"p\",\"location\":\"us-central1\",\"service_name\":\"testdata-studio\",\"revision_name\":\"testdata-studio-00042\"}},\"jsonPayload\":{\"@timestamp\":\"2026-11-19T10:00:00.120Z\",\"log\":{\"level\":\"WARN\",\"logger\":\"com.tx.Example\"},\"message\":\"Spec catalog is slow\",\"requestId\":\"req-12345678\",\"tenantId\":\"ws-acme\",\"trace.id\":\"4bf92f3577b34da6a3ce929d0e0e4736\",\"service\":{\"name\":\"testdata-studio\",\"environment\":\"cloud\"}}}"}}]}
JSON
