package ffmforge.examples

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Duration

import ffmforge.fit.CodecDemoReport
import ffmforge.fit.DeviceInfo
import ffmforge.fit.FitFileDescription
import ffmforge.http.CodecDemoRequest
import ffmforge.http.JsonProtocol
import ffmforge.http.UploadUrlRequest
import ffmforge.http.UploadUrlsResponse
import org.apache.pekko.http.scaladsl.model.StatusCodes
import spray.json.enrichAny
import spray.json.enrichString

/**
 * Deployed Lambda equivalent of `codecDemo`.
 *
 * This uses the public FFMForge API: ask for a presigned upload URL, PUT the local FIT file to S3, then ask the
 * deployed Lambda to decode, re-encode, re-decode, summarize, and verify the uploaded file.
 */
object LambdaCodecDemo {

  import JsonProtocol._

  private val DefaultBaseUrl = "https://ffmforge.com"
  private val DefaultSample  = "samples/19724302447_ACTIVITY.fit"

  def main(args: Array[String]): Unit = {
    val baseUrl = sys.props
      .get("ffmforge.baseUrl")
      .orElse(sys.env.get("FFMFORGE_BASE_URL"))
      .getOrElse(DefaultBaseUrl)
      .stripSuffix("/")
    val input = Paths.get(args.headOption.getOrElse(DefaultSample))
    if (!Files.exists(input)) {
      fail(s"FIT file does not exist: $input")
    }

    val bytes = Files.readAllBytes(input)
    val http  = HttpClient.newHttpClient()

    banner("FFMForge deployed Lambda FIT codec demonstration")
    println(s"API base URL: $baseUrl")
    println(s"Local FIT file: $input")
    println(s"Read ${bytes.length} bytes; requesting a presigned upload URL from Lambda...")

    val upload = postJson(
      http,
      baseUrl,
      "/ffmforge/v1/uploads",
      UploadUrlRequest(Vector(input.getFileName.toString)).toJson,
    ).parseJson
      .convertTo[UploadUrlsResponse]
      .files
      .head

    println(s"  -> upload id: ${upload.id}")
    println("Uploading the FIT bytes directly to private S3 via the presigned URL...")
    putBytes(http, upload.url, bytes)

    println("\nAsking the deployed Lambda to decode, encode, decode, and verify the uploaded FIT file...")
    val report = postJson(http, baseUrl, "/ffmforge/v1/fit/codec-demo", CodecDemoRequest(upload.id).toJson).parseJson
      .convertTo[CodecDemoReport]

    printReport(report, input)
    banner(if (report.passed) "OVERALL RESULT: PASS" else "OVERALL RESULT: FAIL")
  }

  private def postJson(http: HttpClient, baseUrl: String, path: String, body: spray.json.JsValue): String = {
    val res = http.send(
      HttpRequest
        .newBuilder(URI.create(s"$baseUrl$path"))
        .header("content-type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(body.compactPrint))
        .build(),
      HttpResponse.BodyHandlers.ofString(),
    )
    if (res.statusCode() != StatusCodes.OK.intValue) {
      fail(s"POST $path failed with HTTP ${res.statusCode()}: ${res.body()}")
    }
    res.body()
  }

  private def putBytes(http: HttpClient, url: String, bytes: Array[Byte]): Unit = {
    val res = http.send(
      HttpRequest.newBuilder(URI.create(url)).PUT(HttpRequest.BodyPublishers.ofByteArray(bytes)).build(),
      HttpResponse.BodyHandlers.discarding(),
    )
    if (res.statusCode() != StatusCodes.OK.intValue && res.statusCode() != StatusCodes.Created.intValue) {
      fail(s"presigned upload failed with HTTP ${res.statusCode()}")
    }
    println(s"  -> upload accepted with HTTP ${res.statusCode()}")
  }

  private def fail(message: String): Nothing = {
    System.err.println(s"ERROR: $message")
    sys.exit(1)
  }

  private def printReport(report: CodecDemoReport, input: Path): Unit = {
    println("\nDecoded original file:")
    describeFitFile("  original", report.original)
    report.original.startTime.foreach(t => println(s"    first sample at $t"))
    report.original.endTime.foreach(t => println(s"    last  sample at $t"))

    printOverview(report)

    println("\nRe-encoded and re-decoded by the deployed Lambda:")
    describeFitFile("  redecoded", report.redecoded)
    println(s"  -> local input file:    $input")
    println(s"  -> file size:           ${report.originalBytes} bytes -> ${report.reencodedBytes} bytes")
    printStats(
      "  -> definition messages",
      report.originalStats.definitionMessages,
      report.reencodedStats.definitionMessages,
    )
    printStats("  -> data messages", report.originalStats.dataMessages, report.reencodedStats.dataMessages)
    printStats("  -> developer fields", report.originalStats.developerFields, report.reencodedStats.developerFields)

    println("\nFinal file layout summary:")
    println(s"  messages: ${report.layout.totalMessages}")
    println(s"  fields:   ${report.layout.totalFields}")
    report.layout.counts.foreach { case (name, count) => println(f"  $name%-24s $count") }

    println("\nVerifying the second decode matches the first:")
    report.checks.foreach { c =>
      val tag = if (c.passed) "PASS" else "FAIL"
      println(s"  [$tag] ${c.label}: ${c.detail}")
    }
    println(
      s"  => Deployed Lambda round-trip: ${if (report.passed) "PASS - data is faithful" else "FAIL - data differs"}"
    )
  }

  private def printOverview(report: CodecDemoReport): Unit = {
    println(s"\nPrimary recording device: ${report.primaryDevice.map(_.displayName).getOrElse("unknown")}")
    println("Devices used in the recording:")
    if (report.devices.isEmpty) println("  (none recorded)")
    else report.devices.foreach(printDevice)

    val r = report.summary
    println("\nRide summary:")
    println(s"  activity type:  ${r.sport.map(_.toLowerCase).getOrElse("-")}")
    println(s"  total distance: ${r.totalDistanceM.map(distance).getOrElse("-")}")
    println(s"  elapsed time:   ${r.elapsedSeconds.map(hms).getOrElse("-")}")
    println(s"  moving time:    ${r.movingSeconds.map(hms).getOrElse("-")}")
    println(s"  avg speed:      ${r.avgSpeedMps.map(speed).getOrElse("-")}")
    println(s"  max speed:      ${r.maxSpeedMps.map(speed).getOrElse("-")}")
    r.avgPowerW.foreach(p => println(f"  avg power:      $p%.0f W"))
    r.maxPowerW.foreach(p => println(f"  max power:      $p%.0f W"))
    r.avgTempC.foreach(t => println(s"  avg temp:       ${temp(t)}"))
    r.maxTempC.foreach(t => println(s"  max temp:       ${temp(t)}"))
  }

  private def printDevice(d: DeviceInfo): Unit = {
    val bits = Vector(
      d.kind.map(k => s"kind: $k"),
      d.sourceType.map(t => s"conn: $t"),
      d.batteryStatus.map(s => s"battery: $s"),
      d.softwareVersion.map(v => f"sw $v%.2f"),
      d.serialNumber.map(s => s"serial $s"),
    ).flatten.mkString(", ")
    val suffix = if (bits.isEmpty) "" else s"  ($bits)"
    println(s"  [${d.index}] ${d.displayName}$suffix")
  }

  private def describeFitFile(label: String, f: FitFileDescription): Unit = {
    val span = (f.startTime, f.endTime) match {
      case (Some(s), Some(e)) => s", spanning ${humanDuration(Duration.between(s, e))}"
      case _                  => ""
    }
    val dist = f.distanceM.map(d => f", ${d / 1000.0}%.2f km").getOrElse("")
    println(
      s"$label: ${f.records} records, ${f.sessions} session(s), ${f.laps} lap(s), ${f.events} event(s)$span$dist"
    )
  }

  private def printStats(label: String, before: Int, after: Int): Unit =
    println(f"$label%-26s $before -> $after")

  private def distance(m: Double): String = f"${m / 1000.0}%.2f km / ${m / 1609.344}%.2f mi"
  private def speed(mps: Double): String  = f"${mps * 3.6}%.1f km/h / ${mps * 2.236936}%.1f mph"
  private def temp(c: Double): String     = f"$c%.1f C / ${c * 9.0 / 5.0 + 32.0}%.1f F"
  private def hms(seconds: Double): String = {
    val s = seconds.toLong
    f"${s / 3600}%dh ${(s % 3600) / 60}%02dm ${s % 60}%02ds"
  }

  private def humanDuration(d: Duration): String = {
    val s = d.getSeconds
    f"${s / 3600}%dh ${(s % 3600) / 60}%02dm ${s % 60}%02ds"
  }

  private def banner(title: String): Unit = {
    println("\n" + "=" * 78)
    println(title)
    println("=" * 78)
  }
}
