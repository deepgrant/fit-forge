# FFMForge — architecture & interaction

> 🚧 Design reference for the service + frontend. Companion to [ui-spec.md](ui-spec.md).

## Model: a serverless document editor

FFMForge is **action-driven request/response**: the user uploads binary `.fit`
artifacts, Lambda transforms them (merge/edit), and the browser downloads a new
artifact. No polling and no always-on application server.

The deployable shape is:

- Angular assets in a private S3 bucket.
- CloudFront + ACM + Route53 for TLS and routing.
- API Gateway HTTP API + Lambda for `/ffmforge/v1/*`.
- A private S3 data bucket for uploaded, intermediate, and merged `.fit` files.

## State + scaling

Lambda owns no session state. Uploaded/merged `.fit` bytes live in AWS S3, keyed
by opaque ids that encode expiry. Each operation fetches bytes, decodes, acts,
and writes any result back to S3. Re-decoding per operation keeps the codec
lossless because we never serialize the in-memory Garmin SDK-backed model.

Consequences:

- No session affinity or sticky routing.
- No idle container cost.
- Lambda concurrency scales with demand and scales back to zero when idle.
- The frontend and data buckets are private; CloudFront and presigned URLs are
  the public access points.

Deployment-specific values (region, profile, hosted zone id/name, domain, bucket
names, image URI) are supplied through ignored tfvars or environment variables.
They must not be committed.

## Session lifecycle & TTL

S3 lifecycle expiration is day-granularity only, so lifecycle alone cannot
provide a short TTL. FFMForge enforces a short TTL in the app and uses lifecycle
as a backstop:

- Object ids include `expiresAt` (epoch millis), e.g. `fit/uploads/<expires>_<uuid>.fit`.
- Lambda checks expiry on every read and returns `410 Gone` for expired ids.
- EventBridge invokes the Lambda cleanup path every 15 minutes to delete expired objects.
- S3 lifecycle expires `fit/` objects after 1 day as a hard backstop.

Defaults:

- Working object TTL: 2 hours.
- Presigned upload/download URL TTL: 15 minutes.
- S3 lifecycle backstop: 1 day.

## Frontend session handling

The frontend times out instead of sending keep-alive requests:

- It starts a timer matching the backend TTL.
- Near expiry, it shows a soft warning.
- On expiry, it resets to a “session expired — re-upload” state.
- Every API call handles `410`/`404` defensively and prompts re-upload.

## New-version detection

Angular writes a `version.json` into the frontend assets during deployment. The
SPA polls it every ~60 seconds and on window focus. If the served version differs
from the baked-in version, the UI shows a floating “new version available ·
Refresh” toast and reloads on click.

## API surface

CloudFront routes `/ffmforge/v1/*` to API Gateway. `.fit` bytes move directly
between the browser and S3 via presigned URLs.

```text
POST /ffmforge/v1/uploads
  -> { files: [{ id, name, url, expiresAt }] }

POST /ffmforge/v1/fit/describe
  -> summaries/devices/layout for uploaded ids

POST /ffmforge/v1/fit/merge
  -> dry run: MergeReport
  -> real run: stored result id + MergeReport

GET /ffmforge/v1/fit/{id}/download
  -> short-lived presigned GET URL

GET /ffmforge/v1/version
GET /health
```

Errors map from the domain: invalid FIT → `422`; merge conflict/overlap → `409`;
expired id → `410`; unknown id → `404`.

## Frontend

Angular 22, standalone components, signals for workspace state, Zod schemas at
the API boundary, and MapLibre GL for `/track`-derived route rendering. Theme via
`[data-theme]` (default light) + SCSS custom properties — see [ui-spec.md](ui-spec.md).

## Privacy

Ride GPS data is personal. Objects are short-lived, private, encrypted at rest,
and accessed only through unguessable ids and short-lived presigned URLs. No
accounts in v1.
