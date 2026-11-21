#!/usr/bin/env bash
# Routes the Studio API's Cloud Run logs to Pub/Sub for Elastic: Logs Router sink -> topic -> subscription,
# plus the identity the shipper uses to pull. Safe to re-run. Run it in Cloud Shell (or anywhere gcloud is
# signed in as a project owner / logging + pubsub + iam admin).
#
#   PROJECT_ID=my-project SHIPPER=agent    bash docs/examples/elastic/gcp-log-sink.sh
#   PROJECT_ID=my-project SHIPPER=dataflow ELASTIC_URL=<Cloud ID or https URL> bash docs/examples/elastic/gcp-log-sink.sh
#
# SHIPPER=agent     Elastic Agent (Fleet) pulls the subscription with the "Custom Google Pub/Sub Logs" integration.
#                   Fits a self-managed cluster or Elastic Cloud; the agent runs next to Elastic or on a small VM.
# SHIPPER=dataflow  Google's managed "Pub/Sub to Elasticsearch" Dataflow template pushes to Elastic. Needs an
#                   Elastic API key in Secret Manager (secret ELASTIC_API_KEY_SECRET, created by you beforehand).
# SHIPPER=none      Sink, topic and subscription only.
#
# INCLUDE_UI=true also routes the UI service's Cloud Run request logs with status >= 500 (proxy failures).
# Nothing here writes Elastic credentials to disk or to the repository.
set -euo pipefail

PROJECT_ID="${PROJECT_ID:?Set PROJECT_ID}"
REGION="${REGION:-us-central1}"
API_SERVICE="${API_SERVICE:-testdata-studio}"
UI_SERVICE="${UI_SERVICE:-testdata-studio-ui}"
INCLUDE_UI="${INCLUDE_UI:-false}"
TOPIC="${TOPIC:-testdata-studio-logs}"
ERROR_TOPIC="${ERROR_TOPIC:-${TOPIC}-errors}"
SUBSCRIPTION="${SUBSCRIPTION:-${TOPIC}-elastic}"
SINK="${SINK:-${TOPIC}-to-elastic}"
SHIPPER="${SHIPPER:-agent}"
READER_SA="${READER_SA:-elastic-log-reader}"
ELASTIC_API_KEY_SECRET="${ELASTIC_API_KEY_SECRET:-elastic-api-key}"
ELASTIC_NAMESPACE="${ELASTIC_NAMESPACE:-testdata}"

gc() { gcloud --project="$PROJECT_ID" --quiet "$@"; }

filter="resource.type=\"cloud_run_revision\" AND resource.labels.service_name=\"${API_SERVICE}\" AND (logName:\"run.googleapis.com%2Fstdout\" OR logName:\"run.googleapis.com%2Fstderr\")"
if [ "$INCLUDE_UI" = "true" ]; then
  filter="(${filter}) OR (resource.type=\"cloud_run_revision\" AND resource.labels.service_name=\"${UI_SERVICE}\" AND logName:\"run.googleapis.com%2Frequests\" AND httpRequest.status>=500)"
fi

echo "== APIs"
gc services enable logging.googleapis.com pubsub.googleapis.com

echo "== Pub/Sub topic ${TOPIC} and subscription ${SUBSCRIPTION}"
gc pubsub topics describe "$TOPIC" >/dev/null 2>&1 || gc pubsub topics create "$TOPIC"
gc pubsub topics describe "$ERROR_TOPIC" >/dev/null 2>&1 || gc pubsub topics create "$ERROR_TOPIC"
# 7 days of retention: if the shipper is down, logs wait here instead of being lost.
gc pubsub subscriptions describe "$SUBSCRIPTION" >/dev/null 2>&1 || gc pubsub subscriptions create "$SUBSCRIPTION" \
  --topic="$TOPIC" --ack-deadline=60 --message-retention-duration=7d --expiration-period=never

echo "== Logs Router sink ${SINK}"
destination="pubsub.googleapis.com/projects/${PROJECT_ID}/topics/${TOPIC}"
if gc logging sinks describe "$SINK" >/dev/null 2>&1; then
  gc logging sinks update "$SINK" "$destination" --log-filter="$filter"
else
  gc logging sinks create "$SINK" "$destination" --log-filter="$filter"
fi
writer="$(gc logging sinks describe "$SINK" --format='value(writerIdentity)')"
gc pubsub topics add-iam-policy-binding "$TOPIC" --member="$writer" --role=roles/pubsub.publisher >/dev/null

reader="${READER_SA}@${PROJECT_ID}.iam.gserviceaccount.com"
ensure_reader() {
  gc iam service-accounts describe "$reader" >/dev/null 2>&1 || \
    gc iam service-accounts create "$READER_SA" --display-name="Elastic log shipper (Pub/Sub reader)"
  gc pubsub subscriptions add-iam-policy-binding "$SUBSCRIPTION" \
    --member="serviceAccount:${reader}" --role=roles/pubsub.subscriber >/dev/null
  gc pubsub subscriptions add-iam-policy-binding "$SUBSCRIPTION" \
    --member="serviceAccount:${reader}" --role=roles/pubsub.viewer >/dev/null
}

case "$SHIPPER" in
  none)
    ;;
  agent)
    echo "== Shipper identity ${reader} (subscriber on ${SUBSCRIPTION} only)"
    ensure_reader
    cat <<EOF

Next, in Kibana: Fleet -> agent policy -> Add integration "Custom Google Pub/Sub Logs":
  Project ID ${PROJECT_ID}, Topic ${TOPIC}, Subscription name ${SUBSCRIPTION}, Create subscription off,
  Dataset name gcp.pubsub, Namespace ${ELASTIC_NAMESPACE}  (data stream logs-gcp.pubsub-${ELASTIC_NAMESPACE})
Credentials: on a GCE VM, run the agent as ${reader} (no key). Anywhere else, create a key and paste it into the
integration's "Credentials JSON" field, then delete the local file:
  gcloud iam service-accounts keys create reader-key.json --iam-account=${reader} --project=${PROJECT_ID}
EOF
    ;;
  dataflow)
    : "${ELASTIC_URL:?Set ELASTIC_URL to the Elastic Cloud ID or the https Elasticsearch URL}"
    gc services enable dataflow.googleapis.com secretmanager.googleapis.com
    gc secrets describe "$ELASTIC_API_KEY_SECRET" >/dev/null 2>&1 || {
      echo "Secret ${ELASTIC_API_KEY_SECRET} not found. Create it first (the key never touches disk):"
      echo "  read -rs KEY && printf %s \"\$KEY\" | gcloud secrets create ${ELASTIC_API_KEY_SECRET} --data-file=- --project=${PROJECT_ID}"
      exit 1
    }
    echo "== Dataflow worker identity ${reader}"
    ensure_reader
    gc projects add-iam-policy-binding "$PROJECT_ID" --member="serviceAccount:${reader}" \
      --role=roles/dataflow.worker --condition=None >/dev/null
    gc pubsub topics add-iam-policy-binding "$ERROR_TOPIC" \
      --member="serviceAccount:${reader}" --role=roles/pubsub.publisher >/dev/null
    gc secrets add-iam-policy-binding "$ELASTIC_API_KEY_SECRET" \
      --member="serviceAccount:${reader}" --role=roles/secretmanager.secretAccessor >/dev/null
    echo "== Dataflow job ${TOPIC}-to-elastic"
    # Template parameters: https://cloud.google.com/dataflow/docs/guides/templates/provided/pubsub-to-elasticsearch
    gc dataflow flex-template run "${TOPIC}-to-elastic" \
      --region="$REGION" \
      --template-file-gcs-location="gs://dataflow-templates-${REGION}/latest/flex/PubSub_to_Elasticsearch_Flex" \
      --service-account-email="$reader" \
      --max-workers=1 \
      --worker-machine-type=e2-small \
      --parameters="inputSubscription=projects/${PROJECT_ID}/subscriptions/${SUBSCRIPTION},errorOutputTopic=projects/${PROJECT_ID}/topics/${ERROR_TOPIC},connectionUrl=${ELASTIC_URL},apiKeySource=SECRET_MANAGER,apiKeySecretId=projects/${PROJECT_ID}/secrets/${ELASTIC_API_KEY_SECRET}/versions/latest,dataset=pubsub,namespace=${ELASTIC_NAMESPACE}"
    ;;
  *)
    echo "SHIPPER must be agent, dataflow or none, not '${SHIPPER}'." >&2
    exit 1
    ;;
esac

echo
echo "Done. Sink filter: ${filter}"
echo "Install the Elastic side once: docs/examples/elastic/elastic-setup.sh (pipeline + index template)."
