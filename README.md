# fit-forge

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
  message type round-trips, including manufacturer/developer fields fit-forge
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

The stack mirrors the MBTATracker project: a single-module Gradle build with a
Scala 3 + Apache Pekko (Actor/Stream/HTTP) backend and an Angular front-end
served as static files. Today only the FIT-processing core (the `fitforge.fit`
package) exists.

- **Language/build:** Scala 3.8.4, Gradle (wrapper, 9.x), JDK 17 target.
- **FIT library:** official `com.garmin:fit` SDK, isolated behind
  `FitCodec` — only `GarminFitCodec` imports `com.garmin.fit`.
- **Model:** a lossless generic message store (`FitMessage`) is the source of
  truth; typed views (`Record`, `Session`, `Lap`, …) are derived from it.

Key source:

| File | Purpose |
|------|---------|
| `source/scala/fitforge/fit/FitCodec.scala` | Codec facade (decode/encode/stats) |
| `source/scala/fitforge/fit/GarminFitCodec.scala` | Garmin SDK implementation |
| `source/scala/fitforge/fit/FitModel.scala` | Domain model + typed views |
| `source/scala/fitforge/fit/FitMerge.scala` | Join engine + merge report |
| `source/scala/fitforge/fit/FitSummary.scala` | Devices + ride statistics |
| `source/scala/fitforge/fit/FitLayout.scala` | File layout summary |

---

## Requirements

- JDK 17+ (the build targets `-release 17`).
- No global Gradle needed — use the bundled `./gradlew` wrapper.
- Internet access on first build (downloads Gradle, Pekko, and the Garmin SDK
  from Maven Central).

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
