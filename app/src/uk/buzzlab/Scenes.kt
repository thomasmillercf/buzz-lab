package uk.buzzlab

/**
 * A buzz to play on the Feel tab. key names its saved calibration; level is the strength felt in the car, in m/s².
 * curve is the calibration curve it plays from.
 */
class Scene(val key: String, val name: String, val rate: Int, val level: Double, val curve: String = "curve_a")

/** Example settings. Put your own car's measured buzz here. */
object Scenes {
    /** The first two are tuned in calibration; the rest copy the first one's settings. */
    val ALL = listOf(
        Scene("a", "Example A", 30, .70),
        Scene("b", "Example B", 42, .70, "curve_w"),
        Scene("c", "Example C, weaker", 47, .31),
        Scene("slow", "Wheel speed at about 50 mph", 9, .70),
    )

    /** Recordings in assets/clips.json, by key, with their names. None in this copy. */
    val CLIPS = linkedMapOf<String, String>()

    val TYRES = arrayOf("205/55R16", "225/45R17", "235/55R18")
    const val CAR_EXAMPLE = "My car"
    const val TYRE_NOTE = "The buttons are common sizes."
}
