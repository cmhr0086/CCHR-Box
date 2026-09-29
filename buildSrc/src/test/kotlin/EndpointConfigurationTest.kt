import org.junit.Assert.*
import org.junit.Test

class EndpointConfigurationTest {
    @Test fun precedenceAndExplicitEmptyValues() {
        val key = EndpointConfiguration.names.first()
        val property = "https://property.example.invalid/api"
        val environment = "https://environment.example.invalid/api"
        val local = "https://local.example.invalid/api"
        assertEquals(property, EndpointConfiguration.resolve(key, " $property ", environment, local))
        assertEquals(environment, EndpointConfiguration.resolve(key, null, environment, local))
        assertEquals(local, EndpointConfiguration.resolve(key, null, null, local))
        assertEquals("", EndpointConfiguration.resolve(key, null, null, null))
        assertEquals("", EndpointConfiguration.resolve(key, "", environment, local))
        assertEquals("", EndpointConfiguration.resolve(key, null, " ", local))
    }

    @Test fun allEightIndependentCombinations() {
        for (mask in 0..7) {
            EndpointConfiguration.names.forEachIndexed { index, name ->
                val value = if (mask and (1 shl index) != 0) "https://example.invalid/$index" else ""
                assertEquals(value, EndpointConfiguration.resolve(name, null, null, value))
            }
        }
    }

    @Test fun eachFieldRejectsInvalidValuesWithoutDisclosingThem() {
        val invalid = listOf("http://example.invalid", "https:///path", "https://",
            "https://user:private-value@example.invalid", "https://example.invalid/#private-value",
            "https://example.invalid:99999", "https://example.invalid/\nprivate-value")
        for (name in EndpointConfiguration.names) for (value in invalid) {
            val error = runCatching { EndpointConfiguration.resolve(name, value, null, null) }.exceptionOrNull()
            assertTrue(error is IllegalArgumentException)
            assertTrue(error!!.message!!.startsWith(name))
            assertFalse(error.message!!.contains(value))
            assertFalse(error.message!!.contains("private-value"))
            assertNull(error.cause)
        }
    }

    @Test fun generatedJavaLiteralIsEscaped() {
        assertEquals("\"https://example.invalid/api?x=1&y=2\"",
            EndpointConfiguration.javaString("https://example.invalid/api?x=1&y=2"))
        assertEquals("\"a\\\"b\\\\c\"", EndpointConfiguration.javaString("a\"b\\c"))
    }
}
