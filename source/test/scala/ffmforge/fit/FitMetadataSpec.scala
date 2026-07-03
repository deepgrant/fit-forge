package ffmforge.fit

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

final class FitMetadataSpec extends AnyFunSuite with Matchers {

  test("message names use curated and SDK metadata before generic fallbacks") {
    FitMetadata.messageName(FitProfile.Mesg.Sensor) shouldBe "sensor"
    FitMetadata.messageName(FitProfile.Mesg.Session) shouldBe "session"
    FitMetadata.messageName(9999) shouldBe "mesg_9999"
  }

  test("sensor settings fields use FitFileViewer-style labels and enum values") {
    val message = FitMessage(FitProfile.Mesg.Sensor)
      .setNumeric(254, 0)
      .withField(RawField(50, Vector(FitValue.Num(6), FitValue.Num(1), FitValue.Num(0x7a), FitValue.Num(0x6ca2))))
      .setText(2, "CAD Pinarello")
      .setNumeric(10, 2096)
      .setNumeric(14, 100)
      .setNumeric(21, 2122)
      .setNumeric(32, 9999)
      .setNumeric(33, 1)
      .setNumeric(73, 1)
      .setNumeric(52, 122)

    val fields = FitMetadata
      .sortFields("sensor", message.fields)
      .map(field =>
        FitMetadata.fieldName(message.globalNum, "sensor", field.num) ->
          FitMetadata.formatField(message.globalNum, "sensor", message, field)
      )

    fields.take(10).map(_._1) shouldBe Vector(
      "message index",
      "ant id",
      "name",
      "wheel size manual (mm)",
      "calibration factor",
      "wheel size auto (mm)",
      "product",
      "manufacturer",
      "connection type",
      "device type",
    )
    fields should contain allOf (
      "ant id"                 -> "6-1-7A-6CA2",
      "wheel size manual (mm)" -> "2,096",
      "wheel size auto (mm)"   -> "2,122",
      "manufacturer"           -> "garmin",
      "connection type"        -> "antplus",
      "device type"            -> "cadence",
    )
  }

  test("sensor device type recognizes Shimano Di2 settings") {
    val message = FitMessage(FitProfile.Mesg.Sensor)
      .setNumeric(32, 12868)
      .setNumeric(33, 41)
      .setNumeric(52, 34)

    FitMetadata.sensorDeviceTypeName(message, 34) shouldBe "shimano di2"
  }
}
