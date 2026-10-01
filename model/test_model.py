"""Tests for model.json and apply.py's pure parts (validate, payload builders).

Run: cd model && python3 -m unittest -v
"""
import copy
import json
import os
import unittest

from apply import enum_option_valid, object_payload, relationship_payload, validate

MODEL_PATH = os.path.join(os.path.dirname(__file__), "model.json")


def load_model():
    with open(MODEL_PATH, encoding="utf-8") as f:
        return json.load(f)


class ShippedModelTests(unittest.TestCase):
    def setUp(self):
        self.model = load_model()

    def test_validates_clean(self):
        self.assertEqual(validate(self.model), [])

    def test_objects_in_topological_order(self):
        names = [o["name"] for o in self.model["objects"]]
        self.assertEqual(names, ["area", "caja", "tasa", "orden_de_cobro"])
        self.assertEqual([r["name"] for r in self.model["relationships"]], ["caja_area", "tasa_area"])

    def fields(self, name):
        obj = next(o for o in self.model["objects"] if o["name"] == name)
        return {f["name"]: f for f in obj["fields"]}

    def test_area_has_codigo_nombre_and_activa(self):
        fields = self.fields("area")
        self.assertEqual({n: f["type"] for n, f in fields.items()}, {"codigo": "TEXT", "nombre": "TEXT", "activa": "BOOLEAN"})
        self.assertTrue(all(f["required"] for f in fields.values()))
        self.assertEqual([n for n, f in fields.items() if f.get("unique")], ["codigo"])

    def test_caja_has_a_unique_codigo_and_a_unique_serie(self):
        fields = self.fields("caja")
        self.assertEqual({n: f["type"] for n, f in fields.items()},
                         {"codigo": "TEXT", "nombre": "TEXT", "serie": "TEXT", "activa": "BOOLEAN"})
        self.assertTrue(all(f["required"] for f in fields.values()))
        self.assertEqual({n for n, f in fields.items() if f.get("unique")}, {"codigo", "serie"})

    def test_tasa_keeps_the_tarifa_as_data(self):
        fields = self.fields("tasa")
        self.assertEqual({n: f["type"] for n, f in fields.items()}, {
            "codigo": "TEXT", "descripcion": "TEXT", "partida_presupuestal": "TEXT", "importe": "DECIMAL",
            "vigencia_desde": "DATE", "vigencia_hasta": "DATE", "documento_fuente": "TEXT", "clave_vigencia": "TEXT"})
        self.assertEqual({n for n, f in fields.items() if not f.get("required")}, {"vigencia_hasta"})

    def test_tasa_is_unique_by_clave_vigencia_and_not_by_codigo(self):
        # wasichai has no composite unique: clave_vigencia (<codigo>|<vigencia_desde>) stands for (codigo, vigencia_desde)
        fields = self.fields("tasa")
        self.assertEqual([n for n, f in fields.items() if f.get("unique")], ["clave_vigencia"])

    def test_the_area_of_a_caja_is_optional_and_the_one_of_a_tasa_is_required(self):
        # the cajas tributarias have no area
        rels = {r["name"]: r for r in self.model["relationships"]}
        self.assertEqual((rels["caja_area"]["source"], rels["caja_area"]["target"], rels["caja_area"]["fieldName"]),
                         ("caja", "area", "area"))
        self.assertFalse(rels["caja_area"]["required"])
        self.assertEqual((rels["tasa_area"]["source"], rels["tasa_area"]["target"], rels["tasa_area"]["fieldName"]),
                         ("tasa", "area", "area"))
        self.assertTrue(rels["tasa_area"]["required"])

    def test_orden_de_cobro_has_what_it_takes_to_charge_it(self):
        fields = self.fields("orden_de_cobro")
        self.assertEqual({n: f["type"] for n, f in fields.items()}, {
            "sistema_origen": "TEXT", "referencia_externa": "TEXT", "clave_origen": "TEXT", "concepto": "TEXT",
            "detalle": "TEXT", "importe": "DECIMAL", "fecha_exigibilidad": "DATE", "actualizado_a": "DATE",
            "pagador_documento": "TEXT", "pagador_nombre": "TEXT", "pagador_externo_id": "INTEGER", "estado": "ENUM",
            "observacion": "LONG_TEXT"})
        self.assertEqual({n for n, f in fields.items() if not f.get("required")},
                         {"detalle", "pagador_documento", "pagador_nombre", "pagador_externo_id"})
        self.assertEqual(fields["estado"]["enum"], "estado_orden")

    def test_orden_de_cobro_is_unique_by_clave_origen_only(self):
        # clave_origen (<sistema_origen>|<referencia_externa>) stands for orden_referencia_uq: no composite unique
        fields = self.fields("orden_de_cobro")
        self.assertEqual([n for n, f in fields.items() if f.get("unique")], ["clave_origen"])

    def test_orden_de_cobro_knows_no_tributo(self):
        # caja's frontier: the day an orden gains a tributo, an ejercicio or a periodo, it no longer charges a market stall
        for name in self.fields("orden_de_cobro"):
            self.assertFalse(name.startswith(("tributo", "ejercicio", "periodo")), name)

    def test_estado_orden_has_the_three_states(self):
        self.assertEqual(self.model["enums"]["estado_orden"], ["PENDIENTE", "PAGADA", "ANULADA"])

    def test_every_enum_option_passes_core_regex(self):
        for name, options in self.model["enums"].items():
            for opt in options:
                self.assertTrue(enum_option_valid(opt), f"{name}: {opt}")

    def test_labels_and_descriptions_are_in_spanish(self):
        for obj in self.model["objects"]:
            self.assertTrue(obj["label"] and obj["pluralLabel"] and obj["description"], obj["name"])
            for f in obj["fields"]:
                self.assertTrue(f["label"], f"{obj['name']}.{f['name']}")


class PayloadTests(unittest.TestCase):
    def setUp(self):
        self.model = load_model()

    def test_field_payload_carries_required_and_unique(self):
        obj = next(o for o in self.model["objects"] if o["name"] == "caja")
        payload = object_payload(self.model, obj)
        serie = next(f for f in payload["fields"] if f["name"] == "serie")
        self.assertEqual((serie["type"], serie["required"], serie["unique"]), ("TEXT", True, True))
        self.assertNotIn("enum", serie)

    def test_enum_payload_carries_its_options(self):
        obj = next(o for o in self.model["objects"] if o["name"] == "orden_de_cobro")
        estado = next(f for f in object_payload(self.model, obj)["fields"] if f["name"] == "estado")
        self.assertEqual((estado["type"], estado["required"], estado["enumOptions"]),
                         ("ENUM", True, ["PENDIENTE", "PAGADA", "ANULADA"]))

    def test_relationship_payload_is_many_to_one(self):
        rel = next(r for r in self.model["relationships"] if r["name"] == "caja_area")
        self.assertEqual(relationship_payload(rel), {
            "name": "caja_area",
            "label": "Área",
            "inverseLabel": "Cajas",
            "type": "MANY_TO_ONE",
            "source": "caja",
            "target": "area",
            "fieldName": "area",
        })


class ValidationTests(unittest.TestCase):
    def setUp(self):
        self.model = load_model()

    def mutate(self, fn):
        model = copy.deepcopy(self.model)
        fn(model)
        return validate(model)

    def test_option_with_comma_is_refused(self):
        def add(m):
            m["enums"]["tipo_caja"] = ["VENTANILLA", "TRIBUTARIA,GENERAL"]
        errors = self.mutate(add)
        self.assertTrue(any("TRIBUTARIA,GENERAL" in e for e in errors))

    def test_option_over_64_chars_is_refused(self):
        def add(m):
            m["enums"]["tipo_caja"] = ["X" * 65]
        errors = self.mutate(add)
        self.assertTrue(any("invalid characters or length" in e for e in errors))

    def test_geometry_without_its_shape_is_refused(self):
        errors = self.mutate(lambda m: m["objects"][1]["fields"].append({"name": "geom", "label": "G", "type": "GEOMETRY"}))
        self.assertTrue(any("geometryType must be one of" in e for e in errors))

    def test_target_after_source_is_refused(self):
        errors = self.mutate(lambda m: m["objects"].reverse())
        self.assertTrue(any("must appear before source" in e for e in errors))

    def test_reserved_and_keyword_names_are_refused(self):
        errors = self.mutate(lambda m: m["objects"][0]["fields"].extend([
            {"name": "version", "label": "V", "type": "TEXT"},
            {"name": "order", "label": "O", "type": "TEXT"},
        ]))
        self.assertTrue(any("reserved by Core" in e for e in errors))
        self.assertTrue(any("reserved SQL keyword" in e for e in errors))

    def test_unknown_enum_is_refused(self):
        errors = self.mutate(lambda m: m["objects"][0]["fields"].append({"name": "x", "label": "X", "type": "ENUM", "enum": "nope"}))
        self.assertTrue(any("enum 'nope' is not defined" in e for e in errors))


if __name__ == "__main__":
    unittest.main()
