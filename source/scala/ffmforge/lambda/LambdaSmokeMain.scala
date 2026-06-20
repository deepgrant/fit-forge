package ffmforge.lambda

import scala.concurrent.Future
import scala.concurrent.duration.DurationInt
import scala.concurrent.duration.FiniteDuration

import ffmforge.fit.FitCodec
import ffmforge.fit.FitFile
import ffmforge.fit.FitStats
import ffmforge.store.FitStore
import ffmforge.store.PresignedDownload
import ffmforge.store.PresignedUpload
import ffmforge.store.StoreError

/** Tiny local smoke runner for the API Gateway response wrapper. Does not touch AWS. */
object LambdaSmokeMain {

  def main(args: Array[String]): Unit = {
    val api = new FFMForgeLambdaApi(
      new UnusedStore(),
      new UnusedCodec(),
      ffmforge.FFMForgeConfig(8080, 2.hours, 15.minutes, "", "unused"),
    )(using
      scala.concurrent.ExecutionContext.global
    )
    println(api.handle("""{"rawPath":"/health","requestContext":{"http":{"method":"GET"}}}"""))
  }
}

private final class UnusedStore extends FitStore {
  def createUpload(ttl: FiniteDuration, presignTtl: FiniteDuration): Future[PresignedUpload] =
    Future.failed(new UnsupportedOperationException("unused"))
  def put(bytes: Array[Byte], ttl: FiniteDuration): Future[String] =
    Future.failed(new UnsupportedOperationException("unused"))
  def get(id: String): Future[Either[StoreError, Array[Byte]]] =
    Future.failed(new UnsupportedOperationException("unused"))
  def createDownload(id: String, presignTtl: FiniteDuration): Future[Either[StoreError, PresignedDownload]] =
    Future.failed(new UnsupportedOperationException("unused"))
  def delete(id: String): Future[Unit] =
    Future.failed(new UnsupportedOperationException("unused"))
  def sweepExpired(): Future[Int] =
    Future.failed(new UnsupportedOperationException("unused"))
}

private final class UnusedCodec extends FitCodec {
  def decode(bytes: Array[Byte]): FitFile = FitFile(Vector.empty)
  def encode(file: FitFile): Array[Byte]  = Array.emptyByteArray
  def stats(bytes: Array[Byte]): FitStats = FitStats(0, 0, 0)
}
