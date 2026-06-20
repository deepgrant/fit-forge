package fitforge.http

import java.nio.file.Files
import java.nio.file.Paths

import fitforge.fit.FitCodec
import fitforge.fit.FitFile
import fitforge.fit.FitProfile

/** Shared test data built from the committed sample ride. */
object TestFixtures {

  /** The committed worry-free sample activity. */
  def sampleBytes: Array[Byte] = Files.readAllBytes(Paths.get("samples/19724302447_ACTIVITY.fit"))

  /**
   * Split the sample into two `.fit` byte arrays with a recording gap between them (drop a middle slice), so merge
   * tests have two non-overlapping segments — same approach as `FitMergeDemo`.
   */
  def twoSegments(codec: FitCodec): (Array[Byte], Array[Byte]) = {
    val original = codec.decode(sampleBytes)
    val recs     = original.recordMessages
    val n        = recs.size
    val firstEnd = (n * 0.45).toInt
    val sndStart = (n * 0.55).toInt
    val fileIds  = original.messages.filter(_.globalNum == FitProfile.Mesg.FileId)
    val segA     = FitFile(fileIds ++ recs.take(firstEnd))
    val segB     = FitFile(fileIds ++ recs.drop(sndStart))
    (codec.encode(segA), codec.encode(segB))
  }
}
