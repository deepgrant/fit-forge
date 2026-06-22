package ffmforge.http

import java.time.Instant

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
import ffmforge.fit.FitFileDescription
import ffmforge.fit.FitLayout
import ffmforge.fit.FitStats
import ffmforge.fit.RepairOperation
import ffmforge.fit.RideSummary
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import spray.json.enrichAny
import spray.json.enrichString

final class JsonProtocolSpec extends AnyFunSuite with Matchers {

  import JsonProtocol._

  test("codec demo report serializes and deserializes") {
    val now = Instant.parse("2026-06-15T08:00:00Z")
    val report = CodecDemoReport(
      id = "sample-id",
      originalBytes = 123,
      reencodedBytes = 100,
      original = FitFileDescription(10, 1, 1, 2, Some(now), Some(now.plusSeconds(9)), Some(90.0)),
      redecoded = FitFileDescription(10, 1, 1, 2, Some(now), Some(now.plusSeconds(9)), Some(90.0)),
      originalStats = FitStats(dataMessages = 10, definitionMessages = 2, developerFields = 0),
      reencodedStats = FitStats(dataMessages = 10, definitionMessages = 1, developerFields = 0),
      summary =
        RideSummary(Some("cycling"), Some(90.0), Some(9.0), Some(9.0), Some(10.0), Some(12.0), None, None, None, None),
      primaryDevice = Some(
        DeviceInfo(
          0,
          "garmin",
          Some("Edge"),
          Some(1),
          Some("bike_computer"),
          Some(1.0),
          Some(123L),
          Some("good"),
          Some("local"),
        )
      ),
      devices = Vector.empty,
      layout = FitLayout(Vector("record" -> 10), totalMessages = 10, totalFields = 40),
      checks = Vector(CodecCheck("record count preserved", passed = true, "10 == 10")),
      passed = true,
    )

    val json = report.toJson.compactPrint
    json.parseJson.convertTo[CodecDemoReport] shouldBe report
  }

  test("editor open response serializes and deserializes") {
    val now = Instant.parse("2026-06-15T08:00:00Z")
    val response = EditorOpenResponse(
      id = "sample-id",
      summary =
        RideSummary(Some("cycling"), Some(90.0), Some(9.0), Some(9.0), Some(10.0), Some(12.0), None, None, None, None),
      devices = Vector.empty,
      layout = FitLayout(Vector("record" -> 10), totalMessages = 10, totalFields = 40),
      anatomy = Vector(EditorMessageGroup("record", count = 10, status = "warning", issues = 1)),
      diagnostics = Vector(
        DiagnosticIssue(
          id = "power-spike-3",
          kind = "spike",
          severity = "warning",
          title = "Power spike",
          detail = "Suspicious jump",
          messageType = "record",
          startIndex = 3,
          endIndex = 3,
          field = Some("power"),
          suggestedOperations = Vector(RepairOperation("interpolateNumeric", "record", 3, 3, Some("power"), None)),
        )
      ),
      rows = EditorRowsResponse(
        "record",
        offset = 0,
        limit = 80,
        total = 10,
        rows = Vector(
          EditorRecordRow(
            index = 0,
            messageIndex = 2,
            messageType = "record",
            timestamp = Some(now),
            position = Some("42.00000,-71.00000"),
            heartRate = Some(120),
            power = Some(200),
            speedMps = Some(8.0),
            cadence = Some(88),
            altitudeM = Some(100.0),
            temperatureC = Some(21.0),
            fields = Vector(EditorCell("field_7", "200", Some(200.0))),
            issueIds = Vector("power-spike-3"),
          )
        ),
      ),
      verification = EditorVerification("upload-safe", canExport = true, checks = Vector("10 messages readable")),
    )

    val json = response.toJson.compactPrint
    json.parseJson.convertTo[EditorOpenResponse] shouldBe response
  }
}
