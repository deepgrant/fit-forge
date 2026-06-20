package ffmforge.fit

import java.time.Instant
import java.time.temporal.ChronoUnit

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

final class FitMergeSpec extends AnyFunSuite with Matchers {

  /** A simple segment: `points` records, 1s apart, 8 m/s, starting at `startDist`. */
  private def segment(start: Instant, points: Int, startDist: Double): FitFile = {
    val records = (0 until points).map { i =>
      Record(
        timestamp = start.plus(i.toLong, ChronoUnit.SECONDS),
        position = Some(GpsPoint(51.5 + i * 0.0001, -0.12 + i * 0.0001)),
        distanceM = Some(startDist + i * 8.0),
        speedMps = Some(8.0),
      )
    }.toVector
    FitFile.of(FileId(), records)
  }

  private val t0 = Instant.parse("2026-06-15T08:00:00Z")

  test("merge preserves the gap: elapsed includes it, timer time excludes it") {
    val segA       = segment(t0, points = 60, startDist = 0.0) // 0..59s
    val gapSeconds = 600L                                      // 10-minute café stop
    val segB       = segment(t0.plus(59 + gapSeconds, ChronoUnit.SECONDS), points = 60, startDist = 0.0)

    val merged  = FitMerge.merge(Seq(segB, segA)).toOption.get // deliberately out of order
    val session = merged.sessions.head

    val activeSeconds  = 59.0 * 2 // each segment spans 59s
    val elapsedSeconds = activeSeconds + gapSeconds

    session.totalTimerTimeS.get shouldBe activeSeconds +- 0.001
    session.totalElapsedTimeS.get shouldBe elapsedSeconds +- 0.001
  }

  test("merge concatenates records in monotonic timestamp order") {
    val segA   = segment(t0, 30, 0.0)
    val segB   = segment(t0.plus(120, ChronoUnit.SECONDS), 30, 0.0)
    val merged = FitMerge.merge(Seq(segA, segB)).toOption.get

    merged.records should have size 60
    val times = merged.records.map(_.timestamp)
    times shouldBe times.sortWith(_.isBefore(_))
  }

  test("merge brackets the gap with STOP_ALL then START timer events") {
    val segA   = segment(t0, 10, 0.0)
    val segB   = segment(t0.plus(300, ChronoUnit.SECONDS), 10, 0.0)
    val merged = FitMerge.merge(Seq(segA, segB)).toOption.get

    merged.events.map(_.event) shouldBe Vector(
      TimerEvent.Start,   // overall start
      TimerEvent.StopAll, // gap begins
      TimerEvent.Start,   // gap ends
      TimerEvent.StopAll, // overall end
    )
    merged.events(1).timestamp shouldBe segA.records.last.timestamp
    merged.events(2).timestamp shouldBe segB.records.head.timestamp
  }

  test("merge rebases distance to a single cumulative total") {
    val segA   = segment(t0, 60, startDist = 0.0) // covers 0..472m
    val segB   = segment(t0.plus(660, ChronoUnit.SECONDS), 60, startDist = 0.0)
    val merged = FitMerge.merge(Seq(segA, segB)).toOption.get

    val perSegment = 59 * 8.0
    merged.records.last.distanceM.get shouldBe (perSegment * 2) +- 0.001
    merged.sessions.head.totalDistanceM.get shouldBe (perSegment * 2) +- 0.001
  }

  test("OnePerSegment lap strategy yields one lap per source file") {
    val merged = FitMerge
      .merge(Seq(segment(t0, 20, 0.0), segment(t0.plus(120, ChronoUnit.SECONDS), 20, 0.0)))
      .toOption
      .get
    merged.laps should have size 2
  }

  test("overlapping recordings are rejected") {
    val segA = segment(t0, 60, 0.0)
    val segB = segment(t0.plus(30, ChronoUnit.SECONDS), 60, 0.0) // starts before A ends
    FitMerge.merge(Seq(segA, segB)).isLeft shouldBe true
  }

  test("empty input is rejected") {
    FitMerge.merge(Seq.empty).isLeft shouldBe true
  }

  test("merge passes through unmodelled message types (e.g. device_info)") {
    val segA   = FitFile(segment(t0, 30, 0.0).messages :+ FitMessage(23).setInstant(253, t0).setNumeric(2, 1.0))
    val segB   = segment(t0.plus(120, ChronoUnit.SECONDS), 30, 0.0)
    val merged = FitMerge.merge(Seq(segA, segB)).toOption.get
    merged.messages.exists(_.globalNum == 23) shouldBe true
  }

  test("mergeWithReport logs segments, the gap, and the final layout") {
    val segA = segment(t0, 60, 0.0)
    val segB = segment(t0.plus(660, ChronoUnit.SECONDS), 60, 0.0) // 10-minute gap (601s after segA ends)
    val MergeOutcome(merged, report) = FitMerge.mergeWithReport(Seq(segA, segB)).toOption.get

    report.segments.map(_.records) shouldBe Vector(60, 60)
    report.gaps.map(_.afterSegment) shouldBe Vector(1)
    report.gaps.head.seconds shouldBe 601.0 +- 0.001
    report.movingSeconds should be < report.elapsedSeconds
    report.layout.totalMessages shouldBe merged.messages.size
    report.layout.counts.toMap.get("record") shouldBe Some(120)
    report.layout.counts.toMap.get("session") shouldBe Some(1)
  }

  test("joining two segments keeps every record under one session/activity") {
    val merged = FitMerge
      .merge(Seq(segment(t0, 100, 0.0), segment(t0.plus(900, ChronoUnit.SECONDS), 100, 0.0)))
      .toOption
      .get
    merged.records should have size 200
    merged.sessions should have size 1
    merged.messages.count(_.globalNum == FitProfile.Mesg.Activity) shouldBe 1
  }

  test("merged file survives a codec round-trip") {
    val codec = new GarminFitCodec()
    val merged =
      FitMerge.merge(Seq(segment(t0, 60, 0.0), segment(t0.plus(660, ChronoUnit.SECONDS), 60, 0.0))).toOption.get
    val decoded = codec.decode(codec.encode(merged))
    decoded.records should have size merged.records.size
  }
}
