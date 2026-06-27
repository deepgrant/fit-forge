package ffmforge.gpx

import java.time.Instant

final case class GpxDocument(
    metadata: Option[GpxMetadata] = None,
    tracks: Vector[GpxTrack] = Vector.empty,
)

final case class GpxMetadata(
    name: Option[String] = None,
    time: Option[Instant] = None,
)

final case class GpxTrack(
    name: Option[String] = None,
    segments: Vector[GpxTrackSegment] = Vector.empty,
)

final case class GpxTrackSegment(points: Vector[GpxTrackPoint] = Vector.empty)

final case class GpxTrackPoint(
    lat: Double,
    lon: Double,
    elevationM: Option[Double] = None,
    time: Option[Instant] = None,
    extensions: GpxPointExtensions = GpxPointExtensions(),
)

final case class GpxPointExtensions(
    heartRateBpm: Option[Int] = None,
    cadenceRpm: Option[Int] = None,
    powerW: Option[Int] = None,
    speedMps: Option[Double] = None,
    temperatureC: Option[Double] = None,
) {
  def isEmpty: Boolean =
    heartRateBpm.isEmpty && cadenceRpm.isEmpty && powerW.isEmpty && speedMps.isEmpty && temperatureC.isEmpty
}
