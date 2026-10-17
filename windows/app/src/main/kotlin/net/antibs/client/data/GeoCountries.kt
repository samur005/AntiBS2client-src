package net.antibs.client.data

/**
 * 1.0.46: approximate label points (lat, lon) of countries for the home map / globe markers.
 * Generated from Natural Earth 1:50m admin-0 countries (LABEL_Y / LABEL_X), public domain —
 * "Made with Natural Earth", https://www.naturalearthdata.com/about/terms-of-use/
 */
object GeoCountries {
    data class G(val lat: Double, val lon: Double)

    private val table: Map<String, G> = hashMapOf(
        "AD" to G(42.55, 1.54), "AE" to G(23.47, 54.55), "AF" to G(34.16, 66.5), "AG" to G(17.35, -61.79), "AI" to G(18.24, -63.03), "AL" to G(40.65, 20.11),
        "AM" to G(40.46, 44.8), "AO" to G(-12.18, 17.98), "AQ" to G(-79.84, 35.89), "AR" to G(-33.5, -64.17), "AS" to G(-14.33, -170.75), "AT" to G(47.52, 14.13),
        "AU" to G(-24.13, 134.05), "AW" to G(12.52, -69.97), "AX" to G(60.16, 19.87), "AZ" to G(40.4, 47.21), "BA" to G(44.09, 18.07), "BB" to G(13.16, -59.57),
        "BD" to G(24.21, 89.68), "BE" to G(50.79, 4.8), "BF" to G(12.67, -1.36), "BG" to G(42.51, 25.16), "BH" to G(26.06, 50.55), "BI" to G(-3.33, 29.92),
        "BJ" to G(10.32, 2.35), "BL" to G(17.9, -62.83), "BM" to G(32.3, -64.76), "BN" to G(4.45, 114.55), "BO" to G(-16.67, -64.59), "BR" to G(-12.1, -49.56),
        "BS" to G(26.4, -77.15), "BT" to G(27.54, 90.04), "BW" to G(-22.1, 24.18), "BY" to G(53.82, 28.42), "BZ" to G(17.2, -88.71), "CA" to G(60.32, -101.91),
        "CD" to G(-1.86, 23.46), "CF" to G(6.99, 20.91), "CG" to G(0.14, 15.9), "CH" to G(46.72, 7.46), "CI" to G(7.49, -5.57), "CK" to G(-21.22, -159.79),
        "CL" to G(-38.15, -72.32), "CM" to G(4.59, 12.47), "CN" to G(32.5, 106.34), "CN-TW" to G(23.65, 120.87), "CO" to G(3.37, -73.17), "CR" to G(10.07, -84.08),
        "CU" to G(21.33, -77.98), "CV" to G(15.07, -23.64), "CW" to G(12.15, -68.92), "CY" to G(34.91, 33.08), "CZ" to G(49.88, 15.38), "DE" to G(50.96, 9.68),
        "DJ" to G(11.98, 42.5), "DK" to G(55.97, 9.02), "DM" to G(15.46, -61.34), "DO" to G(19.1, -70.65), "DZ" to G(27.4, 2.81), "EC" to G(-1.26, -78.19),
        "EE" to G(58.72, 25.87), "EG" to G(26.19, 29.45), "EH" to G(23.97, -12.63), "ER" to G(15.79, 38.29), "ES" to G(40.09, -3.46), "ET" to G(8.03, 39.09),
        "FI" to G(63.25, 27.28), "FJ" to G(-17.83, 177.98), "FK" to G(-51.61, -58.74), "FM" to G(6.89, 158.23), "FO" to G(62.19, -7.06), "FR" to G(46.7, 2.55),
        "GA" to G(-0.44, 11.84), "GB" to G(54.4, -2.12), "GD" to G(12.11, -61.68), "GE" to G(41.87, 43.74), "GG" to G(49.46, -2.56), "GH" to G(7.72, -1.04),
        "GL" to G(74.32, -39.34), "GM" to G(13.64, -15.0), "GN" to G(10.62, -10.02), "GQ" to G(2.33, 8.99), "GR" to G(39.49, 21.73), "GS" to G(-55.68, -31.06),
        "GT" to G(14.98, -90.5), "GU" to G(13.35, 144.7), "GW" to G(12.16, -14.52), "GY" to G(5.12, -58.94), "HK" to G(22.45, 114.1), "HM" to G(-53.1, 73.51),
        "HN" to G(14.79, -86.89), "HR" to G(45.81, 16.37), "HT" to G(19.26, -72.22), "HU" to G(47.09, 19.45), "ID" to G(-0.95, 101.89), "IE" to G(53.08, -7.8),
        "IL" to G(30.91, 34.85), "IM" to G(54.22, -4.53), "IN" to G(22.69, 79.36), "IO" to G(-6.19, 71.35), "IQ" to G(33.09, 43.26), "IR" to G(32.17, 54.93),
        "IS" to G(64.78, -18.67), "IT" to G(44.73, 11.08), "JE" to G(49.22, -2.09), "JM" to G(18.14, -77.32), "JO" to G(30.81, 36.38), "JP" to G(36.14, 138.44),
        "KE" to G(0.55, 37.91), "KG" to G(41.67, 74.53), "KH" to G(12.65, 104.5), "KI" to G(1.82, -157.38), "KM" to G(-11.73, 43.32), "KN" to G(17.34, -62.76),
        "KP" to G(39.89, 126.44), "KR" to G(36.38, 128.13), "KW" to G(29.41, 47.31), "KY" to G(19.32, -81.24), "KZ" to G(49.05, 68.69), "LA" to G(19.43, 102.53),
        "LB" to G(34.13, 35.99), "LC" to G(13.89, -60.98), "LI" to G(47.11, 9.56), "LK" to G(7.58, 80.7), "LR" to G(6.45, -9.46), "LS" to G(-29.48, 28.25),
        "LT" to G(55.1, 24.09), "LU" to G(49.73, 6.08), "LV" to G(57.07, 25.46), "LY" to G(26.64, 18.01), "MA" to G(31.65, -7.19), "MC" to G(43.74, 7.4),
        "MD" to G(47.43, 28.49), "ME" to G(42.8, 19.14), "MF" to G(18.08, -63.05), "MG" to G(-18.63, 46.7), "MH" to G(7.08, 171.19), "MK" to G(41.56, 21.56),
        "ML" to G(18.69, -2.04), "MM" to G(21.57, 95.8), "MN" to G(46.0, 104.15), "MO" to G(22.13, 113.56), "MP" to G(15.19, 145.73), "MR" to G(19.59, -9.74),
        "MS" to G(16.74, -62.19), "MT" to G(35.89, 14.43), "MU" to G(-20.3, 57.57), "MV" to G(4.17, 73.51), "MW" to G(-13.39, 33.61), "MX" to G(23.92, -102.29),
        "MY" to G(2.53, 113.84), "MZ" to G(-13.94, 37.84), "NA" to G(-20.58, 17.11), "NC" to G(-21.06, 165.08), "NE" to G(17.45, 9.5), "NF" to G(-29.03, 167.95),
        "NG" to G(9.44, 7.5), "NI" to G(12.67, -85.07), "NL" to G(52.42, 5.61), "NO" to G(61.36, 9.68), "NP" to G(28.3, 83.64), "NR" to G(-0.52, 166.93),
        "NU" to G(-19.05, -169.86), "NZ" to G(-39.76, 172.79), "OM" to G(22.12, 57.34), "PA" to G(8.72, -80.35), "PE" to G(-12.98, -72.9), "PF" to G(-17.63, -149.46),
        "PG" to G(-5.7, 143.91), "PH" to G(11.2, 122.47), "PK" to G(29.33, 68.55), "PL" to G(51.99, 19.49), "PM" to G(47.04, -56.33), "PN" to G(-24.36, -128.32),
        "PR" to G(18.23, -66.48), "PS" to G(32.05, 35.29), "PT" to G(39.61, -8.27), "PW" to G(7.52, 134.58), "PY" to G(-21.67, -60.15), "QA" to G(25.24, 51.14),
        "RO" to G(45.73, 24.97), "RS" to G(44.19, 20.79), "RU" to G(58.25, 44.69), "RW" to G(-1.9, 30.1), "SA" to G(23.81, 44.7), "SB" to G(-8.03, 159.17),
        "SC" to G(-4.68, 55.48), "SD" to G(16.33, 29.26), "SE" to G(65.86, 19.02), "SG" to G(1.37, 103.82), "SH" to G(-15.95, -5.71), "SI" to G(46.06, 14.92),
        "SK" to G(48.73, 19.05), "SL" to G(8.62, -11.76), "SM" to G(43.93, 12.44), "SN" to G(15.14, -14.78), "SO" to G(3.57, 45.19), "SR" to G(4.14, -55.91),
        "SS" to G(7.23, 30.39), "ST" to G(0.97, 7.02), "SV" to G(13.69, -88.89), "SX" to G(18.04, -63.07), "SY" to G(35.01, 38.28), "SZ" to G(-26.53, 31.47),
        "TC" to G(21.82, -71.75), "TD" to G(15.14, 18.65), "TF" to G(-49.3, 69.12), "TG" to G(8.81, 1.06), "TH" to G(15.46, 101.07), "TJ" to G(38.2, 72.59),
        "TL" to G(-8.8, 125.85), "TM" to G(39.86, 58.68), "TN" to G(33.69, 9.01), "TO" to G(-21.21, -175.16), "TR" to G(39.35, 34.51), "TT" to G(11.0, -60.92),
        "TV" to G(-8.51, 179.21), "TW" to G(23.75, 120.95), "TZ" to G(-6.05, 34.96), "UA" to G(49.72, 32.14), "UG" to G(1.97, 32.95), "US" to G(39.54, -97.48),
        "UY" to G(-32.96, -55.97), "UZ" to G(41.69, 64.01), "VA" to G(41.9, 12.45), "VC" to G(13.09, -61.34), "VE" to G(7.18, -64.6), "VG" to G(18.43, -64.64),
        "VI" to G(17.75, -64.78), "VN" to G(21.72, 105.39), "VU" to G(-15.37, 166.91), "WF" to G(-14.29, -178.14), "WS" to G(-13.64, -172.44), "XK" to G(42.59, 20.86),
        "YE" to G(15.33, 45.87), "ZA" to G(-29.71, 23.67), "ZM" to G(-14.66, 26.4), "ZW" to G(-18.91, 29.93),
    )

    fun point(cc: String?): G? = cc?.uppercase()?.let { table[if (it == "UK") "GB" else it] }

    /** "🇩🇪 Frankfurt" → "DE" (first regional-indicator pair), null if the name has no flag. */
    fun ccOfName(name: String?): String? {
        if (name.isNullOrEmpty()) return null
        val cps = name.codePoints().toArray()
        for (i in 0 until cps.size - 1) {
            val a = cps[i]; val b = cps[i + 1]
            if (a in 0x1F1E6..0x1F1FF && b in 0x1F1E6..0x1F1FF) return "" + ('A' + (a - 0x1F1E6)) + ('A' + (b - 0x1F1E6))
        }
        return null
    }

    /** Localised country name ("Германия"), falls back to the code. */
    fun name(cc: String, locale: java.util.Locale = java.util.Locale.forLanguageTag("ru")): String =
        runCatching { java.util.Locale.Builder().setRegion(cc).build().getDisplayCountry(locale) }.getOrNull()?.ifBlank { null } ?: cc
}
