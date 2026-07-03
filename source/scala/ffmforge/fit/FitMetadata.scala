package ffmforge.fit

import java.lang.reflect.Method
import java.text.NumberFormat
import java.util.Locale

import scala.util.Try

import ffmforge.fit.FitProfile._

/** Readable FIT message/field metadata and value formatting for the generic editor table. */
object FitMetadata {

  private final case class SdkField(name: String, units: Option[String], profileType: Option[AnyRef])

  private val SensorFieldOrder: Map[Int, Int] = Vector(
    254, 50, 2, 10, 14, 21, 32, 33, 73, 52,
  ).zipWithIndex.toMap

  private val MessageNames: Map[Int, String] = Map(
    104 -> "battery",
    147 -> "sensor",
  )

  private val FieldLabels: Map[String, Map[Int, String]] = Map(
    "activity" -> Map(
      Act.TotalTimer  -> "Total timer",
      Act.NumSessions -> "Sessions",
      Act.Type        -> "Type",
      Act.Event       -> "Event",
      Act.EventType   -> "Event type",
      5               -> "Local timestamp",
      6               -> "Event group",
    ),
    "battery" -> Map(
      0 -> "Voltage",
      2 -> "Temperature",
      3 -> "Level",
      4 -> "Current",
    ),
    "device_info" -> Map(
      Dev.DeviceIndex     -> "Device index",
      Dev.DeviceType      -> "Device type",
      Dev.Manufacturer    -> "Manufacturer",
      Dev.SerialNumber    -> "Serial",
      Dev.Product         -> "Product",
      Dev.SoftwareVersion -> "Software",
      Dev.BatteryStatus   -> "Battery status",
      Dev.SourceType      -> "Source",
      Dev.ProductName     -> "Product name",
    ),
    "event" -> Map(
      Ev.Event     -> "Event",
      Ev.EventType -> "Event type",
      2            -> "Data 16",
      3            -> "Data",
      4            -> "Event group",
      7            -> "Score",
      8            -> "Opponent score",
      9            -> "Front gear num",
      10           -> "Front gear",
      11           -> "Rear gear num",
      12           -> "Rear gear",
      13           -> "Device index",
      14           -> "Activity type",
      15           -> "Start timestamp",
      21           -> "Max radar threat",
      22           -> "Radar threat count",
      23           -> "Avg radar approach",
      24           -> "Max radar approach",
    ),
    "file_creator" -> Map(
      0 -> "Software",
      1 -> "Hardware",
    ),
    "file_id" -> Map(
      Fid.Type         -> "Type",
      Fid.Manufacturer -> "Manufacturer",
      Fid.Product      -> "Product",
      Fid.SerialNumber -> "Serial",
      Fid.TimeCreated  -> "Created",
    ),
    "lap" -> Map(
      254              -> "Index",
      Lp.Event         -> "Event",
      Lp.EventType     -> "Event type",
      Lp.StartTime     -> "Start time",
      3                -> "Start latitude",
      4                -> "Start longitude",
      5                -> "End latitude",
      6                -> "End longitude",
      Lp.TotalElapsed  -> "Elapsed",
      Lp.TotalTimer    -> "Timer",
      Lp.TotalDistance -> "Distance",
      10               -> "Cycles",
      11               -> "Calories",
      12               -> "Fat calories",
      13               -> "Avg speed",
      14               -> "Max speed",
      15               -> "Avg heart rate",
      16               -> "Max heart rate",
      17               -> "Avg cadence",
      18               -> "Max cadence",
      19               -> "Avg power",
      20               -> "Max power",
      21               -> "Ascent",
      22               -> "Descent",
      23               -> "Intensity",
      24               -> "Trigger",
      25               -> "Sport",
      26               -> "Event group",
      33               -> "Normalized power",
      34               -> "Left/right balance",
      42               -> "Avg altitude",
      43               -> "Max altitude",
      44               -> "GPS accuracy",
      50               -> "Avg temperature",
      51               -> "Max temperature",
      52               -> "Moving time",
      57               -> "Time in HR zone",
      58               -> "Time in speed zone",
      59               -> "Time in cadence zone",
      60               -> "Time in power zone",
      62               -> "Min altitude",
      63               -> "Min heart rate",
      70               -> "Active time",
      110              -> "Enhanced avg speed",
      111              -> "Enhanced max speed",
      112              -> "Enhanced avg altitude",
      113              -> "Enhanced min altitude",
      114              -> "Enhanced max altitude",
    ),
    "sensor" -> Map(
      254 -> "message index",
      0   -> "serial",
      1   -> "state",
      2   -> "name",
      3   -> "enabled",
      10  -> "wheel size manual (mm)",
      14  -> "calibration factor",
      17  -> "gear count",
      18  -> "gear teeth",
      19  -> "rear gears",
      20  -> "rear gear teeth",
      21  -> "wheel size auto (mm)",
      28  -> "front gear table",
      29  -> "rear gear table",
      30  -> "gear table",
      32  -> "product",
      33  -> "manufacturer",
      34  -> "software",
      36  -> "mount side",
      37  -> "sensor position",
      38  -> "sensor direction",
      39  -> "sensor status",
      40  -> "light mode",
      47  -> "paired",
      50  -> "ant id",
      51  -> "network",
      52  -> "device type",
      53  -> "transmission type",
      54  -> "protocol",
      60  -> "shift mode",
      61  -> "shift count",
      62  -> "hrm mode",
      73  -> "connection type",
      82  -> "radar mode",
      83  -> "light network",
      84  -> "light beam",
      87  -> "radar",
    ),
    "session" -> Map(
      254                  -> "Index",
      Ses.Event            -> "Event",
      Ses.EventType        -> "Event type",
      Ses.StartTime        -> "Start time",
      3                    -> "Start latitude",
      4                    -> "Start longitude",
      Ses.Sport            -> "Sport",
      Ses.SubSport         -> "Sub sport",
      Ses.TotalElapsed     -> "Elapsed",
      Ses.TotalTimer       -> "Timer",
      Ses.TotalDistance    -> "Distance",
      10                   -> "Cycles",
      11                   -> "Calories",
      13                   -> "Fat calories",
      Ses.AvgSpeed         -> "Avg speed",
      Ses.MaxSpeed         -> "Max speed",
      16                   -> "Avg heart rate",
      17                   -> "Max heart rate",
      18                   -> "Avg cadence",
      19                   -> "Max cadence",
      Ses.AvgPower         -> "Avg power",
      Ses.MaxPower         -> "Max power",
      22                   -> "Ascent",
      23                   -> "Descent",
      24                   -> "Training effect",
      25                   -> "First lap",
      26                   -> "Laps",
      27                   -> "Event group",
      28                   -> "Trigger",
      29                   -> "NE latitude",
      30                   -> "NE longitude",
      31                   -> "SW latitude",
      32                   -> "SW longitude",
      34                   -> "Normalized power",
      35                   -> "TSS",
      36                   -> "Intensity factor",
      37                   -> "Left/right balance",
      38                   -> "End latitude",
      39                   -> "End longitude",
      45                   -> "Threshold power",
      49                   -> "Avg altitude",
      50                   -> "Max altitude",
      51                   -> "GPS accuracy",
      57                   -> "Avg temperature",
      58                   -> "Max temperature",
      59                   -> "Moving time",
      64                   -> "Min heart rate",
      Ses.EnhancedAvgSpeed -> "Enhanced avg speed",
      Ses.EnhancedMaxSpeed -> "Enhanced max speed",
      126                  -> "Enhanced avg altitude",
      127                  -> "Enhanced min altitude",
      128                  -> "Enhanced max altitude",
    ),
  )

  private val ManufacturerAliases: Map[String, String] = Map(
    "_4iiiis"       -> "4iiii",
    "polar_electro" -> "Polar",
    "sram"          -> "SRAM",
  )

  private val Acronyms: Set[String] = Set("ANT", "GPS", "HR", "HRM", "LED", "MTB", "SRAM", "TSS", "USB", "VAM")

  private val IntegerFormat: NumberFormat = NumberFormat.getIntegerInstance(Locale.US)

  private lazy val factoryCreateMesg: Option[Method] =
    Try(Class.forName("com.garmin.fit.Factory").getMethod("createMesg", java.lang.Integer.TYPE)).toOption

  private lazy val profileEnumValueName: Option[Method] = {
    val profileClass = Try(Class.forName("com.garmin.fit.Profile")).toOption
    val typeClass    = Try(Class.forName("com.garmin.fit.Profile$Type")).toOption
    for {
      profile <- profileClass
      tpe     <- typeClass
      method  <- Try(profile.getMethod("enumValueName", tpe, java.lang.Long.TYPE)).toOption
    } yield method
  }

  def messageName(globalNum: Int): String =
    MessageNames
      .get(globalNum)
      .orElse(sdkMessage(globalNum).flatMap(nameOfMessage).filterNot(isUnknown))
      .getOrElse(s"mesg_$globalNum")

  def fieldName(globalNum: Int, messageType: String, fieldNum: Int): String =
    FieldLabels
      .get(messageType)
      .flatMap(_.get(fieldNum))
      .orElse(sdkField(globalNum, fieldNum).map(_.name).filterNot(isUnknown).map(prettyFieldName))
      .getOrElse(s"field $fieldNum")

  def sortFields(messageType: String, fields: Vector[RawField]): Vector[RawField] =
    if (messageType == "sensor")
      fields.sortBy(field => (SensorFieldOrder.getOrElse(field.num, 1000 + field.num), field.num))
    else fields.sortBy(_.num)

  def formatField(globalNum: Int, messageType: String, message: FitMessage, field: RawField): String =
    if (messageType == "sensor" && field.num == 50) {
      sensorAntId(message).getOrElse(
        field.values.map(formatValue(globalNum, messageType, message, field.num, _)).mkString(", ")
      )
    } else {
      field.values.map(formatValue(globalNum, messageType, message, field.num, _)).mkString(", ")
    }

  def manufacturerName(value: Int): String =
    enumString("com.garmin.fit.Manufacturer", classOf[Integer], Integer.valueOf(value))
      .filter(_.nonEmpty)
      .map(prettyManufacturer)
      .getOrElse(ManufacturerEnum.nameOf(value))

  def sourceTypeName(value: Int): Option[String] =
    SourceTypeEnum
      .nameOf(value)
      .orElse(
        enumString("com.garmin.fit.SourceType", classOf[Short], java.lang.Short.valueOf(value.toShort))
          .filter(_.nonEmpty)
          .map(_.toLowerCase(Locale.ROOT))
      )

  def antplusDeviceTypeName(value: Int): Option[String] =
    AntplusDeviceTypeLabels
      .get(value)
      .orElse(
        enumString("com.garmin.fit.AntplusDeviceType", classOf[Short], java.lang.Short.valueOf(value.toShort))
          .filter(_.nonEmpty)
          .map(prettyEnumValue)
      )

  def sensorDeviceTypeName(message: FitMessage, value: Int): String =
    if (
      message.numeric(33).map(v => manufacturerName(v.toInt).equalsIgnoreCase("Shimano")).contains(true) &&
      message.numeric(32).exists(_.toInt == 12868)
    ) "shimano di2"
    else antplusDeviceTypeName(value).getOrElse(formatNumber(value.toDouble))

  def sensorAntId(message: FitMessage): Option[String] =
    message.field(50).flatMap { field =>
      val values = field.values.collect { case FitValue.Num(v) => v.toInt }
      values match {
        case Vector(channel, network, deviceType, deviceNumber, _*) =>
          Some(s"${hex(channel, 1)}-${hex(network, 1)}-${hex(deviceType, 2)}-${hex(deviceNumber, 4)}")
        case _ => None
      }
    }

  def sensorProductName(message: FitMessage, product: Int): Option[String] =
    message.numeric(33).map(v => manufacturerName(v.toInt)).flatMap(GarminProductResolver.nameOf(_, Some(product)))

  def sensorSoftwareVersion(message: FitMessage): Option[Double] =
    message.numeric(34).map(_ / 100.0)

  private def formatValue(
    globalNum: Int,
    messageType: String,
    message: FitMessage,
    fieldNum: Int,
    value: FitValue,
  ): String =
    (messageType, fieldNum, value) match {
      case (_, _, FitValue.Num(v)) if isDateTimeField(messageType, fieldNum)       => formatInstant(v)
      case (_, _, FitValue.Num(v)) if isLatitudeField(messageType, fieldNum)       => formatLatLon(v)
      case (_, _, FitValue.Num(v)) if isLongitudeField(messageType, fieldNum)      => formatLatLon(v)
      case (_, _, FitValue.Num(v)) if isElapsedSecondsField(messageType, fieldNum) => formatSeconds(v)
      case (_, _, FitValue.Num(v)) if isDistanceField(messageType, fieldNum)       => f"$v%.2f m"
      case (_, _, FitValue.Num(v)) if isSpeedField(messageType, fieldNum)          => f"$v%.2f m/s"
      case (_, _, FitValue.Num(v)) if isHeartRateField(messageType, fieldNum)      => f"$v%.0f bpm"
      case (_, _, FitValue.Num(v)) if isCadenceField(messageType, fieldNum)        => f"$v%.0f rpm"
      case (_, _, FitValue.Num(v)) if isPowerField(messageType, fieldNum)          => f"$v%.0f W"
      case (_, _, FitValue.Num(v)) if isAltitudeField(messageType, fieldNum)       => f"$v%.1f m"
      case (_, _, FitValue.Num(v)) if isTemperatureField(messageType, fieldNum)    => f"$v%.0f C"
      case ("battery", 0, FitValue.Num(v))                                         => f"${v / 1000.0}%.3f V"
      case ("battery", 2, FitValue.Num(v))                                         => f"$v%.0f C"
      case ("battery", 3, FitValue.Num(v))                                         => f"$v%.0f%%"
      case ("battery", 4, FitValue.Num(v))                                         => f"${v / 1000.0}%.1f mA"
      case ("device_info", Dev.BatteryStatus, FitValue.Num(v)) =>
        BatteryStatusEnum.nameOf(v.toInt).getOrElse(formatNumber(v))
      case ("device_info", Dev.DeviceType, FitValue.Num(v)) =>
        deviceInfoDeviceType(message, v.toInt).getOrElse(formatNumber(v))
      case ("device_info", Dev.Manufacturer, FitValue.Num(v)) => manufacturerName(v.toInt)
      case ("device_info", Dev.Product, FitValue.Num(v)) =>
        GarminProductResolver.nameOf(manufacturerFromDeviceInfo(message), Some(v.toInt)).getOrElse(formatNumber(v))
      case ("device_info", Dev.SourceType, FitValue.Num(v)) =>
        sourceTypeName(v.toInt).getOrElse(formatNumber(v))
      case ("file_id", Fid.Manufacturer, FitValue.Num(v)) => manufacturerName(v.toInt)
      case ("file_id", Fid.TimeCreated, FitValue.Num(v))  => formatInstant(v)
      case ("sensor", 10 | 21, FitValue.Num(v))           => IntegerFormat.format(v.toLong)
      case ("sensor", 32, FitValue.Num(v)) => sensorProductName(message, v.toInt).getOrElse(formatNumber(v))
      case ("sensor", 33, FitValue.Num(v)) => manufacturerName(v.toInt).toLowerCase(Locale.ROOT)
      case ("sensor", 34, FitValue.Num(v)) => f"${v / 100.0}%.2f"
      case ("sensor", 52, FitValue.Num(v)) => sensorDeviceTypeName(message, v.toInt)
      case ("sensor", 73, FitValue.Num(v)) => sourceTypeName(v.toInt).getOrElse(formatNumber(v))
      case (_, _, FitValue.Num(v)) =>
        sdkEnumValue(globalNum, fieldNum, v)
          .orElse(sdkNumberWithUnits(globalNum, fieldNum, v))
          .getOrElse(formatNumber(v))
      case (_, _, FitValue.Text(v)) => v
    }

  private def deviceInfoDeviceType(message: FitMessage, value: Int): Option[String] =
    message.numeric(Dev.SourceType).map(_.toInt) match {
      case Some(1) => antplusDeviceTypeName(value)
      case _       => None
    }

  private def manufacturerFromDeviceInfo(message: FitMessage): String =
    message.numeric(Dev.Manufacturer).map(v => manufacturerName(v.toInt)).getOrElse("unknown")

  private def sdkNumberWithUnits(globalNum: Int, fieldNum: Int, value: Double): Option[String] =
    sdkField(globalNum, fieldNum).flatMap(_.units).filter(_.nonEmpty).map(unit => s"${formatNumber(value)} $unit")

  private def sdkEnumValue(globalNum: Int, fieldNum: Int, value: Double): Option[String] =
    for {
      method <- profileEnumValueName
      field  <- sdkField(globalNum, fieldNum)
      tpe    <- field.profileType
      raw <- Try(method.invoke(method.getDeclaringClass, tpe, java.lang.Long.valueOf(value.toLong))).toOption.collect {
        case s: String => s
      }
      cleaned <- Option(raw).map(_.trim).filter(_.nonEmpty).filterNot(_ == value.toLong.toString)
    } yield prettyEnumValue(cleaned)

  private def sdkMessage(globalNum: Int): Option[AnyRef] =
    factoryCreateMesg.flatMap(method =>
      Try(method.invoke(method.getDeclaringClass, Integer.valueOf(globalNum))).toOption.collect { case ref: AnyRef =>
        ref
      }
    )

  private def sdkField(globalNum: Int, fieldNum: Int): Option[SdkField] =
    sdkMessage(globalNum).flatMap { message =>
      val field = for {
        method <- Try(message.getClass.getMethod("getField", java.lang.Integer.TYPE)).toOption
        value  <- Try(method.invoke(message, Integer.valueOf(fieldNum))).toOption
      } yield value
      field.collect { case ref: AnyRef => ref }.flatMap(fieldMeta)
    }

  private def fieldMeta(field: AnyRef): Option[SdkField] =
    for {
      name <- invokeString(field, "getName").filter(_.nonEmpty)
    } yield SdkField(
      name,
      invokeString(field, "getUnits"),
      Try(field.getClass.getMethod("getProfileType").invoke(field)).toOption.collect { case ref: AnyRef => ref },
    )

  private def nameOfMessage(message: AnyRef): Option[String] =
    invokeString(message, "getName")

  private def invokeString(target: AnyRef, methodName: String): Option[String] =
    Try(target.getClass.getMethod(methodName).invoke(target)).toOption.collect { case s: String => s.trim }

  private def enumString(className: String, argumentType: Class[?], value: AnyRef): Option[String] =
    for {
      cls    <- Try(Class.forName(className)).toOption
      method <- Try(cls.getMethod("getStringFromValue", argumentType)).toOption
      raw    <- Try(method.invoke(method.getDeclaringClass, value)).toOption.collect { case s: String => s.trim }
    } yield raw

  private def prettyManufacturer(raw: String): String =
    ManufacturerAliases.getOrElse(raw.toLowerCase(Locale.ROOT), prettyEnumValue(raw))

  private def prettyFieldName(raw: String): String =
    raw.split("_").filter(_.nonEmpty).map(_.toLowerCase(Locale.ROOT)).mkString(" ").capitalize

  private def prettyEnumValue(raw: String): String =
    raw
      .split("_")
      .filter(_.nonEmpty)
      .map { part =>
        val upper = part.toUpperCase(Locale.ROOT)
        if (Acronyms.contains(upper) || part.forall(_.isDigit)) upper
        else upper.headOption.fold(part)(head => s"$head${upper.drop(1).toLowerCase(Locale.ROOT)}")
      }
      .mkString(" ")

  private def isUnknown(value: String): Boolean =
    value.trim.isEmpty || value.trim.equalsIgnoreCase("unknown")

  private def hex(value: Int, width: Int): String =
    value.toHexString.toUpperCase(Locale.ROOT).reverse.padTo(width, '0').reverse

  private def formatNumber(value: Double): String =
    if (value.isWhole) value.toLong.toString else f"$value%.3f"

  private def formatInstant(value: Double): String =
    fitSecondsToInstant(value.toLong).toString

  private def formatLatLon(value: Double): String =
    f"${semicirclesToDeg(value.toLong)}%.6f"

  private def formatSeconds(value: Double): String =
    if (value.isWhole) s"${value.toLong} s" else f"$value%.2f s"

  private def isDateTimeField(messageType: String, num: Int): Boolean =
    (messageType, num) match {
      case ("session" | "lap", 2) => true
      case ("event", 15)          => true
      case _                      => false
    }

  private def isLatitudeField(messageType: String, num: Int): Boolean =
    (messageType, num) match {
      case ("session", 3 | 29 | 31 | 38) => true
      case ("lap", 3 | 5)                => true
      case _                             => false
    }

  private def isLongitudeField(messageType: String, num: Int): Boolean =
    (messageType, num) match {
      case ("session", 4 | 30 | 32 | 39) => true
      case ("lap", 4 | 6)                => true
      case _                             => false
    }

  private def isElapsedSecondsField(messageType: String, num: Int): Boolean =
    (messageType, num) match {
      case ("session", 7 | 8 | 59)  => true
      case ("lap", 7 | 8 | 52 | 70) => true
      case ("activity", 0)          => true
      case _                        => false
    }

  private def isDistanceField(messageType: String, num: Int): Boolean =
    (messageType, num) match {
      case ("session", 9) | ("lap", 9) => true
      case _                           => false
    }

  private def isSpeedField(messageType: String, num: Int): Boolean =
    (messageType, num) match {
      case ("session", 14 | 15 | 124 | 125) => true
      case ("lap", 13 | 14 | 110 | 111)     => true
      case _                                => false
    }

  private def isHeartRateField(messageType: String, num: Int): Boolean =
    (messageType, num) match {
      case ("session", 16 | 17 | 64) => true
      case ("lap", 15 | 16 | 63)     => true
      case _                         => false
    }

  private def isCadenceField(messageType: String, num: Int): Boolean =
    (messageType, num) match {
      case ("session", 18 | 19) => true
      case ("lap", 17 | 18)     => true
      case _                    => false
    }

  private def isPowerField(messageType: String, num: Int): Boolean =
    (messageType, num) match {
      case ("session", 20 | 21 | 34 | 45) => true
      case ("lap", 19 | 20 | 33)          => true
      case _                              => false
    }

  private def isAltitudeField(messageType: String, num: Int): Boolean =
    (messageType, num) match {
      case ("session", 22 | 23 | 49 | 50 | 126 | 127 | 128)  => true
      case ("lap", 21 | 22 | 42 | 43 | 62 | 112 | 113 | 114) => true
      case _                                                 => false
    }

  private def isTemperatureField(messageType: String, num: Int): Boolean =
    (messageType, num) match {
      case ("session", 57 | 58)   => true
      case ("lap", 50 | 51 | 124) => true
      case _                      => false
    }

  private val AntplusDeviceTypeLabels: Map[Int, String] = Map(
    1   -> "antfs",
    11  -> "power",
    15  -> "speed distance",
    16  -> "control",
    17  -> "fitness equipment",
    25  -> "environment sensor",
    31  -> "muscle oxygen",
    34  -> "shifting",
    35  -> "lights",
    36  -> "lights",
    40  -> "radar",
    119 -> "weight scale",
    120 -> "external heart rate",
    121 -> "speed cadence",
    122 -> "cadence",
    123 -> "speed",
    124 -> "stride speed distance",
  )
}
