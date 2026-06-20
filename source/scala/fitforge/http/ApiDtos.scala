package fitforge.http

import fitforge.fit.DeviceInfo
import fitforge.fit.FileId
import fitforge.fit.FitLayout
import fitforge.fit.MergeReport
import fitforge.fit.RideSummary

/** One uploaded file's parsed summary, returned from `POST /fitforge/v1/fit/upload`. */
final case class UploadFileResult(
    id: String,
    fileId: FileId,
    summary: RideSummary,
    devices: Vector[DeviceInfo],
    layout: FitLayout,
)

/** Response from `POST /fitforge/v1/fit/upload` (wrapped so the array marshals unambiguously). */
final case class UploadResponse(files: Vector[UploadFileResult])

/** Response from `GET /fitforge/v1/fit/{id}/summary`. */
final case class SummaryResponse(summary: RideSummary, devices: Vector[DeviceInfo], layout: FitLayout)

/** Request body for `POST /fitforge/v1/fit/merge`. */
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
