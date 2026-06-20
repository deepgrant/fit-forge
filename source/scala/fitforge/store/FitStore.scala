package fitforge.store

import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration

/** Why a stored file can't be returned. */
enum StoreError {
  case NotFound
  case Expired
}

/**
 * Async object store for uploaded/merged `.fit` bytes, keyed by an opaque id (the session/capability token). The only
 * production implementation is [[S3FitStore]] (AWS S3); pods hold no state so any pod can serve any id.
 */
trait FitStore {

  /** Store `bytes` with a time-to-live; returns the generated id. */
  def put(bytes: Array[Byte], ttl: FiniteDuration): Future[String]

  /** Fetch bytes by id, or a [[StoreError]] if missing/expired (expired objects are deleted). */
  def get(id: String): Future[Either[StoreError, Array[Byte]]]

  /** Delete an object (best effort). */
  def delete(id: String): Future[Unit]

  /** Delete all objects past their expiry; returns how many were removed. */
  def sweepExpired(): Future[Int]
}
