package ffmforge.fit

import java.time.Duration
import java.time.Instant

final case class FitFileDescription(
  records: Int,
  sessions: Int,
  laps: Int,
  events: Int,
  startTime: Option[Instant],
  endTime: Option[Instant],
  distanceM: Option[Double],
)

final case class CodecCheck(label: String, passed: Boolean, detail: String)

final case class CodecDemoReport(
  id: String,
  originalBytes: Int,
  reencodedBytes: Int,
  original: FitFileDescription,
  redecoded: FitFileDescription,
  originalStats: FitStats,
  reencodedStats: FitStats,
  summary: RideSummary,
  primaryDevice: Option[DeviceInfo],
  devices: Vector[DeviceInfo],
  layout: FitLayout,
  checks: Vector[CodecCheck],
  passed: Boolean,
)

object FitCodecReport {

  def fromBytes(id: String, bytes: Array[Byte], codec: FitCodec): CodecDemoReport = {
    val decoded   = codec.decode(bytes)
    val reencoded = codec.encode(decoded)
    val redecoded = codec.decode(reencoded)
    val checks    = roundTripChecks(decoded, redecoded)

    CodecDemoReport(
      id = id,
      originalBytes = bytes.length,
      reencodedBytes = reencoded.length,
      original = describe(decoded),
      redecoded = describe(redecoded),
      originalStats = codec.stats(bytes),
      reencodedStats = codec.stats(reencoded),
      summary = FitSummary.ride(decoded),
      primaryDevice = FitSummary.primaryDevice(decoded),
      devices = FitSummary.devices(decoded),
      layout = FitLayout.of(decoded),
      checks = checks,
      passed = checks.forall(_.passed),
    )
  }

  private def describe(f: FitFile): FitFileDescription = {
    FitFileDescription(
      records = f.records.size,
      sessions = f.sessions.size,
      laps = f.laps.size,
      events = f.events.size,
      startTime = f.startTime,
      endTime = f.endTime,
      distanceM = f.records.lastOption.flatMap(_.distanceM),
    )
  }

  private def roundTripChecks(decoded: FitFile, redecoded: FitFile): Vector[CodecCheck] = {
    val strings = stringValues(decoded)
    Vector(
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
        "string fields preserved",
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
  }

  private def check(label: String, passed: Boolean, detail: String): CodecCheck =
    CodecCheck(label, passed, detail)

  private def stringValues(f: FitFile): List[String] =
    f.messages.flatMap(_.fields).flatMap(_.values).collect { case FitValue.Text(s) => s }.sorted.toList

  private def noFieldDropped(before: FitFile, after: FitFile): Boolean =
    before.messages.size == after.messages.size &&
      before.messages.zip(after.messages).forall { case (a, b) =>
        a.fields.map(_.num).toSet.subsetOf(b.fields.map(_.num).toSet)
      }

  def humanDuration(d: Duration): String = {
    val s = d.getSeconds
    f"${s / 3600}%dh ${(s % 3600) / 60}%02dm ${s % 60}%02ds"
  }
}
