package ffmforge.gpx

import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.Locale

import scala.xml.Elem
import scala.xml.Node
import scala.xml.NodeSeq
import scala.xml.PrettyPrinter
import scala.xml.Text

object GpxNamespaces {
  val Gpx: String      = "http://www.topografix.com/GPX/1/1"
  val GpxTpx: String   = "http://www.garmin.com/xmlschemas/TrackPointExtension/v1"
  val FFMForge: String = "https://ffmforge.com/xmlschemas/gpx/extensions/v1"
  val Xsi: String      = "http://www.w3.org/2001/XMLSchema-instance"
  val SchemaLocation: String =
    "http://www.topografix.com/GPX/1/1 http://www.topografix.com/GPX/1/1/gpx.xsd " +
      "http://www.garmin.com/xmlschemas/TrackPointExtension/v1 " +
      "http://www.garmin.com/xmlschemas/TrackPointExtensionv1.xsd"
}

object GpxXmlWriter {
  def write(document: GpxDocument): String =
    s"""<?xml version="1.0" encoding="UTF-8"?>
${new PrettyPrinter(160, 2).format(toXml(document))}
"""

  def writeBytes(document: GpxDocument): Array[Byte] =
    write(document).getBytes(StandardCharsets.UTF_8)

  def toXml(document: GpxDocument): Elem =
    <gpx
      xmlns={GpxNamespaces.Gpx}
      xmlns:gpxtpx={GpxNamespaces.GpxTpx}
      xmlns:ffmforge={GpxNamespaces.FFMForge}
      xmlns:xsi={GpxNamespaces.Xsi}
      xsi:schemaLocation={GpxNamespaces.SchemaLocation}
      version="1.1"
      creator="FFMForge">{children(document)}</gpx>

  private def children(document: GpxDocument): NodeSeq =
    document.metadata.map(metadata).getOrElse(NodeSeq.Empty) ++ document.tracks.map(track)

  private def metadata(value: GpxMetadata): Node =
    <metadata>{optionalName(value.name)}{optionalTime(value.time)}</metadata>

  private def track(value: GpxTrack): Node =
    <trk>{optionalName(value.name)}{value.segments.map(trackSegment)}</trk>

  private def trackSegment(value: GpxTrackSegment): Node =
    <trkseg>{value.points.map(trackPoint)}</trkseg>

  private def trackPoint(value: GpxTrackPoint): Node =
    <trkpt lat={coord(value.lat)} lon={coord(value.lon)}>
      {optionalElevation(value.elevationM)}
      {optionalTime(value.time)}
      {pointExtensions(value.extensions)}
    </trkpt>

  private def pointExtensions(value: GpxPointExtensions): NodeSeq =
    if (value.isEmpty) NodeSeq.Empty
    else <extensions>{garminTrackPointExtension(value)}{ffmforgeTrackPointExtension(value)}</extensions>

  private def garminTrackPointExtension(value: GpxPointExtensions): NodeSeq = {
    val children =
      optionalHr(value.heartRateBpm) ++
        optionalCadence(value.cadenceRpm) ++
        optionalTemperature(value.temperatureC)
    if (children.isEmpty) NodeSeq.Empty else <gpxtpx:TrackPointExtension>{children}</gpxtpx:TrackPointExtension>
  }

  private def ffmforgeTrackPointExtension(value: GpxPointExtensions): NodeSeq = {
    val children = optionalPower(value.powerW) ++ optionalSpeed(value.speedMps)
    if (children.isEmpty) NodeSeq.Empty else <ffmforge:TrackPointExtension>{children}</ffmforge:TrackPointExtension>
  }

  private def optionalName(value: Option[String]): NodeSeq =
    value.map(v => <name>{Text(v)}</name>).getOrElse(NodeSeq.Empty)

  private def optionalTime(value: Option[Instant]): NodeSeq =
    value.map(v => <time>{Text(v.toString)}</time>).getOrElse(NodeSeq.Empty)

  private def optionalElevation(value: Option[Double]): NodeSeq =
    value.map(v => <ele>{Text(number(v, decimals = 2))}</ele>).getOrElse(NodeSeq.Empty)

  private def optionalHr(value: Option[Int]): NodeSeq =
    value.map(v => <gpxtpx:hr>{v.toString}</gpxtpx:hr>).getOrElse(NodeSeq.Empty)

  private def optionalCadence(value: Option[Int]): NodeSeq =
    value.map(v => <gpxtpx:cad>{v.toString}</gpxtpx:cad>).getOrElse(NodeSeq.Empty)

  private def optionalTemperature(value: Option[Double]): NodeSeq =
    value.map(v => <gpxtpx:atemp>{number(v, decimals = 1)}</gpxtpx:atemp>).getOrElse(NodeSeq.Empty)

  private def optionalPower(value: Option[Int]): NodeSeq =
    value.map(v => <ffmforge:power unit="W">{v.toString}</ffmforge:power>).getOrElse(NodeSeq.Empty)

  private def optionalSpeed(value: Option[Double]): NodeSeq =
    value.map(v => <ffmforge:speed unit="m/s">{number(v, decimals = 3)}</ffmforge:speed>).getOrElse(NodeSeq.Empty)

  private def coord(value: Double): String =
    number(value, decimals = 7)

  private def number(value: Double, decimals: Int): String =
    String.format(Locale.ROOT, s"%.${decimals}f", value)
}
