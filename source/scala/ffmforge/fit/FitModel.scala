package ffmforge.fit

import java.time.Instant

/**
 * Typed, read-only projections of the common FIT data, plus the [[FitFile]] container.
 *
 * The lossless source of truth is the ordered list of [[FitMessage]]s inside a [[FitFile]]; the case classes below are
 * convenient *views* derived from it (and used to synthesise files in tests/examples via [[FitFile.of]]). Only
 * [[GarminFitCodec]] imports `com.garmin.fit`.
 */

/** A GPS coordinate in decimal degrees. */
final case class GpsPoint(lat: Double, lon: Double)

/** A single `record` message — one trackpoint sample. */
final case class Record(
    timestamp: Instant,
    position: Option[GpsPoint] = None,
    distanceM: Option[Double] = None,
    speedMps: Option[Double] = None,
    altitudeM: Option[Double] = None,
    heartRate: Option[Int] = None,
    cadence: Option[Int] = None,
    power: Option[Int] = None,
)

/** Timer events bracket active vs. paused periods of a ride. */
enum TimerEvent {
  case Start, StopAll
}

/** A timer `event` message (start/stop), used to model pauses. */
final case class Event(timestamp: Instant, event: TimerEvent)

/** A `lap` summary. */
final case class Lap(
    startTime: Instant,
    timestamp: Instant,
    totalElapsedTimeS: Option[Double] = None,
    totalTimerTimeS: Option[Double] = None,
    totalDistanceM: Option[Double] = None,
)

/** A `session` summary (one continuous workout). */
final case class Session(
    startTime: Instant,
    timestamp: Instant,
    totalElapsedTimeS: Option[Double] = None,
    totalTimerTimeS: Option[Double] = None,
    totalDistanceM: Option[Double] = None,
    sport: Option[String] = None,
)

/** The required `file_id` message identifying the file's origin. */
final case class FileId(
    manufacturer: Option[Int] = None,
    product: Option[Int] = None,
    serialNumber: Option[Long] = None,
    timeCreated: Option[Instant] = None,
)

/**
 * A whole FIT activity file as its lossless ordered message list. Typed accessors below derive the common data on
 * demand; the raw `messages` retain every field and message type so encode reproduces them.
 */
final case class FitFile(messages: Vector[FitMessage]) {
  private def of(num: Int): Vector[FitMessage] = messages.filter(_.globalNum == num)

  def recordMessages: Vector[FitMessage] = of(FitProfile.Mesg.Record)

  def records: Vector[Record]   = recordMessages.flatMap(FitViews.record)
  def events: Vector[Event]     = of(FitProfile.Mesg.Event).flatMap(FitViews.event)
  def laps: Vector[Lap]         = of(FitProfile.Mesg.Lap).flatMap(FitViews.lap)
  def sessions: Vector[Session] = of(FitProfile.Mesg.Session).flatMap(FitViews.session)
  def fileId: FileId            = of(FitProfile.Mesg.FileId).headOption.flatMap(FitViews.fileId).getOrElse(FileId())

  def startTime: Option[Instant] = records.headOption.map(_.timestamp)
  def endTime: Option[Instant]   = records.lastOption.map(_.timestamp)
}

object FitFile {

  /** Build a file from typed parts (used by tests/examples). Real files come from [[GarminFitCodec.decode]]. */
  def of(
      fileId: FileId,
      records: Vector[Record],
      events: Vector[Event] = Vector.empty,
      laps: Vector[Lap] = Vector.empty,
      sessions: Vector[Session] = Vector.empty,
  ): FitFile = {
    val msgs =
      Vector(FitViews.toMessage(fileId)) ++
        events.map(FitViews.toMessage) ++
        records.map(FitViews.toMessage) ++
        laps.map(FitViews.toMessage) ++
        sessions.map(FitViews.toMessage)
    FitFile(msgs)
  }
}

/** Pure conversions between the typed views and generic [[FitMessage]]s (no SDK types involved). */
object FitViews {
  import FitProfile._

  // ── message -> typed view ────────────────────────────────────────────────

  def record(m: FitMessage): Option[Record] =
    m.instant(Rec.Timestamp).map { ts =>
      val pos =
        for {
          la <- m.numeric(Rec.PositionLat)
          lo <- m.numeric(Rec.PositionLong)
        } yield GpsPoint(semicirclesToDeg(la.toLong), semicirclesToDeg(lo.toLong))
      Record(
        timestamp = ts,
        position = pos,
        distanceM = m.numeric(Rec.Distance),
        speedMps = m.numeric(Rec.EnhancedSpeed).orElse(m.numeric(Rec.Speed)),
        altitudeM = m.numeric(Rec.EnhancedAltitude).orElse(m.numeric(Rec.Altitude)),
        heartRate = m.numeric(Rec.HeartRate).map(_.toInt),
        cadence = m.numeric(Rec.Cadence).map(_.toInt),
        power = m.numeric(Rec.Power).map(_.toInt),
      )
    }

  def event(m: FitMessage): Option[Event] =
    for {
      ts   <- m.instant(Ev.Timestamp)
      et   <- m.numeric(Ev.EventType)
      kind <- eventTypeToTimer(et.toInt)
    } yield Event(ts, kind)

  private def eventTypeToTimer(et: Int): Option[TimerEvent] =
    if (et == EventTypeEnum.Start.toInt) Some(TimerEvent.Start)
    else if (et == EventTypeEnum.Stop.toInt || et == EventTypeEnum.StopAll.toInt) Some(TimerEvent.StopAll)
    else None

  def lap(m: FitMessage): Option[Lap] =
    for {
      st <- m.instant(Lp.StartTime)
      ts <- m.instant(Lp.Timestamp)
    } yield Lap(st, ts, m.numeric(Lp.TotalElapsed), m.numeric(Lp.TotalTimer), m.numeric(Lp.TotalDistance))

  def session(m: FitMessage): Option[Session] =
    for {
      st <- m.instant(Ses.StartTime)
      ts <- m.instant(Ses.Timestamp)
    } yield Session(
      st,
      ts,
      m.numeric(Ses.TotalElapsed),
      m.numeric(Ses.TotalTimer),
      m.numeric(Ses.TotalDistance),
      m.numeric(Ses.Sport).map(SportEnum.nameOf),
    )

  def fileId(m: FitMessage): Option[FileId] =
    Some(
      FileId(
        manufacturer = m.numeric(Fid.Manufacturer).map(_.toInt),
        product = m.numeric(Fid.Product).map(_.toInt),
        serialNumber = m.numeric(Fid.SerialNumber).map(_.toLong),
        timeCreated = m.instant(Fid.TimeCreated),
      )
    )

  // ── typed view -> message ────────────────────────────────────────────────

  def toMessage(r: Record): FitMessage = {
    val withPos = r.position.fold(FitMessage(Mesg.Record).setInstant(Rec.Timestamp, r.timestamp)) { p =>
      FitMessage(Mesg.Record)
        .setInstant(Rec.Timestamp, r.timestamp)
        .setNumeric(Rec.PositionLat, degToSemicircles(p.lat).toDouble)
        .setNumeric(Rec.PositionLong, degToSemicircles(p.lon).toDouble)
    }
    withPos
      .setNumericOpt(Rec.Distance, r.distanceM)
      .setNumericOpt(Rec.EnhancedSpeed, r.speedMps)
      .setNumericOpt(Rec.EnhancedAltitude, r.altitudeM)
      .setNumericOpt(Rec.HeartRate, r.heartRate.map(_.toDouble))
      .setNumericOpt(Rec.Cadence, r.cadence.map(_.toDouble))
      .setNumericOpt(Rec.Power, r.power.map(_.toDouble))
  }

  def toMessage(e: Event): FitMessage =
    FitMessage(Mesg.Event)
      .setInstant(Ev.Timestamp, e.timestamp)
      .setNumeric(Ev.Event, EventEnum.Timer)
      .setNumeric(
        Ev.EventType,
        e.event match {
          case TimerEvent.Start   => EventTypeEnum.Start
          case TimerEvent.StopAll => EventTypeEnum.StopAll
        },
      )

  def toMessage(l: Lap): FitMessage =
    FitMessage(Mesg.Lap)
      .setInstant(Lp.StartTime, l.startTime)
      .setInstant(Lp.Timestamp, l.timestamp)
      .setNumericOpt(Lp.TotalElapsed, l.totalElapsedTimeS)
      .setNumericOpt(Lp.TotalTimer, l.totalTimerTimeS)
      .setNumericOpt(Lp.TotalDistance, l.totalDistanceM)

  def toMessage(s: Session): FitMessage =
    FitMessage(Mesg.Session)
      .setInstant(Ses.StartTime, s.startTime)
      .setInstant(Ses.Timestamp, s.timestamp)
      .setNumericOpt(Ses.TotalElapsed, s.totalElapsedTimeS)
      .setNumericOpt(Ses.TotalTimer, s.totalTimerTimeS)
      .setNumericOpt(Ses.TotalDistance, s.totalDistanceM)
      .setNumeric(Ses.Sport, s.sport.map(SportEnum.valueOf).getOrElse(SportEnum.Cycling))

  def toMessage(id: FileId): FitMessage = {
    val base = FitMessage(Mesg.FileId).setNumeric(Fid.Type, FileEnum.Activity)
    base
      .setNumericOpt(Fid.Manufacturer, id.manufacturer.map(_.toDouble))
      .setNumericOpt(Fid.Product, id.product.map(_.toDouble))
      .setNumericOpt(Fid.SerialNumber, id.serialNumber.map(_.toDouble))
      .setInstant(Fid.TimeCreated, id.timeCreated.getOrElse(Instant.now()))
  }
}
