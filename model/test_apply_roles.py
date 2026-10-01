"""Pruebas de roles.json y de apply_roles.py contra un FakeCore.

Correr: cd model && python3 -m unittest -v
"""
import copy
import io
import json
import os
import tempfile
import unittest
from contextlib import redirect_stderr, redirect_stdout

import apply_roles
from fake_core import FakeCore

HERE = os.path.dirname(os.path.abspath(__file__))
ROLES_PATH = os.path.join(HERE, "roles.json")
MODEL_PATH = os.path.join(HERE, "model.json")
ROLES = ["SISTEMA_ORIGEN", "CAJERO", "SUPERVISOR_CAJA", "TESORERIA"]


def load(path):
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def permisos(rol):
    """Los permisos de un rol de roles.json como pares (objeto, acción)."""
    return {(objeto, accion) for objeto, acciones in rol["permisos"].items() for accion in acciones}


class RolesJsonTests(unittest.TestCase):
    def setUp(self):
        self.roles = {r["name"]: r for r in load(ROLES_PATH)["roles"]}
        self.objetos = [o["name"] for o in load(MODEL_PATH)["objects"]]

    def test_declara_los_cuatro_roles_en_orden(self):
        self.assertEqual(list(self.roles), ROLES)

    def test_valida_contra_el_modelo(self):
        self.assertEqual(apply_roles.validate(load(ROLES_PATH), load(MODEL_PATH)), [])

    def test_el_sistema_de_origen_lee_y_da_de_alta_ordenes_y_nada_mas(self):
        self.assertEqual(permisos(self.roles["SISTEMA_ORIGEN"]), {("orden_de_cobro", "READ"), ("orden_de_cobro", "CREATE")})

    def test_el_cajero_lee_areas_cajas_tasas_y_ordenes(self):
        self.assertEqual(permisos(self.roles["CAJERO"]),
                         {("area", "READ"), ("caja", "READ"), ("tasa", "READ"), ("orden_de_cobro", "READ")})

    def test_el_supervisor_de_caja_puede_lo_mismo_que_el_cajero(self):
        self.assertEqual(permisos(self.roles["SUPERVISOR_CAJA"]), permisos(self.roles["CAJERO"]))

    def test_tesoreria_lee_cada_objeto_del_modelo(self):
        # un objeto nuevo en model.json sin su READ para tesorería hace fallar esta prueba
        self.assertEqual(permisos(self.roles["TESORERIA"]), {(o, "READ") for o in self.objetos})


class ValidationTests(unittest.TestCase):
    def setUp(self):
        self.roles = load(ROLES_PATH)
        self.model = load(MODEL_PATH)

    def mutate(self, fn):
        roles = copy.deepcopy(self.roles)
        fn(roles)
        return apply_roles.validate(roles, self.model)

    def test_un_objeto_que_el_modelo_no_tiene_se_rechaza(self):
        errors = self.mutate(lambda r: r["roles"][0]["permisos"].update({"recibo": ["READ"]}))
        self.assertTrue(any("recibo" in e for e in errors), errors)

    def test_una_accion_que_core_no_conoce_se_rechaza(self):
        errors = self.mutate(lambda r: r["roles"][0]["permisos"].update({"caja": ["MANAGE_METADATA"]}))
        self.assertTrue(any("MANAGE_METADATA" in e for e in errors), errors)

    def test_un_nombre_que_core_no_acepta_se_rechaza(self):
        errors = self.mutate(lambda r: r["roles"][0].update({"name": "cajero"}))
        self.assertTrue(any("cajero" in e for e in errors), errors)

    def test_un_rol_repetido_se_rechaza(self):
        errors = self.mutate(lambda r: r["roles"].append(copy.deepcopy(r["roles"][0])))
        self.assertTrue(any("duplicate" in e for e in errors), errors)


class ApplyRolesTestCase(unittest.TestCase):
    def setUp(self):
        self.core = FakeCore()
        self.addCleanup(self.core.stop)

    def run_cli(self, extra=(), roles_path=ROLES_PATH):
        args = ["--roles", roles_path, "--core", self.core.base_url, "--email", "admin@wasichai.local",
                "--password", "admin", *extra]
        out, err = io.StringIO(), io.StringIO()
        with redirect_stdout(out), redirect_stderr(err):
            code = apply_roles.main(args)
        return code, out.getvalue(), err.getvalue()

    def writes(self):
        return [(m, p, b) for m, p, _, b in self.core.requests if m in ("POST", "PUT") and p != "/api/auth/login"]


class ApplyRolesTests(ApplyRolesTestCase):
    def test_dry_run_no_llama_a_core_e_imprime_lo_que_mandaria(self):
        code, out, err = self.run_cli(["--dry-run"])
        self.assertEqual(code, 0, err)
        self.assertEqual(self.core.requests, [])
        self.assertEqual(out.count("# POST /api/roles\n"), 4)
        self.assertEqual(out.count("/permissions\n"), 4)

    def test_crea_cada_rol_con_sus_permisos(self):
        code, out, err = self.run_cli()
        self.assertEqual(code, 0, err)
        posts = [b["name"] for m, p, b in self.writes() if m == "POST"]
        self.assertEqual(posts, ROLES)
        puts = {p: b for m, p, b in self.writes() if m == "PUT"}
        self.assertEqual(puts["/api/roles/SISTEMA_ORIGEN/permissions"], {"permissions": [
            {"objectName": "orden_de_cobro", "action": "READ"}, {"objectName": "orden_de_cobro", "action": "CREATE"}]})
        self.assertEqual(len(puts), 4)
        for method, path, auth, body in self.core.requests:
            self.assertEqual(auth, None if path == "/api/auth/login" else "Bearer t")
        self.assertIn("done: 4 created, 0 updated, 0 skipped", out)

    def test_la_segunda_corrida_no_escribe_nada(self):
        self.run_cli()
        self.core.requests.clear()
        code, out, err = self.run_cli()
        self.assertEqual(code, 0, err)
        self.assertEqual(self.writes(), [])
        self.assertIn("done: 0 created, 0 updated, 4 skipped", out)

    def test_sincroniza_los_permisos_de_un_rol_que_ya_existe(self):
        # un CAJERO que no lee tasas y al que en el admin le dieron UPDATE sobre caja: queda como dice roles.json
        self.core.roles["CAJERO"] = {"name": "CAJERO", "label": "Cajero", "permissions": [
            {"objectName": "area", "action": "READ", "allowed": True},
            {"objectName": "caja", "action": "READ", "allowed": True},
            {"objectName": "caja", "action": "UPDATE", "allowed": True},
            {"objectName": "orden_de_cobro", "action": "READ", "allowed": True}]}
        code, out, err = self.run_cli()
        self.assertEqual(code, 0, err)
        self.assertNotIn(("POST", "/api/roles"), [(m, p) for m, p, b in self.writes() if b.get("name") == "CAJERO"])
        cajero = [b for m, p, b in self.writes() if p == "/api/roles/CAJERO/permissions"]
        self.assertEqual(cajero, [{"permissions": [
            {"objectName": "area", "action": "READ"}, {"objectName": "caja", "action": "READ"},
            {"objectName": "tasa", "action": "READ"}, {"objectName": "orden_de_cobro", "action": "READ"}]}])
        self.assertIn("update role CAJERO (permissions)", out)
        self.assertIn("done: 3 created, 1 updated, 0 skipped", out)

    def test_rehace_la_etiqueta_que_difiere(self):
        self.run_cli()
        self.core.roles["TESORERIA"]["label"] = "Otra"
        self.core.requests.clear()
        code, out, err = self.run_cli()
        self.assertEqual(code, 0, err)
        self.assertEqual(self.writes(), [("PUT", "/api/roles/TESORERIA", {"label": "Tesorería"})])
        self.assertIn("done: 0 created, 1 updated, 3 skipped", out)

    def test_un_rechazo_de_core_detiene_la_corrida(self):
        self.core.fail_on_role = "CAJERO"
        code, out, err = self.run_cli()
        self.assertEqual(code, 1)
        self.assertIn("error POST /api/roles", err)
        self.assertEqual([b["name"] for m, p, b in self.writes() if m == "POST"], ["SISTEMA_ORIGEN", "CAJERO"])

    def test_un_roles_json_invalido_no_llama_a_core(self):
        roles = load(ROLES_PATH)
        roles["roles"][0]["permisos"]["arqueo"] = ["READ"]
        with tempfile.TemporaryDirectory() as carpeta:
            path = os.path.join(carpeta, "roles.json")
            with open(path, "w", encoding="utf-8") as f:
                json.dump(roles, f)
            code, out, err = self.run_cli(roles_path=path)
        self.assertEqual(code, 2)
        self.assertIn("arqueo", out)
        self.assertEqual(self.core.requests, [])

    def test_un_archivo_que_falta_o_que_no_es_json_sale_con_2_sin_traza(self):
        with tempfile.TemporaryDirectory() as carpeta:
            roto = os.path.join(carpeta, "roto.json")
            with open(roto, "w", encoding="utf-8") as f:
                f.write("{ no es json")
            falta = os.path.join(carpeta, "no-existe.json")
            for extra, nombre in (([], falta), ([], roto), (["--model", falta], falta), (["--model", roto], roto)):
                roles = nombre if not extra else ROLES_PATH
                code, out, err = self.run_cli(extra, roles_path=roles)
                self.assertEqual(code, 2, (extra, nombre, out, err))
                self.assertIn(nombre, err)
                self.assertNotIn("Traceback", err)
        self.assertEqual(self.core.requests, [])


if __name__ == "__main__":
    unittest.main()
