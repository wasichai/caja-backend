package caja.comun

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import wasichai.core.common.ValidationException

// regla 10: toda escritura dice por qué, con al menos 5 caracteres que no sean espacios y a lo sumo 500
class ObservacionTest {
    @Test
    fun `se guarda recortada`() {
        assertEquals("alta desde rentas", Observacion.de("  alta desde rentas \n").texto)
    }

    @Test
    fun `cinco caracteres bastan y quinientos caben`() {
        assertEquals("abcde", Observacion.de("abcde").texto)
        assertEquals(500, Observacion.de("x".repeat(500)).texto.length)
    }

    @Test
    fun `sin observacion no se guarda`() {
        listOf(null, "", "     ").forEach { assertEquals("observacion", rechazada(it), it.toString()) }
    }

    @Test
    fun `cuatro caracteres no explican nada, aunque los rodeen espacios`() {
        assertEquals("observacion", rechazada("abcd"))
        assertEquals("observacion", rechazada("   abcd    "))
    }

    @Test
    fun `quinientos uno no caben`() {
        assertEquals("observacion", rechazada("x".repeat(501)))
    }

    @Test
    fun `el maximo se cuenta en code points, como lo cuenta la plataforma`() {
        // un emoji son dos chars y un code point: 500 caben, 501 no
        assertEquals(500, Observacion.de("\uD83D\uDE00".repeat(500)).texto.codePointCount(0, 1000))
        assertEquals("observacion", rechazada("\uD83D\uDE00".repeat(501)))
    }

    @Test
    fun `un caracter de control se rechaza, salvo tabulador y saltos de linea`() {
        listOf("abc\u0000def", "abc\u0007def", "abc\u001Bdef", "abc\u007Fdef", "abc\u0085def").forEach {
            assertEquals("observacion", rechazada(it), it)
        }
        assertEquals("uno\tdos\ntres\r\ncuatro", Observacion.de("uno\tdos\ntres\r\ncuatro").texto)
    }

    private fun rechazada(texto: String?): String =
        assertThrows<ValidationException> { Observacion.de(texto) }
            .violations
            .single()
            .field
}
