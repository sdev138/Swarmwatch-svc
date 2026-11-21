# Cloud Run deployment

Clone Swarmwatch-svc and nanda-frontend beside each other. Run Cloud Build from their common parent directory; this produces one image containing the React dashboard, Java API, Java25 runtime, and matching Playwright Chromium. No provider key or local runtime settings file belongs in the build context.

```sh
gcloud builds submit . --config=Swarmwatch-svc/deploy/cloudbuild.yaml --ignore-file=Swarmwatch-svc/deploy/workspace.gcloudignore --project=YOUR_PROJECT_ID --region=us-central1
```

Create the Artifact Registry repository and dedicated build/runtime service accounts first. Grant the build account source-bucket read, registry write and logging access. Runtime needs Datastore read/write, access to the two existing provider secrets, plus the explicitly approved Nanda CREATE-only project role and prefix-restricted registration-secret management role defined beside this file.

Deploy the resulting image with real GCP_PROJECT_ID and GOOGLE_CLIENT_ID environment settings, a dedicated runtime service account, one minimum and maximum instance, one CPU,2GiB memory and CPU throttling disabled. Public ingress serves the sign-in page; API routes verify Google ID tokens. Keep min/max1 until distributed worker leases replace the single-instance orchestration model. Use CloudRun gen2 and the Chromium sandbox under a non-root user.

Add the returned CloudRun HTTPS origin to the Google OAuth web client's authorized JavaScript origins. Google Identity Services requires an exact origin match. The OAuth client secret is not needed in this ID-token flow.
