#!/usr/bin/env bash
set -euo pipefail
: "${GCP_PROJECT_ID:?Set the Google Cloud project ID}"
: "${GOOGLE_CLIENT_ID:?Set the existing Google OAuth web client ID}"
: "${IMAGE:?Set the immutable Artifact Registry image digest reference}"
gcloud run deploy swarmwatch \
  --project="$GCP_PROJECT_ID" \
  --region=us-central1 \
  --image="$IMAGE" \
  --service-account="swarmwatch-runtime@$GCP_PROJECT_ID.iam.gserviceaccount.com" \
  --execution-environment=gen2 \
  --cpu=1 --memory=2Gi \
  --min=1 --max=1 --max-instances=1 \
  --no-cpu-throttling \
  --concurrency=20 --timeout=300 --port=8080 \
  --set-env-vars="GCP_PROJECT_ID=$GCP_PROJECT_ID,GOOGLE_CLIENT_ID=$GOOGLE_CLIENT_ID" \
  --startup-probe='httpGet.path=/actuator/health,httpGet.port=8080,periodSeconds=10,timeoutSeconds=3,failureThreshold=24' \
  --liveness-probe='httpGet.path=/actuator/health,httpGet.port=8080,periodSeconds=30,timeoutSeconds=3,failureThreshold=3' \
  --allow-unauthenticated \
  --labels=app=swarmwatch \
  --quiet --format=json
