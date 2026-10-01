package app.hovanki.client.errors

import kotlin.test.Test
import kotlin.test.assertNull

class ErrorReporterTest {
    @Test
    fun theNoopReporterSendsNothingAndHasNoId() {
        assertNull(NoopErrorReporter.capture(IllegalStateException("anything")))
    }
}
