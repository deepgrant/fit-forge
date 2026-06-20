package ffmforge.examples

import java.nio.file.Files
import java.nio.file.Paths
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit

import ffmforge.fit.Event
import ffmforge.fit.FileId
import ffmforge.fit.FitCodec
import ffmforge.fit.FitFile
import ffmforge.fit.FitSummary
import ffmforge.fit.FitValue
import ffmforge.fit.GarminFitCodec
import ffmforge.fit.GpsPoint
import ffmforge.fit.Lap
import ffmforge.fit.Record
import ffmforge.fit.Session
import ffmforge.fit.TimerEvent

/**
 * Runnable demonstration of the FIT codec. It is NOT part of the application — it lives in `ffmforge.examples` as a
 * human-readable, narrated proof that decode -> model -> encode round-trips with the Garmin SDK.
 *
 *   - With no argument it synthesises a ride in memory (so no sample file is needed), encodes it to real `.fit` bytes,
 *     decodes them back, and verifies every field survived the trip.
 *   - Given a path it round-trips a real `.fit` file: decode -> re-encode -> re-decode, then compares the two decodes.
 *
 * Run: `./gradlew codecDemo` or `./gradlew codecDemo --args="/path/to/activity.fit"`.
 */
object FitCodecDemo {
  def main(args: Array[String]): Unit = {
    val codec = new GarminFitCodec()

    banner("FFMForge FIT codec demonstration")
    println("The Garmin FIT format is a binary container: a header, a stream of definition/data")
    println("messages, and a trailing CRC-16. This demo runs real encode/decode against that format")
    println("and checks the decoded data matches what we put in.\n")

    val syntheticOk = syntheticRoundTrip(codec)

    val realOk = args.headOption match {
      case Some(path) => realRoundTrip(codec, path)
      case None =>
        println("\n(no file path supplied — skipping the real-file round-trip)")
        println("    pass one with:  ./gradlew codecDemo --args=\"/path/to/activity.fit\"")
        true
    }

    banner(if (syntheticOk && realOk) "OVERALL RESULT: PASS" else "OVERALL RESULT: FAIL")
  }

  // ── synthetic round-trip ──────────────────────────────────────────────────

  private def syntheticRoundTrip(codec: FitCodec): Boolean = {
    banner("1. Synthetic ride: model -> encode -> decode")

    println("Building an in-memory FitFile (our domain model)...")
    val original = SampleRide.build()
    describeFitFile("  built", original)

    println("\nEncoding the model to FIT bytes (FileEncoder writes header + messages + CRC)...")
    val bytes = codec.encode(original)
    println(s"  -> produced ${bytes.length} bytes of binary .fit")
    println(f"  -> header signature: ${signature(bytes)}   (offset 8-11 must read '.FIT')")

    println("\nDecoding those bytes back into a fresh FitFile (FitDecoder reads + validates CRC)...")
    val decoded = codec.decode(bytes)
    describeFitFile("  decoded", decoded)

    println("\nVerifying the decoded data matches the original, field by field:")
    val first = original.records.head
    val back  = decoded.records.head
    val results = Vector(
      check(
        "record count",
        decoded.records.size == original.records.size,
        s"${decoded.records.size} == ${original.records.size}",
      ),
      check(
        "session count",
        decoded.sessions.size == original.sessions.size,
        s"${decoded.sessions.size} == ${original.sessions.size}",
      ),
      check("lap count", decoded.laps.size == original.laps.size, s"${decoded.laps.size} == ${original.laps.size}"),
      check(
        "first GPS point (semicircle round-trip)",
        latLonClose(first.position, back.position),
        f"${fmtPos(first.position)} -> ${fmtPos(back.position)}",
      ),
      check(
        "first distance (m)",
        closeOpt(first.distanceM, back.distanceM, 0.5),
        s"${first.distanceM.getOrElse("-")} -> ${back.distanceM.getOrElse("-")}",
      ),
      check(
        "first speed (m/s)",
        closeOpt(first.speedMps, back.speedMps, 0.05),
        s"${first.speedMps.getOrElse("-")} -> ${back.speedMps.getOrElse("-")}",
      ),
      check(
        "first heart rate (bpm)",
        first.heartRate == back.heartRate,
        s"${first.heartRate.getOrElse("-")} -> ${back.heartRate.getOrElse("-")}",
      ),
      check(
        "last timestamp",
        decoded.records.last.timestamp == original.records.last.timestamp,
        s"${decoded.records.last.timestamp}",
      ),
    )
    summarise("Synthetic round-trip", results.forall(identity))
  }

  // ── real-file round-trip ────────────────────────────────────────────────────

  private def realRoundTrip(codec: FitCodec, path: String): Boolean = {
    banner(s"2. Real file round-trip: $path")

    println("Reading the file from disk...")
    val in = Files.readAllBytes(Paths.get(path))
    println(s"  -> read ${in.length} bytes")

    println("\nDecoding the original file...")
    val decoded = codec.decode(in)
    describeFitFile("  original", decoded)
    decoded.records.headOption.foreach(r => println(s"    first sample at ${r.timestamp}"))
    decoded.records.lastOption.foreach(r => println(s"    last  sample at ${r.timestamp}"))

    printOverview(decoded)

    println("\nRe-encoding the decoded model, then decoding that again...")
    val reencoded = codec.encode(decoded)
    val redecoded = codec.decode(reencoded)
    println(s"  -> re-encoded to ${reencoded.length} bytes; re-decoded ${redecoded.records.size} records")

    val outPath = roundTripPath(Paths.get(path))
    Files.write(outPath, reencoded): Unit
    println(s"  -> wrote round-tripped file to $outPath")

    val before = codec.stats(in)
    val after  = codec.stats(reencoded)
    println(s"  -> file size:           ${in.length} bytes -> ${reencoded.length} bytes")
    println(
      s"  -> definition messages: ${before.definitionMessages} -> ${after.definitionMessages}  (FIT structural overhead)"
    )
    println(s"  -> data messages:       ${before.dataMessages} -> ${after.dataMessages}")
    println(s"  -> developer fields:    ${before.developerFields} -> ${after.developerFields}")

    println("\nVerifying the second decode matches the first (option 3 = nothing lost):")
    val strings = stringValues(decoded)
    val results = Vector(
      check(
        "message count preserved",
        redecoded.messages.size == decoded.messages.size,
        s"${redecoded.messages.size} == ${decoded.messages.size}",
      ),
      check(
        "no field dropped from any message",
        noFieldDropped(decoded, redecoded),
        s"${decoded.messages.map(_.fields.size).sum} fields in -> ${redecoded.messages.map(_.fields.size).sum} out",
      ),
      check(
        "string fields preserved (manufacturer/device names, etc.)",
        strings == stringValues(redecoded),
        s"${strings.size} strings incl. ${strings.distinct.take(3).mkString(", ")}",
      ),
      check(
        "GPS points preserved",
        redecoded.records.count(_.position.isDefined) == decoded.records.count(_.position.isDefined),
        s"${decoded.records.count(_.position.isDefined)} points",
      ),
      check(
        "record count preserved",
        redecoded.records.size == decoded.records.size,
        s"${redecoded.records.size} == ${decoded.records.size}",
      ),
      check(
        "first/last timestamp preserved",
        redecoded.records.headOption.map(_.timestamp) == decoded.records.headOption.map(_.timestamp) &&
          redecoded.records.lastOption.map(_.timestamp) == decoded.records.lastOption.map(_.timestamp),
        s"${decoded.records.headOption.map(_.timestamp).getOrElse("-")} .. " +
          s"${decoded.records.lastOption.map(_.timestamp).getOrElse("-")}",
      ),
    )
    summarise("Real-file round-trip", results.forall(identity))
  }

  /** Dump the devices used and the headline ride statistics. */
  private def printOverview(file: FitFile): Unit = {
    val devices = FitSummary.devices(file)
    println(s"\nPrimary recording device: ${FitSummary.primaryDevice(file).map(_.displayName).getOrElse("unknown")}")
    println("Devices used in the recording:")
    if (devices.isEmpty) println("  (none recorded)")
    else
      devices.foreach { d =>
        val bits = Vector(
          d.kind.map(k => s"kind: $k"),
          d.sourceType.map(t => s"conn: $t"),
          d.batteryStatus.map(s => s"battery: $s"),
          d.softwareVersion.map(v => f"sw $v%.2f"),
          d.serialNumber.map(s => s"serial $s"),
        ).flatten.mkString(", ")
        val suffix = if (bits.isEmpty) "" else s"  ($bits)"
        println(s"  [${d.index}] ${d.displayName}$suffix")
      }

    val r = FitSummary.ride(file)
    println("\nRide summary:")
    println(s"  activity type:  ${r.sport.map(_.toLowerCase).getOrElse("-")}")
    println(s"  total distance: ${r.totalDistanceM.map(distance).getOrElse("-")}")
    println(s"  elapsed time:   ${r.elapsedSeconds.map(hms).getOrElse("-")}")
    println(s"  moving time:    ${r.movingSeconds.map(hms).getOrElse("-")}")
    println(s"  avg speed:      ${r.avgSpeedMps.map(speed).getOrElse("-")}")
    println(s"  max speed:      ${r.maxSpeedMps.map(speed).getOrElse("-")}")
    r.avgPowerW.foreach(p => println(f"  avg power:      ${p}%.0f W"))
    r.maxPowerW.foreach(p => println(f"  max power:      ${p}%.0f W"))
    r.avgTempC.foreach(t => println(s"  avg temp:       ${temp(t)}"))
    r.maxTempC.foreach(t => println(s"  max temp:       ${temp(t)}"))
  }

  private def distance(m: Double): String = f"${m / 1000.0}%.2f km / ${m / 1609.344}%.2f mi"
  private def speed(mps: Double): String  = f"${mps * 3.6}%.1f km/h / ${mps * 2.236936}%.1f mph"
  private def temp(c: Double): String     = f"$c%.1f °C / ${c * 9.0 / 5.0 + 32.0}%.1f °F"
  private def hms(seconds: Double): String = {
    val s = seconds.toLong
    f"${s / 3600}%dh ${(s % 3600) / 60}%02dm ${s % 60}%02ds"
  }

  // ── output helpers ──────────────────────────────────────────────────────────

  private def banner(title: String): Unit = {
    println("\n" + "=" * 78)
    println(title)
    println("=" * 78)
  }

  /** Print a PASS/FAIL line for one verification and return whether it passed. */
  private def check(label: String, passed: Boolean, detail: String): Boolean = {
    val tag = if (passed) "PASS" else "FAIL"
    println(s"  [$tag] $label: $detail")
    passed
  }

  private def summarise(what: String, ok: Boolean): Boolean = {
    println(s"  => $what: ${if (ok) "PASS — data is faithful" else "FAIL — data differs"}")
    ok
  }

  /** One-line summary of a FitFile's contents and span. */
  private def describeFitFile(label: String, f: FitFile): Unit = {
    val span = (f.startTime, f.endTime) match {
      case (Some(s), Some(e)) => s", spanning ${humanDuration(Duration.between(s, e))}"
      case _                  => ""
    }
    val dist = f.records.lastOption.flatMap(_.distanceM).map(d => f", ${d / 1000.0}%.2f km").getOrElse("")
    println(
      s"$label: ${f.records.size} records, ${f.sessions.size} session(s), ${f.laps.size} lap(s), " +
        s"${f.events.size} event(s)$span$dist"
    )
  }

  private def signature(bytes: Array[Byte]): String =
    if (bytes.length >= 12) new String(bytes.slice(8, 12), "US-ASCII") else "<too short>"

  private def fmtPos(p: Option[GpsPoint]): String =
    p.map(g => f"${g.lat}%.5f,${g.lon}%.5f").getOrElse("<none>")

  private def latLonClose(a: Option[GpsPoint], b: Option[GpsPoint]): Boolean = {
    (a, b) match {
      case (Some(x), Some(y)) => math.abs(x.lat - y.lat) < 1e-4 && math.abs(x.lon - y.lon) < 1e-4
      case _                  => false
    }
  }

  private def closeOpt(a: Option[Double], b: Option[Double], tol: Double): Boolean = {
    (a, b) match {
      case (Some(x), Some(y)) => math.abs(x - y) <= tol
      case (None, None)       => true
      case _                  => false
    }
  }

  private def humanDuration(d: Duration): String = {
    val s = d.getSeconds
    f"${s / 3600}%dh ${(s % 3600) / 60}%02dm ${s % 60}%02ds"
  }

  /** Sibling output path for the re-encoded file, e.g. `ride.fit` -> `ride.roundtrip.fit`. */
  private def roundTripPath(in: java.nio.file.Path): java.nio.file.Path = {
    val name = in.getFileName.toString
    val base = if (name.contains(".")) name.substring(0, name.lastIndexOf('.')) else name
    Option(in.getParent).getOrElse(Paths.get(".")).resolve(s"$base.roundtrip.fit")
  }

  /** Every string field value in the file, sorted — the fragile part of a round-trip. */
  private def stringValues(f: FitFile): List[String] =
    f.messages.flatMap(_.fields).flatMap(_.values).collect { case FitValue.Text(s) => s }.sorted.toList

  /** True if no message lost any of its field numbers across the round-trip. */
  private def noFieldDropped(before: FitFile, after: FitFile): Boolean =
    before.messages.size == after.messages.size &&
      before.messages.zip(after.messages).forall { case (a, b) =>
        a.fields.map(_.num).toSet.subsetOf(b.fields.map(_.num).toSet)
      }
}

/** Builds a small but valid synthetic cycling activity for the demo and tests. */
object SampleRide {
  def build(start: Instant = Instant.parse("2026-06-15T08:00:00Z"), points: Int = 60): FitFile = {
    val records = (0 until points).map { i =>
      val t = start.plus(i.toLong, ChronoUnit.SECONDS)
      Record(
        timestamp = t,
        position = Some(GpsPoint(51.5074 + i * 0.0001, -0.1278 + i * 0.0001)),
        distanceM = Some(i * 8.0),
        speedMps = Some(8.0),
        altitudeM = Some(35.0 + i * 0.1),
        heartRate = Some(120 + (i % 20)),
        cadence = Some(85),
        power = Some(200 + (i % 30)),
      )
    }.toVector

    val end = records.last.timestamp
    FitFile.of(
      fileId = FileId(manufacturer = Some(1), product = Some(1), timeCreated = Some(start)),
      records = records,
      events = Vector(Event(start, TimerEvent.Start), Event(end, TimerEvent.StopAll)),
      laps = Vector(
        Lap(start, end, Some((points - 1).toDouble), Some((points - 1).toDouble), Some(records.last.distanceM.get))
      ),
      sessions = Vector(
        Session(
          start,
          end,
          Some((points - 1).toDouble),
          Some((points - 1).toDouble),
          Some(records.last.distanceM.get),
          Some("CYCLING"),
        )
      ),
    )
  }
}
