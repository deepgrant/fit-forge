# FFMForge

**FIT (Flexible and Interoperable Data Transfer) file editor and joiner.**

The primary goal: when a single long ride is captured as **multiple FIT
recordings** (a battery swap, a device restart, a long café stop that splits the
file), join them back into **one continuous, valid `.fit`** — a complete record
of the whole journey.

---

## 🚧 Under construction

This repository is in an **early foundation / feasibility phase**. The FIT
codec and the merge engine work and are tested against real Garmin data, but the
web application (HTTP API + Angular UI) is **not built yet**.

### What works today
- **Lossless FIT codec** — decode/encode real `.fit` files via the official
  Garmin FIT Java SDK, wrapped behind a `FitCodec` facade. Every field and
  message type round-trips, including manufacturer/developer fields FFMForge
  doesn't interpret (verified on real activity files).
- **Merge engine** — join multiple recordings of one ride into a single
  activity. Gaps between recordings are **preserved as pauses** (so elapsed time
  includes the gap but moving time does not), distances are rebased to one
  cumulative total, and session/lap/activity aggregates are recomputed.
- **Summaries** — extract the devices used (sensor kind, battery, manufacturer),
  the file layout (message-type histogram), and ride stats (distance, elapsed /
  moving time, avg/max speed and power, avg/max temperature, sport, primary
  recording device).

### Not built yet
- Pekko HTTP service (upload / summary / merge / download endpoints).
- Angular front-end (upload, map view, merge, download).
- Editing beyond join (trim, delete bad GPS points, metadata, lap ops).

---

## Architecture (intended)

This is a single-module Gradle build with a Scala 3 + Apache Pekko (Actor/Stream/HTTP) 
backend and an Angular front-end served as static files. Today only the FIT-processing 
core (the `ffmforge.fit` package) exists.

- **Language/build:** Scala 3.8.4, Gradle (wrapper, 9.x), JDK 21 target.
- **Java version — pinned to JDK 21 (LTS):** the build declares a Gradle
  **Java toolchain** of 21 and compiles with `-release 21`, so the whole build
  (compile, tests, demos) runs on JDK 21 regardless of the JDK that launches
  Gradle. 21 is the current LTS; pinning it via the toolchain keeps builds
  reproducible across machines with different JDKs installed and matches the
  `eclipse-temurin:21` deployment image. Gradle auto-detects an installed JDK 21;
  if yours is in a non-standard location (e.g. a keg-only Homebrew install), set
  `org.gradle.java.installations.paths` in `~/.gradle/gradle.properties`.
- **FIT library:** official `com.garmin:fit` SDK, isolated behind
  `FitCodec` — only `GarminFitCodec` imports `com.garmin.fit`.
- **Model:** a lossless generic message store (`FitMessage`) is the source of
  truth; typed views (`Record`, `Session`, `Lap`, …) are derived from it.

Key source:

| File | Purpose |
|------|---------|
| `source/scala/ffmforge/fit/FitCodec.scala` | Codec facade (decode/encode/stats) |
| `source/scala/ffmforge/fit/GarminFitCodec.scala` | Garmin SDK implementation |
| `source/scala/ffmforge/fit/FitModel.scala` | Domain model + typed views |
| `source/scala/ffmforge/fit/FitMerge.scala` | Join engine + merge report |
| `source/scala/ffmforge/fit/FitSummary.scala` | Devices + ride statistics |
| `source/scala/ffmforge/fit/FitLayout.scala` | File layout summary |

---

## Requirements

- JDK 21 available on the machine (the build uses a Gradle toolchain targeting
  `-release 21`). Gradle can launch on JDK 17+ but will compile/test/run on 21.
- Node compatible with Angular 22 for frontend work: use Node `^24.15.0`
  for now. Node 26.3.1 currently triggers an esbuild deadlock during
  `ng build` in this project, even though Angular's published engine range
  allows Node 26.
- No global Gradle needed — use the bundled `./gradlew` wrapper.
- Internet access on first build (downloads Gradle, Pekko, and the Garmin SDK
  from Maven Central; downloads Angular packages from npm for frontend work).

---

## Setting up JDK 21 for Gradle

The build uses a Gradle **Java toolchain** pinned to JDK 21, so a JDK 21 must be
installed and discoverable by Gradle. (Gradle's auto-download / foojay resolver
is intentionally not used — it currently fails against the foojay API.)

**1. Install a JDK 21.** Any vendor works — for example:

- macOS (Homebrew): `brew install openjdk@21`
- SDKMAN (any OS): `sdk install java 21-tem`
- Or download a build from [Adoptium](https://adoptium.net/temurin/releases/?version=21).

**2. Make sure Gradle can find it.** Check what Gradle detects:

```bash
./gradlew -q javaToolchains
```

If `JDK 21` is listed, you're done. If it isn't (common with **keg-only
Homebrew** installs, which aren't symlinked into a standard location), tell
Gradle where it is by adding the path to **`~/.gradle/gradle.properties`**
(user-level — not committed):

```properties
org.gradle.java.installations.paths=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
```

Adjust the path for your install (`sdk home java 21-tem`, or the Adoptium
install dir). Multiple paths can be comma-separated. Alternatively, macOS users
can symlink the JDK so it is auto-detected:

```bash
sudo ln -sfn /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk \
  /Library/Java/JavaVirtualMachines/openjdk-21.jdk
```

Gradle itself may launch on any JDK 17+, but it will compile, test, and run the
demos on JDK 21 via the toolchain.

---

## Build & test

```bash
./gradlew check      # compile (-Werror) + scalafix + scalafmt + all unit tests
./gradlew test       # unit tests only
```

Quality gates are enforced on every build: Scala compiles with `-Werror`,
Scalafix checks import ordering and bans `var`/`null`/`throw`/`asInstanceOf`
(outside the SDK facade), and Scalafmt checks formatting. To auto-fix:

```bash
./gradlew scalafix spotlessApply
```

---

## AWS deployment tasks

Gradle wraps the OpenTofu, Docker, AWS CLI, and frontend sync commands used for
AWS deployment. These tasks read sensitive deployment values from ignored local
OpenTofu config (`infra/local.auto.tfvars`) or OpenTofu outputs; do not commit
those local files.

```bash
./gradlew tofuInit
./gradlew tofuValidate
./gradlew tofuPlan
./gradlew deployInfra
```

Backend image flow:

```bash
./gradlew refreshBackend
```

`refreshBackend` runs the quality gates, builds the Lambda Docker image, pushes
an immutable epoch-millis tag to the configured ECR repository, writes that image
URI back into ignored local OpenTofu config, applies OpenTofu, and re-enables the
Lambda if it had been disabled.

Frontend flow:

```bash
./gradlew refreshFrontend
```

`refreshFrontend` syncs the static frontend artifact to the private frontend S3
bucket and invalidates CloudFront. By default it builds and syncs the Angular
production output from `frontend/dist/ffm-forge-ui/browser`. Override it with a
static directory when needed:

```bash
./gradlew refreshFrontend -PffmForgeFrontendDir=/path/to/static/site
```

Full deployment:

```bash
./gradlew deploy
```

`deploy` refreshes the backend first, then syncs the frontend.

Undeploy tears down the deployed serving/runtime stack while preserving buckets:

```bash
./gradlew undeploy -PconfirmUndeploy=true
```

This empties the frontend/data buckets and destroys the deployed frontend and
backend resources: CloudFront, Route53 records, ACM certificate, API Gateway,
Lambda, EventBridge, and IAM resources. It does **not** delete the S3 buckets.
The bucket resources are protected with OpenTofu `prevent_destroy`, and the
Gradle destroy wrappers deliberately target only non-bucket resources.

Scoped teardown tasks are also available:

```bash
./gradlew undeployFrontend -PconfirmUndeploy=true
./gradlew undeployBackend -PconfirmUndeploy=true
```

There is no broad Gradle task for unrestricted `tofu destroy`.

Optional deployment properties:

```bash
-PffmForgeAwsProfile=default
-PffmForgeAwsRegion=us-east-1
-PffmForgeImageTag=1781977881000
```

If omitted, the AWS profile defaults to `AWS_PROFILE` or `default`, the region
defaults to `AWS_REGION`, `AWS_DEFAULT_REGION`, or `us-east-1`, and image tags
default to the current epoch millis.

---

## Frontend

The Angular app lives under `frontend/`. It is Angular 22, standalone component
based, and uses signals for workspace state plus Zod at the API boundary.

Use Node 24.15.x for frontend build and serve tasks until the Node 26/esbuild
deadlock is resolved.

```bash
./gradlew frontendInstall
./gradlew frontendBuild
./gradlew frontendServe
```

The first implemented screen is the merge workspace shell: upload FIT segments,
describe them through `/ffmforge/v1/fit/describe`, preview the merge, then merge
and download the resulting `.fit`.

---

## Runnable demos

Two narrated demos exercise the codec and the merge on real or synthetic data.
Both are **gated on `check`**, so they only run after lint + tests pass.

### Codec round-trip + ride summary

```bash
./gradlew codecDemo                                    # synthetic ride (no file needed)
./gradlew codecDemo --args="samples/your_ride.fit"     # real file
```

With a real file it decodes the activity, prints the devices used and a ride
summary (distance, times, speeds, power, temperature — in metric and imperial),
then re-encodes and re-decodes to prove nothing was lost. The re-encoded file is
written next to the input as `*.roundtrip.fit`.

### Deployed Lambda codec round-trip

```bash
./gradlew lambdaCodecDemo --args="samples/your_ride.fit"
./gradlew lambdaCodecDemo -PffmForgeBaseUrl=https://ffmforge.com --args="samples/your_ride.fit"
```

This is the deployed Lambda equivalent of `codecDemo`: it requests a presigned
upload URL from the public API, uploads the local FIT file to private S3, then
asks Lambda to decode, re-encode, re-decode, summarize, and verify the uploaded
file. The backend must already include the `/ffmforge/v1/fit/codec-demo` route,
so run `./gradlew refreshBackend` before using it against a deployed environment.

### Join two recordings

```bash
./gradlew mergeDemo                                     # synthetic two-segment join
./gradlew mergeDemo --args="samples/your_ride.fit"      # splits the ride in two, re-joins
```

Given a real file it splits the ride into two recordings (dropping a middle
slice to simulate a gap), then joins them and prints a full report: what was
read per segment, the gap preserved as a pause, the recomputed totals, and the
final file layout. The merged file is written as `*.merged.fit`.

> **Sample files:** `.fit` files and the `samples/` directory are git-ignored —
> drop your own activity files into `samples/` to try the demos. (macOS note:
> files under `~/Downloads`, `~/Desktop`, `~/Documents` are protected by privacy
> controls; copy them into the project to let the JVM read them.)

---

## License

See [LICENSE](LICENSE). Note the bundled Garmin FIT SDK is distributed under the
Garmin FIT Protocol License (proprietary, free to use).
