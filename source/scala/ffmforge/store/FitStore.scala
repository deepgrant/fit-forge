package ffmforge.store

import java.time.Instant

import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration

/** Why a stored file can't be returned. */
enum StoreError {
  case NotFound
  case Expired
}

final case class PresignedUpload(id: String, url: String, expiresAt: Instant)
final case class PresignedDownload(id: String, url: String, expiresAt: Instant)

/**
 * Async object store for uploaded/merged `.fit` bytes, keyed by an opaque id (the session/capability token). The only
 * production implementation is [[S3FitStore]] (AWS S3); pods hold no state so any pod can serve any id.
 */
trait FitStore {

  /** Create an id and presigned URL that lets a browser upload bytes directly to S3. */
  def createUpload(ttl: FiniteDuration, presignTtl: FiniteDuration): Future[PresignedUpload]

  /** Store `bytes` with a time-to-live; returns the generated id. */
  def put(bytes: Array[Byte], ttl: FiniteDuration): Future[String]

  /** Fetch bytes by id, or a [[StoreError]] if missing/expired (expired objects are deleted). */
  def get(id: String): Future[Either[StoreError, Array[Byte]]]

  /** Create a presigned URL for downloading an existing object. */
  def createDownload(id: String, presignTtl: FiniteDuration): Future[Either[StoreError, PresignedDownload]]

  /** Delete an object (best effort). */
  def delete(id: String): Future[Unit]

  /** Delete all objects past their expiry; returns how many were removed. */
  def sweepExpired(): Future[Int]
}
