package ffmforge.fit

import ffmforge.fit.FitProfile._

/** A device that contributed to a recording (from a `device_info` message). */
final case class DeviceInfo(
  index: Int,
  manufacturer: String,
  productName: Option[String],
  product: Option[Int],
  kind: Option[String],
  softwareVersion: Option[Double],
  serialNumber: Option[Long],
  batteryStatus: Option[String],
  sourceType: Option[String],
) {

  /** Best human label: the product name if present, else manufacturer (+ product number). */
  def displayName: String =
    productName.getOrElse(product.fold(manufacturer)(p => s"$manufacturer (product $p)"))
}

/** A configured sensor from a `sensor` settings message. */
final case class SensorInfo(
  index: Int,
  manufacturer: String,
  productName: Option[String],
  product: Option[Int],
  kind: Option[String],
  name: Option[String],
  antId: Option[String],
  sourceType: Option[String],
  softwareVersion: Option[Double],
  wheelSizeManualMm: Option[Double],
  wheelSizeAutoMm: Option[Double],
  calibrationFactor: Option[Double],
)

/** Headline ride statistics (read from the `session`, with record-derived fallbacks). */
final case class RideSummary(
  sport: Option[String],
  totalDistanceM: Option[Double],
  elapsedSeconds: Option[Double],
  movingSeconds: Option[Double],
  avgSpeedMps: Option[Double],
  maxSpeedMps: Option[Double],
  avgPowerW: Option[Double],
  maxPowerW: Option[Double],
  avgTempC: Option[Double],
  maxTempC: Option[Double],
)

object FitSummary {

  private val UnknownManufacturer: String = "unknown"

  private val ProductManufacturerHints: Map[Int, String] = Map(
    20    -> "Garmin",
    1016  -> "SRAM",
    2567  -> "Garmin",
    2875  -> "Garmin",
    3107  -> "Garmin",
    3192  -> "Garmin",
    3299  -> "Garmin",
    3578  -> "Garmin",
    3808  -> "Garmin",
    4470  -> "Garmin",
    12868 -> "Shimano",
  )

  /**
   * Distinct devices used in the recording. FIT emits multiple (often partial) `device_info` messages per device; we
   * coalesce compatible rows, preferring the most complete/most recent value of each field.
   */
  def devices(file: FitFile): Vector[DeviceInfo] =
    file.messages
      .filter(_.globalNum == Mesg.DeviceInfo)
      .flatMap(toDevice)
      .foldLeft(Vector.empty[DeviceInfo])(addDevice)
      .sortBy(device => (device.index, device.displayName, device.serialNumber.getOrElse(0L)))

  /** Configured sensor settings, decoded from Garmin's `sensor` message when present. */
  def sensors(file: FitFile): Vector[SensorInfo] =
    file.messages
      .filter(_.globalNum == Mesg.Sensor)
      .zipWithIndex
      .map { case (message, rowIndex) => toSensor(message, rowIndex) }
      .sortBy(_.index)

  /** The device that recorded the file: the local head unit (lowest index), else the lowest-index device. */
  def primaryDevice(file: FitFile): Option[DeviceInfo] = {
    val ds = devices(file)
    ds.filter(_.sourceType.contains("local")).minByOption(_.index).orElse(ds.minByOption(_.index))
  }

  /** Fill gaps in `a` from `b` (later message), keeping later non-empty values. */
  private def coalesce(a: DeviceInfo, b: DeviceInfo): DeviceInfo =
    DeviceInfo(
      index = a.index,
      manufacturer = if (b.manufacturer == UnknownManufacturer) a.manufacturer else b.manufacturer,
      productName = b.productName.orElse(a.productName),
      product = b.product.orElse(a.product),
      kind = b.kind.orElse(a.kind),
      softwareVersion = b.softwareVersion.orElse(a.softwareVersion),
      serialNumber = b.serialNumber.orElse(a.serialNumber),
      batteryStatus = b.batteryStatus.orElse(a.batteryStatus),
      sourceType = b.sourceType.orElse(a.sourceType),
    )

  private def addDevice(devices: Vector[DeviceInfo], next: DeviceInfo): Vector[DeviceInfo] = {
    val index = devices.indexWhere(existing => samePhysicalDevice(existing, next))
    if (index < 0) devices :+ next
    else devices.updated(index, coalesce(devices(index), next))
  }

  private def samePhysicalDevice(a: DeviceInfo, b: DeviceInfo): Boolean =
    (a.serialNumber, b.serialNumber) match {
      case (Some(left), Some(right)) => left == right
      case _                         => a.index == b.index && !identityConflict(a, b)
    }

  private def identityConflict(a: DeviceInfo, b: DeviceInfo): Boolean =
    conflicts(knownManufacturer(a), knownManufacturer(b)) ||
      conflicts(a.product, b.product) ||
      conflicts(a.productName.map(normalizeLabel), b.productName.map(normalizeLabel))

  private def conflicts[A](a: Option[A], b: Option[A]): Boolean =
    a.zip(b).exists { case (left, right) => left != right }

  private def toDevice(m: FitMessage): Option[DeviceInfo] =
    m.numeric(Dev.DeviceIndex).map { idx =>
      val source = m.numeric(Dev.SourceType).map(_.toInt)
      val rawManufacturer =
        m.numeric(Dev.Manufacturer).map(v => FitMetadata.manufacturerName(v.toInt)).getOrElse(UnknownManufacturer)
      val product     = m.numeric(Dev.Product).map(_.toInt)
      val productName = m.text(Dev.ProductName)
      val manufacturer =
        if (isUnknownManufacturer(rawManufacturer))
          inferManufacturer(productName, product).getOrElse(rawManufacturer)
        else rawManufacturer
      DeviceInfo(
        index = idx.toInt,
        manufacturer = manufacturer,
        productName = productName.orElse(GarminProductResolver.nameOf(manufacturer, product)),
        product = product,
        kind = m.numeric(Dev.DeviceType).flatMap { dt =>
          if (source.contains(1)) AntplusDeviceTypeEnum.nameOf(dt.toInt) else None
        },
        softwareVersion = m.numeric(Dev.SoftwareVersion),
        serialNumber = m.numeric(Dev.SerialNumber).map(_.toLong),
        batteryStatus = m.numeric(Dev.BatteryStatus).flatMap(v => BatteryStatusEnum.nameOf(v.toInt)),
        sourceType = m.numeric(Dev.SourceType).flatMap(v => SourceTypeEnum.nameOf(v.toInt)),
      )
    }

  private def toSensor(m: FitMessage, rowIndex: Int): SensorInfo = {
    val rawManufacturer = m.numeric(33).map(v => FitMetadata.manufacturerName(v.toInt)).getOrElse(UnknownManufacturer)
    val product         = m.numeric(32).map(_.toInt)
    val name            = m.text(2)
    val manufacturer =
      if (isUnknownManufacturer(rawManufacturer)) inferManufacturer(name, product).getOrElse(rawManufacturer)
      else rawManufacturer
    SensorInfo(
      index = m.numeric(254).map(_.toInt).getOrElse(rowIndex),
      manufacturer = manufacturer,
      productName = product.flatMap(p =>
        GarminProductResolver.nameOf(manufacturer, Some(p)).orElse(FitMetadata.sensorProductName(m, p))
      ),
      product = product,
      kind = m.numeric(52).map(v => FitMetadata.sensorDeviceTypeName(m, v.toInt)),
      name = name,
      antId = FitMetadata.sensorAntId(m),
      sourceType = m.numeric(73).flatMap(v => FitMetadata.sourceTypeName(v.toInt)),
      softwareVersion = FitMetadata.sensorSoftwareVersion(m),
      wheelSizeManualMm = m.numeric(10),
      wheelSizeAutoMm = m.numeric(21),
      calibrationFactor = m.numeric(14),
    )
  }

  private def knownManufacturer(device: DeviceInfo): Option[String] =
    if (!isUnknownManufacturer(device.manufacturer)) Some(normalizeLabel(device.manufacturer))
    else inferManufacturer(device.productName, device.product).map(normalizeLabel)

  private def inferManufacturer(name: Option[String], product: Option[Int]): Option[String] =
    name.flatMap(inferManufacturerFromName).orElse(product.flatMap(ProductManufacturerHints.get))

  private def inferManufacturerFromName(name: String): Option[String] = {
    val normalized = normalizeLabel(name)
    if (normalized.contains("sram")) Some("SRAM")
    else if (normalized.contains("shimano") || normalized.contains("di2")) Some("Shimano")
    else if (normalized.contains("polar")) Some("Polar")
    else if (normalized.contains("wahoo")) Some("Wahoo Fitness")
    else if (
      normalized.contains("garmin") ||
      normalized.contains("tacx") ||
      normalized.contains("rally") ||
      normalized.contains("varia") ||
      normalized.contains("edge") ||
      normalized.contains("hrm dual") ||
      normalized.contains("vector")
    ) Some("Garmin")
    else None
  }

  private def isUnknownManufacturer(value: String): Boolean =
    normalizeLabel(value) == UnknownManufacturer || normalizeLabel(value).startsWith("manufacturer #")

  private def normalizeLabel(value: String): String =
    value.trim.toLowerCase.replaceAll("[_-]+", " ").replaceAll("\\s+", " ")

  def ride(file: FitFile): RideSummary = {
    val session  = file.messages.find(_.globalNum == Mesg.Session)
    val distance = session.flatMap(_.numeric(Ses.TotalDistance))
    val elapsed  = session.flatMap(_.numeric(Ses.TotalElapsed))
    val moving   = session.flatMap(_.numeric(Ses.TotalTimer))

    val avg = session
      .flatMap(m => m.numeric(Ses.EnhancedAvgSpeed).orElse(m.numeric(Ses.AvgSpeed)))
      .orElse(for { d <- distance; mv <- moving if mv > 0 } yield d / mv)

    val max = session
      .flatMap(m => m.numeric(Ses.EnhancedMaxSpeed).orElse(m.numeric(Ses.MaxSpeed)))
      .orElse(file.records.flatMap(_.speedMps).maxOption)

    val sport = session.flatMap(_.numeric(Ses.Sport)).map(SportEnum.nameOf)

    val powers   = file.recordMessages.flatMap(_.numeric(Rec.Power))
    val avgPower = session.flatMap(_.numeric(Ses.AvgPower)).orElse(mean(powers))
    val maxPower = session.flatMap(_.numeric(Ses.MaxPower)).orElse(powers.maxOption)

    val temps   = file.recordMessages.flatMap(_.numeric(Rec.Temperature))
    val avgTemp = mean(temps)
    val maxTemp = temps.maxOption

    RideSummary(sport, distance, elapsed, moving, avg, max, avgPower, maxPower, avgTemp, maxTemp)
  }

  private def mean(xs: Vector[Double]): Option[Double] = if (xs.nonEmpty) Some(xs.sum / xs.size) else None
}
