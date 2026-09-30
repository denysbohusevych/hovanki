package app.hovanki.device

/**
 * Gravity in the phone's frame, in g, the same on both platforms (the platforms convert): x to the right of the screen,
 * y to its top, z out of the screen, and the vector points down to the earth, as iOS's `CMDeviceMotion.gravity` does.
 * Lying screen up: z = −1; upright in portrait: y = −1. Android's `TYPE_GRAVITY` points the other way: negate it and
 * divide by 9.81. The radio lab's `motion` and [CarryClassifier] read it.
 */
data class Gravity(val x: Double, val y: Double, val z: Double)

/** How the phone lies, from [Gravity] (docs/radio-lab.md §7.3): a pocket is upright or tilted, a table flat. */
enum class Orientation {
    FLAT_UP,
    FLAT_DOWN,
    UPRIGHT,
    UPSIDE_DOWN,
    TILTED,
    ;

    val key: String get() = name.lowercase()

    companion object {
        /** Within about 37° of an axis counts as along it. */
        const val AXIS = 0.8

        fun of(gravity: Gravity): Orientation = when {
            gravity.z <= -AXIS -> FLAT_UP
            gravity.z >= AXIS -> FLAT_DOWN
            gravity.y <= -AXIS -> UPRIGHT
            gravity.y >= AXIS -> UPSIDE_DOWN
            else -> TILTED
        }
    }
}
