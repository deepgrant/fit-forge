package ffmforge.http

import java.time.Instant

import ffmforge.fit.DeviceInfo
import ffmforge.fit.FileId
import ffmforge.fit.FitLayout
import ffmforge.fit.MergeReport
import ffmforge.fit.RideSummary

/** One uploaded file's parsed summary, returned from `POST /ffmforge/v1/fit/upload`. */
final case class UploadFileResult(
    id: String,
    fileId: FileId,
    summary: RideSummary,
    devices: Vector[DeviceInfo],
    layout: FitLayout,
)

/** Response from `POST /ffmforge/v1/fit/upload` (wrapped so the array marshals unambiguously). */
final case class UploadResponse(files: Vector[UploadFileResult])

final case class UploadUrlRequest(files: Vector[String])
final case class UploadUrlResult(id: String, name: String, url: String, expiresAt: Instant)
final case class UploadUrlsResponse(files: Vector[UploadUrlResult])
final case class DescribeRequest(ids: Vector[String])
final case class DownloadUrlResponse(id: String, url: String, expiresAt: Instant)
final case class CodecDemoRequest(id: String)

/** Response from `GET /ffmforge/v1/fit/{id}/summary`. */
final case class SummaryResponse(summary: RideSummary, devices: Vector[DeviceInfo], layout: FitLayout)

/** Request body for `POST /ffmforge/v1/fit/merge`. */
final case class MergeRequest(
    ids: Vector[String],
    gapHandling: String,
    lapStrategy: String,
    dryRun: Boolean,
)

/** Response from a merge: `id` is present unless this was a dry run. */
final case class MergeResponse(id: Option[String], report: MergeReport)

/** Uniform error body. */
final case class ApiError(message: String)
