package fitforge.fit

import java.time.Instant

/**
 * A single FIT field value. FIT fields are numeric or string (and may be arrays); this captures both losslessly enough
 * for round-tripping while staying free of any SDK types.
 */
enum FitValue {
  case Num(value: Double)
  case Text(value: String)
}

/** One field of a message: its profile number and its (possibly multiple) values. */
final case class RawField(num: Int, values: Vector[FitValue]) {
  def firstNumeric: Option[Double] = values.collectFirst { case FitValue.Num(v) => v }
  def firstText: Option[String]    = values.collectFirst { case FitValue.Text(v) => v }
}

/**
 * A generic FIT message — the lossless source of truth. Every message decoded from a file is kept here with ALL of its
 * fields, so nothing is dropped on round-trip. Typed views (see [[FitFile]]) read the common fields back out by number,
 * and the merge edits messages through the helpers below without ever discarding the fields it doesn't understand.
 *
 * `original` holds the opaque SDK message this was decoded from (as `AnyRef`, so this file stays SDK-free). The codec
 * writes it back VERBATIM unless the message was edited — that is what makes passthrough of manufacturer/developer
 * fields (whose types the FIT profile can't reconstruct) truly lossless. Any edit clears `original` so the codec
 * rebuilds the message from `fields` instead.
 */
final case class FitMessage(globalNum: Int, fields: Vector[RawField], original: Option[AnyRef] = None) {

  def field(num: Int): Option[RawField]  = fields.find(_.num == num)
  def numeric(num: Int): Option[Double]  = field(num).flatMap(_.firstNumeric)
  def text(num: Int): Option[String]     = field(num).flatMap(_.firstText)
  def instant(num: Int): Option[Instant] = numeric(num).map(s => FitProfile.fitSecondsToInstant(s.toLong))

  /** Replace (or add) a single-valued field, keeping every other field intact. Marks the message as edited. */
  def withField(f: RawField): FitMessage =
    copy(fields = fields.filterNot(_.num == f.num) :+ f, original = None)

  def setNumeric(num: Int, value: Double): FitMessage = withField(RawField(num, Vector(FitValue.Num(value))))
  def setText(num: Int, value: String): FitMessage    = withField(RawField(num, Vector(FitValue.Text(value))))
  def setInstant(num: Int, i: Instant): FitMessage    = setNumeric(num, FitProfile.instantToFitSeconds(i).toDouble)

  def setNumericOpt(num: Int, value: Option[Double]): FitMessage = value.fold(this)(v => setNumeric(num, v))
}

object FitMessage {
  def apply(globalNum: Int): FitMessage = FitMessage(globalNum, Vector.empty, None)
}
