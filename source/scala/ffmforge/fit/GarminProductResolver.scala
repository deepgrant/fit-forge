package ffmforge.fit

import scala.util.Try

/** Resolves Garmin manufacturer/product ids using the Garmin FIT SDK product table without importing SDK types here. */
object GarminProductResolver {

  private val GarminManufacturer = "garmin"

  private val ProductClass = "com.garmin.fit.GarminProduct"

  private val ProductOverrides: Map[Int, String] = Map(
    // Newer than the bundled Garmin FIT SDK product table in use here.
    4470 -> "VARIA_VUE"
  )

  private val PrefixLabels: Map[String, String] = Map(
    "EDGE"       -> "Edge",
    "FR"         -> "Forerunner",
    "HRM"        -> "HRM",
    "FENIX"      -> "Fenix",
    "EPIX"       -> "Epix",
    "VENU"       -> "Venu",
    "VIVOACTIVE" -> "Vivoactive",
    "VIVOSMART"  -> "Vivosmart",
    "VIVOFIT"    -> "Vivofit",
    "INSTINCT"   -> "Instinct",
    "TACX"       -> "Tacx",
    "VARIA"      -> "Varia",
    "RALLY"      -> "Rally",
    "VECTOR"     -> "Vector",
  )

  private val Acronyms: Set[String] =
    Set("ANT", "APAC", "GPS", "HRM", "MTB", "NFC", "OHR", "RCT", "RTL", "RVR", "SEA", "USB", "UT", "WW")

  private val CompactModelPattern = "(?i)(RCT|RTL|RVR|UT)\\d+".r

  private lazy val getStringFromValue =
    Try {
      val cls = Class.forName(ProductClass)
      cls.getMethod("getStringFromValue", classOf[Integer])
    }.toOption

  def nameOf(manufacturer: String, product: Option[Int]): Option[String] =
    if (manufacturer.trim.equalsIgnoreCase(GarminManufacturer)) product.flatMap(nameOf)
    else None

  def nameOf(product: Int): Option[String] =
    ProductOverrides
      .get(product)
      .orElse(
        getStringFromValue
          .flatMap(method => Try(method.invoke(ProductClass, Integer.valueOf(product))).toOption)
          .collect { case value: String => value }
      )
      .map(_.trim)
      .filter(_.nonEmpty)
      .filterNot(_.equalsIgnoreCase("invalid"))
      .map(prettyProductName)

  private def prettyProductName(raw: String): String = {
    val tokens = raw.split("_").toVector.flatMap(splitAlphaNumeric).filter(_.nonEmpty)
    if (tokens.isEmpty) raw
    else tokens.zipWithIndex.map { case (token, index) => prettyToken(token, index) }.mkString(" ")
  }

  private def splitAlphaNumeric(token: String): Vector[String] =
    token match {
      case CompactModelPattern(_) => Vector(token)
      case _ =>
        token
          .replaceAll("(?<=[A-Za-z])(?=\\d)", " ")
          .replaceAll("(?<=\\d)(?=[A-Za-z])", " ")
          .split("\\s+")
          .toVector
    }

  private def prettyToken(token: String, index: Int): String = {
    val upper = token.toUpperCase
    if (index == 0) PrefixLabels.getOrElse(upper, defaultToken(token))
    else defaultToken(token)
  }

  private def defaultToken(token: String): String = {
    val upper = token.toUpperCase
    if (token.forall(_.isDigit) || Acronyms.contains(upper) || CompactModelPattern.matches(token)) upper
    else upper.headOption.fold(token)(head => s"$head${upper.drop(1).toLowerCase}")
  }
}
