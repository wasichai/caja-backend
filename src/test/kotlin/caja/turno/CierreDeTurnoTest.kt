package caja.turno

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

// la máquina de estados del turno (CierreDeTurno, EstadoDeTurno y SituacionDelCajero de caja): el estado no se guarda,
// se deriva del ÚLTIMO movimiento por secuencia. un cierre lo deja CERRADO, una reversión ABIERTO; sin movimientos,
// ABIERTO. y de ahí sale el cierre vigente
class CierreDeTurnoTest {
    private val cierre1 = Movimiento("c1", TipoDeMovimiento.CIERRE, 1)
    private val reversion2 = Movimiento("r2", TipoDeMovimiento.REVERSION, 2)
    private val cierre3 = Movimiento("c3", TipoDeMovimiento.CIERRE, 3)

    @Test
    fun `el ultimo movimiento manda, no la cuenta de cierres contra reversiones`() {
        assertEquals(EstadoDelTurno.CERRADO, EstadoDelTurno.de(listOf(cierre1)))
        assertEquals(EstadoDelTurno.ABIERTO, EstadoDelTurno.de(listOf(cierre1, reversion2)))
        assertEquals(EstadoDelTurno.CERRADO, EstadoDelTurno.de(listOf(cierre1, reversion2, cierre3)))
    }

    @Test
    fun `el ultimo es el de mayor secuencia, llegue en el orden que llegue`() {
        // los cierres y las reversiones se leen de dos objetos: llegan juntos pero no ordenados
        assertEquals(EstadoDelTurno.CERRADO, EstadoDelTurno.de(listOf(reversion2, cierre3, cierre1)))
        assertEquals(EstadoDelTurno.ABIERTO, EstadoDelTurno.de(listOf(reversion2, cierre1)))
    }

    @Test
    fun `el cierre vigente es el ultimo movimiento si es un cierre`() {
        assertNull(cierreVigente(emptyList()))
        assertEquals(cierre1, cierreVigente(listOf(cierre1)))
        assertNull(cierreVigente(listOf(cierre1, reversion2)))
        assertEquals(cierre3, cierreVigente(listOf(reversion2, cierre1, cierre3)))
    }

    @Test
    fun `la secuencia siguiente es la cantidad de movimientos mas uno, comun al cierre y a la reversion`() {
        assertEquals(1L, siguienteSecuencia(emptyList()))
        assertEquals(2L, siguienteSecuencia(listOf(cierre1)))
        assertEquals(4L, siguienteSecuencia(listOf(cierre1, reversion2, cierre3)))
    }

    @Test
    fun `una historia rota no se interpreta, se rechaza`() {
        // dos movimientos con la misma secuencia, o un hueco: el candado del turno los habría ordenado
        assertThrows<IllegalStateException> { EstadoDelTurno.de(listOf(cierre1, Movimiento("c1bis", TipoDeMovimiento.CIERRE, 1))) }
        assertThrows<IllegalStateException> { siguienteSecuencia(listOf(cierre1, cierre3)) }
        // una reversión sin un cierre delante no reversa nada
        assertThrows<IllegalStateException> { EstadoDelTurno.de(listOf(Movimiento("r1", TipoDeMovimiento.REVERSION, 1))) }
        // dos cierres seguidos serían dos arqueos vigentes sobre el mismo dinero
        assertThrows<IllegalStateException> { EstadoDelTurno.de(listOf(cierre1, Movimiento("c2", TipoDeMovimiento.CIERRE, 2))) }
    }

    @Test
    fun `la situacion del cajero distingue el que no abrio del que ya cerro, y no elige entre dos abiertos`() {
        assertEquals(SituacionDelCajero.SIN_ABRIR, SituacionDelCajero.de(emptyList()))
        assertEquals(SituacionDelCajero.ABIERTO, SituacionDelCajero.de(listOf(EstadoDelTurno.ABIERTO)))
        assertEquals(SituacionDelCajero.ABIERTO, SituacionDelCajero.de(listOf(EstadoDelTurno.CERRADO, EstadoDelTurno.ABIERTO)))
        assertEquals(SituacionDelCajero.CERRADO, SituacionDelCajero.de(listOf(EstadoDelTurno.CERRADO, EstadoDelTurno.CERRADO)))
        assertEquals(SituacionDelCajero.VARIOS_ABIERTOS, SituacionDelCajero.de(listOf(EstadoDelTurno.ABIERTO, EstadoDelTurno.ABIERTO)))
    }
}
