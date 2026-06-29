package ffmforge.fit

import java.time.Instant

import ffmforge.fit.FitProfile._

final case class EditorMessageGroup(name: String, count: Int, status: String, issues: Int)
final case class EditorCell(field: String, value: String, numeric: Option[Double])
final case class EditorRecordRow(
  index: Int,
  messageIndex: Int,
  messageType: String,
  timestamp: Option[Instant],
  position: Option[String],
  heartRate: Option[Int],
  power: Option[Int],
  speedMps: Option[Double],
  cadence: Option[Int],
  altitudeM: Option[Double],
  temperatureC: Option[Double],
  fields: Vector[EditorCell],
  issueIds: Vector[String],
)
final case class DiagnosticIssue(
  id: String,
  kind: String,
  severity: String,
  title: String,
  detail: String,
  messageType: String,
  startIndex: Int,
  endIndex: Int,
  field: Option[String],
  suggestedOperations: Vector[RepairOperation],
)
final case class RepairOperation(
  kind: String,
  messageType: String,
  startIndex: Int,
  endIndex: Int,
  field: Option[String],
  value: Option[Double],
)
final case class RepairChange(rowIndex: Int, field: String, before: String, after: String, method: String)
final case class EditorVerification(status: String, canExport: Boolean, checks: Vector[String])
final case class RepairPreview(
  operations: Vector[RepairOperation],
  changes: Vector[RepairChange],
  verification: EditorVerification,
)
final case class EditorRowsResponse(
  messageType: String,
  offset: Int,
  limit: Int,
  total: Int,
  rows: Vector[EditorRecordRow],
)
final case class EditorOpenResponse(
  id: String,
  summary: RideSummary,
  devices: Vector[DeviceInfo],
  layout: FitLayout,
  anatomy: Vector[EditorMessageGroup],
  diagnostics: Vector[DiagnosticIssue],
  rows: EditorRowsResponse,
  verification: EditorVerification,
)
final case class ExportRepairResponse(id: String, preview: RepairPreview)

object FitEditor {

  private val DefaultLimit              = 80
  private val MaxInterpolatedGpsSamples = 5

  def open(id: String, file: FitFile): EditorOpenResponse = {
    val diagnostics = diagnose(file)
    EditorOpenResponse(
      id = id,
      summary = FitSummary.ride(file),
      devices = FitSummary.devices(file),
      layout = FitLayout.of(file),
      anatomy = anatomy(file, diagnostics),
      diagnostics = diagnostics,
      rows = rows(file, "record", 0, DefaultLimit, diagnostics),
      verification = verification(file, diagnostics),
    )
  }

  def rows(
    file: FitFile,
    messageType: String,
    offset: Int,
    limit: Int,
    diagnostics: Vector[DiagnosticIssue] = Vector.empty,
  ): EditorRowsResponse = {
    val requestedType = normalizeMessageType(messageType)
    val selected = file.messages.zipWithIndex.filter { case (message, _) =>
      Mesg.nameOf(message.globalNum) == requestedType
    }.toVector
    val safeOffset = Math.max(0, offset)
    val safeLimit  = Math.max(1, Math.min(limit, 250))
    val issueMap   = diagnosticsByRow(diagnostics)
    val page = selected
      .slice(safeOffset, safeOffset + safeLimit)
      .zipWithIndex
      .map { case ((message, messageIndex), pageIndex) =>
        rowOf(
          message,
          messageIndex,
          requestedType,
          safeOffset + pageIndex,
          issueMap.getOrElse(safeOffset + pageIndex, Vector.empty),
        )
      }
    EditorRowsResponse(requestedType, safeOffset, safeLimit, selected.size, page)
  }

  def preview(file: FitFile, operations: Vector[RepairOperation]): RepairPreview = {
    val changes  = operations.flatMap(op => changesFor(file, op))
    val repaired = applyOperations(file, operations)
    RepairPreview(operations, changes, verification(repaired, diagnose(repaired)))
  }

  def repair(file: FitFile, operations: Vector[RepairOperation]): (FitFile, RepairPreview) = {
    val repaired = applyOperations(file, operations)
    (
      repaired,
      RepairPreview(
        operations,
        operations.flatMap(op => changesFor(file, op)),
        verification(repaired, diagnose(repaired)),
      ),
    )
  }

  def diagnose(file: FitFile): Vector[DiagnosticIssue] = {
    val recordMessages = file.recordMessages
    val typedRecords = recordMessages.zipWithIndex.flatMap { case (message, index) =>
      FitViews.record(message).map(record => index -> record)
    }

    val missingTimestamp = recordMessages.zipWithIndex.collect {
      case (message, index) if message.instant(Rec.Timestamp).isEmpty =>
        issue(
          s"missing-ts-$index",
          "timestamp",
          "error",
          "Missing timestamp",
          s"Record $index has no timestamp and cannot be safely ordered.",
          index,
          index,
          Some("timestamp"),
          Vector(RepairOperation("deleteRecord", "record", index, index, None, None)),
        )
    }

    val nonMonotonic = typedRecords
      .sliding(2)
      .collect {
        case Seq((_, a), (index, b)) if !b.timestamp.isAfter(a.timestamp) =>
          issue(
            s"timestamp-order-$index",
            "timestamp",
            "error",
            "Timestamp is not increasing",
            s"Record $index is not after the previous record.",
            index,
            index,
            Some("timestamp"),
            Vector(RepairOperation("deleteRecord", "record", index, index, None, None)),
          )
      }
      .toVector

    val hasPowerMeter = FitSummary.devices(file).exists(_.kind.contains("bike_power"))

    missingTimestamp ++ nonMonotonic ++
      numericDropouts(typedRecords, "heartRate", _.heartRate.map(_.toDouble), zeroIsDropout = true) ++
      numericSpikes(typedRecords, "heartRate", _.heartRate.map(_.toDouble), absoluteMax = 240.0, deltaMax = 45.0) ++
      (if (hasPowerMeter) numericDropouts(typedRecords, "power", _.power.map(_.toDouble), zeroIsDropout = false)
       else Vector.empty) ++
      numericSpikes(typedRecords, "power", _.power.map(_.toDouble), absoluteMax = 1200.0, deltaMax = 650.0) ++
      numericSpikes(typedRecords, "speedMps", _.speedMps, absoluteMax = 28.0, deltaMax = 12.0) ++
      gpsIssues(typedRecords)
  }

  private def anatomy(file: FitFile, diagnostics: Vector[DiagnosticIssue]): Vector[EditorMessageGroup] = {
    val issueCounts = diagnostics.groupBy(_.messageType).view.mapValues(_.size).toMap
    FitLayout.of(file).counts.map { case (name, count) =>
      val issues = issueCounts.getOrElse(name, 0)
      EditorMessageGroup(name, count, if (issues == 0) "ok" else "warning", issues)
    }
  }

  private def rowOf(
    message: FitMessage,
    messageIndex: Int,
    messageType: String,
    rowIndex: Int,
    issueIds: Vector[String],
  ): EditorRecordRow = {
    val record = if (messageType == "record") FitViews.record(message) else None
    val pos    = record.flatMap(_.position).map(p => f"${p.lat}%.5f,${p.lon}%.5f")
    EditorRecordRow(
      index = rowIndex,
      messageIndex = messageIndex,
      messageType = messageType,
      timestamp = message.instant(Rec.Timestamp).orElse(record.map(_.timestamp)),
      position = pos,
      heartRate = record.flatMap(_.heartRate),
      power = record.flatMap(_.power),
      speedMps = record.flatMap(_.speedMps),
      cadence = record.flatMap(_.cadence),
      altitudeM = record.flatMap(_.altitudeM),
      temperatureC = if (messageType == "record") message.numeric(Rec.Temperature) else None,
      fields = message.fields.sortBy(_.num).filterNot(_.num == Rec.Timestamp).map(fieldCell(messageType, _)),
      issueIds = issueIds,
    )
  }

  private def fieldCell(messageType: String, field: RawField): EditorCell =
    EditorCell(
      fieldName(messageType, field.num),
      field.values.map(value => formatFieldValue(messageType, field.num, value)).mkString(", "),
      field.firstNumeric,
    )

  private def fieldName(messageType: String, num: Int): String =
    messageType match {
      case "session" => sessionFieldName(num)
      case "lap"     => lapFieldName(num)
      case "event"   => eventFieldName(num)
      case "activity" =>
        num match {
          case Act.TotalTimer  => "Total timer"
          case Act.NumSessions => "Sessions"
          case Act.Type        => "Type"
          case Act.Event       => "Event"
          case Act.EventType   => "Event type"
          case 5               => "Local timestamp"
          case 6               => "Event group"
          case _               => s"field $num"
        }
      case "file_creator" =>
        num match {
          case 0 => "Software"
          case 1 => "Hardware"
          case _ => s"field $num"
        }
      case "sensor" =>
        num match {
          case 0   => "Serial"
          case 1   => "State"
          case 2   => "Name"
          case 3   => "Enabled"
          case 10  => "Wheel size"
          case 14  => "Subtype"
          case 17  => "Gear count"
          case 18  => "Gear teeth"
          case 19  => "Rear gears"
          case 20  => "Rear gear teeth"
          case 21  => "Wheel size"
          case 28  => "Front gear table"
          case 29  => "Rear gear table"
          case 30  => "Gear table"
          case 32  => "Product"
          case 33  => "Manufacturer"
          case 34  => "Software"
          case 36  => "Mount side"
          case 37  => "Sensor position"
          case 38  => "Sensor direction"
          case 39  => "Sensor status"
          case 40  => "Light mode"
          case 47  => "Paired"
          case 50  => "ANT id"
          case 51  => "Network"
          case 52  => "Device type"
          case 53  => "Transmission type"
          case 54  => "Protocol"
          case 60  => "Shift mode"
          case 61  => "Shift count"
          case 62  => "HRM mode"
          case 73  => "Source"
          case 82  => "Radar mode"
          case 83  => "Light network"
          case 84  => "Light beam"
          case 87  => "Radar"
          case 254 => "Index"
          case _   => s"field $num"
        }
      case "battery" =>
        num match {
          case 0 => "Voltage"
          case 2 => "Temperature"
          case 3 => "Level"
          case 4 => "Current"
          case _ => s"field $num"
        }
      case "device_info" =>
        num match {
          case Dev.DeviceIndex     => "Device index"
          case Dev.DeviceType      => "Device type"
          case Dev.Manufacturer    => "Manufacturer"
          case Dev.SerialNumber    => "Serial"
          case Dev.Product         => "Product"
          case Dev.SoftwareVersion => "Software"
          case Dev.BatteryStatus   => "Battery status"
          case Dev.SourceType      => "Source"
          case Dev.ProductName     => "Product name"
          case _                   => s"field $num"
        }
      case "file_id" =>
        num match {
          case Fid.Type         => "Type"
          case Fid.Manufacturer => "Manufacturer"
          case Fid.Product      => "Product"
          case Fid.SerialNumber => "Serial"
          case Fid.TimeCreated  => "Created"
          case _                => s"field $num"
        }
      case _ => s"field $num"
    }

  private def formatFieldValue(messageType: String, num: Int, value: FitValue): String =
    (messageType, num, value) match {
      case (_, _, FitValue.Num(v)) if isDateTimeField(messageType, num)       => formatInstant(v)
      case (_, _, FitValue.Num(v)) if isLatitudeField(messageType, num)       => formatLatLon(v)
      case (_, _, FitValue.Num(v)) if isLongitudeField(messageType, num)      => formatLatLon(v)
      case (_, _, FitValue.Num(v)) if isElapsedSecondsField(messageType, num) => formatSeconds(v)
      case (_, _, FitValue.Num(v)) if isDistanceField(messageType, num)       => f"$v%.2f m"
      case (_, _, FitValue.Num(v)) if isSpeedField(messageType, num)          => f"$v%.2f m/s"
      case (_, _, FitValue.Num(v)) if isHeartRateField(messageType, num)      => f"$v%.0f bpm"
      case (_, _, FitValue.Num(v)) if isCadenceField(messageType, num)        => f"$v%.0f rpm"
      case (_, _, FitValue.Num(v)) if isPowerField(messageType, num)          => f"$v%.0f W"
      case (_, _, FitValue.Num(v)) if isAltitudeField(messageType, num)       => f"$v%.1f m"
      case (_, _, FitValue.Num(v)) if isTemperatureField(messageType, num)    => f"$v%.0f C"
      case ("battery", 0, FitValue.Num(v))                                    => f"${v / 1000.0}%.3f V"
      case ("battery", 2, FitValue.Num(v))                                    => f"$v%.0f C"
      case ("battery", 3, FitValue.Num(v))                                    => f"$v%.0f%%"
      case ("battery", 4, FitValue.Num(v))                                    => f"${v / 1000.0}%.1f mA"
      case ("device_info", Dev.BatteryStatus, FitValue.Num(v)) =>
        BatteryStatusEnum.nameOf(v.toInt).getOrElse(formatValue(value))
      case ("device_info", Dev.SourceType, FitValue.Num(v)) =>
        SourceTypeEnum.nameOf(v.toInt).getOrElse(formatValue(value))
      case ("device_info", Dev.Manufacturer, FitValue.Num(v)) => ManufacturerEnum.nameOf(v.toInt)
      case ("file_id", Fid.TimeCreated, FitValue.Num(v))      => formatInstant(v)
      case ("sensor", 34, FitValue.Num(v))                    => f"${v / 100.0}%.2f"
      case _                                                  => formatValue(value)
    }

  private def sessionFieldName(num: Int): String =
    num match {
      case 254                  => "Index"
      case Ses.Event            => "Event"
      case Ses.EventType        => "Event type"
      case Ses.StartTime        => "Start time"
      case 3                    => "Start latitude"
      case 4                    => "Start longitude"
      case Ses.Sport            => "Sport"
      case Ses.SubSport         => "Sub sport"
      case Ses.TotalElapsed     => "Elapsed"
      case Ses.TotalTimer       => "Timer"
      case Ses.TotalDistance    => "Distance"
      case 10                   => "Cycles"
      case 11                   => "Calories"
      case 13                   => "Fat calories"
      case Ses.AvgSpeed         => "Avg speed"
      case Ses.MaxSpeed         => "Max speed"
      case 16                   => "Avg heart rate"
      case 17                   => "Max heart rate"
      case 18                   => "Avg cadence"
      case 19                   => "Max cadence"
      case Ses.AvgPower         => "Avg power"
      case Ses.MaxPower         => "Max power"
      case 22                   => "Ascent"
      case 23                   => "Descent"
      case 24                   => "Training effect"
      case 25                   => "First lap"
      case 26                   => "Laps"
      case 27                   => "Event group"
      case 28                   => "Trigger"
      case 29                   => "NE latitude"
      case 30                   => "NE longitude"
      case 31                   => "SW latitude"
      case 32                   => "SW longitude"
      case 34                   => "Normalized power"
      case 35                   => "TSS"
      case 36                   => "Intensity factor"
      case 37                   => "Left/right balance"
      case 38                   => "End latitude"
      case 39                   => "End longitude"
      case 45                   => "Threshold power"
      case 49                   => "Avg altitude"
      case 50                   => "Max altitude"
      case 51                   => "GPS accuracy"
      case 57                   => "Avg temperature"
      case 58                   => "Max temperature"
      case 59                   => "Moving time"
      case 64                   => "Min heart rate"
      case Ses.EnhancedAvgSpeed => "Enhanced avg speed"
      case Ses.EnhancedMaxSpeed => "Enhanced max speed"
      case 126                  => "Enhanced avg altitude"
      case 127                  => "Enhanced min altitude"
      case 128                  => "Enhanced max altitude"
      case _                    => s"field $num"
    }

  private def lapFieldName(num: Int): String =
    num match {
      case 254              => "Index"
      case Lp.Event         => "Event"
      case Lp.EventType     => "Event type"
      case Lp.StartTime     => "Start time"
      case 3                => "Start latitude"
      case 4                => "Start longitude"
      case 5                => "End latitude"
      case 6                => "End longitude"
      case Lp.TotalElapsed  => "Elapsed"
      case Lp.TotalTimer    => "Timer"
      case Lp.TotalDistance => "Distance"
      case 10               => "Cycles"
      case 11               => "Calories"
      case 12               => "Fat calories"
      case 13               => "Avg speed"
      case 14               => "Max speed"
      case 15               => "Avg heart rate"
      case 16               => "Max heart rate"
      case 17               => "Avg cadence"
      case 18               => "Max cadence"
      case 19               => "Avg power"
      case 20               => "Max power"
      case 21               => "Ascent"
      case 22               => "Descent"
      case 23               => "Intensity"
      case 24               => "Trigger"
      case 25               => "Sport"
      case 26               => "Event group"
      case 33               => "Normalized power"
      case 34               => "Left/right balance"
      case 42               => "Avg altitude"
      case 43               => "Max altitude"
      case 44               => "GPS accuracy"
      case 50               => "Avg temperature"
      case 51               => "Max temperature"
      case 52               => "Moving time"
      case 57               => "Time in HR zone"
      case 58               => "Time in speed zone"
      case 59               => "Time in cadence zone"
      case 60               => "Time in power zone"
      case 62               => "Min altitude"
      case 63               => "Min heart rate"
      case 70               => "Active time"
      case 110              => "Enhanced avg speed"
      case 111              => "Enhanced max speed"
      case 112              => "Enhanced avg altitude"
      case 113              => "Enhanced min altitude"
      case 114              => "Enhanced max altitude"
      case _                => s"field $num"
    }

  private def eventFieldName(num: Int): String =
    num match {
      case Ev.Event     => "Event"
      case Ev.EventType => "Event type"
      case 2            => "Data 16"
      case 3            => "Data"
      case 4            => "Event group"
      case 7            => "Score"
      case 8            => "Opponent score"
      case 9            => "Front gear num"
      case 10           => "Front gear"
      case 11           => "Rear gear num"
      case 12           => "Rear gear"
      case 13           => "Device index"
      case 14           => "Activity type"
      case 15           => "Start timestamp"
      case 21           => "Max radar threat"
      case 22           => "Radar threat count"
      case 23           => "Avg radar approach"
      case 24           => "Max radar approach"
      case _            => s"field $num"
    }

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

  private def formatInstant(value: Double): String =
    fitSecondsToInstant(value.toLong).toString

  private def formatLatLon(value: Double): String =
    f"${semicirclesToDeg(value.toLong)}%.6f"

  private def formatSeconds(value: Double): String =
    if (value.isWhole) s"${value.toLong} s" else f"$value%.2f s"

  private def formatValue(value: FitValue): String = value match {
    case FitValue.Num(v)  => if (v.isWhole) v.toLong.toString else f"$v%.3f"
    case FitValue.Text(v) => v
  }

  private def diagnosticsByRow(diagnostics: Vector[DiagnosticIssue]): Map[Int, Vector[String]] =
    diagnostics
      .filter(_.messageType == "record")
      .flatMap(issue => (issue.startIndex to issue.endIndex).map(_ -> issue.id))
      .groupMap(_._1)(_._2)

  private def numericDropouts(
    rows: Vector[(Int, Record)],
    field: String,
    valueOf: Record => Option[Double],
    zeroIsDropout: Boolean,
  ): Vector[DiagnosticIssue] =
    contiguousRanges(
      rows.collect {
        case (index, record) if valueOf(record).isEmpty || (zeroIsDropout && valueOf(record).contains(0.0)) =>
          index
      }
    ).map { case (start, end) =>
      val detail =
        if (zeroIsDropout) {
          s"Records $start-$end have missing or zero ${fieldLabel(field).toLowerCase} values."
        } else {
          s"Records $start-$end have missing ${fieldLabel(field).toLowerCase} values."
        }
      issue(
        s"$field-dropout-$start-$end",
        "dropout",
        "warning",
        s"${fieldLabel(field)} dropout",
        detail,
        start,
        end,
        Some(field),
        Vector(RepairOperation("interpolateNumeric", "record", start, end, Some(field), None)),
      )
    }

  private def contiguousRanges(indices: Vector[Int]): Vector[(Int, Int)] =
    indices.sorted.foldLeft(Vector.empty[(Int, Int)]) {
      case (Vector(), index) => Vector(index -> index)
      case (ranges, index) if index == ranges.last._2 + 1 =>
        ranges.init :+ (ranges.last._1 -> index)
      case (ranges, index) => ranges :+ (index -> index)
    }

  private def numericSpikes(
    rows: Vector[(Int, Record)],
    field: String,
    valueOf: Record => Option[Double],
    absoluteMax: Double,
    deltaMax: Double,
  ): Vector[DiagnosticIssue] =
    rows
      .sliding(3)
      .collect {
        case Seq((_, a), (index, b), (_, c))
            if valueOf(b).exists(v =>
              v > absoluteMax ||
                (valueOf(a).exists(av => Math.abs(v - av) > deltaMax) &&
                  valueOf(c).exists(cv => Math.abs(v - cv) > deltaMax))
            ) =>
          issue(
            s"$field-spike-$index",
            "spike",
            "warning",
            s"${fieldLabel(field)} spike",
            s"Record $index has a suspicious ${fieldLabel(field).toLowerCase} jump.",
            index,
            index,
            Some(field),
            Vector(RepairOperation("interpolateNumeric", "record", index, index, Some(field), None)),
          )
      }
      .toVector

  private def gpsIssues(rows: Vector[(Int, Record)]): Vector[DiagnosticIssue] =
    contiguousRanges(rows.collect { case (index, record) if record.position.isEmpty => index }).map {
      case (start, end) =>
        val count = end - start + 1
        val canInterpolate = count <= MaxInterpolatedGpsSamples &&
          rows.exists { case (index, record) => index < start && record.position.isDefined } &&
          rows.exists { case (index, record) => index > end && record.position.isDefined }
        val title = if (count == 1) "GPS sample missing" else "GPS samples missing"
        val detail =
          if (canInterpolate) {
            s"Records $start-$end are missing GPS position data and are bracketed by valid GPS samples."
          } else {
            s"Records $start-$end are missing GPS position data; interpolation is not suggested for this range."
          }
        issue(
          s"gps-missing-$start-$end",
          "gps",
          "warning",
          title,
          detail,
          start,
          end,
          Some("position"),
          if (canInterpolate)
            Vector(RepairOperation("interpolatePosition", "record", start, end, Some("position"), None))
          else Vector.empty,
        )
    }

  private def issue(
    id: String,
    kind: String,
    severity: String,
    title: String,
    detail: String,
    start: Int,
    end: Int,
    field: Option[String],
    operations: Vector[RepairOperation],
  ): DiagnosticIssue =
    DiagnosticIssue(id, kind, severity, title, detail, "record", start, end, field, operations)

  private def changesFor(file: FitFile, operation: RepairOperation): Vector[RepairChange] =
    selectedRecordMessages(file, operation).flatMap { case (message, rowIndex) =>
      operation.kind match {
        case "deleteRecord" =>
          Vector(RepairChange(rowIndex, "record", "present", "deleted", "delete"))
        case "interpolateNumeric" =>
          operation.field
            .flatMap(fieldNum)
            .flatMap { num =>
              interpolatedValue(file, rowIndex, num).map { next =>
                RepairChange(
                  rowIndex,
                  operation.field.get,
                  valueText(message.numeric(num)),
                  valueText(Some(next)),
                  "interpolate",
                )
              }
            }
            .toVector
        case "interpolatePosition" =>
          interpolatedPosition(file, rowIndex)
            .map(next =>
              RepairChange(
                rowIndex,
                "position",
                formatPosition(FitViews.record(message).flatMap(_.position)),
                formatPosition(Some(next)),
                "interpolate",
              )
            )
            .toVector
        case "replaceNumeric" =>
          for {
            field <- operation.field.toVector
            num   <- fieldNum(field).toVector
            value <- operation.value.toVector
          } yield RepairChange(rowIndex, field, valueText(message.numeric(num)), valueText(Some(value)), "replace")
        case "markPause" =>
          Vector(RepairChange(rowIndex, "timer", "active", "pause event inserted", "mark pause"))
        case "recalculateSummary" =>
          Vector(RepairChange(rowIndex, "summary", "current", "recalculated", "recalculate"))
        case _ => Vector.empty
      }
    }

  private def applyOperations(file: FitFile, operations: Vector[RepairOperation]): FitFile =
    operations.foldLeft(file)(applyOperation)

  private def applyOperation(file: FitFile, operation: RepairOperation): FitFile = operation.kind match {
    case "deleteRecord" =>
      removeRecordRange(file, operation.startIndex, operation.endIndex)
    case "interpolateNumeric" =>
      operation.field.flatMap(fieldNum) match {
        case Some(num) =>
          editRecordRange(
            file,
            operation,
            (message, row) => interpolatedValue(file, row, num).fold(message)(message.setNumeric(num, _)),
          )
        case None => file
      }
    case "interpolatePosition" =>
      editRecordRange(
        file,
        operation,
        (message, row) =>
          interpolatedPosition(file, row).fold(message)(p =>
            message
              .setNumeric(Rec.PositionLat, degToSemicircles(p.lat).toDouble)
              .setNumeric(Rec.PositionLong, degToSemicircles(p.lon).toDouble)
          ),
      )
    case "replaceNumeric" =>
      (operation.field.flatMap(fieldNum), operation.value) match {
        case (Some(num), Some(value)) =>
          editRecordRange(file, operation, (message, _) => message.setNumeric(num, value))
        case _ => file
      }
    case "markPause" =>
      markPause(file, operation.startIndex)
    case "recalculateSummary" =>
      recalculateSummary(file)
    case _ => file
  }

  private def selectedRecordMessages(file: FitFile, operation: RepairOperation): Vector[(FitMessage, Int)] =
    file.recordMessages.zipWithIndex.collect {
      case (message, index)
          if operation.messageType == "record" && index >= operation.startIndex && index <= operation.endIndex =>
        message -> index
    }

  private def editRecordRange(
    file: FitFile,
    operation: RepairOperation,
    f: (FitMessage, Int) => FitMessage,
  ): FitFile = {
    val (messages, _) = file.messages.foldLeft((Vector.empty[FitMessage], -1)) { case ((out, recordIndex), message) =>
      if (message.globalNum == Mesg.Record) {
        val nextIndex = recordIndex + 1
        val nextMessage =
          if (nextIndex >= operation.startIndex && nextIndex <= operation.endIndex) f(message, nextIndex) else message
        (out :+ nextMessage, nextIndex)
      } else (out :+ message, recordIndex)
    }
    file.copy(messages = messages)
  }

  private def removeRecordRange(file: FitFile, start: Int, end: Int): FitFile = {
    val (messages, _) = file.messages.foldLeft((Vector.empty[FitMessage], -1)) { case ((out, recordIndex), message) =>
      if (message.globalNum == Mesg.Record) {
        val nextIndex = recordIndex + 1
        val nextOut   = if (nextIndex >= start && nextIndex <= end) out else out :+ message
        (nextOut, nextIndex)
      } else (out :+ message, recordIndex)
    }
    file.copy(messages = messages)
  }

  private def markPause(file: FitFile, recordIndex: Int): FitFile =
    file.recordMessages.lift(recordIndex).flatMap(_.instant(Rec.Timestamp)) match {
      case Some(ts) =>
        val stop  = FitViews.toMessage(Event(ts, TimerEvent.StopAll))
        val start = FitViews.toMessage(Event(ts.plusSeconds(1), TimerEvent.Start))
        file.copy(messages = file.messages ++ Vector(stop, start))
      case None => file
    }

  private def recalculateSummary(file: FitFile): FitFile = {
    val records = file.records
    if (records.isEmpty) file
    else {
      val start    = records.head.timestamp
      val end      = records.last.timestamp
      val elapsed  = end.getEpochSecond - start.getEpochSecond
      val distance = records.lastOption.flatMap(_.distanceM)
      val session = FitViews.toMessage(
        Session(
          start,
          end,
          totalElapsedTimeS = Some(elapsed.toDouble),
          totalTimerTimeS = Some(elapsed.toDouble),
          totalDistanceM = distance,
          sport = FitSummary.ride(file).sport,
        )
      )
      file.copy(messages = file.messages.filterNot(_.globalNum == Mesg.Session) :+ session)
    }
  }

  private def interpolatedValue(file: FitFile, row: Int, field: Int): Option[Double] = {
    val records = file.recordMessages
    val before  = records.take(row).reverseIterator.flatMap(_.numeric(field)).nextOption()
    val after   = records.drop(row + 1).iterator.flatMap(_.numeric(field)).nextOption()
    (before, after) match {
      case (Some(a), Some(b)) => Some((a + b) / 2.0)
      case (Some(a), None)    => Some(a)
      case (None, Some(b))    => Some(b)
      case _                  => None
    }
  }

  private def interpolatedPosition(file: FitFile, row: Int): Option[GpsPoint] = {
    val records = file.recordMessages.zipWithIndex.flatMap { case (message, index) =>
      FitViews.record(message).map(index -> _)
    }
    for {
      target              <- records.find(_._1 == row).map(_._2)
      (beforeRow, before) <- records.takeWhile(_._1 < row).reverse.find(_._2.position.isDefined)
      (afterRow, after)   <- records.dropWhile(_._1 <= row).find(_._2.position.isDefined)
      beforePos           <- before.position
      afterPos            <- after.position
      fraction = interpolationFraction(before, beforeRow, target, row, after, afterRow)
    } yield GpsPoint(
      beforePos.lat + (afterPos.lat - beforePos.lat) * fraction,
      beforePos.lon + (afterPos.lon - beforePos.lon) * fraction,
    )
  }

  private def interpolationFraction(
    before: Record,
    beforeRow: Int,
    target: Record,
    targetRow: Int,
    after: Record,
    afterRow: Int,
  ): Double = {
    val totalSeconds  = after.timestamp.getEpochSecond - before.timestamp.getEpochSecond
    val targetSeconds = target.timestamp.getEpochSecond - before.timestamp.getEpochSecond
    if (totalSeconds > 0) targetSeconds.toDouble / totalSeconds.toDouble
    else (targetRow - beforeRow).toDouble / Math.max(1, afterRow - beforeRow).toDouble
  }

  private def fieldNum(field: String): Option[Int] = field match {
    case "heartRate"    => Some(Rec.HeartRate)
    case "power"        => Some(Rec.Power)
    case "speedMps"     => Some(Rec.EnhancedSpeed)
    case "cadence"      => Some(Rec.Cadence)
    case "altitudeM"    => Some(Rec.EnhancedAltitude)
    case "temperatureC" => Some(Rec.Temperature)
    case _              => None
  }

  private def fieldLabel(field: String): String = field match {
    case "heartRate" => "Heart rate"
    case "speedMps"  => "Speed"
    case other       => other.split("(?=[A-Z])").mkString(" ").capitalize
  }

  private def valueText(value: Option[Double]): String =
    value.fold("-")(v => if (v.isWhole) v.toLong.toString else f"$v%.2f")

  private def formatPosition(position: Option[GpsPoint]): String =
    position.fold("-")(p => f"${p.lat}%.6f,${p.lon}%.6f")

  private def normalizeMessageType(messageType: String): String =
    messageType.trim.toLowerCase match {
      case ""        => "record"
      case "records" => "record"
      case other     => other
    }

  private def verification(file: FitFile, diagnostics: Vector[DiagnosticIssue]): EditorVerification = {
    val errors = diagnostics.count(_.severity == "error")
    val checks = Vector(
      s"${file.messages.size} messages readable",
      s"${file.recordMessages.size} record messages",
      if (errors == 0) "no blocking record errors" else s"$errors blocking record errors",
    )
    EditorVerification(if (errors == 0) "upload-safe" else "needs repair", errors == 0, checks)
  }
}
