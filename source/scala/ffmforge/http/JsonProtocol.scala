package ffmforge.http

import java.time.Instant

import scala.util.Try

import ffmforge.DownloadFormat
import ffmforge.fit.CodecCheck
import ffmforge.fit.CodecDemoReport
import ffmforge.fit.DeviceInfo
import ffmforge.fit.DiagnosticIssue
import ffmforge.fit.EditorCell
import ffmforge.fit.EditorMessageGroup
import ffmforge.fit.EditorOpenResponse
import ffmforge.fit.EditorRecordRow
import ffmforge.fit.EditorRowsResponse
import ffmforge.fit.EditorVerification
import ffmforge.fit.ExportRepairResponse
import ffmforge.fit.FileId
import ffmforge.fit.FitFile
import ffmforge.fit.FitFileDescription
import ffmforge.fit.FitLayout
import ffmforge.fit.FitStats
import ffmforge.fit.GapInfo
import ffmforge.fit.LapStrategy
import ffmforge.fit.MergeReport
import ffmforge.fit.RepairChange
import ffmforge.fit.RepairOperation
import ffmforge.fit.RepairPreview
import ffmforge.fit.RideSummary
import ffmforge.fit.SegmentInfo
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

  implicit val downloadFormatFormat: JsonFormat[DownloadFormat] = new JsonFormat[DownloadFormat] {
    def write(format: DownloadFormat): JsValue = JsString(format.wireName)
    def read(v: JsValue): DownloadFormat = v match {
      case JsString(s) => DownloadFormat.fromWireName(s).getOrElse(deserializationError("download format expected"))
      case _           => deserializationError("download format string expected")
    }
  }

  implicit val fileIdFormat: RootJsonFormat[FileId]                         = jsonFormat4(FileId.apply)
  implicit val fitStatsFormat: RootJsonFormat[FitStats]                     = jsonFormat3(FitStats.apply)
  implicit val rideSummaryFormat: RootJsonFormat[RideSummary]               = jsonFormat10(RideSummary.apply)
  implicit val deviceInfoFormat: RootJsonFormat[DeviceInfo]                 = jsonFormat9(DeviceInfo.apply)
  implicit val fitFileDescriptionFormat: RootJsonFormat[FitFileDescription] = jsonFormat7(FitFileDescription.apply)
  implicit val codecCheckFormat: RootJsonFormat[CodecCheck]                 = jsonFormat3(CodecCheck.apply)
  implicit val segmentInfoFormat: RootJsonFormat[SegmentInfo]               = jsonFormat4(SegmentInfo.apply)
  implicit val gapInfoFormat: RootJsonFormat[GapInfo]                       = jsonFormat2(GapInfo.apply)
  implicit val editorCellFormat: RootJsonFormat[EditorCell]                 = jsonFormat3(EditorCell.apply)
  implicit val repairOperationFormat: RootJsonFormat[RepairOperation]       = jsonFormat6(RepairOperation.apply)
  implicit val diagnosticIssueFormat: RootJsonFormat[DiagnosticIssue]       = jsonFormat10(DiagnosticIssue.apply)
  implicit val editorMessageGroupFormat: RootJsonFormat[EditorMessageGroup] = jsonFormat4(EditorMessageGroup.apply)
  implicit val editorRecordRowFormat: RootJsonFormat[EditorRecordRow]       = jsonFormat13(EditorRecordRow.apply)
  implicit val editorVerificationFormat: RootJsonFormat[EditorVerification] = jsonFormat3(EditorVerification.apply)
  implicit val repairChangeFormat: RootJsonFormat[RepairChange]             = jsonFormat5(RepairChange.apply)
  implicit val repairPreviewFormat: RootJsonFormat[RepairPreview]           = jsonFormat3(RepairPreview.apply)
  implicit val editorRowsResponseFormat: RootJsonFormat[EditorRowsResponse] = jsonFormat5(EditorRowsResponse.apply)
  implicit val exportRepairResponseFormat: RootJsonFormat[ExportRepairResponse] = jsonFormat2(
    ExportRepairResponse.apply
  )

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

  implicit val editorOpenResponseFormat: RootJsonFormat[EditorOpenResponse] = jsonFormat8(EditorOpenResponse.apply)

  implicit val summaryResponseFormat: RootJsonFormat[SummaryResponse]         = jsonFormat3(SummaryResponse.apply)
  implicit val codecDemoReportFormat: RootJsonFormat[CodecDemoReport]         = jsonFormat13(CodecDemoReport.apply)
  implicit val mergeReportFormat: RootJsonFormat[MergeReport]                 = jsonFormat8(MergeReport.apply)
  implicit val uploadFileResultFormat: RootJsonFormat[UploadFileResult]       = jsonFormat5(UploadFileResult.apply)
  implicit val uploadResponseFormat: RootJsonFormat[UploadResponse]           = jsonFormat1(UploadResponse.apply)
  implicit val uploadUrlRequestFormat: RootJsonFormat[UploadUrlRequest]       = jsonFormat1(UploadUrlRequest.apply)
  implicit val uploadUrlResultFormat: RootJsonFormat[UploadUrlResult]         = jsonFormat4(UploadUrlResult.apply)
  implicit val uploadUrlsResponseFormat: RootJsonFormat[UploadUrlsResponse]   = jsonFormat1(UploadUrlsResponse.apply)
  implicit val describeRequestFormat: RootJsonFormat[DescribeRequest]         = jsonFormat1(DescribeRequest.apply)
  implicit val editorFileRequestFormat: RootJsonFormat[EditorFileRequest]     = jsonFormat1(EditorFileRequest.apply)
  implicit val editorRowsRequestFormat: RootJsonFormat[EditorRowsRequest]     = jsonFormat4(EditorRowsRequest.apply)
  implicit val editorRepairRequestFormat: RootJsonFormat[EditorRepairRequest] = jsonFormat2(EditorRepairRequest.apply)
  implicit val downloadUrlResponseFormat: RootJsonFormat[DownloadUrlResponse] = jsonFormat5(DownloadUrlResponse.apply)
  implicit val codecDemoRequestFormat: RootJsonFormat[CodecDemoRequest]       = jsonFormat1(CodecDemoRequest.apply)
  implicit val mergeResponseFormat: RootJsonFormat[MergeResponse]             = jsonFormat2(MergeResponse.apply)
  implicit val apiErrorFormat: RootJsonFormat[ApiError]                       = jsonFormat1(ApiError.apply)

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
