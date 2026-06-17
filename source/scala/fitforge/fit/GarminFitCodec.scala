package fitforge.fit

import java.nio.file.Files

import scala.jdk.CollectionConverters._

import com.garmin.fit.Decoder
import com.garmin.fit.Field
import com.garmin.fit.FileEncoder
import com.garmin.fit.Fit
import com.garmin.fit.Mesg
import com.garmin.fit.MesgDefinition
import com.garmin.fit.MesgDefinitionListener
import com.garmin.fit.MesgListener

/**
 * [[FitCodec]] implementation backed by the official Garmin FIT Java SDK.
 *
 * This is the ONLY file in fit-forge permitted to import `com.garmin.fit`. Decoding captures EVERY message and field
 * generically into [[FitMessage]]s (nothing is dropped), and encoding rebuilds them through `Factory.createMesg` so the
 * fields fit-forge never interprets still survive the round-trip.
 */
final class GarminFitCodec extends FitCodec {
  import GarminFitCodec._

  def decode(bytes: Array[Byte]): FitFile = {
    val decoder = new Decoder(bytes)
    val builder = Vector.newBuilder[FitMessage]
    val listener = new MesgListener {
      override def onMesg(mesg: Mesg): Unit = builder += fromMesg(mesg)
    }
    decoder.addListener(listener)
    decoder.read(): Unit
    FitFile(builder.result())
  }

  def encode(file: FitFile): Array[Byte] = {
    val tmp     = Files.createTempFile("fit-forge-", ".fit")
    val encoder = new FileEncoder(tmp.toFile, Fit.ProtocolVersion.V2_0)
    try file.messages.foreach(fm => encoder.write(mesgFor(fm)))
    finally encoder.close()

    val out = Files.readAllBytes(tmp)
    Files.deleteIfExists(tmp): Unit
    out
  }

  def stats(bytes: Array[Byte]): FitStats = {
    val decoder   = new Decoder(bytes)
    val devCounts = Vector.newBuilder[Int]
    val defCounts = Vector.newBuilder[Int]
    decoder.addListener(new MesgListener {
      override def onMesg(mesg: Mesg): Unit = devCounts += mesg.getDeveloperFields.asScala.size
    })
    decoder.addListener(new MesgDefinitionListener {
      override def onMesgDefinition(mesgDef: MesgDefinition): Unit = defCounts += 1
    })
    decoder.read(): Unit
    val devs = devCounts.result()
    FitStats(dataMessages = devs.size, definitionMessages = defCounts.result().size, developerFields = devs.sum)
  }
}

private object GarminFitCodec {

  /** Write the untouched original verbatim (lossless); rebuild only edited/synthesised messages. */
  private def mesgFor(fm: FitMessage): Mesg =
    fm.original match {
      case Some(m: Mesg) => m
      case _             => toMesg(fm)
    }

  private def fromMesg(m: Mesg): FitMessage =
    FitMessage(m.getNum, m.getFields.asScala.iterator.map(fieldOf).toVector, Some(m))

  private def fieldOf(f: Field): RawField = {
    val values = (0 until f.getNumValues).iterator.flatMap { i =>
      Option(f.getValue(i)).flatMap {
        case s: String           => Some(FitValue.Text(s))
        case n: java.lang.Number => Some(FitValue.Num(n.doubleValue))
        case _                   => None
      }
    }.toVector
    RawField(f.getNum, values)
  }

  private def toMesg(fm: FitMessage): Mesg = {
    val m = com.garmin.fit.Factory.createMesg(fm.globalNum)
    fm.fields.foreach { rf =>
      rf.values.iterator.zipWithIndex.foreach { case (v, i) =>
        val obj: Object = v match {
          case FitValue.Num(d)  => java.lang.Double.valueOf(d)
          case FitValue.Text(s) => s
        }
        m.setFieldValue(rf.num, i, obj, Fit.SUBFIELD_INDEX_MAIN_FIELD)
      }
    }
    m
  }
}
