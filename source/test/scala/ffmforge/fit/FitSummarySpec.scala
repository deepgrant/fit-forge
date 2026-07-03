package ffmforge.fit

import java.time.Instant
import java.time.temporal.ChronoUnit

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

final class FitSummarySpec extends AnyFunSuite with Matchers {

  private val t0 = Instant.parse("2026-06-15T08:00:00Z")

  test("ride summary reports sport and power (averaged from records when the session omits them)") {
    val recs = (0 until 10).map { i =>
      Record(timestamp = t0.plus(i.toLong, ChronoUnit.SECONDS), power = Some(100 + i * 10))
    }.toVector
    val file = FitFile.of(
      FileId(),
      recs,
      sessions = Vector(Session(t0, t0.plus(9, ChronoUnit.SECONDS), sport = Some("CYCLING"))),
    )

    val r = FitSummary.ride(file)
    r.sport shouldBe Some("CYCLING")
    r.maxPowerW shouldBe Some(190.0)
    r.avgPowerW.get shouldBe 145.0 +- 0.001
  }

  test("ride summary reports avg/max temperature from record temperature fields") {
    val fileId = FitViews.toMessage(FileId(timeCreated = Some(t0)))
    val recs = (0 until 5).map { i =>
      FitMessage(FitProfile.Mesg.Record)
        .setInstant(FitProfile.Rec.Timestamp, t0.plus(i.toLong, ChronoUnit.SECONDS))
        .setNumeric(FitProfile.Rec.Temperature, 20.0 + i)
    }.toVector
    val r = FitSummary.ride(FitFile(fileId +: recs))

    r.maxTempC shouldBe Some(24.0)
    r.avgTempC.get shouldBe 22.0 +- 0.001
  }

  test("temperature is absent when no record records it") {
    val recs = Vector(Record(timestamp = t0, power = Some(150)))
    val r    = FitSummary.ride(FitFile.of(FileId(), recs))
    r.avgTempC shouldBe None
    r.maxTempC shouldBe None
  }

  test("primary device is the local head unit; ANT+ sensor kind is decoded") {
    val fileId = FitViews.toMessage(FileId(timeCreated = Some(t0)))
    val headUnit = FitMessage(FitProfile.Mesg.DeviceInfo)
      .setNumeric(FitProfile.Dev.DeviceIndex, 0)
      .setNumeric(FitProfile.Dev.Manufacturer, 1) // Garmin
      .setNumeric(FitProfile.Dev.Product, 4440)   // Edge 1050
      .setNumeric(FitProfile.Dev.SourceType, 5)   // local
    val hrStrap = FitMessage(FitProfile.Mesg.DeviceInfo)
      .setNumeric(FitProfile.Dev.DeviceIndex, 4)
      .setNumeric(FitProfile.Dev.Manufacturer, 123) // Polar
      .setNumeric(FitProfile.Dev.SourceType, 1)     // antplus
      .setNumeric(FitProfile.Dev.DeviceType, 120)   // heart_rate
    val file = FitFile(Vector(fileId, headUnit, hrStrap))

    FitSummary.devices(file) should have size 2
    FitSummary.primaryDevice(file).map(_.index) shouldBe Some(0)
    FitSummary.primaryDevice(file).map(_.manufacturer) shouldBe Some("Garmin")
    FitSummary.primaryDevice(file).flatMap(_.productName) shouldBe Some("Edge 1050")
    FitSummary.devices(file).find(_.index == 4).flatMap(_.kind) shouldBe Some("heart_rate")
  }

  test("Shimano and SRAM manufacturer ids are decoded from device info") {
    val shimano = FitMessage(FitProfile.Mesg.DeviceInfo)
      .setNumeric(FitProfile.Dev.DeviceIndex, 7)
      .setNumeric(FitProfile.Dev.Manufacturer, 41)
      .setNumeric(FitProfile.Dev.Product, 12868)
      .setNumeric(FitProfile.Dev.SourceType, 0)
    val sram = FitMessage(FitProfile.Mesg.DeviceInfo)
      .setNumeric(FitProfile.Dev.DeviceIndex, 8)
      .setNumeric(FitProfile.Dev.Manufacturer, 268)
      .setNumeric(FitProfile.Dev.SourceType, 1)
    val devices = FitSummary.devices(FitFile(Vector(shimano, sram)))

    devices should have size 2
    devices.find(_.index == 7).map(_.manufacturer) shouldBe Some("Shimano")
    devices.find(_.index == 7).flatMap(_.product) shouldBe Some(12868)
    devices.find(_.index == 8).map(_.manufacturer) shouldBe Some("SRAM")
  }

  test("device info coalescing does not combine incompatible devices sharing an index") {
    val rallyNameOnly = FitMessage(FitProfile.Mesg.DeviceInfo)
      .setNumeric(FitProfile.Dev.DeviceIndex, 4)
      .setText(FitProfile.Dev.ProductName, "Rally 200")
    val polarHeartRate = FitMessage(FitProfile.Mesg.DeviceInfo)
      .setNumeric(FitProfile.Dev.DeviceIndex, 4)
      .setNumeric(FitProfile.Dev.Manufacturer, 123)
      .setNumeric(FitProfile.Dev.Product, 2)
      .setNumeric(FitProfile.Dev.SourceType, 1)
      .setNumeric(FitProfile.Dev.DeviceType, 120)
      .setNumeric(FitProfile.Dev.SerialNumber, 551562213)
    val devices = FitSummary.devices(FitFile(Vector(rallyNameOnly, polarHeartRate)))

    devices should have size 2
    devices.find(_.productName.contains("Rally 200")).map(_.manufacturer) shouldBe Some("Garmin")
    devices.find(_.serialNumber.contains(551562213L)).map(_.manufacturer) shouldBe Some("Polar")
    devices.find(_.serialNumber.contains(551562213L)).flatMap(_.kind) shouldBe Some("heart_rate")
  }

  test("unknown manufacturers are inferred from device and sensor names or product ids") {
    val sramDevice = FitMessage(FitProfile.Mesg.DeviceInfo)
      .setNumeric(FitProfile.Dev.DeviceIndex, 9)
      .setText(FitProfile.Dev.ProductName, "SRAM Eagle")
    val variaLight = FitMessage(FitProfile.Mesg.DeviceInfo)
      .setNumeric(FitProfile.Dev.DeviceIndex, 10)
      .setNumeric(FitProfile.Dev.Product, 2567)
    val tacxSensor = FitMessage(FitProfile.Mesg.Sensor)
      .setNumeric(254, 12)
      .setText(2, "Garmin Tacx")
      .setNumeric(32, 2875)
      .setNumeric(52, 17)
      .setNumeric(73, 1)

    val file = FitFile(Vector(sramDevice, variaLight, tacxSensor))

    FitSummary.devices(file).find(_.index == 9).map(_.manufacturer) shouldBe Some("SRAM")
    FitSummary.devices(file).find(_.index == 10).map(_.manufacturer) shouldBe Some("Garmin")
    FitSummary.devices(file).find(_.index == 10).flatMap(_.productName) shouldBe Some("Varia UT800")
    FitSummary.sensors(file).find(_.index == 12).map(_.manufacturer) shouldBe Some("Garmin")
    FitSummary.sensors(file).find(_.index == 12).flatMap(_.sourceType) shouldBe Some("antplus")
  }

  test("sensor settings are decoded into a hardware inventory") {
    val cadence = FitMessage(FitProfile.Mesg.Sensor)
      .setNumeric(254, 0)
      .withField(RawField(50, Vector(FitValue.Num(6), FitValue.Num(1), FitValue.Num(0x7a), FitValue.Num(0x6ca2))))
      .setText(2, "CAD Pinarello")
      .setNumeric(10, 2096)
      .setNumeric(21, 2122)
      .setNumeric(32, 9999)
      .setNumeric(33, 1)
      .setNumeric(34, 240)
      .setNumeric(52, 122)
      .setNumeric(73, 1)
    val shimano = FitMessage(FitProfile.Mesg.Sensor)
      .setNumeric(254, 1)
      .setNumeric(32, 12868)
      .setNumeric(33, 41)
      .setNumeric(52, 34)
      .setNumeric(73, 1)

    val sensors = FitSummary.sensors(FitFile(Vector(cadence, shimano)))

    sensors should have size 2
    sensors.head.name shouldBe Some("CAD Pinarello")
    sensors.head.antId shouldBe Some("6-1-7A-6CA2")
    sensors.head.kind shouldBe Some("cadence")
    sensors.head.sourceType shouldBe Some("antplus")
    sensors.head.softwareVersion shouldBe Some(2.4)
    sensors.head.wheelSizeManualMm shouldBe Some(2096.0)
    sensors.head.wheelSizeAutoMm shouldBe Some(2122.0)
    sensors(1).manufacturer shouldBe "Shimano"
    sensors(1).kind shouldBe Some("shimano di2")
  }

  test("Garmin product ids are resolved through the FIT SDK product table") {
    GarminProductResolver.nameOf("Garmin", Some(4440)) shouldBe Some("Edge 1050")
    GarminProductResolver.nameOf("Garmin", Some(3808)) shouldBe Some("Varia RCT715")
    GarminProductResolver.nameOf("Garmin", Some(4470)) shouldBe Some("Varia Vue")
    GarminProductResolver.nameOf("Polar", Some(4440)) shouldBe None
  }
}
