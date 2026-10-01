"""Pruebas de import_cajas.py contra un FakeCore: las reglas de ImportarCajas de caja, fila por fila.

Correr: cd model && python3 -m unittest -v
"""
import io
import os
import tempfile
import unittest
from contextlib import redirect_stderr, redirect_stdout

import import_cajas as ic
from fake_core import FakeCore

HERE = os.path.dirname(os.path.abspath(__file__))
EJEMPLO = os.path.join(HERE, "data", "ejemplos", "cajas.csv")
CABECERA = "codigo,nombre,serie,codigoArea,nombreArea\n"


class ImportCajasTestCase(unittest.TestCase):
    def setUp(self):
        self.core = FakeCore()
        self.addCleanup(self.core.stop)
        carpeta = tempfile.TemporaryDirectory()
        self.addCleanup(carpeta.cleanup)
        self.carpeta = carpeta.name

    def csv(self, *filas, cabecera=CABECERA):
        ruta = os.path.join(self.carpeta, "cajas.csv")
        with open(ruta, "w", encoding="utf-8") as f:
            f.write(cabecera + "".join(fila + "\n" for fila in filas))
        return ruta

    def run_main(self, archivo, *extra):
        out, err = io.StringIO(), io.StringIO()
        with redirect_stdout(out), redirect_stderr(err):
            code = ic.main(["--core", self.core.base_url, "--archivo", archivo, *extra])
        return code, out.getvalue(), err.getvalue()

    def writes(self):
        return [(m, p) for m, p, _, _ in self.core.requests if m in ("POST", "PUT", "DELETE") and p != "/api/auth/login"]

    def cajas(self):
        return {r["attributes"]["codigo"]: r["attributes"] for r in self.core.records.get("caja", [])}

    def areas(self):
        return {r["attributes"]["codigo"]: r for r in self.core.records.get("area", [])}


class ArchivoDeEjemploTests(ImportCajasTestCase):
    def test_el_archivo_de_ejemplo_se_carga_entero(self):
        code, out, err = self.run_main(EJEMPLO)
        self.assertEqual(code, 0, err)
        self.assertEqual(sorted(self.cajas()), ["C-01", "C-02", "C-10", "C-11", "C-12"])
        self.assertEqual(sorted(self.areas()), ["A-10", "A-11", "A-12"])
        self.assertIn("cajas: 5 creadas, 0 rechazadas", out)
        self.assertIn("áreas: 3 creadas, 0 reutilizadas", out)
        self.assertNotIn("rechazada línea", out)

    def test_cada_caja_queda_con_su_area_o_sin_ella(self):
        self.run_main(EJEMPLO)
        areas = self.areas()
        cajas = self.cajas()
        self.assertEqual(cajas["C-10"], {"codigo": "C-10", "nombre": "Caja de tasas - Tramite documentario", "serie": "010",
                                         "activa": True, "area": areas["A-10"]["id"]})
        self.assertEqual(areas["A-10"]["attributes"], {"codigo": "A-10", "nombre": "TRAMITE DOCUMENTARIO", "activa": True})
        # las cajas tributarias no tienen área: ni siquiera la clave
        self.assertEqual(cajas["C-01"], {"codigo": "C-01", "nombre": "Caja tributaria 1", "serie": "001", "activa": True})

    def test_dry_run_informa_5_cajas_y_3_areas_y_no_escribe(self):
        code, out, err = self.run_main(EJEMPLO, "--dry-run")
        self.assertEqual(code, 0, err)
        self.assertEqual(self.writes(), [])
        self.assertEqual(self.core.records, {})
        self.assertIn("cajas: 5 por crear, 0 rechazadas", out)
        self.assertIn("áreas: 3 por crear, 0 reutilizadas", out)
        self.assertIn("dry run: no se escribió nada", out)

    def test_una_segunda_corrida_rechaza_cada_fila_por_repetida_y_no_escribe(self):
        self.run_main(EJEMPLO)
        self.core.requests.clear()
        code, out, err = self.run_main(EJEMPLO)
        self.assertEqual(code, 0, err)
        self.assertEqual(self.writes(), [])
        self.assertIn("cajas: 0 creadas, 5 rechazadas", out)
        self.assertEqual(out.count("rechazada línea"), 5)


class AreaTests(ImportCajasTestCase):
    def test_un_area_que_ya_existe_se_reutiliza_y_no_se_renombra(self):
        existente = self.core.add_record("area", {"codigo": "A-10", "nombre": "NOMBRE VIGENTE", "activa": True})
        code, out, err = self.run_main(self.csv("C-10,Caja de tasas,010,A-10,OTRO NOMBRE"))
        self.assertEqual(code, 0, err)
        self.assertEqual(len(self.core.records["area"]), 1)
        self.assertEqual(self.core.records["area"][0]["attributes"]["nombre"], "NOMBRE VIGENTE")
        self.assertEqual(self.cajas()["C-10"]["area"], existente["id"])
        self.assertIn("áreas: 0 creadas, 1 reutilizadas", out)

    def test_dos_cajas_del_mismo_archivo_comparten_el_area_nueva(self):
        code, out, err = self.run_main(self.csv("C-10,Una,010,A-10,TRAMITE", "C-11,Otra,011,A-10,"))
        self.assertEqual(code, 0, err)
        self.assertEqual(len(self.core.records["area"]), 1)
        self.assertEqual(self.cajas()["C-10"]["area"], self.cajas()["C-11"]["area"])
        self.assertIn("áreas: 1 creadas, 1 reutilizadas", out)

    def test_el_codigo_del_area_se_normaliza_a_mayusculas(self):
        self.core.add_record("area", {"codigo": "A-10", "nombre": "TRAMITE", "activa": True})
        self.run_main(self.csv("C-10,Una,010, a-10 ,"))
        self.assertEqual(len(self.core.records["area"]), 1)
        self.assertIn("area", self.cajas()["C-10"])

    def test_un_area_sin_nombre_se_rechaza_con_su_linea(self):
        code, out, err = self.run_main(self.csv("C-60,Caja,060,A-60,", "C-61,Caja,061,,"))
        self.assertEqual(code, 0, err)
        self.assertEqual(list(self.cajas()), ["C-61"])
        self.assertNotIn("area", self.core.records)
        self.assertIn("rechazada línea 2: el área 'A-60' no está registrada y la fila no dice cómo se llama", out)

    def test_una_caja_sin_codigo_de_area_entra_sin_area(self):
        self.run_main(self.csv("C-01,Caja tributaria,001,,"))
        self.assertEqual(self.cajas()["C-01"], {"codigo": "C-01", "nombre": "Caja tributaria", "serie": "001", "activa": True})

    def test_un_area_solo_se_crea_si_la_caja_se_acepta(self):
        self.core.add_record("caja", {"codigo": "C-10", "nombre": "Vieja", "serie": "010", "activa": True})
        self.run_main(self.csv("C-10,Repetida,011,A-99,UN AREA"))
        self.assertNotIn("area", self.core.records)


class RechazoPorFilaTests(ImportCajasTestCase):
    def test_una_fila_incompleta_se_rechaza_y_la_siguiente_entra(self):
        code, out, err = self.run_main(self.csv(",Sin codigo,001,,", "C-02,,002,,", "C-03,Sin serie,,,", "C-04,Completa,004,,"))
        self.assertEqual(code, 0, err)
        self.assertEqual(list(self.cajas()), ["C-04"])
        self.assertIn("rechazada línea 2: falta el código", out)
        self.assertIn("rechazada línea 3: falta el nombre", out)
        self.assertIn("rechazada línea 4: falta la serie", out)
        self.assertIn("cajas: 1 creadas, 3 rechazadas", out)

    def test_una_fila_con_menos_de_tres_columnas_se_rechaza(self):
        code, out, err = self.run_main(self.csv("C-02,Corta", "C-04,Completa,004"))
        self.assertEqual(list(self.cajas()), ["C-04"])
        self.assertIn("rechazada línea 2: la fila trae 2 columna(s) y hacen falta al menos 3: codigo, nombre, serie", out)

    def test_un_codigo_repetido_se_rechaza_sin_llevarse_a_la_siguiente(self):
        self.core.add_record("caja", {"codigo": "C-40", "nombre": "Ya estaba", "serie": "040", "activa": True})
        code, out, err = self.run_main(self.csv("C-40,Repetida,041,,", "C-41,Nueva,042,,"))
        self.assertEqual(code, 0, err)
        self.assertEqual(sorted(self.cajas()), ["C-40", "C-41"])
        self.assertEqual(self.cajas()["C-40"]["nombre"], "Ya estaba")
        self.assertIn("rechazada línea 2: ya hay una caja con el código 'C-40' o con la serie '041'", out)
        # se comprueba antes de escribir: core contesta 500 a un duplicado
        self.assertEqual(self.writes(), [("POST", "/api/objects/caja/records")])

    def test_una_serie_repetida_se_rechaza(self):
        self.core.add_record("caja", {"codigo": "C-50", "nombre": "Ya estaba", "serie": "050", "activa": True})
        code, out, err = self.run_main(self.csv("C-51,Misma serie,050,,", "C-52,Otra,052,,"))
        self.assertEqual(sorted(self.cajas()), ["C-50", "C-52"])
        self.assertIn("rechazada línea 2: ya hay una caja con el código 'C-51' o con la serie '050'", out)

    def test_dos_filas_del_mismo_archivo_no_repiten_codigo_ni_serie(self):
        code, out, err = self.run_main(self.csv("C-70,Primera,070,,", "C-70,Mismo codigo,071,,", "C-72,Misma serie,070,,"))
        self.assertEqual(list(self.cajas()), ["C-70"])
        self.assertIn("rechazada línea 3", out)
        self.assertIn("rechazada línea 4", out)

    def test_el_dry_run_ve_los_repetidos_del_archivo_y_los_de_core(self):
        self.core.add_record("caja", {"codigo": "C-40", "nombre": "Ya estaba", "serie": "040", "activa": True})
        code, out, err = self.run_main(self.csv("C-40,Repetida,041,,", "C-41,Nueva,042,,", "C-41,Otra vez,043,,"), "--dry-run")
        self.assertEqual(code, 0, err)
        self.assertIn("cajas: 1 por crear, 2 rechazadas", out)
        self.assertEqual(self.writes(), [])

    def test_la_serie_se_guarda_en_mayusculas_y_no_pasa_de_cinco_caracteres(self):
        code, out, err = self.run_main(self.csv("C-80,Con minusculas,ab1,,", "C-81,Muy larga,ABCDEF,,", "C-82,De cinco,ABCDE,,"))
        self.assertEqual({c: a["serie"] for c, a in self.cajas().items()}, {"C-80": "AB1", "C-82": "ABCDE"})
        self.assertIn("rechazada línea 3: la serie 'ABCDEF' tiene 6 caracteres y debe tener de 1 a 5", out)

    def test_el_codigo_se_guarda_en_mayusculas_y_sin_espacios(self):
        self.run_main(self.csv(" c-90 ,Caja,090,,"))
        self.assertEqual(list(self.cajas()), ["C-90"])

    def test_las_cajas_nuevas_entran_activas(self):
        self.run_main(self.csv("C-01,Una,001,,", "C-02,Otra,002,A-02,AREA"))
        self.assertTrue(all(c["activa"] is True for c in self.cajas().values()))
        self.assertTrue(all(r["attributes"]["activa"] is True for r in self.core.records["area"]))

    def test_una_linea_en_blanco_no_es_una_fila(self):
        code, out, err = self.run_main(self.csv("C-01,Una,001,,", "", "C-02,Otra,002,,"))
        self.assertEqual(sorted(self.cajas()), ["C-01", "C-02"])
        self.assertIn("cajas: 2 creadas, 0 rechazadas", out)


class CoreTests(ImportCajasTestCase):
    def test_sale_1_si_core_rechaza_una_caja_y_sigue_con_las_demas(self):
        self.core.fail_on_record = "caja"
        code, out, err = self.run_main(self.csv("C-01,Una,001,,", "C-02,Otra,002,,"))
        self.assertEqual(code, 1)
        self.assertEqual(len([w for w in self.writes() if w[1].endswith("/caja/records")]), 2)
        self.assertIn("core rechazó la línea 2", err)
        self.assertIn("core rechazó la línea 3", err)

    def test_sale_1_si_core_no_responde(self):
        self.core.stop()
        self.core = FakeCore()
        self.addCleanup(self.core.stop)
        self.core.login_response = {}
        code, out, err = self.run_main(EJEMPLO)
        self.assertEqual(code, 1)
        self.assertIn("error", err)

    def test_falta_una_columna_obligatoria_en_la_cabecera(self):
        code, out, err = self.run_main(self.csv("C-01,Una", cabecera="codigo,nombre\n"))
        self.assertEqual(code, 1)
        self.assertIn("serie", err)
        self.assertEqual(self.core.requests, [])

    def test_no_usa_float(self):
        with open(os.path.join(HERE, "import_cajas.py"), encoding="utf-8") as f:
            self.assertNotIn("float(", f.read())


if __name__ == "__main__":
    unittest.main()
