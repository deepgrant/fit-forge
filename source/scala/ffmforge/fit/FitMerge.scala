package ffmforge.fit

import java.time.Duration
import java.time.Instant

import ffmforge.fit.FitProfile._

/** Lap handling when merging segments into one activity. */
enum LapStrategy {

  /** One lap per source recording (preserves the natural segment boundaries). */
  case OnePerSegment

  /** Keep the original lap messages from every source file. */
  case KeepOriginal
}

/** Options controlling a merge. */
final case class MergeOptions(lapStrategy: LapStrategy = LapStrategy.OnePerSegment)

/** What was read from one input recording. */
final case class SegmentInfo(records: Int, start: Instant, end: Instant, distanceM: Option[Double])

/** A preserved pause: the gap after segment `afterSegment` (1-based). */
final case class GapInfo(afterSegment: Int, seconds: Double)

/** A structured log of a merge: what was read, what changed, and the final file's layout. */
final case class MergeReport(
  segments: Vector[SegmentInfo],
  gaps: Vector[GapInfo],
  totalDistanceM: Option[Double],
  totalAscentM: Option[Double],
  totalDescentM: Option[Double],
  elapsedSeconds: Double,
  movingSeconds: Double,
  timerEventsAdded: Int,
  lapStrategy: LapStrategy,
  layout: FitLayout,
)

/** A merge result paired with its report. */
final case class MergeOutcome(file: FitFile, report: MergeReport)

/**
 * Joins multiple activity recordings of a single ride into one continuous file.
 *
 * Gaps between recordings (a café stop, a battery swap) are PRESERVED AS PAUSES: original timestamps are kept and a
 * `STOP_ALL`/`START` timer-event pair brackets each gap, so the merged `total_elapsed_time` includes the gaps while
 * `total_timer_time` (moving/active time) excludes them.
 *
 * The merge operates on the lossless [[FitMessage]] store: record/session/file_id messages are edited in place (so
 * every field FFMForge doesn't model is carried through), and unmodelled message types are passed through unchanged.
 */
object FitMerge {

  private val Handled: Set[Int] = Set(Mesg.FileId, Mesg.Record, Mesg.Event, Mesg.Lap, Mesg.Session, Mesg.Activity)
  private val ElevationNoiseThresholdM: Double = 1.0

  private final case class ElevationTotals(totalAscentM: Option[Double], totalDescentM: Option[Double])
  private final case class ElevationAccumulator(
    pivot: Double,
    extreme: Double,
    trend: Int,
    ascentM: Double,
    descentM: Double,
  )

  private final case class ElevationSummary(
    totalAscentM: Option[Double],
    totalDescentM: Option[Double],
    avgAltitudeM: Option[Double],
    minAltitudeM: Option[Double],
    maxAltitudeM: Option[Double],
  )

  /** Merge `files` (in any order) into one activity, or describe why it can't. */
  def merge(files: Seq[FitFile], options: MergeOptions = MergeOptions()): Either[String, FitFile] =
    validated(files).map(assemble(_, options))

  /** As [[merge]], but also returns a [[MergeReport]] logging what was read and changed. */
  def mergeWithReport(files: Seq[FitFile], options: MergeOptions = MergeOptions()): Either[String, MergeOutcome] =
    validated(files).map { ordered =>
      val merged = assemble(ordered, options)
      MergeOutcome(merged, buildReport(ordered, merged, options))
    }

  /** Validate inputs and return the segments ordered by start time. */
  private def validated(files: Seq[FitFile]): Either[String, Seq[FitFile]] = {
    for {
      nonEmpty <- Either.cond(files.nonEmpty, files, "No files to merge")
      withRecords <- Either.cond(
        nonEmpty.forall(_.records.nonEmpty),
        nonEmpty,
        "Every file must contain at least one record",
      )
      ordered = withRecords.sortBy(_.records.head.timestamp)
      _ <- ensureNoOverlap(ordered)
    } yield ordered
  }

  private def buildReport(ordered: Seq[FitFile], merged: FitFile, options: MergeOptions): MergeReport = {
    val segments = ordered.map { f =>
      val rs = f.records
      SegmentInfo(rs.size, rs.head.timestamp, rs.last.timestamp, rs.lastOption.flatMap(_.distanceM))
    }.toVector

    val gaps = ordered
      .sliding(2)
      .zipWithIndex
      .collect { case (Seq(a, b), i) => GapInfo(i + 1, seconds(a.records.last.timestamp, b.records.head.timestamp)) }
      .toVector

    val start   = ordered.head.records.head.timestamp
    val end     = ordered.last.records.last.timestamp
    val session = merged.messages.find(_.globalNum == Mesg.Session)

    MergeReport(
      segments = segments,
      gaps = gaps,
      totalDistanceM = merged.sessions.headOption.flatMap(_.totalDistanceM),
      totalAscentM = session.flatMap(_.numeric(Ses.TotalAscent)),
      totalDescentM = session.flatMap(_.numeric(Ses.TotalDescent)),
      elapsedSeconds = seconds(start, end),
      movingSeconds = ordered.map(f => seconds(f.records.head.timestamp, f.records.last.timestamp)).sum,
      timerEventsAdded = timerEvents(ordered).size,
      lapStrategy = options.lapStrategy,
      layout = FitLayout.of(merged),
    )
  }

  private def ensureNoOverlap(ordered: Seq[FitFile]): Either[String, Unit] = {
    val overlap = ordered.sliding(2).collectFirst {
      case Seq(a, b) if b.records.head.timestamp.isBefore(a.records.last.timestamp) =>
        s"Recordings overlap in time: a segment starts at ${b.records.head.timestamp} " +
          s"before the previous one ends at ${a.records.last.timestamp}"
    }
    overlap.toLeft(())
  }

  private def assemble(ordered: Seq[FitFile], options: MergeOptions): FitFile = {
    val rebasedPerFile = rebaseDistances(ordered)
    val allRecords     = rebasedPerFile.flatten

    val start = ordered.head.records.head.timestamp
    val end   = ordered.last.records.last.timestamp

    val elapsedS      = seconds(start, end)
    val timerS        = ordered.map(f => seconds(f.records.head.timestamp, f.records.last.timestamp)).sum
    val totalDistance = allRecords.lastOption.flatMap(_.numeric(Rec.Distance))
    val elevation     = elevationSummary(ordered, allRecords)

    val timerEventMsgs = timerEvents(ordered).map(FitViews.toMessage)
    val otherEventMsgs = ordered.flatMap(_.messages).filter { m =>
      m.globalNum == Mesg.Event && !m.numeric(Ev.Event).contains(EventEnum.Timer)
    }

    val lapMsgs = options.lapStrategy match {
      case LapStrategy.OnePerSegment => onePerSegmentLaps(ordered, rebasedPerFile)
      case LapStrategy.KeepOriginal  => ordered.flatMap(_.messages).filter(_.globalNum == Mesg.Lap).toVector
    }

    val sessionMsg = applySessionElevation(
      baseMessage(ordered, Mesg.Session)
        .setInstant(Ses.StartTime, start)
        .setInstant(Ses.Timestamp, end)
        .setNumeric(Ses.TotalElapsed, elapsedS)
        .setNumeric(Ses.TotalTimer, timerS)
        .setNumericOpt(Ses.TotalDistance, totalDistance),
      elevation,
    )

    val activityMsg = baseMessage(ordered, Mesg.Activity)
      .setInstant(Act.Timestamp, end)
      .setNumeric(Act.TotalTimer, timerS)
      .setNumeric(Act.NumSessions, 1)

    val fileIdMsg = ordered.head.messages
      .find(_.globalNum == Mesg.FileId)
      .getOrElse(FitViews.toMessage(FileId()))
      .setInstant(Fid.TimeCreated, start)

    val passthrough = ordered.flatMap(_.messages).filterNot(m => Handled.contains(m.globalNum)).toVector

    val timeline = (timerEventMsgs ++ otherEventMsgs ++ allRecords).sortBy(timestampMillis)

    FitFile(
      Vector(fileIdMsg) ++ passthrough ++ timeline ++ lapMsgs ++ Vector(sessionMsg, activityMsg)
    )
  }

  /** Make the record `distance` field cumulative across the whole merged ride, per file segment. */
  private def rebaseDistances(ordered: Seq[FitFile]): Vector[Vector[FitMessage]] = {
    val (_, rebased) = ordered.foldLeft((0.0, Vector.empty[Vector[FitMessage]])) { case ((offset, acc), file) =>
      val recs     = file.recordMessages
      val segStart = recs.headOption.flatMap(_.numeric(Rec.Distance)).getOrElse(0.0)
      val shifted =
        recs.map(r => r.numeric(Rec.Distance).fold(r)(d => r.setNumeric(Rec.Distance, offset + (d - segStart))))
      val segEnd = recs.lastOption.flatMap(_.numeric(Rec.Distance)).getOrElse(segStart)
      (offset + (segEnd - segStart), acc :+ shifted)
    }
    rebased
  }

  /** A single START, a STOP_ALL/START pair across every gap, then a final STOP_ALL. */
  private def timerEvents(ordered: Seq[FitFile]): Vector[Event] = {
    val starts  = ordered.map(_.records.head.timestamp)
    val ends    = ordered.map(_.records.last.timestamp)
    val opening = Vector(Event(starts.head, TimerEvent.Start))
    val gaps = ends.init.zip(starts.tail).flatMap { case (prevEnd, nextStart) =>
      Vector(Event(prevEnd, TimerEvent.StopAll), Event(nextStart, TimerEvent.Start))
    }
    val closing = Vector(Event(ends.last, TimerEvent.StopAll))
    (opening ++ gaps ++ closing).toVector
  }

  private def onePerSegmentLaps(
    ordered: Seq[FitFile],
    rebasedPerFile: Vector[Vector[FitMessage]],
  ): Vector[FitMessage] =
    ordered
      .zip(rebasedPerFile)
      .flatMap { case (file, recs) =>
        for {
          first <- recs.headOption
          last  <- recs.lastOption
          st    <- first.instant(Rec.Timestamp)
          ts    <- last.instant(Rec.Timestamp)
        } yield applyLapElevation(
          FitViews.toMessage(
            Lap(
              startTime = st,
              timestamp = ts,
              totalElapsedTimeS = Some(seconds(st, ts)),
              totalTimerTimeS = Some(seconds(st, ts)),
              totalDistanceM = last.numeric(Rec.Distance).map(_ - first.numeric(Rec.Distance).getOrElse(0.0)),
            )
          ),
          elevationSummary(Seq(file), recs),
        )
      }
      .toVector

  /**
   * Prefer device-calculated climbing totals, falling back to each segment's altitude records without treating the jump
   * between recordings as real terrain. Record samples also provide whole-activity average/min/max altitude.
   */
  private def elevationSummary(files: Seq[FitFile], records: Seq[FitMessage]): ElevationSummary = {
    val altitudes     = recordAltitudes(records)
    val segmentTotals = files.map(sourceElevationTotals)
    ElevationSummary(
      totalAscentM = completeSum(segmentTotals.map(_.totalAscentM)),
      totalDescentM = completeSum(segmentTotals.map(_.totalDescentM)),
      avgAltitudeM = mean(altitudes),
      minAltitudeM = altitudes.minOption,
      maxAltitudeM = altitudes.maxOption,
    )
  }

  private def sourceElevationTotals(file: FitFile): ElevationTotals = {
    val derived = derivedElevationTotals(file.recordMessages)
    ElevationTotals(
      totalAscentM = summarizedElevationTotal(file, Ses.TotalAscent, Lp.TotalAscent)
        .orElse(derived.flatMap(_.totalAscentM)),
      totalDescentM = summarizedElevationTotal(file, Ses.TotalDescent, Lp.TotalDescent)
        .orElse(derived.flatMap(_.totalDescentM)),
    )
  }

  private def summarizedElevationTotal(file: FitFile, sessionField: Int, lapField: Int): Option[Double] = {
    val sessions = file.messages.filter(_.globalNum == Mesg.Session)
    val laps     = file.messages.filter(_.globalNum == Mesg.Lap)
    completeSum(sessions.map(_.numeric(sessionField))).orElse(completeSum(laps.map(_.numeric(lapField))))
  }

  /**
   * Derive gain/loss from one recording's altitude samples when its summary messages omit those totals. The hysteresis
   * ignores sub-metre sensor jitter while retaining gradual climbs, and operating per file avoids counting the altitude
   * discontinuity between recordings.
   */
  private def derivedElevationTotals(records: Seq[FitMessage]): Option[ElevationTotals] = {
    val altitudes = recordAltitudes(records)
    altitudes.headOption.filter(_ => altitudes.size >= 2).map { first =>
      val initial = ElevationAccumulator(first, first, trend = 0, ascentM = 0.0, descentM = 0.0)
      val accumulated = altitudes.tail.foldLeft(initial) { (state, altitude) =>
        state.trend match {
          case 0 if altitude - state.pivot >= ElevationNoiseThresholdM =>
            state.copy(extreme = altitude, trend = 1)
          case 0 if state.pivot - altitude >= ElevationNoiseThresholdM =>
            state.copy(extreme = altitude, trend = -1)
          case 1 if altitude > state.extreme =>
            state.copy(extreme = altitude)
          case 1 if state.extreme - altitude >= ElevationNoiseThresholdM =>
            state.copy(
              pivot = state.extreme,
              extreme = altitude,
              trend = -1,
              ascentM = state.ascentM + state.extreme - state.pivot,
            )
          case -1 if altitude < state.extreme =>
            state.copy(extreme = altitude)
          case -1 if altitude - state.extreme >= ElevationNoiseThresholdM =>
            state.copy(
              pivot = state.extreme,
              extreme = altitude,
              trend = 1,
              descentM = state.descentM + state.pivot - state.extreme,
            )
          case _ => state
        }
      }

      val ascent =
        if (accumulated.trend > 0) accumulated.ascentM + accumulated.extreme - accumulated.pivot
        else accumulated.ascentM
      val descent =
        if (accumulated.trend < 0) accumulated.descentM + accumulated.pivot - accumulated.extreme
        else accumulated.descentM

      ElevationTotals(Some(ascent), Some(descent))
    }
  }

  private def recordAltitudes(records: Seq[FitMessage]): Seq[Double] =
    records
      .flatMap(record => record.numeric(Rec.EnhancedAltitude).orElse(record.numeric(Rec.Altitude)))
      .filter(_.isFinite)

  private def applySessionElevation(message: FitMessage, elevation: ElevationSummary): FitMessage =
    Vector(
      Ses.TotalAscent         -> elevation.totalAscentM,
      Ses.TotalDescent        -> elevation.totalDescentM,
      Ses.AvgAltitude         -> elevation.avgAltitudeM,
      Ses.MaxAltitude         -> elevation.maxAltitudeM,
      Ses.EnhancedAvgAltitude -> elevation.avgAltitudeM,
      Ses.EnhancedMinAltitude -> elevation.minAltitudeM,
      Ses.EnhancedMaxAltitude -> elevation.maxAltitudeM,
    ).foldLeft(message) { case (result, (field, value)) => setNumericOrRemove(result, field, value) }

  private def applyLapElevation(message: FitMessage, elevation: ElevationSummary): FitMessage =
    Vector(
      Lp.TotalAscent         -> elevation.totalAscentM,
      Lp.TotalDescent        -> elevation.totalDescentM,
      Lp.AvgAltitude         -> elevation.avgAltitudeM,
      Lp.MinAltitude         -> elevation.minAltitudeM,
      Lp.MaxAltitude         -> elevation.maxAltitudeM,
      Lp.EnhancedAvgAltitude -> elevation.avgAltitudeM,
      Lp.EnhancedMinAltitude -> elevation.minAltitudeM,
      Lp.EnhancedMaxAltitude -> elevation.maxAltitudeM,
    ).foldLeft(message) { case (result, (field, value)) => setNumericOrRemove(result, field, value) }

  private def setNumericOrRemove(message: FitMessage, field: Int, value: Option[Double]): FitMessage =
    value.fold(message.removeField(field))(message.setNumeric(field, _))

  private def completeSum(values: Seq[Option[Double]]): Option[Double] =
    Option.when(values.nonEmpty && values.forall(_.isDefined))(values.flatten.sum)

  private def mean(values: Seq[Double]): Option[Double] =
    Option.when(values.nonEmpty)(values.sum / values.size)

  /** The first existing message of `num` across the segments (to preserve its extra fields), or a fresh one. */
  private def baseMessage(ordered: Seq[FitFile], num: Int): FitMessage =
    ordered.flatMap(_.messages).find(_.globalNum == num).getOrElse(FitMessage(num))

  private def timestampMillis(m: FitMessage): Long =
    m.instant(Rec.Timestamp).map(_.toEpochMilli).getOrElse(Long.MinValue)

  private def seconds(from: Instant, to: Instant): Double =
    Duration.between(from, to).toMillis / 1000.0
}
