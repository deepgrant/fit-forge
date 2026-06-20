package ffmforge.fit

/** A format/layout summary of a FIT file: how many of each message type, and totals. */
final case class FitLayout(counts: Vector[(String, Int)], totalMessages: Int, totalFields: Int)

object FitLayout {

  /** Summarise a file by message type, ordered by global message number. */
  def of(file: FitFile): FitLayout = {
    val counts = file.messages
      .groupBy(_.globalNum)
      .toVector
      .sortBy { case (num, _) => num }
      .map { case (num, msgs) => (FitProfile.Mesg.nameOf(num), msgs.size) }
    FitLayout(counts, file.messages.size, file.messages.map(_.fields.size).sum)
  }
}
