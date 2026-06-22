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
      heartRate = message.numeric(Rec.HeartRate).map(_.toInt),
      power = message.numeric(Rec.Power).map(_.toInt),
      speedMps = message.numeric(Rec.EnhancedSpeed).orElse(message.numeric(Rec.Speed)),
      cadence = message.numeric(Rec.Cadence).map(_.toInt),
      altitudeM = message.numeric(Rec.EnhancedAltitude).orElse(message.numeric(Rec.Altitude)),
      temperatureC = message.numeric(Rec.Temperature),
      fields = message.fields.sortBy(_.num).map(fieldCell),
      issueIds = issueIds,
    )
  }

  private def fieldCell(field: RawField): EditorCell =
    EditorCell(s"field_${field.num}", field.values.map(formatValue).mkString(", "), field.firstNumeric)

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
