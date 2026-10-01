"""Pruebas de import_tasas.py contra un FakeCore, fila por fila.

Las tarifas de aquí son inventadas y solo viven en estas pruebas: las cifras del TUPA salen de la normativa
verificada, no del repositorio.

Correr: cd model && python3 -m unittest -v
"""
import io
import os
import tempfile
import unittest
from contextlib import redirect_stderr, redirect_stdout

import import_tasas as it
from fake_core import FakeCore

HERE = os.path.dirname(os.path.abspath(__file__))
CABECERA = "codigo,descripcion,codigoArea,partidaPresupuestal,importe,vigenciaDesde,vigenciaHasta,documentoFuente\n"
FILA = "T-01,Copia certificada,A-10,1.3.2.1.1,12.50,2026-01-01,2026-12-31,Ordenanza de prueba 001"


def fila(**cambios):
    """FILA con las celdas que se piden cambiadas, por nombre de columna."""
    columnas = ["codigo", "descripcion", "codigoArea", "partidaPresupuestal", "importe", "vigenciaDesde", "vigenciaHasta",
                "documentoFuente"]
    celdas = dict(zip(columnas, FILA.split(",")))
    celdas.update(cambios)
    return ",".join(celdas[c] for c in columnas)


class ImportTasasTestCase(unittest.TestCase):
    def setUp(self):
        self.core = FakeCore()
        self.addCleanup(self.core.stop)
        self.area = self.core.add_record("area", {"codigo": "A-10", "nombre": "TRAMITE DOCUMENTARIO", "activa": True})
        carpeta = tempfile.TemporaryDirectory()
        self.addCleanup(carpeta.cleanup)
        self.carpeta = carpeta.name

    def csv(self, *filas, cabecera=CABECERA):
        ruta = os.path.join(self.carpeta, "tasas.csv")
        with open(ruta, "w", encoding="utf-8") as f:
            f.write(cabecera + "".join(f_ + "\n" for f_ in filas))
        return ruta

    def run_main(self, archivo, *extra):
        out, err = io.StringIO(), io.StringIO()
        with redirect_stdout(out), redirect_stderr(err):
            code = it.main(["--core", self.core.base_url, "--archivo", archivo, *extra])
        return code, out.getvalue(), err.getvalue()

    def writes(self):
        return [(m, p) for m, p, _, _ in self.core.requests if m in ("POST", "PUT", "DELETE") and p != "/api/auth/login"]

    def tasas(self):
        return [r["attributes"] for r in self.core.records.get("tasa", [])]


class CargaTests(ImportTasasTestCase):
    def test_una_fila_valida_entra_con_su_area_y_su_clave_de_vigencia(self):
        code, out, err = self.run_main(self.csv(FILA))
        self.assertEqual(code, 0, err)
        self.assertEqual(self.tasas(), [{
            "codigo": "T-01", "descripcion": "Copia certificada", "partida_presupuestal": "1.3.2.1.1", "importe": "12.50",
            "vigencia_desde": "2026-01-01", "vigencia_hasta": "2026-12-31", "documento_fuente": "Ordenanza de prueba 001",
            "clave_vigencia": "T-01|2026-01-01", "area": self.area["id"]}])
        self.assertIn("tasas: 1 creadas, 0 rechazadas", out)

    def test_la_vigencia_hasta_puede_ir_vacia_y_entonces_no_se_manda(self):
        self.run_main(self.csv(fila(vigenciaHasta="")))
        self.assertNotIn("vigencia_hasta", self.tasas()[0])

    def test_un_importe_entero_se_guarda_con_dos_decimales(self):
        self.run_main(self.csv(fila(importe="8"), fila(codigo="T-02", importe="0"), fila(codigo="T-03", importe="3.5")))
        self.assertEqual([t["importe"] for t in self.tasas()], ["8.00", "0.00", "3.50"])

    def test_el_codigo_de_area_se_busca_en_mayusculas(self):
        self.run_main(self.csv(fila(codigoArea="a-10")))
        self.assertEqual(self.tasas()[0]["area"], self.area["id"])

    def test_la_misma_tasa_en_otra_vigencia_entra(self):
        code, out, err = self.run_main(self.csv(FILA, fila(vigenciaDesde="2027-01-01", vigenciaHasta="", importe="13.00")))
        self.assertEqual(code, 0, err)
        self.assertEqual([t["clave_vigencia"] for t in self.tasas()], ["T-01|2026-01-01", "T-01|2027-01-01"])

    def test_dry_run_no_escribe(self):
        code, out, err = self.run_main(self.csv(FILA, fila(codigo="T-02")), "--dry-run")
        self.assertEqual(code, 0, err)
        self.assertEqual(self.writes(), [])
        self.assertIn("tasas: 2 por crear, 0 rechazadas", out)
        self.assertIn("dry run: no se escribió nada", out)


class RechazoPorFilaTests(ImportTasasTestCase):
    def assert_rechaza(self, fila_mala, motivo, linea=2):
        """La fila mala se rechaza con su línea y su motivo, y la buena que le sigue entra."""
        code, out, err = self.run_main(self.csv(fila_mala, fila(codigo="T-99")))
        self.assertEqual(code, 0, err)
        self.assertEqual([t["codigo"] for t in self.tasas()], ["T-99"])
        self.assertIn(f"rechazada línea {linea}: {motivo}", out)
        self.assertIn("tasas: 1 creadas, 1 rechazadas", out)

    def test_un_area_que_no_existe_se_rechaza(self):
        self.assert_rechaza(fila(codigoArea="A-77"), "el área 'A-77' no existe")

    def test_una_fila_sin_area_se_rechaza(self):
        self.assert_rechaza(fila(codigoArea=""), "falta el código del área")

    def test_las_celdas_obligatorias_no_pueden_faltar(self):
        for columna, motivo in [("codigo", "falta el código"), ("descripcion", "falta la descripción"),
                                ("partidaPresupuestal", "falta la partida presupuestal"), ("importe", "falta el importe"),
                                ("vigenciaDesde", "falta la vigencia desde"), ("documentoFuente", "falta el documento fuente")]:
            with self.subTest(columna):
                self.setUp()
                self.assert_rechaza(fila(**{columna: ""}), motivo)

    def test_un_importe_con_mas_de_dos_decimales_se_rechaza(self):
        self.assert_rechaza(fila(importe="12.505"), "el importe '12.505' no es un decimal de hasta 2 decimales")

    def test_un_importe_negativo_se_rechaza(self):
        self.assert_rechaza(fila(importe="-1.00"), "el importe '-1.00' no es un decimal de hasta 2 decimales")

    def test_un_importe_que_no_es_un_numero_se_rechaza(self):
        for malo in ("abc", "1,50", "1e2", "NaN", "Infinity", "1.", ".5"):
            with self.subTest(malo):
                self.setUp()
                # entre comillas, para que la coma del importe no corte la celda
                self.assert_rechaza(fila(importe=f'"{malo}"'), f"el importe '{malo}' no es un decimal de hasta 2 decimales")

    def test_un_importe_enorme_rechaza_esa_fila_y_no_la_corrida(self):
        enorme = "9" * 40
        code, out, err = self.run_main(self.csv(fila(importe=enorme), fila(codigo="T-02")))
        self.assertEqual(code, 0, err)
        self.assertIn(f"rechazada línea 2: el importe '{enorme}' es demasiado grande", out)
        self.assertEqual([t["codigo"] for t in self.tasas()], ["T-02"])

    def test_un_importe_con_digitos_que_no_son_ascii_se_rechaza(self):
        # \d acepta dígitos de cualquier alfabeto; un importe se escribe con 0-9
        self.assert_rechaza(fila(importe="١٢"), "el importe '١٢' no es un decimal de hasta 2 decimales")

    def test_una_fecha_con_digitos_que_no_son_ascii_se_rechaza(self):
        self.assert_rechaza(fila(vigenciaDesde="٢٠٢٦-01-01"), "la vigencia desde '٢٠٢٦-01-01' no es una fecha AAAA-MM-DD")

    def test_una_vigencia_hasta_anterior_a_la_desde_se_rechaza(self):
        self.assert_rechaza(fila(vigenciaDesde="2026-06-01", vigenciaHasta="2026-05-31"),
                            "la vigencia hasta 2026-05-31 es anterior a la vigencia desde 2026-06-01")

    def test_una_vigencia_hasta_igual_a_la_desde_entra(self):
        code, out, err = self.run_main(self.csv(fila(vigenciaDesde="2026-06-01", vigenciaHasta="2026-06-01")))
        self.assertEqual(len(self.tasas()), 1)

    def test_una_fecha_mal_escrita_se_rechaza(self):
        self.assert_rechaza(fila(vigenciaDesde="01/01/2026"), "la vigencia desde '01/01/2026' no es una fecha AAAA-MM-DD")
        self.setUp()
        self.assert_rechaza(fila(vigenciaHasta="2026-02-30"), "la vigencia hasta '2026-02-30' no es una fecha AAAA-MM-DD")

    def test_una_clave_de_vigencia_que_core_ya_tiene_se_rechaza_sin_escribir_esa_fila(self):
        self.core.add_record("tasa", {"codigo": "T-01", "clave_vigencia": "T-01|2026-01-01"})
        code, out, err = self.run_main(self.csv(FILA, fila(codigo="T-02")))
        self.assertEqual(code, 0, err)
        self.assertIn("rechazada línea 2: ya hay una tasa con la clave de vigencia 'T-01|2026-01-01'", out)
        # se comprueba antes de escribir: core contesta 500 a un duplicado
        self.assertEqual(self.writes(), [("POST", "/api/objects/tasa/records")])

    def test_dos_filas_del_mismo_archivo_no_repiten_clave_de_vigencia(self):
        code, out, err = self.run_main(self.csv(FILA, fila(importe="99.00")))
        self.assertEqual(len(self.tasas()), 1)
        self.assertIn("rechazada línea 3: ya hay una tasa con la clave de vigencia 'T-01|2026-01-01'", out)

    def test_una_fila_con_menos_columnas_que_la_cabecera_se_rechaza(self):
        code, out, err = self.run_main(self.csv("T-01,Corta,A-10", fila(codigo="T-99")))
        self.assertEqual([t["codigo"] for t in self.tasas()], ["T-99"])
        self.assertIn("rechazada línea 2: la fila trae 3 columna(s) y hacen falta 8", out)


class CoreTests(ImportTasasTestCase):
    def test_sale_1_si_core_rechaza_una_tasa_y_sigue_con_las_demas(self):
        self.core.fail_on_record = "tasa"
        code, out, err = self.run_main(self.csv(FILA, fila(codigo="T-02")))
        self.assertEqual(code, 1)
        self.assertEqual(len(self.writes()), 2)
        self.assertIn("core rechazó la línea 2", err)
        self.assertIn("core rechazó la línea 3", err)

    def test_falta_una_columna_obligatoria_en_la_cabecera(self):
        code, out, err = self.run_main(self.csv("T-01,Copia", cabecera="codigo,descripcion\n"))
        self.assertEqual(code, 1)
        self.assertIn("importe", err)
        self.assertEqual(self.core.requests, [])

    def test_no_usa_float(self):
        with open(os.path.join(HERE, "import_tasas.py"), encoding="utf-8") as f:
            self.assertNotIn("float(", f.read())

    def test_no_hay_un_archivo_de_tarifas_en_el_repositorio(self):
        # las cifras del TUPA salen de la normativa verificada: aquí no se publica ninguna
        self.assertEqual(sorted(os.listdir(os.path.join(HERE, "data", "ejemplos"))), ["cajas.csv"])


if __name__ == "__main__":
    unittest.main()
