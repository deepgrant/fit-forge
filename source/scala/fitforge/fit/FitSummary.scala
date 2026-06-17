package fitforge.fit

import fitforge.fit.FitProfile._

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

  /**
   * Distinct devices used in the recording, keyed by device index. FIT emits multiple (often partial) `device_info`
   * messages per device; we coalesce them, preferring the most complete/most recent value of each field.
   */
  def devices(file: FitFile): Vector[DeviceInfo] =
    file.messages
      .filter(_.globalNum == Mesg.DeviceInfo)
      .flatMap(toDevice)
      .groupBy(_.index)
      .values
      .map(_.reduceLeft(coalesce))
      .toVector
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
      manufacturer = if (b.manufacturer == "unknown") a.manufacturer else b.manufacturer,
      productName = b.productName.orElse(a.productName),
      product = b.product.orElse(a.product),
      kind = b.kind.orElse(a.kind),
      softwareVersion = b.softwareVersion.orElse(a.softwareVersion),
      serialNumber = b.serialNumber.orElse(a.serialNumber),
      batteryStatus = b.batteryStatus.orElse(a.batteryStatus),
      sourceType = b.sourceType.orElse(a.sourceType),
    )

  private def toDevice(m: FitMessage): Option[DeviceInfo] =
    m.numeric(Dev.DeviceIndex).map { idx =>
      val source = m.numeric(Dev.SourceType).map(_.toInt)
      DeviceInfo(
        index = idx.toInt,
        manufacturer = m.numeric(Dev.Manufacturer).map(v => ManufacturerEnum.nameOf(v.toInt)).getOrElse("unknown"),
        productName = m.text(Dev.ProductName),
        product = m.numeric(Dev.Product).map(_.toInt),
        kind = m.numeric(Dev.DeviceType).flatMap { dt =>
          if (source.contains(1)) AntplusDeviceTypeEnum.nameOf(dt.toInt) else None
        },
        softwareVersion = m.numeric(Dev.SoftwareVersion),
        serialNumber = m.numeric(Dev.SerialNumber).map(_.toLong),
        batteryStatus = m.numeric(Dev.BatteryStatus).flatMap(v => BatteryStatusEnum.nameOf(v.toInt)),
        sourceType = m.numeric(Dev.SourceType).flatMap(v => SourceTypeEnum.nameOf(v.toInt)),
      )
    }

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
