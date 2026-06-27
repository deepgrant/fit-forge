package ffmforge

enum DownloadFormat(val wireName: String, val extension: String, val contentType: String) {
  case Fit extends DownloadFormat("fit", "fit", "application/vnd.ant.fit")
  case Gpx extends DownloadFormat("gpx", "gpx", "application/gpx+xml")
}

object DownloadFormat {
  val Default: DownloadFormat = DownloadFormat.Fit

  def fromWireName(value: String): Option[DownloadFormat] =
    DownloadFormat.values.find(_.wireName.equalsIgnoreCase(value.trim))
}
