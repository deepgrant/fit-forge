package ffmforge.gpx

import java.time.Instant

import ffmforge.fit.FitFile
import ffmforge.fit.FitMessage
import ffmforge.fit.FitProfile
import ffmforge.fit.TimerEvent

object FitToGpx {
  import FitProfile._

  def convert(file: FitFile, trackName: Option[String] = None): GpxDocument = {
    val points   = file.recordMessages.flatMap(trackPoint).sortBy(_.time.map(_.toEpochMilli).getOrElse(Long.MinValue))
    val segments = splitSegments(points, pauseRestartTimes(file))
    GpxDocument(
      metadata = Some(GpxMetadata(name = trackName, time = file.startTime.orElse(points.flatMap(_.time).headOption))),
      tracks = Vector(GpxTrack(name = trackName.orElse(Some("FFMForge export")), segments = segments)),
    )
  }

  private def trackPoint(message: FitMessage): Option[GpxTrackPoint] =
    for {
      timestamp <- message.instant(Rec.Timestamp)
      lat       <- message.numeric(Rec.PositionLat)
      lon       <- message.numeric(Rec.PositionLong)
    } yield GpxTrackPoint(
      lat = semicirclesToDeg(lat.toLong),
      lon = semicirclesToDeg(lon.toLong),
      elevationM = message.numeric(Rec.EnhancedAltitude).orElse(message.numeric(Rec.Altitude)),
      time = Some(timestamp),
      extensions = GpxPointExtensions(
        heartRateBpm = message.numeric(Rec.HeartRate).map(_.toInt),
        cadenceRpm = message.numeric(Rec.Cadence).map(_.toInt),
        powerW = message.numeric(Rec.Power).map(_.toInt),
        speedMps = message.numeric(Rec.EnhancedSpeed).orElse(message.numeric(Rec.Speed)),
        temperatureC = message.numeric(Rec.Temperature),
      ),
    )

  private def pauseRestartTimes(file: FitFile): Vector[Instant] = {
    final case class PauseState(paused: Boolean, restarts: Vector[Instant])

    file.events
      .sortBy(_.timestamp.toEpochMilli)
      .foldLeft(PauseState(paused = false, restarts = Vector.empty)) {
        case (state, event) if event.event == TimerEvent.StopAll =>
          state.copy(paused = true)
        case (state, event) if event.event == TimerEvent.Start && state.paused =>
          PauseState(paused = false, restarts = state.restarts :+ event.timestamp)
        case (state, event) if event.event == TimerEvent.Start =>
          state.copy(paused = false)
        case (state, _) => state
      }
      .restarts
  }

  private def splitSegments(points: Vector[GpxTrackPoint], restartTimes: Vector[Instant]): Vector[GpxTrackSegment] = {
    final case class SplitState(
        segments: Vector[GpxTrackSegment],
        current: Vector[GpxTrackPoint],
        restarts: Vector[Instant],
    )

    val initial = SplitState(Vector.empty, Vector.empty, restartTimes.sortBy(_.toEpochMilli))
    val state = points.foldLeft(initial) { (state, point) =>
      val pointTime = point.time.getOrElse(Instant.MIN)
      if (state.current.nonEmpty && state.restarts.headOption.exists(!_.isAfter(pointTime))) {
        SplitState(
          segments = state.segments :+ GpxTrackSegment(state.current),
          current = Vector(point),
          restarts = state.restarts.dropWhile(!_.isAfter(pointTime)),
        )
      } else {
        state.copy(current = state.current :+ point)
      }
    }

    if (state.current.nonEmpty) state.segments :+ GpxTrackSegment(state.current)
    else state.segments
  }
}
