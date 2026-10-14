package org.empco.buyerverifier

/** Reads Android system properties; faked in unit tests. */
interface PropSource {
    fun get(name: String): String?
}

/**
 * Reads properties by running `getprop`. Works without any permission for the
 * build/product properties this app needs and avoids hidden-API reflection.
 */
class SystemPropSource : PropSource {
    override fun get(name: String): String? = try {
        val proc = Runtime.getRuntime().exec(arrayOf("getprop", name))
        val value = proc.inputStream.bufferedReader().readText().trim()
        proc.waitFor()
        value.ifEmpty { null }
    } catch (e: Exception) {
        null
    }
}
