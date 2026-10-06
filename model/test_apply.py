"""End-to-end tests for apply.py's CLI against a fake Core.

Run: cd model && python3 -m unittest -v
"""
import io
import json
import os
import socket
import tempfile
import unittest
from contextlib import redirect_stdout, redirect_stderr

import apply
from fake_core import FakeCore

MODEL_PATH = os.path.join(os.path.dirname(__file__), "model.json")

OBJECTS = 13
RELATIONSHIPS = 18
OBJECT_ORDER = ["area", "caja", "tasa", "turno", "recibo", "orden_de_cobro", "linea_recibo", "pago_evento",
                "anulacion_recibo", "reimpresion_recibo", "cierre_turno", "cierre_turno_linea", "reversion_cierre"]
RELATIONSHIP_ORDER = ["caja_area", "tasa_area", "turno_caja", "recibo_caja", "recibo_turno", "orden_recibo",
                      "linea_recibo_recibo", "linea_recibo_orden", "linea_recibo_tasa", "pago_evento_recibo",
                      "pago_evento_turno", "anulacion_recibo_recibo", "anulacion_recibo_caja", "anulacion_recibo_turno",
                      "reimpresion_recibo_recibo", "cierre_turno_turno", "cierre_turno_linea_cierre_turno",
                      "reversion_cierre_turno"]
# what apply.py prints: every object and relationship, and all but the one a test touches
TOTAL = OBJECTS + RELATIONSHIPS
OTHERS = TOTAL - 1


def load_model():
    with open(MODEL_PATH, encoding="utf-8") as f:
        return json.load(f)


def model_flags(model):
    """What GET /api/objects tells of each object's write rules and constraints once model.json is applied (Core leaves
    uniqueConstraints out when an object has none)."""
    return {o["name"]: {"apiOnly": o.get("apiOnly", False), "appendOnly": o.get("appendOnly", False),
                        **({"uniqueConstraints": o["uniqueConstraints"]} if o.get("uniqueConstraints") else {})}
            for o in model["objects"]}


APPEND_ONLY = ["turno", "recibo", "linea_recibo", "anulacion_recibo", "reimpresion_recibo", "cierre_turno",
               "cierre_turno_linea", "reversion_cierre"]
API_ONLY = APPEND_ONLY + ["orden_de_cobro", "pago_evento"]
# the objects whose constraints name a relationship's field: they wait for phase 3 (orden_de_cobro and tasa go in the POST)
DROP_PUTS = ["tasa", "turno", "recibo", "orden_de_cobro", "linea_recibo", "anulacion_recibo", "reimpresion_recibo",
             "cierre_turno", "cierre_turno_linea", "reversion_cierre"]
WAITING = ["turno", "cierre_turno", "cierre_turno_linea", "reversion_cierre"]


def core_fields(model, obj_name, drop=(), options=None):
    """What Core answers for an object that has model.json's fields, minus `drop`; `options` overrides enum options."""
    obj = next(o for o in model["objects"] if o["name"] == obj_name)
    fields = []
    for f in obj["fields"]:
        if f["name"] in drop:
            continue
        field = {"name": f["name"], "label": f["label"], "type": f["type"]}
        if f["type"] == "ENUM":
            field["enumOptions"] = (options or {}).get(f["name"], model["enums"][f["enum"]])
        fields.append(field)
    # the relationships' fields too: Core lists them as RELATION, with the unique the model gives them
    fields += [{"name": r["fieldName"], "label": r["label"], "type": "RELATION", "unique": r.get("unique", False)}
               for r in model["relationships"] if r["source"] == obj_name]
    return fields


class ApplyCliTestCase(unittest.TestCase):
    def setUp(self):
        self.core = FakeCore()
        self.addCleanup(self.core.stop)

    def run_cli(self, extra_args, core_url=None, model_path=MODEL_PATH):
        args = [
            "--model", model_path,
            "--core", core_url or self.core.base_url,
            "--email", "admin@wasichai.local",
            "--password", "admin",
        ] + extra_args
        out = io.StringIO()
        err = io.StringIO()
        with redirect_stdout(out), redirect_stderr(err):
            code = apply.main(args)
        return code, out.getvalue(), err.getvalue()


class DryRunTests(ApplyCliTestCase):
    def test_dry_run_makes_no_requests_and_prints_headers(self):
        code, out, err = self.run_cli(["--dry-run"])
        self.assertEqual(code, 0)
        self.assertEqual(self.core.requests, [])
        self.assertEqual(out.count("# POST /api/objects"), OBJECTS)
        self.assertEqual(out.count("# POST /api/relationships"), RELATIONSHIPS)
        # the write rules go in each object's POST
        self.assertIn('"apiOnly": true', out)
        self.assertIn('"appendOnly": false', out)
        # phase 3: the constraints that name a relationship's field, and the unique of the annulment's relation
        self.assertEqual(out.count("# PUT /api/objects/"), len(WAITING))
        self.assertIn("# PUT /api/metadata/objects/anulacion_recibo/fields/recibo", out)


class HappyPathTests(ApplyCliTestCase):
    def test_creates_everything_in_order(self):
        code, out, err = self.run_cli([])
        self.assertEqual(code, 0, msg=err)

        object_posts = [r[3] for r in self.core.requests if r[1] == "/api/objects" and r[0] == "POST"]
        self.assertEqual([p["name"] for p in object_posts], OBJECT_ORDER)
        self.assertEqual([p["name"] for p in object_posts if p["apiOnly"]], [n for n in OBJECT_ORDER if n in API_ONLY])
        self.assertEqual([p["name"] for p in object_posts if p["appendOnly"]], [n for n in OBJECT_ORDER if n in APPEND_ONLY])

        rel_posts = [r[3]["name"] for r in self.core.requests if r[1] == "/api/relationships" and r[0] == "POST"]
        self.assertEqual(rel_posts, RELATIONSHIP_ORDER)

        # the required relationships, each made required right after its POST. caja_area is not (the cajas tributarias
        # have no area), nor orden_recibo (a PENDIENTE orden has no recibo), nor a linea's orden or tasa
        puts = [(r[1], r[3]) for r in self.core.requests if r[0] == "PUT" and r[1].startswith("/api/metadata/")]
        self.assertEqual(puts[:-1], [(f"/api/metadata/objects/{path}", {"required": True}) for path in (
            "tasa/fields/area", "turno/fields/caja", "recibo/fields/caja", "recibo/fields/turno",
            "linea_recibo/fields/recibo", "pago_evento/fields/recibo", "pago_evento/fields/turno",
            "anulacion_recibo/fields/recibo", "anulacion_recibo/fields/caja", "anulacion_recibo/fields/turno",
            "reimpresion_recibo/fields/recibo", "cierre_turno/fields/turno", "cierre_turno_linea/fields/cierre_turno",
            "reversion_cierre/fields/turno")])
        # the unique of the annulment's relation comes last, in phase 3
        self.assertEqual(puts[-1], ("/api/metadata/objects/anulacion_recibo/fields/recibo", {"unique": True}))

        # orden_de_cobro and tasa carry their constraints in the POST; the others get the whole object by PUT, after the
        # relationships, with the labels and the write rules the PUT replaces
        by_name = {o["name"]: o for o in load_model()["objects"]}
        self.assertEqual({p["name"]: p["uniqueConstraints"] for p in object_posts if "uniqueConstraints" in p},
                         {"orden_de_cobro": [["sistema_origen", "referencia_externa"]], "tasa": [["codigo", "vigencia_desde"]]})
        object_puts = [(r[1], r[3]) for r in self.core.requests if r[0] == "PUT" and r[1].startswith("/api/objects/")]
        self.assertEqual([path for path, _ in object_puts], [f"/api/objects/{n}" for n in WAITING])
        for path, body in object_puts:
            obj = by_name[path.rsplit("/", 1)[1]]
            self.assertEqual(body, {"label": obj["label"], "pluralLabel": obj["pluralLabel"], "description": obj["description"],
                                    "enabled": True, "apiOnly": True, "appendOnly": True,
                                    "uniqueConstraints": obj["uniqueConstraints"]})
        last_relationship = max(i for i, r in enumerate(self.core.requests) if r[1] == "/api/relationships")
        first_object_put = min(i for i, r in enumerate(self.core.requests) if r[0] == "PUT" and r[1].startswith("/api/objects/"))
        self.assertGreater(first_object_put, last_relationship)

        for method, path, auth, body in self.core.requests:
            if path == "/api/auth/login":
                self.assertIsNone(auth)
            else:
                self.assertEqual(auth, "Bearer t")

        self.assertIn(f"done: {TOTAL} created, {len(WAITING) + 1} updated, 0 skipped", out)


class IdempotencyTests(ApplyCliTestCase):
    def setUp(self):
        model = load_model()
        self.core = FakeCore(
            existing_objects=[o["name"] for o in model["objects"]],
            existing_relationships=[r["name"] for r in model["relationships"]],
            existing_fields={o["name"]: core_fields(model, o["name"]) for o in model["objects"]},
            object_flags=model_flags(model),
        )
        self.addCleanup(self.core.stop)

    def test_everything_skips(self):
        code, out, err = self.run_cli([])
        self.assertEqual(code, 0, msg=err)
        object_posts = [r for r in self.core.requests if r[1] == "/api/objects" and r[0] == "POST"]
        self.assertEqual(object_posts, [])
        self.assertEqual([r for r in self.core.requests if r[0] == "POST" and "/fields" in r[1]], [])
        # neither the constraints nor the unique of the relation differ: nothing is put
        self.assertEqual([r for r in self.core.requests if r[0] == "PUT" and not r[1].endswith("/fields/" + r[1].rsplit("/", 1)[1])], [])
        self.assertEqual([r for r in self.core.requests if r[0] == "PUT" and r[1].startswith("/api/objects/")], [])
        self.assertIn(f"done: 0 created, 0 updated, {TOTAL} skipped", out)


def model_with_tipo_caja(directory, options=("VENTANILLA", "TRIBUTARIA")):
    """A copy of model.json with an ENUM field on caja (estado_orden is the shipped one, on orden_de_cobro): apply.py's option sync
    is tested through it. Returns the copy's path and the model."""
    model = load_model()
    model["enums"]["tipo_caja"] = list(options)
    caja = next(o for o in model["objects"] if o["name"] == "caja")
    caja["fields"].append({"name": "tipo_caja", "label": "Tipo de caja", "type": "ENUM", "enum": "tipo_caja"})
    path = os.path.join(directory, "model.json")
    with open(path, "w", encoding="utf-8") as f:
        json.dump(model, f, ensure_ascii=False)
    return path, model


def existing_core(model, fields):
    return FakeCore(
        existing_objects=[o["name"] for o in model["objects"]],
        existing_relationships=[r["name"] for r in model["relationships"]],
        existing_fields=fields,
        object_flags=model_flags(model),
    )


class SyncTests(ApplyCliTestCase):
    """tasa as an earlier model left it: its new fields are added, nothing else."""

    NEW_FIELDS = ["vigencia_hasta", "documento_fuente"]

    def setUp(self):
        model = load_model()
        fields = {o["name"]: core_fields(model, o["name"]) for o in model["objects"]}
        fields["tasa"] = core_fields(model, "tasa", drop=self.NEW_FIELDS)
        self.core = existing_core(model, fields)
        self.addCleanup(self.core.stop)

    def test_adds_missing_fields_only(self):
        code, out, err = self.run_cli([])
        self.assertEqual(code, 0, msg=err)
        added = [r[3] for r in self.core.requests if r[0] == "POST" and r[1] == "/api/metadata/objects/tasa/fields"]
        self.assertEqual([f["name"] for f in added], self.NEW_FIELDS)
        documento = next(f for f in added if f["name"] == "documento_fuente")
        self.assertEqual((documento["type"], documento["required"], documento["unique"]), ("TEXT", True, False))
        self.assertIn(f"done: 2 created, 0 updated, {OTHERS} skipped", out)


class ExtraFieldsTests(ApplyCliTestCase):
    """A field model.json no longer has (tasa.clave_vigencia, as the earlier model left it) is reported, kept, and
    relaxed when required: a record written without it stays valid."""

    def setUp(self):
        model = load_model()
        fields = {o["name"]: core_fields(model, o["name"]) for o in model["objects"]}
        fields["tasa"].append({"name": "clave_vigencia", "label": "Clave de vigencia", "type": "TEXT", "required": True, "unique": True})
        fields["turno"].append({"name": "clave_turno", "label": "Clave del turno", "type": "TEXT", "required": False})
        self.core = existing_core(model, fields)
        self.addCleanup(self.core.stop)

    def test_reports_the_extra_and_relaxes_the_required(self):
        code, out, err = self.run_cli([])
        self.assertEqual(code, 0, msg=err)
        self.assertIn("extra  field tasa.clave_vigencia", out)
        self.assertIn("extra  field turno.clave_turno", out)
        puts = [(r[1], r[3]) for r in self.core.requests if r[0] == "PUT" and "/fields/" in r[1] and "required" in (r[3] or {})
                and r[3]["required"] is False]
        self.assertEqual(puts, [("/api/metadata/objects/tasa/fields/clave_vigencia", {"required": False})])
        self.assertEqual([r for r in self.core.requests if r[0] == "DELETE"], [])
        self.assertIn(f"done: 0 created, 1 updated, {OTHERS} skipped", out)


class UniqueConstraintsTests(ApplyCliTestCase):
    """An object Core has without its constraints gets the complete list by a PUT of the whole object, orden_de_cobro and
    tasa too (an existing object never gets the POST); the relation's unique is put once; and a second run puts nothing."""

    def setUp(self):
        model = load_model()
        fields = {o["name"]: core_fields(model, o["name"]) for o in model["objects"]}
        next(f for f in fields["anulacion_recibo"] if f["name"] == "recibo")["unique"] = False
        self.core = existing_core(model, fields)
        self.core.object_flags = {n: {k: v for k, v in flags.items() if k != "uniqueConstraints"}
                                  for n, flags in model_flags(model).items()}
        self.addCleanup(self.core.stop)

    def test_puts_every_constraint_and_the_unique_of_the_relation(self):
        code, out, err = self.run_cli([])
        self.assertEqual(code, 0, msg=err)
        by_name = {o["name"]: o for o in load_model()["objects"]}
        object_puts = [(r[1], r[3]) for r in self.core.requests if r[0] == "PUT" and r[1].startswith("/api/objects/")]
        self.assertEqual([path for path, _ in object_puts],
                         [f"/api/objects/{n}" for n in OBJECT_ORDER if n in WAITING + ["orden_de_cobro", "tasa"]])
        for path, body in object_puts:
            obj = by_name[path.rsplit("/", 1)[1]]
            self.assertEqual(body["uniqueConstraints"], obj["uniqueConstraints"])
            self.assertEqual((body["label"], body["pluralLabel"], body["enabled"]), (obj["label"], obj["pluralLabel"], True))
        field_puts = [(r[1], r[3]) for r in self.core.requests if r[0] == "PUT" and r[1].endswith("/fields/recibo")
                      and r[3] == {"unique": True}]
        self.assertEqual(field_puts, [("/api/metadata/objects/anulacion_recibo/fields/recibo", {"unique": True})])
        self.assertIn("update object turno (uniqueConstraints=[[\"caja\", \"cajero\", \"fecha\"]])", out)
        self.assertIn(f"done: 0 created, {len(WAITING) + 3} updated, {TOTAL} skipped", out)

    def test_a_refusal_of_the_put_stops_the_apply(self):
        self.core.fail_put_object = "turno"
        code, out, err = self.run_cli([])
        self.assertEqual(code, 1)
        self.assertIn("error PUT /api/objects/turno -> 409", err)


class EnumOptionsTests(ApplyCliTestCase):
    """The ENUM options of a field are synced: the ones Core lacks are added, the ones model.json dropped go unless a
    record uses one (apply.py's, tested through a model that has an enum)."""

    OLD = ["VENTANILLA", "TRIBUTARIA", "OBSOLETA", "EN_USO"]

    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.path, model = model_with_tipo_caja(directory.name, ["VENTANILLA", "TRIBUTARIA", "MIXTA"])
        fields = {o["name"]: core_fields(model, o["name"]) for o in model["objects"]}
        fields["caja"] = core_fields(model, "caja", options={"tipo_caja": self.OLD})
        self.core = existing_core(model, fields)
        self.addCleanup(self.core.stop)

    def option_puts(self):
        return [(r[1], r[3]["enumOptions"]) for r in self.core.requests if r[0] == "PUT" and "enumOptions" in (r[3] or {})]

    def test_adds_the_new_option_and_drops_the_unused_ones(self):
        self.core.add_record("caja", {"tipo_caja": "EN_USO"})
        code, out, err = self.run_cli([], model_path=self.path)
        self.assertEqual(code, 0, msg=err)
        # a caja already has EN_USO: it stays storable, after model.json's options
        self.assertEqual(self.option_puts(), [(
            "/api/metadata/objects/caja/fields/tipo_caja", ["VENTANILLA", "TRIBUTARIA", "MIXTA", "EN_USO"])])
        self.assertIn("keep   option caja.tipo_caja EN_USO: 1 record uses it", out)
        self.assertIn(f"done: 0 created, 1 updated, {OTHERS} skipped", out)

    def test_an_unused_option_goes(self):
        code, out, err = self.run_cli([], model_path=self.path)
        self.assertEqual(code, 0, msg=err)
        self.assertEqual(self.option_puts(), [("/api/metadata/objects/caja/fields/tipo_caja", ["VENTANILLA", "TRIBUTARIA", "MIXTA"])])
        self.assertIn("-OBSOLETA, EN_USO", out)

    def count_answers(self, status, payload):
        """The records GET, the count asked before dropping an option, answers this."""
        records = self.core._records
        self.core._records = lambda method, *args: (status, payload) if method == "GET" else records(method, *args)

    def assert_drops_nothing(self):
        code, out, err = self.run_cli([], model_path=self.path)
        self.assertEqual(code, 1)
        self.assertIn("error GET /api/objects/caja/records", err)
        self.assertEqual(self.option_puts(), [])

    def test_a_count_core_refuses_drops_nothing(self):
        self.count_answers(500, {"message": "boom"})
        self.assert_drops_nothing()

    def test_a_404_is_not_a_zero(self):
        self.count_answers(404, {"detail": "not found"})
        self.assert_drops_nothing()

    def test_an_answer_without_its_count_is_not_a_zero(self):
        self.count_answers(200, {"content": []})
        self.assert_drops_nothing()


class RelaxRequiredTests(ApplyCliTestCase):
    """A field model.json no longer requires is made optional; nothing is made required."""

    def setUp(self):
        model = load_model()
        fields = {o["name"]: core_fields(model, o["name"]) for o in model["objects"]}
        for f in fields["tasa"]:
            # Core as an earlier model left it: vigencia_hasta required. codigo is required in both
            f["required"] = f["name"] in ("vigencia_hasta", "codigo")
        self.core = existing_core(model, fields)
        self.addCleanup(self.core.stop)

    def test_relaxes_required_only(self):
        code, out, err = self.run_cli([])
        self.assertEqual(code, 0, msg=err)
        puts = [(r[1], r[3]) for r in self.core.requests if r[0] == "PUT" and "/api/metadata/objects/tasa/fields/" in r[1]
                and r[1] != "/api/metadata/objects/tasa/fields/area"]
        self.assertEqual(puts, [("/api/metadata/objects/tasa/fields/vigencia_hasta", {"required": False})])
        self.assertIn("update field tasa.vigencia_hasta (optional)", out)
        self.assertIn(f"done: 0 created, 1 updated, {OTHERS} skipped", out)


class RelabelTests(ApplyCliTestCase):
    """A field model.json labels differently gets model.json's label: caja.nombre as "Rótulo"."""

    def setUp(self):
        model = load_model()
        fields = {o["name"]: core_fields(model, o["name"]) for o in model["objects"]}
        next(f for f in fields["caja"] if f["name"] == "nombre")["label"] = "Rótulo"
        self.core = existing_core(model, fields)
        self.addCleanup(self.core.stop)

    def test_relabels_only_what_differs(self):
        code, out, err = self.run_cli([])
        self.assertEqual(code, 0, msg=err)
        puts = [(r[1], r[3]) for r in self.core.requests if r[0] == "PUT" and "/fields/" in r[1] and "label" in (r[3] or {})]
        self.assertEqual(puts, [("/api/metadata/objects/caja/fields/nombre", {"label": "Nombre"})])
        self.assertIn("update field caja.nombre (label)", out)
        self.assertIn(f"done: 0 created, 1 updated, {OTHERS} skipped", out)


class ObjectFlagsTests(ApplyCliTestCase):
    """An object Core has with other write rules gets them from model.json by a PUT that replaces the object."""

    def setUp(self):
        model = load_model()
        fields = {o["name"]: core_fields(model, o["name"]) for o in model["objects"]}
        flags = model_flags(model)
        flags["recibo"] = {"apiOnly": False, "appendOnly": False}
        flags["caja"] = {"apiOnly": True, "appendOnly": False}  # a catalogue is neither
        self.core = existing_core(model, fields)
        self.core.object_flags = flags
        self.addCleanup(self.core.stop)

    def test_puts_the_objects_that_differ_only(self):
        code, out, err = self.run_cli([])
        self.assertEqual(code, 0, msg=err)
        puts = [(r[1], r[3]) for r in self.core.requests if r[0] == "PUT" and r[1].startswith("/api/objects/")]
        model = load_model()
        recibo = next(o for o in model["objects"] if o["name"] == "recibo")
        caja = next(o for o in model["objects"] if o["name"] == "caja")

        def body(obj, api, append):
            # labels and enabled go every time, and never the constraints
            return {"label": obj["label"], "pluralLabel": obj["pluralLabel"], "description": obj["description"], "enabled": True,
                    "apiOnly": api, "appendOnly": append}
        self.assertEqual(puts, [("/api/objects/caja", body(caja, False, False)), ("/api/objects/recibo", body(recibo, True, True))])
        self.assertTrue(all("uniqueConstraints" not in b for _, b in puts))
        self.assertIn(f"done: 0 created, 2 updated, {OBJECTS - 2 + RELATIONSHIPS} skipped", out)
        self.assertEqual(self.core.object_flags["recibo"], {"apiOnly": True, "appendOnly": True})


class FailureStopsTests(ApplyCliTestCase):
    def setUp(self):
        self.core = FakeCore(fail_on_post_object="caja")
        self.addCleanup(self.core.stop)

    def test_500_on_second_object_aborts(self):
        code, out, err = self.run_cli([])
        self.assertEqual(code, 1)
        self.assertIn("boom", err)
        object_posts = [r for r in self.core.requests if r[1] == "/api/objects" and r[0] == "POST"]
        self.assertEqual(len(object_posts), 2)
        self.assertEqual([r for r in self.core.requests if r[1] == "/api/relationships"], [])


class RequiredPutFailureTests(ApplyCliTestCase):
    """The required PUT tolerates nothing, not even 409."""

    def setUp(self):
        self.core = FakeCore(fail_put=True, fail_put_status=409)
        self.addCleanup(self.core.stop)

    def test_409_on_required_put_aborts(self):
        code, out, err = self.run_cli([])
        self.assertEqual(code, 1)
        self.assertIn("/api/metadata/objects/tasa/fields/area", err)
        rel_posts = [r[3]["name"] for r in self.core.requests if r[1] == "/api/relationships" and r[0] == "POST"]
        self.assertEqual(rel_posts, ["caja_area", "tasa_area"])


class ConnectionFailureTests(unittest.TestCase):
    def test_closed_port_is_fatal_with_no_traceback(self):
        s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        s.bind(("127.0.0.1", 0))
        port = s.getsockname()[1]
        s.close()

        out = io.StringIO()
        err = io.StringIO()
        with redirect_stdout(out), redirect_stderr(err):
            code = apply.main(["--model", MODEL_PATH, "--core", f"http://127.0.0.1:{port}"])
        self.assertEqual(code, 1)
        self.assertIn("connection failed", err.getvalue())
        self.assertNotIn("Traceback", err.getvalue())


class LoginMissingTokenTests(ApplyCliTestCase):
    def setUp(self):
        self.core = FakeCore(login_response={})
        self.addCleanup(self.core.stop)

    def test_login_without_token_is_fatal(self):
        code, out, err = self.run_cli([])
        self.assertEqual(code, 1)
        self.assertIn("error POST /api/auth/login -> no token in response", err)
        self.assertEqual(len(self.core.requests), 1)


class DropTests(ApplyCliTestCase):
    def test_drop_deletes_relationships_then_objects_in_reverse(self):
        code, out, err = self.run_cli(["--drop"])
        self.assertEqual(code, 0, msg=err)
        deletes = [r[1] for r in self.core.requests if r[0] == "DELETE"]
        self.assertEqual(deletes, [f"/api/relationships/{n}" for n in reversed(RELATIONSHIP_ORDER)]
                         + [f"/api/objects/{n}" for n in reversed(OBJECT_ORDER)])
        self.assertIn(f"done: {TOTAL} deleted, 0 skipped", out)

    def test_drop_dry_run_makes_no_requests(self):
        code, out, err = self.run_cli(["--drop", "--dry-run"])
        self.assertEqual(code, 0, msg=err)
        self.assertEqual(self.core.requests, [])
        self.assertEqual(out.count("# DELETE "), OBJECTS + RELATIONSHIPS)
        self.assertEqual(out.count("# PUT /api/objects/"), len(DROP_PUTS))

    def test_drop_turns_append_only_off_before_deleting_relationships_and_objects(self):
        model = load_model()
        self.core = FakeCore(existing_objects=[o["name"] for o in model["objects"]], object_flags=model_flags(model))
        self.addCleanup(self.core.stop)
        code, out, err = self.run_cli(["--drop"])
        self.assertEqual(code, 0, msg=err)
        calls = [(r[0], r[1]) for r in self.core.requests if r[0] in ("PUT", "DELETE")]
        puts = [(m, p) for m, p in calls if m == "PUT"]
        self.assertEqual(puts, [("PUT", f"/api/objects/{n}") for n in OBJECT_ORDER if n in DROP_PUTS])
        # the PUTs come first, then the relationships, then the objects
        self.assertEqual(calls[:len(puts)], puts)
        self.assertEqual([p for m, p in calls[len(puts):]], [f"/api/relationships/{n}" for n in reversed(RELATIONSHIP_ORDER)]
                         + [f"/api/objects/{n}" for n in reversed(OBJECT_ORDER)])
        for _, body in [(r[1], r[3]) for r in self.core.requests if r[0] == "PUT"]:
            self.assertIs(body["appendOnly"], False)
            # Core refuses to delete a relationship whose field sits in a constraint: they are emptied too
            self.assertEqual(body["uniqueConstraints"], [])
        self.assertIn(f"done: {TOTAL} deleted, 0 skipped", out)


class ValidateOnlyTests(ApplyCliTestCase):
    def test_validate_only_makes_no_requests(self):
        code, out, err = self.run_cli(["--validate-only"])
        self.assertEqual(code, 0, msg=err)
        self.assertEqual(self.core.requests, [])


if __name__ == "__main__":
    unittest.main()
