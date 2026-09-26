package app.hovanki.e2e.cli

import app.hovanki.e2e.route.GpsNoise
import app.hovanki.e2e.route.Route
import app.hovanki.e2e.scenario.GameSetups
import app.hovanki.shared.protocol.GeoPoint
import app.hovanki.shared.protocol.LocationSample
import java.util.Locale
import kotlin.system.exitProcess

/**
 * The same [Route] and [GpsNoise] the bots use, for devices: prints the fixes (CSV or emulator `geo fix` commands) or
 * feeds them to a device in real time with `adb emu geo fix` / `xcrun simctl location set`.
 *
 * `./gradlew :e2e:route --args="--to 50.4481,30.5402 --speed 1.5 --adb emulator-5554"`
 */
object RouteCli {
    fun run(options: CliArgs): Int {
        val from = options.single("from")?.let(::point) ?: GameSetups.PARK
        val targets = options.list("to").chunked(2).map { (lat, lon) -> GeoPoint(lat.toDouble(), lon.toDouble()) }
        require(targets.isNotEmpty()) { "Pass at least one --to <lat,lon>" }
        val route = Route(listOf(from) + targets, options.single("speed")?.toDouble() ?: Route.WALKING)
        val interval = options.single("interval")?.toLong() ?: 1_000L
        val hold = ((options.single("hold")?.toDouble() ?: 0.0) * 1000).toLong()
        val seed = options.single("seed")?.toLong() ?: 1L
        val noise = when (options.single("noise") ?: "none") {
            "none" -> GpsNoise.NONE
            "open-sky" -> GpsNoise.openSky(seed)
            "city" -> GpsNoise.city(seed)
            else -> error("--noise: none, open-sky or city")
        }
        val adb = options.single("adb")
        val simctl = options.single("simctl")
        val start = System.currentTimeMillis()
        val samples = route.samples(startMillis = start, intervalMillis = interval, noise = noise, holdMillis = hold)

        if (adb == null && simctl == null) {
            val format = options.single("format") ?: "csv"
            if (format == "csv") println("offset_ms,lat,lon,accuracy_m,mock")
            for (sample in samples) println(if (format == "geo-fix") geoFix(sample) else csv(sample, start))
            return 0
        }
        for (sample in samples) {
            val wait = sample.timestampMillis - System.currentTimeMillis()
            if (wait > 0) Thread.sleep(wait)
            val lat = sample.point.lat.fmt()
            val lon = sample.point.lon.fmt()
            val command = if (adb != null) {
                listOf("adb", "-s", adb, "emu", "geo", "fix", lon, lat)
            } else {
                listOf("xcrun", "simctl", "location", checkNotNull(simctl), "set", "$lat,$lon")
            }
            val exit = ProcessBuilder(command).inheritIO().start().waitFor()
            if (exit != 0) return exit
            println(csv(sample, start))
        }
        return 0
    }

    private fun point(text: String): GeoPoint {
        val (lat, lon) = text.split(',').map { it.trim().toDouble() }
        return GeoPoint(lat, lon)
    }

    private fun csv(sample: LocationSample, start: Long): String = listOf(
        sample.timestampMillis - start,
        sample.point.lat.fmt(),
        sample.point.lon.fmt(),
        sample.accuracyMeters,
        sample.isMock,
    ).joinToString(",")

    /** Emulator console syntax: longitude first. */
    private fun geoFix(sample: LocationSample) = "geo fix ${sample.point.lon.fmt()} ${sample.point.lat.fmt()}"

    private fun Double.fmt() = String.format(Locale.ROOT, "%.6f", this)
}

/** Entry point of `./gradlew :e2e:route --args="..."`. */
fun main(args: Array<String>) {
    exitProcess(RouteCli.run(CliArgs(args.toList())))
}
