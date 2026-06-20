# fit-forge — architecture & interaction

> 🚧 Design reference for the (not-yet-built) service + frontend. Records the
> backend/frontend interaction model, the scaling approach, and the session
> lifecycle decisions. Companion to [ui-spec.md](ui-spec.md).

## Model: a document editor

fit-forge is **action-driven request/response**: the user uploads binary `.fit`
artifacts, the server transforms them (merge/edit), and returns a new artifact.
No polling, no websockets.

**Stack:** single-module Gradle, Pekko HTTP, Spray-JSON, self-signed HTTPS,
Angular served as static files with a dev proxy, Zod at the API boundary,
MapLibre GL for the map. Inputs/outputs add **multipart upload** and **streamed
binary download**; the frontend uses **Angular 22 signals** for workspace state.

## State + scaling: stateless pods, S3-backed sessions

Pods hold **no** session state. Uploaded/merged `.fit` **bytes** live in
**AWS S3**, keyed by id; the id is the session / capability token. Each operation fetches bytes → decodes → acts → (for merge)
stores the result under a new id. Re-decoding per op is cheap (ms) and keeps the
codec losslessly correct for free (we never serialize the in-memory model / SDK
refs — we keep the original bytes).

Consequences:
- **No session affinity / sticky routing** — round-robin ingress; any pod serves
  any request.
- **Horizontal autoscale** (K8s HPA on CPU / in-flight; KEDA for scale-to-zero).
  Scaling N→1 is safe because pods own nothing — drain connections and terminate.
- Optional later: a best-effort per-pod LRU decode cache (perf only, not
  correctness).

Build implication: a `FitStore` trait (`put(bytes) → id`, `get(id) → bytes`,
`delete(id)`, `sweepExpired`) with an AWS S3 implementation via
**`pekko-connectors-s3`** (streams to/from S3). Credentials come from the AWS
Default Credentials Provider Chain (`default` profile locally, IAM role in the
cloud); region **us-east-1**. The bucket is created by OpenTofu (`infra/`), not
by the app.

## Session lifecycle & TTL

S3 lifecycle expiration is **day-granularity only** (minimum 1 day, run as a
once-daily batch — an object can live up to ~48h before the rule reaps it). So
lifecycle alone cannot give a short TTL. We enforce a short TTL in the app and
use lifecycle only as a backstop:

- Stamp each object with an **`expiresAt`** (object metadata/tag) at upload, set
  to **now + 2h** (env-configurable, can be minutes).
- **Check it on read** — any GET of an expired id returns `410 Gone` and lazily
  deletes the object.
- A lightweight **scheduled sweeper** (a Pekko scheduler in the pod) lists and
  deletes expired objects so they don't linger.
- The **S3 lifecycle rule = 1 day** is a guaranteed hard backstop (covers
  anything the sweeper misses, e.g. all pods down).

**Defaults:** app TTL **2h** (tunable down to minutes), lifecycle backstop **1
day** (an S3 bucket lifecycle rule).

## Frontend session handling: timeout + reset (no keep-alive)

We **timeout**, not heartbeat. Keep-alive pinging means background timers, a
refresh endpoint, and re-stamping `expiresAt` on every ping — which fights the
short-TTL and privacy goals.

- The client starts a timer matching the backend TTL; near expiry it shows a soft
  "this session will expire soon" notice, and on expiry flips to a **"session
  expired — re-upload"** empty state.
- Every API call **defensively handles `410`/`404`** (the authoritative signal):
  if an id is gone, reset the workspace and prompt to re-upload.
- No keep-alive traffic. Re-uploading is a mild, honest cost — the user still has
  the original `.fit` files locally, so it's never data loss.

## New-version detection → "refresh" toast

Standard SPA "new build available" pattern, cheap because Angular already hashes
asset filenames:

- The build writes a **`version.json`** (git sha / image tag) into the static
  assets. The running SPA knows its own **baked-in version**.
- The SPA **polls `version.json`** (served uncached) **every ~60s and on
  window-focus**. When the served version ≠ the baked-in version, it shows a
  **floating toast: "A new version of fitforge is available · Refresh"**.
- Click → `window.location.reload()` → new `index.html` + new hashed bundles
  load. No service worker required.

The container image's version identifier (md5 / tag) feeds straight into
`version.json` at build time, so a new container push is exactly what trips the
toast. (Angular-native alternative: `SwUpdate` via a PWA service worker — only
worth it if offline support is wanted later.)

## API surface

Path prefix **`/fitforge/v1/`** (versioned; allows `/v2/` later). `/health` is
kept un-versioned for LB / k8s probes.

```
POST /fitforge/v1/fit/upload         multipart(N files)
     → [{ id, fileId, summary: RideSummary, devices: DeviceInfo[],
           layout: FitLayout }]  + cross-file gapAnalysis
GET  /fitforge/v1/fit/{id}/track     → GeoJSON LineString (+ start/end/gap features)
GET  /fitforge/v1/fit/{id}/summary   → RideSummary + devices
POST /fitforge/v1/fit/merge          { ids[], gapHandling, lapStrategy, dryRun }
     → { id?, report: MergeReport }      (dryRun: report only, no stored file)
GET  /fitforge/v1/fit/{id}/download  → application/octet-stream (.fit)
POST /fitforge/v1/fit/{id}/edit      (later) { ops[] } → { id, ... }
GET  /health
```

The merge **`dryRun`** flag returns an authoritative `MergeReport` without
producing a stored file — it powers the live merge report in the UI; "Merge &
download" runs it for real.

**Errors** map from the domain: invalid FIT → `422` + message; `FitMerge`
`Left(...)` (e.g. overlapping segments) → `409` + that message; expired/unknown
id → `410`/`404` → client resets and re-prompts upload.

## Data contract

Responses serialize types that already exist in the codec layer:
`RideSummary` / `DeviceInfo` (summary), `MergeReport` / `MergeOutcome` (merge),
`FitLayout` (file layout). The track endpoint derives GeoJSON from
`FitFile.records`. Units stay SI on the wire; the UI formats metric + imperial.

## Frontend

Angular 22, standalone components, **signals** for workspace state
(`segments`, `order`, `options`, `mergeReport`, `result`); **Zod** schemas mirror
each response; **MapLibre GL** renders `/track` with the Forge route styling.
Theme via `[data-theme]` (default light) + SCSS custom properties — see
[ui-spec.md](ui-spec.md).

## Privacy

Ride GPS data is personal: S3 TTL eviction (≤ 2h app / 1 day backstop), nothing
persisted long-term, no accounts in v1. The id is an unguessable capability
token.

## Dev / prod wiring

In dev, `ng serve` proxies API calls to the backend; in prod the backend serves
the built Angular as static files. The backend uses **real AWS S3** everywhere
(`default` profile locally via the credential chain, IAM role in the cloud),
region **us-east-1**, against the OpenTofu-provisioned bucket. `./gradlew run`
runs it locally; `docker-compose.yml` runs the container with `~/.aws` mounted.
Tests hit the same real bucket and cancel themselves when no AWS credentials
resolve.
