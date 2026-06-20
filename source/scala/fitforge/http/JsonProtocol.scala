package fitforge.http

import java.time.Instant

import scala.util.Try

import spray.json.DefaultJsonProtocol
import spray.json.JsArray
import spray.json.JsBoolean
import spray.json.JsNumber
import spray.json.JsObject
import spray.json.JsString
import spray.json.JsValue
import spray.json.JsonFormat
import spray.json.RootJsonFormat
import spray.json.deserializationError
import spray.json.enrichAny

import fitforge.fit.DeviceInfo
import fitforge.fit.FileId
import fitforge.fit.FitFile
import fitforge.fit.FitLayout
import fitforge.fit.GapInfo
import fitforge.fit.LapStrategy
import fitforge.fit.MergeReport
import fitforge.fit.RideSummary
import fitforge.fit.SegmentInfo

/** Spray-JSON formats for the API + a GeoJSON writer for the track endpoint. */
object JsonProtocol extends DefaultJsonProtocol {

  implicit val instantFormat: JsonFormat[Instant] = new JsonFormat[Instant] {
    def write(i: Instant): JsValue = JsString(i.toString)
    def read(v: JsValue): Instant = v match {
      case JsString(s) => Instant.parse(s)
      case _           => deserializationError("ISO-8601 instant string expected")
    }
  }

  implicit val lapStrategyFormat: JsonFormat[LapStrategy] = new JsonFormat[LapStrategy] {
    def write(l: LapStrategy): JsValue = JsString(l.toString)
    def read(v: JsValue): LapStrategy = v match {
      case JsString(s) => Try(LapStrategy.valueOf(s)).getOrElse(LapStrategy.OnePerSegment)
      case _           => deserializationError("lap strategy string expected")
    }
  }

  implicit val fileIdFormat: RootJsonFormat[FileId]           = jsonFormat4(FileId.apply)
  implicit val rideSummaryFormat: RootJsonFormat[RideSummary] = jsonFormat10(RideSummary.apply)
  implicit val deviceInfoFormat: RootJsonFormat[DeviceInfo]   = jsonFormat9(DeviceInfo.apply)
  implicit val segmentInfoFormat: RootJsonFormat[SegmentInfo] = jsonFormat4(SegmentInfo.apply)
  implicit val gapInfoFormat: RootJsonFormat[GapInfo]         = jsonFormat2(GapInfo.apply)

  implicit val fitLayoutFormat: RootJsonFormat[FitLayout] = new RootJsonFormat[FitLayout] {
    def write(l: FitLayout): JsValue = JsObject(
      "counts" -> JsArray(
        l.counts.map { case (t, c) => JsObject("type" -> JsString(t), "count" -> JsNumber(c)) }.toVector
      ),
      "totalMessages" -> JsNumber(l.totalMessages),
      "totalFields"   -> JsNumber(l.totalFields),
    )
    def read(v: JsValue): FitLayout = {
      val o = v.asJsObject
      val counts = o.fields.get("counts") match {
        case Some(JsArray(es)) =>
          es.map { e =>
            val eo = e.asJsObject
            val t  = eo.fields.get("type").collect { case JsString(s) => s }.getOrElse(deserializationError("type"))
            val c =
              eo.fields.get("count").collect { case JsNumber(n) => n.toInt }.getOrElse(deserializationError("count"))
            (t, c)
          }.toVector
        case _ => deserializationError("counts array expected")
      }
      val total = o.fields
        .get("totalMessages")
        .collect { case JsNumber(n) => n.toInt }
        .getOrElse(deserializationError("totalMessages"))
      val fields = o.fields
        .get("totalFields")
        .collect { case JsNumber(n) => n.toInt }
        .getOrElse(deserializationError("totalFields"))
      FitLayout(counts, total, fields)
    }
  }

  implicit val summaryResponseFormat: RootJsonFormat[SummaryResponse]   = jsonFormat3(SummaryResponse.apply)
  implicit val mergeReportFormat: RootJsonFormat[MergeReport]           = jsonFormat8(MergeReport.apply)
  implicit val uploadFileResultFormat: RootJsonFormat[UploadFileResult] = jsonFormat5(UploadFileResult.apply)
  implicit val uploadResponseFormat: RootJsonFormat[UploadResponse]     = jsonFormat1(UploadResponse.apply)
  implicit val mergeResponseFormat: RootJsonFormat[MergeResponse]       = jsonFormat2(MergeResponse.apply)
  implicit val apiErrorFormat: RootJsonFormat[ApiError]                 = jsonFormat1(ApiError.apply)

  implicit val mergeRequestFormat: RootJsonFormat[MergeRequest] = new RootJsonFormat[MergeRequest] {
    def write(r: MergeRequest): JsValue = JsObject(
      "ids"         -> r.ids.toJson,
      "gapHandling" -> JsString(r.gapHandling),
      "lapStrategy" -> JsString(r.lapStrategy),
      "dryRun"      -> JsBoolean(r.dryRun),
    )
    def read(v: JsValue): MergeRequest = {
      val fields = v.asJsObject.fields
      MergeRequest(
        ids = fields.get("ids").map(_.convertTo[Vector[String]]).getOrElse(Vector.empty),
        gapHandling = fields.get("gapHandling").map(_.convertTo[String]).getOrElse("preserve"),
        lapStrategy = fields.get("lapStrategy").map(_.convertTo[String]).getOrElse("OnePerSegment"),
        dryRun = fields.get("dryRun").exists(_.convertTo[Boolean]),
      )
    }
  }

  /** Threshold (seconds) between consecutive record timestamps that counts as a recording gap on the map. */
  private val GapThresholdSeconds = 30L

  /** A GeoJSON FeatureCollection: the track LineString plus start/finish/gap point features. */
  def trackGeoJson(file: FitFile): JsValue = {
    val points = file.records.flatMap(r => r.position.map(p => (p, r.timestamp)))
    val coords = JsArray(points.map { case (p, _) => JsArray(JsNumber(p.lon), JsNumber(p.lat)) }.toVector)

    def point(lon: Double, lat: Double, kind: String): JsValue = JsObject(
      "type"       -> JsString("Feature"),
      "properties" -> JsObject("type" -> JsString(kind)),
      "geometry"   -> JsObject("type" -> JsString("Point"), "coordinates" -> JsArray(JsNumber(lon), JsNumber(lat))),
    )

    val line = JsObject(
      "type"       -> JsString("Feature"),
      "properties" -> JsObject("type" -> JsString("track")),
      "geometry"   -> JsObject("type" -> JsString("LineString"), "coordinates" -> coords),
    )

    val gapMarkers = points
      .sliding(2)
      .collect {
        case Seq((p, t1), (_, t2)) if t2.getEpochSecond - t1.getEpochSecond > GapThresholdSeconds =>
          point(p.lon, p.lat, "gap")
      }
      .toVector

    val ends = Vector(
      points.headOption.map { case (p, _) => point(p.lon, p.lat, "start") },
      points.lastOption.map { case (p, _) => point(p.lon, p.lat, "finish") },
    ).flatten

    JsObject("type" -> JsString("FeatureCollection"), "features" -> JsArray(line +: (ends ++ gapMarkers)))
  }
}
