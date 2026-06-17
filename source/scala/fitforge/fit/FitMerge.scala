package fitforge.fit

import java.time.Duration
import java.time.Instant

import fitforge.fit.FitProfile._

/** Lap handling when merging segments into one activity. */
enum LapStrategy {

  /** One lap per source recording (preserves the natural segment boundaries). */
  case OnePerSegment

  /** Keep the original lap messages from every source file. */
  case KeepOriginal
}

/** Options controlling a merge. */
final case class MergeOptions(lapStrategy: LapStrategy = LapStrategy.OnePerSegment)

/**
 * Joins multiple activity recordings of a single ride into one continuous file.
 *
 * Gaps between recordings (a café stop, a battery swap) are PRESERVED AS PAUSES: original timestamps are kept and a
 * `STOP_ALL`/`START` timer-event pair brackets each gap, so the merged `total_elapsed_time` includes the gaps while
 * `total_timer_time` (moving/active time) excludes them.
 *
 * The merge operates on the lossless [[FitMessage]] store: record/session/file_id messages are edited in place (so
 * every field fit-forge doesn't model is carried through), and unmodelled message types are passed through unchanged.
 */
object FitMerge {

  private val Handled: Set[Int] = Set(Mesg.FileId, Mesg.Record, Mesg.Event, Mesg.Lap, Mesg.Session, Mesg.Activity)

  /** Merge `files` (in any order) into one activity, or describe why it can't. */
  def merge(files: Seq[FitFile], options: MergeOptions = MergeOptions()): Either[String, FitFile] = {
    for {
      nonEmpty <- Either.cond(files.nonEmpty, files, "No files to merge")
      withRecords <- Either.cond(
        nonEmpty.forall(_.records.nonEmpty),
        nonEmpty,
        "Every file must contain at least one record",
      )
      ordered = withRecords.sortBy(_.records.head.timestamp)
      _ <- ensureNoOverlap(ordered)
    } yield assemble(ordered, options)
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

    val timerEventMsgs = timerEvents(ordered).map(FitViews.toMessage)
    val otherEventMsgs = ordered.flatMap(_.messages).filter { m =>
      m.globalNum == Mesg.Event && !m.numeric(Ev.Event).contains(EventEnum.Timer)
    }

    val lapMsgs = options.lapStrategy match {
      case LapStrategy.OnePerSegment => onePerSegmentLaps(rebasedPerFile).map(FitViews.toMessage)
      case LapStrategy.KeepOriginal  => ordered.flatMap(_.messages).filter(_.globalNum == Mesg.Lap).toVector
    }

    val sessionMsg = baseMessage(ordered, Mesg.Session)
      .setInstant(Ses.StartTime, start)
      .setInstant(Ses.Timestamp, end)
      .setNumeric(Ses.TotalElapsed, elapsedS)
      .setNumeric(Ses.TotalTimer, timerS)
      .setNumericOpt(Ses.TotalDistance, totalDistance)

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

  private def onePerSegmentLaps(rebasedPerFile: Vector[Vector[FitMessage]]): Vector[Lap] =
    rebasedPerFile.flatMap { recs =>
      for {
        first <- recs.headOption
        last  <- recs.lastOption
        st    <- first.instant(Rec.Timestamp)
        ts    <- last.instant(Rec.Timestamp)
      } yield Lap(
        startTime = st,
        timestamp = ts,
        totalElapsedTimeS = Some(seconds(st, ts)),
        totalTimerTimeS = Some(seconds(st, ts)),
        totalDistanceM = last.numeric(Rec.Distance).map(_ - first.numeric(Rec.Distance).getOrElse(0.0)),
      )
    }

  /** The first existing message of `num` across the segments (to preserve its extra fields), or a fresh one. */
  private def baseMessage(ordered: Seq[FitFile], num: Int): FitMessage =
    ordered.flatMap(_.messages).find(_.globalNum == num).getOrElse(FitMessage(num))

  private def timestampMillis(m: FitMessage): Long =
    m.instant(Rec.Timestamp).map(_.toEpochMilli).getOrElse(Long.MinValue)

  private def seconds(from: Instant, to: Instant): Double =
    Duration.between(from, to).toMillis / 1000.0
}
