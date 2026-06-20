package ffmforge.examples

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Instant
import java.time.temporal.ChronoUnit

import ffmforge.fit.FileId
import ffmforge.fit.FitFile
import ffmforge.fit.FitMerge
import ffmforge.fit.FitProfile
import ffmforge.fit.GarminFitCodec
import ffmforge.fit.GpsPoint
import ffmforge.fit.MergeOutcome
import ffmforge.fit.MergeReport
import ffmforge.fit.Record

/**
 * Demonstrates joining two recordings of one ride into a single continuous file, with a full log of what was read, what
 * changed, and the final file's layout.
 *
 *   - With no argument it synthesises two segments with a café-stop gap between them and joins them.
 *   - Given a real `.fit` path it splits that ride into two recordings (dropping a middle slice to simulate a gap) and
 *     re-joins them — a real-data test of the merge.
 *
 * Run: `./gradlew mergeDemo` or `./gradlew mergeDemo --args="samples/ride.fit"`.
 */
object FitMergeDemo {
  def main(args: Array[String]): Unit = {
    val codec = new GarminFitCodec()
    banner("FFMForge merge demonstration")
    args.headOption match {
      case Some(path) => realJoin(codec, path)
      case None       => syntheticJoin(codec)
    }
  }

  // ── synthetic two-segment join ──────────────────────────────────────────────

  private def syntheticJoin(codec: GarminFitCodec): Unit = {
    val t0   = Instant.parse("2026-06-15T08:00:00Z")
    val segA = buildSegment(t0, points = 600, startDistanceM = 0.0) // 10 min
    val segB = buildSegment(t0.plus(20, ChronoUnit.MINUTES), points = 600, startDistanceM = 0.0)
    println("Built two synthetic 10-minute segments with a 10-minute gap between them.\n")
    join(codec, Seq(segB, segA), expectedRecords = 1200, out = None) // deliberately out of order
  }

  // ── real-file split-and-join ────────────────────────────────────────────────

  private def realJoin(codec: GarminFitCodec, path: String): Unit = {
    val original = codec.decode(Files.readAllBytes(Paths.get(path)))
    val recs     = original.recordMessages
    val n        = recs.size
    val firstEnd = (n * 0.45).toInt
    val sndStart = (n * 0.55).toInt
    val fileIds  = original.messages.filter(_.globalNum == FitProfile.Mesg.FileId)

    val segA = FitFile(fileIds ++ recs.take(firstEnd))
    val segB = FitFile(fileIds ++ recs.drop(sndStart))

    println(s"Read $n records from $path.")
    println(s"Split into two recordings of ${segA.recordMessages.size} and ${segB.recordMessages.size} records,")
    println(s"dropping the middle ${sndStart - firstEnd} records to simulate a recording gap.\n")

    join(
      codec,
      Seq(segA, segB),
      expectedRecords = firstEnd + (n - sndStart),
      out = Some(roundTripPath(Paths.get(path))),
    )
  }

  // ── shared join + verification ──────────────────────────────────────────────

  private def join(codec: GarminFitCodec, segments: Seq[FitFile], expectedRecords: Int, out: Option[Path]): Unit =
    FitMerge.mergeWithReport(segments) match {
      case Left(err) => println(s"MERGE FAILED: $err")
      case Right(MergeOutcome(merged, report)) =>
        printReport(report)

        println("\nEncoding the merged file and decoding it back to verify...")
        val bytes = codec.encode(merged)
        out.foreach { p =>
          Files.write(p, bytes): Unit
          println(s"  -> wrote merged file to $p (${bytes.length} bytes)")
        }
        val rt = codec.decode(bytes)

        println("\nVerifying the merged result:")
        val checks = Vector(
          check("all records kept", rt.records.size == expectedRecords, s"${rt.records.size} == $expectedRecords"),
          check("exactly one session", rt.sessions.size == 1, s"${rt.sessions.size}"),
          check("exactly one activity", merged.messages.count(_.globalNum == FitProfile.Mesg.Activity) == 1, "1"),
          check("records are time-ordered", isSorted(rt.records.map(_.timestamp)), "monotonic"),
          check(
            "moving time < elapsed time (gap preserved as pause)",
            report.movingSeconds < report.elapsedSeconds,
            f"${dur(report.movingSeconds)} < ${dur(report.elapsedSeconds)}",
          ),
        )
        banner(if (checks.forall(identity)) "MERGE RESULT: PASS" else "MERGE RESULT: FAIL")
    }

  // ── report formatting ────────────────────────────────────────────────────────

  private def printReport(r: MergeReport): Unit = {
    println("Read:")
    r.segments.zipWithIndex.foreach { case (s, i) =>
      val dist = s.distanceM.map(d => f", ${d / 1000.0}%.2f km").getOrElse("")
      println(f"  [${i + 1}] ${s.records}%5d records  ${s.start} .. ${s.end}$dist")
    }

    println("\nChanges applied:")
    println(s"  - ordered ${r.segments.size} segment(s) by start time")
    r.gaps.foreach { g =>
      println(s"  - gap of ${dur(g.seconds)} after segment ${g.afterSegment} preserved as a pause (STOP_ALL/START)")
    }
    println(s"  - regenerated ${r.timerEventsAdded} timer event(s) for the joined timeline")
    r.totalDistanceM.foreach(d => println(f"  - distance rebased to one cumulative total: ${d / 1000.0}%.2f km"))
    println(s"  - recomputed session: elapsed ${dur(r.elapsedSeconds)}, moving ${dur(r.movingSeconds)}")
    println(s"  - lap strategy: ${r.lapStrategy}")

    println("\nFinal file layout:")
    r.layout.counts.foreach { case (name, count) => println(f"  $name%-20s x$count%-6d") }
    println(s"  total: ${r.layout.totalMessages} messages, ${r.layout.totalFields} fields")
  }

  // ── helpers ──────────────────────────────────────────────────────────────────

  private def buildSegment(start: Instant, points: Int, startDistanceM: Double): FitFile = {
    val records = (0 until points).map { i =>
      Record(
        timestamp = start.plus(i.toLong, ChronoUnit.SECONDS),
        position = Some(GpsPoint(51.5074 + i * 0.00005, -0.1278 + i * 0.00005)),
        distanceM = Some(startDistanceM + i * 7.5),
        speedMps = Some(7.5),
        heartRate = Some(135),
      )
    }.toVector
    FitFile.of(FileId(manufacturer = Some(1), product = Some(1), timeCreated = Some(start)), records)
  }

  private def isSorted(xs: Vector[Instant]): Boolean = xs == xs.sortWith(_.isBefore(_))

  private def roundTripPath(in: Path): Path = {
    val name = in.getFileName.toString
    val base = if (name.contains(".")) name.substring(0, name.lastIndexOf('.')) else name
    Option(in.getParent).getOrElse(Paths.get(".")).resolve(s"$base.merged.fit")
  }

  private def banner(title: String): Unit = {
    println("\n" + "=" * 78)
    println(title)
    println("=" * 78)
  }

  private def check(label: String, passed: Boolean, detail: String): Boolean = {
    println(s"  [${if (passed) "PASS" else "FAIL"}] $label: $detail")
    passed
  }

  private def dur(seconds: Double): String = {
    val s = seconds.toLong
    f"${s / 3600}%dh ${(s % 3600) / 60}%02dm ${s % 60}%02ds"
  }
}
