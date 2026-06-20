package ffmforge.store

import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

import scala.concurrent.Await
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.duration.DurationInt

import ffmforge.http.AwsS3Support
import org.scalatest.BeforeAndAfterAll
import org.scalatest.Outcome
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

final class S3FitStoreSpec extends AnyFunSuite with Matchers with BeforeAndAfterAll {

  private given ExecutionContext = ExecutionContext.global

  private val base       = Instant.parse("2026-06-15T08:00:00Z")
  private val clock      = new AtomicReference[Instant](base)
  private lazy val store = AwsS3Support.newStore(() => clock.get)
  private val ttl        = 2.hours

  override def withFixture(test: NoArgTest): Outcome = {
    assume(AwsS3Support.available, "AWS credentials/test bucket not available")
    super.withFixture(test)
  }

  override def afterAll(): Unit = ()

  private def await[A](f: Future[A]): A     = Await.result(f, 30.seconds)
  private def bytes(s: String): Array[Byte] = s.getBytes("UTF-8")

  test("put then get returns the same bytes") {
    clock.set(base)
    val id = await(store.put(bytes("hello-fit"), ttl))
    await(store.get(id)).map(_.toSeq) shouldBe Right(bytes("hello-fit").toSeq)
  }

  test("get of an unknown id is NotFound") {
    val id = s"${base.toEpochMilli + 999999}_${UUID.randomUUID()}"
    await(store.get(id)) shouldBe Left(StoreError.NotFound)
  }

  test("delete removes the object") {
    clock.set(base)
    val id = await(store.put(bytes("x"), ttl))
    await(store.delete(id))
    await(store.get(id)) shouldBe Left(StoreError.NotFound)
  }

  test("get past expiry is Expired and deletes the object") {
    clock.set(base)
    val id = await(store.put(bytes("y"), ttl))
    clock.set(base.plusSeconds(3.hours.toSeconds))
    await(store.get(id)) shouldBe Left(StoreError.Expired)
    clock.set(base)
    await(store.get(id)) shouldBe Left(StoreError.NotFound)
  }

  test("sweepExpired deletes all past-expiry objects") {
    clock.set(base)
    val id1 = await(store.put(bytes("a"), ttl))
    val id2 = await(store.put(bytes("b"), ttl))
    clock.set(base.plusSeconds(3.hours.toSeconds))
    await(store.sweepExpired()) should be >= 2
    clock.set(base)
    await(store.get(id1)) shouldBe Left(StoreError.NotFound)
    await(store.get(id2)) shouldBe Left(StoreError.NotFound)
  }
}
