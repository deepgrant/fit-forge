package ffmforge.fit

import java.time.Instant

/**
 * FIT profile constants and unit conversions, kept SDK-free so the whole `fit` package can manipulate messages by field
 * number without importing `com.garmin.fit`. Numbers come from the Garmin FIT Profile.
 */
object FitProfile {

  /** FIT timestamps are seconds since 1989-12-31T00:00:00Z. */
  val FitEpochOffsetSeconds: Long = 631065600L

  def fitSecondsToInstant(s: Long): Instant = Instant.ofEpochSecond(FitEpochOffsetSeconds + s)
  def instantToFitSeconds(i: Instant): Long = i.getEpochSecond - FitEpochOffsetSeconds

  /** GPS is stored as 32-bit "semicircles": deg = sc * 180 / 2^31. */
  private val SemicirclesPerDegree: Double = 2147483648.0 / 180.0

  def degToSemicircles(deg: Double): Int = Math.round(deg * SemicirclesPerDegree).toInt
  def semicirclesToDeg(sc: Long): Double = sc.toDouble / SemicirclesPerDegree

  /** Global message numbers. */
  object Mesg {
    val FileId: Int     = 0
    val Session: Int    = 18
    val Lap: Int        = 19
    val Record: Int     = 20
    val Event: Int      = 21
    val DeviceInfo: Int = 23
    val Activity: Int   = 34

    private val names: Map[Int, String] = Map(
      0   -> "file_id",
      18  -> "session",
      19  -> "lap",
      20  -> "record",
      21  -> "event",
      22  -> "source",
      23  -> "device_info",
      34  -> "activity",
      49  -> "file_creator",
      78  -> "hrv",
      104 -> "battery",
      147 -> "sensor",
      206 -> "field_description",
      207 -> "developer_data_id",
    )

    /** Human name for a global message number, or `mesg_<n>` if unknown. */
    def nameOf(num: Int): String = names.getOrElse(num, s"mesg_$num")
  }

  object Rec {
    val PositionLat: Int      = 0
    val PositionLong: Int     = 1
    val Altitude: Int         = 2
    val HeartRate: Int        = 3
    val Cadence: Int          = 4
    val Distance: Int         = 5
    val Speed: Int            = 6
    val Power: Int            = 7
    val Temperature: Int      = 13
    val EnhancedSpeed: Int    = 73
    val EnhancedAltitude: Int = 78
    val Timestamp: Int        = 253
  }

  object Ses {
    val Event: Int            = 0
    val EventType: Int        = 1
    val StartTime: Int        = 2
    val Sport: Int            = 5
    val SubSport: Int         = 6
    val TotalElapsed: Int     = 7
    val TotalTimer: Int       = 8
    val TotalDistance: Int    = 9
    val AvgSpeed: Int         = 14
    val MaxSpeed: Int         = 15
    val AvgPower: Int         = 20
    val MaxPower: Int         = 21
    val EnhancedAvgSpeed: Int = 124
    val EnhancedMaxSpeed: Int = 125
    val Timestamp: Int        = 253
  }

  object Dev {
    val DeviceIndex: Int     = 0
    val DeviceType: Int      = 1
    val Manufacturer: Int    = 2
    val SerialNumber: Int    = 3
    val Product: Int         = 4
    val SoftwareVersion: Int = 5
    val BatteryStatus: Int   = 11
    val SourceType: Int      = 25
    val ProductName: Int     = 27
    val Timestamp: Int       = 253
  }

  object Lp {
    val Event: Int         = 0
    val EventType: Int     = 1
    val StartTime: Int     = 2
    val TotalElapsed: Int  = 7
    val TotalTimer: Int    = 8
    val TotalDistance: Int = 9
    val Timestamp: Int     = 253
  }

  object Ev {
    val Event: Int     = 0
    val EventType: Int = 1
    val Timestamp: Int = 253
  }

  object Fid {
    val Type: Int         = 0
    val Manufacturer: Int = 1
    val Product: Int      = 2
    val SerialNumber: Int = 3
    val TimeCreated: Int  = 4
  }

  object Act {
    val TotalTimer: Int  = 0
    val NumSessions: Int = 1
    val Type: Int        = 2
    val Event: Int       = 3
    val EventType: Int   = 4
    val Timestamp: Int   = 253
  }

  /** Enum values we set generically (from the FIT Profile). */
  object EventEnum { val Timer: Double = 0 }
  object EventTypeEnum {
    val Start: Double   = 0
    val Stop: Double    = 1
    val StopAll: Double = 4
  }
  object FileEnum         { val Activity: Double = 4 }
  object ActivityTypeEnum { val Manual: Double = 0   }

  /** A small subset of the `sport` enum — enough to round-trip common activity names. */
  object SportEnum {
    val Cycling: Double = 2

    private val byName: Map[String, Double] =
      Map("GENERIC" -> 0, "RUNNING" -> 1, "CYCLING" -> 2, "SWIMMING" -> 5, "WALKING" -> 11, "HIKING" -> 17)
    private val byValue: Map[Double, String] = byName.map { case (k, v) => (v, k) }

    def valueOf(name: String): Double = byName.getOrElse(name.toUpperCase, Cycling)
    def nameOf(value: Double): String = byValue.getOrElse(value, "CYCLING")
  }

  /** A few well-known `manufacturer` enum values; unknown values render as `manufacturer #N`. */
  object ManufacturerEnum {
    private val names: Map[Int, String] =
      Map(
        1   -> "Garmin",
        15  -> "Dynastream",
        23  -> "Suunto",
        32  -> "Wahoo Fitness",
        123 -> "Polar",
        255 -> "Development",
      )
    def nameOf(value: Int): String = names.getOrElse(value, s"manufacturer #$value")
  }

  /** Common ANT+ `device_type` values (the sensor kind), used when source_type is antplus. */
  object AntplusDeviceTypeEnum {
    private val names: Map[Int, String] = Map(
      1   -> "antfs",
      11  -> "bike_power",
      15  -> "multi_sport_speed_distance",
      16  -> "control",
      25  -> "fitness_equipment",
      119 -> "weight_scale",
      120 -> "heart_rate",
      121 -> "bike_speed_cadence",
      122 -> "bike_cadence",
      123 -> "bike_speed",
      124 -> "stride_speed_distance",
    )
    def nameOf(value: Int): Option[String] = names.get(value)
  }

  /** `battery_status` enum. */
  object BatteryStatusEnum {
    private val names: Map[Int, String] =
      Map(1 -> "new", 2 -> "good", 3 -> "ok", 4 -> "low", 5 -> "critical", 6 -> "charging", 7 -> "unknown")
    def nameOf(value: Int): Option[String] = names.get(value)
  }

  /** `source_type` enum — how the device was connected. */
  object SourceTypeEnum {
    private val names: Map[Int, String] =
      Map(0 -> "ant", 1 -> "antplus", 2 -> "bluetooth", 3 -> "bluetooth_le", 4 -> "wifi", 5 -> "local")
    def nameOf(value: Int): Option[String] = names.get(value)
  }
}
