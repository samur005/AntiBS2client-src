package net.antibs.client.data

/** 2.0.12: one ping scale for the banner and URL test: 0–100 dark green, 100–200 green, 200–300 orange, 300+ / failure red. */
object PingLevel {
    enum class Level { EXCELLENT, GOOD, MID, BAD, NONE }
    /** null = not measured («—»), negative = failure / timeout. */
    fun of(ms: Long?): Level = when {
        ms == null -> Level.NONE
        ms < 0 -> Level.BAD
        ms < 100 -> Level.EXCELLENT
        ms < 200 -> Level.GOOD
        ms < 300 -> Level.MID
        else -> Level.BAD
    }
    const val EXCELLENT = 0xFF1B8E3EL
    const val GOOD = 0xFF34C759L
    const val MID = 0xFFF59E0BL
    const val BAD = 0xFFE5484DL
    /** ARGB, or null for NONE (caller's neutral colour). */
    fun argb(l: Level): Long? = when (l) { Level.EXCELLENT -> EXCELLENT; Level.GOOD -> GOOD; Level.MID -> MID; Level.BAD -> BAD; Level.NONE -> null }
}
