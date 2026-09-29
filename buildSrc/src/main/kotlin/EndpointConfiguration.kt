import java.net.URI

/** Values are never included in validation errors: they may identify private services. */
object EndpointConfiguration {
    val names = listOf(
        "CCHR_SUBSCRIPTION_ENDPOINT",
        "CCHR_ANNOUNCEMENT_ENDPOINT",
        "CCHR_APP_CONTROL_ENDPOINT",
    )

    fun resolve(name: String, property: String?, environment: String?, local: String?): String {
        val value = (property ?: environment ?: local ?: "").trim()
        if (value.isEmpty()) return value
        val uri = runCatching { URI(value) }.getOrNull()
        require(uri != null && uri.scheme.equals("https", ignoreCase = true) &&
            !uri.host.isNullOrBlank() && uri.rawUserInfo == null && uri.rawFragment == null &&
            (uri.port == -1 || uri.port in 1..65535)) {
            "$name must be empty or a valid HTTPS endpoint (without credentials or fragment)"
        }
        return value
    }

    fun javaString(value: String): String =
        "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
