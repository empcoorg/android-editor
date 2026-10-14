package org.empco.buyerverifier

/** IMEI helpers — same rules as docker/bin/android-authenticity-check. */
object Identity {

    private val PLACEHOLDERS = setOf(
        "000000000000000",
        "111111111111111",
        "123456789012345"
    )

    fun digitsOnly(s: String): String = s.filter { it.isDigit() }

    fun isPlaceholder(s: String): Boolean = digitsOnly(s) in PLACEHOLDERS

    /** A well-formed 15-digit IMEI passes the Luhn check. */
    fun imeiLuhn(raw: String): Boolean {
        val s = digitsOnly(raw)
        if (s.length != 15) return false
        var sum = 0
        var alt = false
        for (i in s.indices.reversed()) {
            var d = s[i] - '0'
            if (alt) {
                d *= 2
                if (d > 9) d -= 9
            }
            sum += d
            alt = !alt
        }
        return sum % 10 == 0
    }
}
