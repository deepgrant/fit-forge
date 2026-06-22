package ffmforge.fit

import java.time.Instant

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

final class FitEditorSpec extends AnyFunSuite with Matchers {

  private val t0    = Instant.parse("2026-06-15T08:00:00Z")
  private val codec = new GarminFitCodec()

  test("anatomy counts match layout and record paging is stable") {
    val file = sampleFile()
    val open = FitEditor.open("sample", file)

    open.anatomy.map(g => g.name -> g.count).toSet shouldBe FitLayout.of(file).counts.toSet
    open.rows.messageType shouldBe "record"
    open.rows.total shouldBe file.recordMessages.size
    open.rows.rows.head.index shouldBe 0

    val page = FitEditor.rows(file, "record", 2, 2)
    page.rows.map(_.index) shouldBe Vector(2, 3)
    page.rows.head.heartRate shouldBe Some(120)
  }

  test("diagnostics detect dropouts, spikes, missing timestamps, and non-monotonic timestamps") {
    val diagnostics = FitEditor.diagnose(sampleFile())

    diagnostics.map(_.kind) should contain("dropout")
    diagnostics.map(_.kind) should contain("spike")
    diagnostics.map(_.kind) should contain("timestamp")
    diagnostics.exists(_.title == "Timestamp is not increasing") shouldBe true
  }

  test("repair preview reports before and after values") {
    val file = sampleFile()
    val preview = FitEditor.preview(
      file,
      Vector(RepairOperation("interpolateNumeric", "record", 3, 3, Some("power"), None)),
    )

    preview.changes should not be empty
    preview.changes.head.before shouldBe "1200"
    preview.changes.head.after shouldBe "205"
  }

  test("zero power is a valid reading when a power meter is present") {
    val file = FitFile(
      Vector(FitViews.toMessage(FileId(timeCreated = Some(t0))), bikePowerDevice) ++
        Vector(
          record(t0, hr = Some(118), power = Some(190), speed = Some(8.0)),
          record(t0.plusSeconds(1), hr = Some(119), power = Some(0), speed = Some(8.1)),
          record(t0.plusSeconds(2), hr = Some(120), power = Some(210), speed = Some(8.2)),
        )
    )

    FitEditor.diagnose(file).filter(_.field.contains("power")).map(_.kind) should not contain "dropout"
  }

  test("missing power readings are grouped into one dropout range") {
    val file = FitFile(
      Vector(FitViews.toMessage(FileId(timeCreated = Some(t0))), bikePowerDevice) ++
        Vector(
          record(t0, hr = Some(118), power = Some(190), speed = Some(8.0)),
          record(t0.plusSeconds(1), hr = Some(119), power = None, speed = Some(8.1)),
          record(t0.plusSeconds(2), hr = Some(120), power = None, speed = Some(8.2)),
          record(t0.plusSeconds(3), hr = Some(121), power = Some(210), speed = Some(8.3)),
        )
    )

    val powerDropouts =
      FitEditor.diagnose(file).filter(issue => issue.field.contains("power") && issue.kind == "dropout")
    powerDropouts should have size 1
    powerDropouts.head.startIndex shouldBe 1
    powerDropouts.head.endIndex shouldBe 2
  }

  test("non-record message rows use generic fields instead of record telemetry columns") {
    val batteryMessage = FitMessage(FitProfile.Mesg.Battery)
      .setInstant(FitProfile.Rec.Timestamp, t0)
      .setNumeric(0, 3797)
      .setNumeric(2, 45)
      .setNumeric(3, 37)
      .setNumeric(4, -111000)
    val rows = FitEditor.rows(FitFile(Vector(batteryMessage)), "battery", 0, 10)

    rows.rows should have size 1
    val row = rows.rows.head
    row.heartRate shouldBe None
    row.power shouldBe None
    row.position shouldBe None
    row.fields.map(field => field.field -> field.value) should contain allOf (
      "Voltage"     -> "3.797 V",
      "Temperature" -> "45 C",
      "Level"       -> "37%",
      "Current"     -> "-111.0 mA",
    )
  }

  test("common non-record message rows use readable field labels") {
    readableFields("session", FitProfile.Mesg.Session)(
      FitProfile.Ses.StartTime     -> "Start time",
      FitProfile.Ses.TotalElapsed  -> "Elapsed",
      FitProfile.Ses.TotalDistance -> "Distance",
      FitProfile.Ses.AvgPower      -> "Avg power",
    )
    readableFields("lap", FitProfile.Mesg.Lap)(
      FitProfile.Lp.StartTime  -> "Start time",
      FitProfile.Lp.TotalTimer -> "Timer",
      13                       -> "Avg speed",
      15                       -> "Avg heart rate",
    )
    readableFields("event", FitProfile.Mesg.Event)(
      FitProfile.Ev.Event     -> "Event",
      FitProfile.Ev.EventType -> "Event type",
      13                      -> "Device index",
      15                      -> "Start timestamp",
    )
    readableFields("activity", FitProfile.Mesg.Activity)(
      FitProfile.Act.TotalTimer  -> "Total timer",
      FitProfile.Act.NumSessions -> "Sessions",
      FitProfile.Act.EventType   -> "Event type",
    )
    readableFields("file_creator", FitProfile.Mesg.FileCreator)(
      0 -> "Software",
      1 -> "Hardware",
    )
    readableFields("sensor", FitProfile.Mesg.Sensor)(
      0   -> "Serial",
      2   -> "Name",
      32  -> "Product",
      34  -> "Software",
      254 -> "Index",
    )
  }

  test("single missing GPS sample is grouped and can be interpolated") {
    val file = FitFile(
      Vector(FitViews.toMessage(FileId(timeCreated = Some(t0)))) ++
        Vector(
          record(t0, hr = Some(118), power = Some(190), speed = Some(8.0), lat = 42.0, lon = -71.0),
          record(
            t0.plusSeconds(1),
            hr = Some(119),
            power = Some(195),
            speed = Some(8.1),
            lat = 42.001,
            lon = -71.001,
            includePosition = false,
          ),
          record(t0.plusSeconds(2), hr = Some(120), power = Some(200), speed = Some(8.2), lat = 42.002, lon = -71.002),
        )
    )

    val gpsIssues = FitEditor.diagnose(file).filter(_.kind == "gps")
    gpsIssues should have size 1
    gpsIssues.head.startIndex shouldBe 1
    gpsIssues.head.endIndex shouldBe 1
    gpsIssues.head.suggestedOperations.map(_.kind) shouldBe Vector("interpolatePosition")

    val (repaired, preview) = FitEditor.repair(file, gpsIssues.head.suggestedOperations)
    preview.changes should have size 1
    preview.changes.head.field shouldBe "position"
    FitEditor.diagnose(repaired).filter(_.kind == "gps") shouldBe empty

    val repairedPosition = repaired.records(1).position.getOrElse(fail("Expected repaired GPS position"))
    repairedPosition.lat shouldBe (42.001 +- 0.000001)
    repairedPosition.lon shouldBe (-71.001 +- 0.000001)
  }

  test("repaired file re-encodes and preserves unrelated messages") {
    val file = sampleFile()
    val (repaired, preview) = FitEditor.repair(
      file,
      Vector(
        RepairOperation("interpolateNumeric", "record", 3, 3, Some("power"), None),
        RepairOperation("deleteRecord", "record", 5, 6, None, None),
        RepairOperation("recalculateSummary", "record", 0, 0, None, None),
      ),
    )

    preview.verification.canExport shouldBe true
    repaired.messages.exists(_.globalNum == FitProfile.Mesg.DeviceInfo) shouldBe true
    val roundTrip = codec.decode(codec.encode(repaired))
    roundTrip.recordMessages(3).numeric(FitProfile.Rec.Power) shouldBe Some(205.0)
  }

  private def sampleFile(): FitFile = {
    val records = Vector(
      record(t0, hr = Some(118), power = Some(190), speed = Some(8.0)),
      record(t0.plusSeconds(1), hr = Some(119), power = Some(200), speed = Some(8.1)),
      record(t0.plusSeconds(2), hr = Some(120), power = Some(210), speed = Some(8.2)),
      record(t0.plusSeconds(3), hr = None, power = Some(1200), speed = Some(8.3)),
      record(t0.plusSeconds(4), hr = Some(122), power = Some(200), speed = Some(8.2)),
      record(t0.plusSeconds(3), hr = Some(123), power = Some(205), speed = Some(8.1)),
      FitMessage(FitProfile.Mesg.Record).setNumeric(FitProfile.Rec.Power, 210),
    )
    val device = FitMessage(FitProfile.Mesg.DeviceInfo)
      .setNumeric(FitProfile.Dev.DeviceIndex, 0)
      .setNumeric(FitProfile.Dev.Manufacturer, 1)
    FitFile(Vector(FitViews.toMessage(FileId(timeCreated = Some(t0))), device) ++ records)
  }

  private def bikePowerDevice: FitMessage =
    FitMessage(FitProfile.Mesg.DeviceInfo)
      .setNumeric(FitProfile.Dev.DeviceIndex, 5)
      .setNumeric(FitProfile.Dev.Manufacturer, 1)
      .setNumeric(FitProfile.Dev.SourceType, 1)
      .setNumeric(FitProfile.Dev.DeviceType, 11)

  private def readableFields(messageType: String, globalNum: Int)(fields: (Int, String)*): Unit = {
    val message = fields.foldLeft(FitMessage(globalNum).setInstant(FitProfile.Rec.Timestamp, t0)) {
      case (m, (fieldNum, _)) =>
        if (fieldNum == 2 && messageType == "sensor") m.setText(fieldNum, "sensor name")
        else m.setNumeric(fieldNum, 1)
    }
    val labels = FitEditor.rows(FitFile(Vector(message)), messageType, 0, 10).rows.head.fields.map(_.field).toSet
    fields.foreach { case (_, label) =>
      labels should contain(label)
    }
  }

  private def record(
      timestamp: Instant,
      hr: Option[Int],
      power: Option[Int],
      speed: Option[Double],
      lat: Double = 42.0,
      lon: Double = -71.0,
      includePosition: Boolean = true,
  ): FitMessage = {
    val base = FitMessage(FitProfile.Mesg.Record)
      .setInstant(FitProfile.Rec.Timestamp, timestamp)
      .setNumericOpt(FitProfile.Rec.HeartRate, hr.map(_.toDouble))
      .setNumericOpt(FitProfile.Rec.Power, power.map(_.toDouble))
      .setNumericOpt(FitProfile.Rec.EnhancedSpeed, speed)
    if (includePosition) {
      base
        .setNumeric(FitProfile.Rec.PositionLat, FitProfile.degToSemicircles(lat).toDouble)
        .setNumeric(FitProfile.Rec.PositionLong, FitProfile.degToSemicircles(lon).toDouble)
    } else base
  }
}
