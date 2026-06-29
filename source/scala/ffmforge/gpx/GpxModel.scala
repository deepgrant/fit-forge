package ffmforge.gpx

import java.time.Instant

final case class GpxDocument(
  metadata: GpxMetadata = GpxMetadata(name = None, time = None),
  tracks: Vector[GpxTrack] = Vector.empty[GpxTrack],
)

final case class GpxMetadata(
  name: Option[String] = None,
  time: Option[Instant] = None,
) {
  def isEmpty: Boolean = name.isEmpty && time.isEmpty
}

final case class GpxTrack(
  name: Option[String] = None,
  segments: Vector[GpxTrackSegment] = Vector.empty[GpxTrackSegment],
)

final case class GpxTrackSegment(points: Vector[GpxTrackPoint] = Vector.empty[GpxTrackPoint])

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
