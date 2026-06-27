package ffmforge.store

import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.DurationInt

import ffmforge.http.AwsS3Support
import org.scalatest.BeforeAndAfterAll
import org.scalatest.Outcome
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.Millis
import org.scalatest.time.Seconds
import org.scalatest.time.Span

final class S3FitStoreSpec extends AnyFunSuite with Matchers with BeforeAndAfterAll with ScalaFutures {

  private given ExecutionContext = ExecutionContext.global
  implicit override val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = Span(30, Seconds), interval = Span(100, Millis))

  private val base       = Instant.parse("2026-06-15T08:00:00Z")
  private val clock      = new AtomicReference[Instant](base)
  private lazy val store = AwsS3Support.newStore(() => clock.get)
  private val ttl        = 2.hours

  override def withFixture(test: NoArgTest): Outcome = {
    assume(AwsS3Support.available, "AWS credentials/test bucket not available")
    super.withFixture(test)
  }

  override def afterAll(): Unit = ()

  private def bytes(s: String): Array[Byte] = s.getBytes("UTF-8")

  test("put then get returns the same bytes") {
    clock.set(base)
    val id = store.put(bytes("hello-fit"), ttl).futureValue
    store.get(id).futureValue.map(_.toSeq) shouldBe Right(bytes("hello-fit").toSeq)
  }

  test("get of an unknown id is NotFound") {
    val id = s"${base.toEpochMilli + 999999}_${UUID.randomUUID()}"
    store.get(id).futureValue shouldBe Left(StoreError.NotFound)
  }

  test("delete removes the object") {
    clock.set(base)
    val id = store.put(bytes("x"), ttl).futureValue
    store.delete(id).futureValue
    store.get(id).futureValue shouldBe Left(StoreError.NotFound)
  }

  test("get past expiry is Expired and deletes the object") {
    clock.set(base)
    val id = store.put(bytes("y"), ttl).futureValue
    clock.set(base.plusSeconds(3.hours.toSeconds))
    store.get(id).futureValue shouldBe Left(StoreError.Expired)
    clock.set(base)
    store.get(id).futureValue shouldBe Left(StoreError.NotFound)
  }

  test("sweepExpired deletes all past-expiry objects") {
    clock.set(base)
    val id1 = store.put(bytes("a"), ttl).futureValue
    val id2 = store.put(bytes("b"), ttl).futureValue
    clock.set(base.plusSeconds(3.hours.toSeconds))
    store.sweepExpired().futureValue should be >= 2
    clock.set(base)
    store.get(id1).futureValue shouldBe Left(StoreError.NotFound)
    store.get(id2).futureValue shouldBe Left(StoreError.NotFound)
  }
}
