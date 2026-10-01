package caja.comun

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.ZoneId

class LimaTest {
    @Test
    fun `el reloj de caja da la hora de Lima`() {
        assertEquals(ZoneId.of("America/Lima"), Relojes().reloj().zone)
    }
}
