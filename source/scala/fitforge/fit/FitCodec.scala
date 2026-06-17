package fitforge.fit

/**
 * Facade isolating the FIT binary codec from the rest of the application.
 *
 * The only production implementation is [[GarminFitCodec]], backed by the proprietary Garmin FIT Java SDK. Keeping this
 * trait as the single seam means the SDK dependency is swappable and the rest of fit-forge never imports
 * `com.garmin.fit`.
 */
trait FitCodec {

  /** Decode raw `.fit` bytes into the domain model. */
  def decode(bytes: Array[Byte]): FitFile

  /** Encode the domain model into a valid `.fit` activity file. */
  def encode(file: FitFile): Array[Byte]
}
