package ffmforge.fit

import java.time.Instant

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

final class FitCodecSpec extends AnyFunSuite with Matchers {

  private val codec = new GarminFitCodec()
  private val t0    = Instant.parse("2026-06-15T08:00:00Z")

  test("modelled record fields survive a round-trip") {
    val rec = FitMessage(FitProfile.Mesg.Record)
      .setInstant(FitProfile.Rec.Timestamp, t0)
      .setNumeric(FitProfile.Rec.Distance, 100.0)
      .setNumeric(FitProfile.Rec.HeartRate, 142)
    val file = FitFile(Vector(FitViews.toMessage(FileId(timeCreated = Some(t0))), rec))

    val rt    = codec.decode(codec.encode(file))
    val rtRec = rt.recordMessages.head
    rtRec.numeric(FitProfile.Rec.Distance) shouldBe Some(100.0)
    rtRec.numeric(FitProfile.Rec.HeartRate) shouldBe Some(142.0)
  }

  test("UNMODELLED record fields survive a round-trip (option 3 fidelity)") {
    val rec = FitMessage(FitProfile.Mesg.Record)
      .setInstant(FitProfile.Rec.Timestamp, t0)
      .setNumeric(13, 21.0) // temperature °C — FFMForge never interprets this field
    val file = FitFile(Vector(FitViews.toMessage(FileId(timeCreated = Some(t0))), rec))

    val rt = codec.decode(codec.encode(file))
    rt.recordMessages.head.numeric(13).map(_.round) shouldBe Some(21L)
  }

  test("UNMODELLED message types pass through a round-trip") {
    val deviceInfo = FitMessage(23).setInstant(253, t0).setNumeric(2, 1.0) // device_info, manufacturer
    val file       = FitFile(Vector(FitViews.toMessage(FileId(timeCreated = Some(t0))), deviceInfo))

    val rt = codec.decode(codec.encode(file))
    rt.messages.exists(_.globalNum == 23) shouldBe true
  }
}
