#!/usr/bin/env python3
"""Apply model/model.json to a wasichai Core (caja-backend).

Reads a JSON metadata model (objects, fields, relationships, enums), checks it
against Core's rules (--validate-only stops there) and creates it in Core via
the REST API, in file order (so relationship targets already exist), marking
each required relationship's field required. A third phase, after the
relationships, puts what needs them: each object's uniqueConstraints (a set may
name a relationship's field) and the `unique: true` of a relationship's field.
Today that is 13 objects and 18 relationships: "done: 31 created, 5 updated" on
an empty Core (the four objects whose constraints name a relationship, and that
one field), "31 skipped" on a second run.

On a Core that already has the model it syncs instead: it adds the fields and
the ENUM options model.json has and Core lacks, drops the ENUM options it no
longer lists and no record uses (a list it changes takes model.json's order, the
options kept for being in use last), relaxes a field model.json no longer
requires (and reports it as extra), relabels one labelled differently and puts
an object's write rules (apiOnly, appendOnly) and its uniqueConstraints when
they differ. It never renames, retypes, makes required or removes a field or an
option in use, so imported records stay valid. --drop first turns appendOnly off
and empties uniqueConstraints (Core refuses to delete a relationship on such an
object, or one whose field sits in a constraint), then tears everything down in
reverse order (data included).

GEOMETRY fields are wasichai-gis's: caja does not install it, so model.json has none. Adapted from wasichai's
examples/gis-sample/perene/apply.py. Stdlib only. See README.md.
"""
import argparse
import json
import os
import re
import sys
import urllib.parse

from core_client import Client, CoreError

# Keyword list copied verbatim from
# wasichai-core/src/main/kotlin/wasichai/core/platform/SqlIdentifier.kt (SQL_KEYWORDS).
SQL_KEYWORDS = frozenset({
    "all", "alter", "and", "any", "array", "as", "asc", "begin", "between",
    "by", "case", "cast", "check", "column", "commit", "constraint",
    "create", "cross", "current_date", "current_time", "current_timestamp",
    "current_user", "default", "delete", "desc", "distinct", "do", "drop",
    "else", "end", "except", "exists", "false", "fetch", "for", "foreign",
    "from", "full", "grant", "group", "having", "in", "index", "inner",
    "insert", "intersect", "into", "is", "join", "key", "left", "like",
    "limit", "not", "null", "offset", "on", "or", "order", "outer",
    "primary", "references", "returning", "right", "rollback", "select",
    "session_user", "set", "some", "table", "then", "to", "true", "union",
    "unique", "update", "user", "using", "values", "view", "when", "where",
    "with",
})

# Field names Core reserves: system columns + query-param names used on list endpoints.
RESERVED_FIELD_NAMES = frozenset({
    "id", "organization_id", "created_at", "updated_at", "created_by",
    "updated_by", "workflow_state", "version",
    "page", "size", "sort", "dir", "q", "bbox", "geometry", "limit", "count", "after",
})

# the write rules an object declares (ADR-040): both default to false
OBJECT_FLAGS = ("apiOnly", "appendOnly")

VALID_NAME = re.compile(r"^[a-z][a-z0-9_]{0,48}$")

# Mirror Core's ENUM option regex (metadata/MetadataService.kt):
# ^[\p{L}0-9 _.-]{1,64}$ -- Unicode letters, ASCII digits, space, `_`, `.`, `-`.
# Checked per character: Python's `\w` tricks also match non-letter "word" characters.
ENUM_OPTION_EXTRA_CHARS = re.compile(r"[0-9 _.\-]")


def enum_option_valid(opt):
    if not isinstance(opt, str) or not (1 <= len(opt) <= 64):
        return False
    return all(c.isalpha() or ENUM_OPTION_EXTRA_CHARS.fullmatch(c) for c in opt)


MAX_OBJECT_NAME = 39
MAX_FIELD_NAME = 49
MAX_RELATIONSHIP_NAME = 35

# core's scalar types, plus GEOMETRY from wasichai-gis (caja does not install it; the check is kept as copied)
FIELD_TYPES = frozenset({
    "TEXT", "LONG_TEXT", "INTEGER", "DECIMAL", "BOOLEAN", "DATE", "DATETIME",
    "ENUM", "EMAIL", "URL", "UUID", "GEOMETRY",
})

# mirror wasichai-gis's GeometryFieldType: a geometry names its shape; srid 1..999999 (default 4326), 2d or 3d
GEOMETRY_TYPES = frozenset({"POINT", "LINESTRING", "POLYGON", "MULTIPOINT", "MULTILINESTRING", "MULTIPOLYGON"})


# ---------------------------------------------------------------------------
# validation
# ---------------------------------------------------------------------------

def _check_identifier(name, kind, maxlen, label):
    """Mirror SqlIdentifier.requireValidName: shape, length, not a keyword."""
    errors = []
    if not isinstance(name, str) or not name:
        return [f"{label}: {kind} name is missing"]
    if len(name) > maxlen:
        errors.append(f"{label}: {kind} name '{name}' exceeds {maxlen} characters")
    if not VALID_NAME.fullmatch(name):
        errors.append(
            f"{label}: {kind} name '{name}' must match ^[a-z][a-z0-9_]{{0,48}}$"
        )
    if name in SQL_KEYWORDS:
        errors.append(f"{label}: {kind} name '{name}' is a reserved SQL keyword")
    return errors


def validate(model):
    """Pure structural + Core-mirroring validation. Returns a list of error strings."""
    errors = []

    enums = model.get("enums")
    if not isinstance(enums, dict):
        errors.append("enums must be an object")
        enums = {}
    else:
        for ename, opts in enums.items():
            if not isinstance(opts, list) or not opts or not all(isinstance(o, str) for o in opts):
                errors.append(f"enum {ename}: must be a non-empty list of strings")
                continue
            seen_opts = set()
            for opt in opts:
                if not enum_option_valid(opt):
                    errors.append(f"enum {ename}: option '{opt}' has invalid characters or length")
                if opt in seen_opts:
                    errors.append(f"enum {ename}: duplicate option '{opt}'")
                seen_opts.add(opt)

    objects = model.get("objects")
    if not isinstance(objects, list) or not objects:
        errors.append("objects must be a non-empty list")
        objects = []

    relationships = model.get("relationships")
    if not isinstance(relationships, list):
        errors.append("relationships must be a list")
        relationships = []

    object_order = []
    seen_objects = set()
    fields_by_object = {}
    types_by_object = {}

    for obj in objects:
        oname = obj.get("name", "")
        label = f"object {oname}"
        if oname in seen_objects:
            errors.append(f"{label}: duplicate object name")
        seen_objects.add(oname)
        object_order.append(oname)
        errors.extend(_check_identifier(oname, "object", MAX_OBJECT_NAME, label))
        for flag in OBJECT_FLAGS:
            if flag in obj and not isinstance(obj[flag], bool):
                errors.append(f"{label}: {flag} must be true or false")

        field_names = set()
        for field in obj.get("fields", []):
            fname = field.get("name", "")
            ftype = field.get("type")
            flabel = f"{label} field {fname}"
            if fname in field_names:
                errors.append(f"{flabel}: duplicate field name")
            field_names.add(fname)

            errors.extend(_check_identifier(fname, "field", MAX_FIELD_NAME, flabel))
            if fname in RESERVED_FIELD_NAMES:
                errors.append(f"{flabel}: field name is reserved by Core")

            if ftype == "RELATION":
                errors.append(f"{flabel}: type RELATION is not allowed here (relationships create it)")
            elif ftype not in FIELD_TYPES:
                errors.append(f"{flabel}: unknown type '{ftype}'")

            if ftype == "GEOMETRY":
                if str(field.get("geometryType", "")).upper() not in GEOMETRY_TYPES:
                    errors.append(f"{flabel}: geometryType must be one of {', '.join(sorted(GEOMETRY_TYPES))}")
                srid = field.get("srid", 4326)
                if not isinstance(srid, int) or not 1 <= srid <= 999999:
                    errors.append(f"{flabel}: srid must be an integer between 1 and 999999")
                if field.get("dimension", 2) not in (2, 3):
                    errors.append(f"{flabel}: dimension must be 2 or 3")
                if field.get("unique") or field.get("required"):
                    errors.append(f"{flabel}: a geometry can be neither unique nor required here")

            if ftype == "ENUM":
                enum_name = field.get("enum")
                if not enum_name or enum_name not in enums:
                    errors.append(f"{flabel}: enum '{enum_name}' is not defined in enums")

        fields_by_object[oname] = field_names
        types_by_object[oname] = {f.get("name"): f.get("type") for f in obj.get("fields", [])}

    rel_names = set()
    seen_source_field_names = set()
    for rel in relationships:
        rname = rel.get("name", "")
        label = f"relationship {rname}"
        if rname in rel_names:
            errors.append(f"{label}: duplicate relationship name")
        rel_names.add(rname)
        errors.extend(_check_identifier(rname, "relationship", MAX_RELATIONSHIP_NAME, label))

        source = rel.get("source")
        target = rel.get("target")
        if source not in seen_objects:
            errors.append(f"{label}: source '{source}' is not a known object")
        if target not in seen_objects:
            errors.append(f"{label}: target '{target}' is not a known object")

        if source == target:
            errors.append(f"{label}: self-references are not allowed")
        elif source in object_order and target in object_order:
            if object_order.index(target) > object_order.index(source):
                errors.append(
                    f"{label}: target '{target}' must appear before source '{source}' in objects"
                )

        field_name = rel.get("fieldName")
        errors.extend(_check_identifier(field_name, "field", MAX_FIELD_NAME, label))
        if field_name in RESERVED_FIELD_NAMES:
            errors.append(f"{label}: fieldName '{field_name}' is reserved by Core")
        if source in fields_by_object and field_name in fields_by_object[source]:
            errors.append(f"{label}: fieldName '{field_name}' collides with an existing field of {source}")

        source_field_key = (source, field_name)
        if source_field_key in seen_source_field_names:
            errors.append(
                f"{label}: fieldName '{field_name}' duplicates another relationship from {source}"
            )
        seen_source_field_names.add(source_field_key)

        if "unique" in rel and not isinstance(rel["unique"], bool):
            errors.append(f"{label}: unique must be true or false")

    # a constraint may name a relationship's field: it is declared once the relationship exists (phase 3)
    relation_fields = {(r.get("source"), r.get("fieldName")) for r in relationships}
    for obj in objects:
        oname = obj.get("name", "")
        label = f"object {oname}"
        constraints = obj.get("uniqueConstraints", [])
        if not isinstance(constraints, list):
            errors.append(f"{label}: uniqueConstraints must be a list of field lists")
            continue
        for fields in constraints:
            if not isinstance(fields, list) or not all(isinstance(n, str) for n in fields):
                errors.append(f"{label}: a uniqueConstraints entry must be a list of field names")
                continue
            if len(fields) < 2:
                errors.append(f"{label}: uniqueConstraints {fields} needs at least 2 fields (one field is unique: true)")
            if len(set(fields)) != len(fields):
                errors.append(f"{label}: uniqueConstraints {fields} names a field twice")
            for fname in fields:
                if fname not in fields_by_object.get(oname, set()) and (oname, fname) not in relation_fields:
                    errors.append(f"{label}: uniqueConstraints {fields} names '{fname}', which is not a field of {oname}")
                elif types_by_object.get(oname, {}).get(fname) == "LONG_TEXT":
                    errors.append(f"{label}: uniqueConstraints {fields} names '{fname}', a LONG_TEXT field cannot be unique")

    return errors


# ---------------------------------------------------------------------------
# payload builders (pure)
# ---------------------------------------------------------------------------

def field_payload(model, f):
    field = {
        "name": f["name"],
        "label": f["label"],
        "type": f["type"],
        "required": f.get("required", False),
        "unique": f.get("unique", False),
    }
    if "description" in f:
        field["description"] = f["description"]
    if f["type"] == "ENUM":
        field["enumOptions"] = model["enums"][f["enum"]]
    if f["type"] == "GEOMETRY":
        field["geometryType"] = f["geometryType"].upper()
        field["srid"] = f.get("srid", 4326)
        field["dimension"] = f.get("dimension", 2)
    return field


def object_flags(obj):
    """The write rules model.json gives an object, both always present."""
    return {flag: obj.get(flag, False) for flag in OBJECT_FLAGS}


def unique_constraints(obj):
    """The field sets model.json makes unique on an object, in its order."""
    return obj.get("uniqueConstraints", [])


def _own_unique_constraints(obj):
    """The sets whose fields are all the object's own: Core takes those in the POST. One that names a relationship's
    field waits for the relationship (phase 3)."""
    own = {f["name"] for f in obj["fields"]}
    return [fields for fields in unique_constraints(obj) if all(name in own for name in fields)]


def object_payload(model, obj):
    sets = _own_unique_constraints(obj)
    return {
        "name": obj["name"],
        "label": obj["label"],
        "pluralLabel": obj["pluralLabel"],
        "description": obj.get("description", ""),
        "fields": [field_payload(model, f) for f in obj["fields"]],
        **object_flags(obj),
        **({"uniqueConstraints": sets} if sets else {}),
    }


def object_update_payload(obj, **flags):
    """The body of PUT /api/objects/{name}, which REPLACES the object: what it leaves out reverts (a missing
    pluralLabel takes the label, a missing description goes null), so the labels go every time. uniqueConstraints is
    left out: null keeps the ones Core has (a caller that changes them adds the key)."""
    return {
        "label": obj["label"],
        "pluralLabel": obj["pluralLabel"],
        "description": obj.get("description", ""),
        "enabled": True,
        **{**object_flags(obj), **flags},
    }


def missing_options(model, field, existing):
    """Options of an ENUM field in model.json that Core does not have yet, in model order."""
    if field["type"] != "ENUM":
        return []
    have = set(existing.get("enumOptions") or [])
    return [o for o in model["enums"][field["enum"]] if o not in have]


def dropped_options(model, field, existing):
    """Options Core has for an ENUM field that model.json no longer lists, in Core's order."""
    if field["type"] != "ENUM":
        return []
    keep = set(model["enums"][field["enum"]])
    return [o for o in existing.get("enumOptions") or [] if o not in keep]


def _records_using(client, object_name, field_name, option):
    """How many records of the object store the option in the field. An answer without that count (a 404, another
    shape) is an error, not a zero: the option is never dropped on a guess."""
    query = urllib.parse.urlencode({"page": 0, "size": 1, field_name: option})
    data = client.get(f"/api/objects/{object_name}/records?{query}")
    total = data.get("totalElements") if isinstance(data, dict) else None
    if not isinstance(total, int):
        raise CoreError("no record count", json.dumps(data))
    return total


def relationship_payload(rel):
    return {
        "name": rel["name"],
        "label": rel["label"],
        "inverseLabel": rel["inverseLabel"],
        "type": "MANY_TO_ONE",
        "source": rel["source"],
        "target": rel["target"],
        "fieldName": rel["fieldName"],
    }


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------

def _existing_objects(data):
    """The objects Core has, by name, with the flags and constraints GET /api/objects already carries."""
    if isinstance(data, dict):
        data = data.get("items", data.get("content", []))
    return {o["name"]: o for o in (data or [])}


def _fatal(method, path, err):
    print(f"error {method} {path} -> {err.status}\n{err.body}", file=sys.stderr)


def _apply_required_put(client, rel):
    """Returns True to keep going, False if this was fatal (caller must stop and exit 1).

    Unlike object/relationship POSTs (which tolerate 409 "ya existe"), this PUT tolerates
    nothing: every non-2xx response is fatal.
    """
    if not rel.get("required"):
        return True
    path = f"/api/metadata/objects/{rel['source']}/fields/{rel['fieldName']}"
    try:
        client.put(path, {"required": True})
    except CoreError as e:
        _fatal("PUT", path, e)
        return False
    return True


def _fields_by_name(data):
    if isinstance(data, dict):
        data = data.get("items", data.get("content", []))
    return {f["name"]: f for f in (data or [])}


def sync_object(client, model, obj, current_object=None):
    """An object that already exists: put its write rules (apiOnly, appendOnly) when they differ from model.json's, add the fields model.json has and Core lacks, and the ENUM options
    it lacks, drop the ENUM options model.json no longer lists and no record uses, make optional what model.json no
    longer requires, and relabel what it labels differently. Nothing is renamed, retyped or made required, and no
    field or used option is removed, so imported records stay valid.
    Returns (added, updated), or None when Core refused (already reported)."""
    name = obj["name"]
    added = updated = 0
    wanted = object_flags(obj)
    if current_object is not None and any(bool(current_object.get(f)) != wanted[f] for f in OBJECT_FLAGS):
        object_path = f"/api/objects/{name}"
        try:
            client.put(object_path, object_update_payload(obj))
        except CoreError as e:
            _fatal("PUT", object_path, e)
            return None
        print(f"update object {name} (apiOnly={str(wanted['apiOnly']).lower()}, appendOnly={str(wanted['appendOnly']).lower()})")
        updated += 1
    path = f"/api/metadata/objects/{name}/fields"
    try:
        existing = _fields_by_name(client.get(path))
    except CoreError as e:
        _fatal("GET", path, e)
        return None
    for field in obj["fields"]:
        current = existing.get(field["name"])
        if current is None:
            try:
                status, _ = client.post(path, field_payload(model, field))
            except CoreError as e:
                _fatal("POST", path, e)
                return None
            print(f"add    field {name}.{field['name']} ({status})")
            added += 1
            continue
        extra = missing_options(model, field, current)
        dropped = []
        for option in dropped_options(model, field, current):
            try:
                using = _records_using(client, name, field["name"], option)
            except CoreError as e:
                _fatal("GET", f"/api/objects/{name}/records", e)
                return None
            if using:
                records = "1 record uses" if using == 1 else f"{using} records use"
                print(f"keep   option {name}.{field['name']} {option}: {records} it")
            else:
                dropped.append(option)
        if extra or dropped:
            # model.json's order (page 5 sorts the tipos de unidad urbana), then what it no longer lists and a record uses
            kept = [o for o in dropped_options(model, field, current) if o not in dropped]
            options = model["enums"][field["enum"]] + kept
            field_path = f"{path}/{field['name']}"
            try:
                client.put(field_path, {"enumOptions": options})
            except CoreError as e:
                _fatal("PUT", field_path, e)
                return None
            changes = ([f"+{', '.join(extra)}"] if extra else []) + ([f"-{', '.join(dropped)}"] if dropped else [])
            print(f"update field {name}.{field['name']} ({'; '.join(changes)})")
            updated += 1
        # a field model.json stopped requiring is relaxed (always safe); one it started requiring is left alone
        if current.get("required") and not field.get("required"):
            field_path = f"{path}/{field['name']}"
            try:
                client.put(field_path, {"required": False})
            except CoreError as e:
                _fatal("PUT", field_path, e)
                return None
            print(f"update field {name}.{field['name']} (optional)")
            updated += 1
        # a label is only what the admin shows: model.json's wins
        if current.get("label") != field["label"]:
            field_path = f"{path}/{field['name']}"
            try:
                client.put(field_path, {"label": field["label"]})
            except CoreError as e:
                _fatal("PUT", field_path, e)
                return None
            print(f"update field {name}.{field['name']} (label)")
            updated += 1
    # a field model.json no longer has is reported, never removed (it may hold data); one that is required is relaxed, so
    # a record written without it is still valid. a relationship's field is not model.json's field list: it is not extra
    wanted_names = {f["name"] for f in obj["fields"]} | {r["fieldName"] for r in model["relationships"] if r["source"] == name}
    for field_name, current in existing.items():
        if field_name in wanted_names or current.get("type") == "RELATION":
            continue
        print(f"extra  field {name}.{field_name} (model.json no longer has it)")
        if current.get("required"):
            field_path = f"{path}/{field_name}"
            try:
                client.put(field_path, {"required": False})
            except CoreError as e:
                _fatal("PUT", field_path, e)
                return None
            print(f"update field {name}.{field_name} (optional)")
            updated += 1
    return added, updated


def do_apply(client, model):
    created = 0
    updated = 0
    skipped = 0

    existing = _existing_objects(client.get("/api/objects"))
    for obj in model["objects"]:
        name = obj["name"]
        if name in existing:
            synced = sync_object(client, model, obj, existing[name])
            if synced is None:
                return 1
            added, changed = synced
            created += added
            updated += changed
            if not added and not changed:
                print(f"skip   object {name} (exists)")
                skipped += 1
            continue
        try:
            status, _ = client.post("/api/objects", object_payload(model, obj))
            print(f"create object {name} ({status})")
            created += 1
        except CoreError as e:
            if e.status == 409:
                print(f"skip   object {name} (exists)")
                skipped += 1
            else:
                _fatal("POST", "/api/objects", e)
                return 1

    for rel in model["relationships"]:
        name = rel["name"]
        try:
            status, _ = client.post("/api/relationships", relationship_payload(rel))
            print(f"create relationship {name} ({status})")
            created += 1
        except CoreError as e:
            if e.status == 409:
                print(f"skip   relationship {name} (exists)")
                skipped += 1
            else:
                _fatal("POST", "/api/relationships", e)
                return 1
        if not _apply_required_put(client, rel):
            return 1

    changed = _apply_uniques(client, model)
    if changed is None:
        return 1
    updated += changed

    print(f"done: {created} created, {updated} updated, {skipped} skipped")
    return 0


def _apply_uniques(client, model):
    """Phase 3, once the relationships exist: the uniqueConstraints of every object (the full list, so the PUT carries the
    whole object) and the `unique: true` of a relationship's field. Each only when it differs from what Core has
    (Core leaves the key out of an object with none). Returns how many it put, or None when Core refused."""
    changed = 0
    stored = _existing_objects(client.get("/api/objects"))
    for obj in model["objects"]:
        name = obj["name"]
        wanted = unique_constraints(obj)
        if name not in stored or stored[name].get("uniqueConstraints", []) == wanted:
            continue
        path = f"/api/objects/{name}"
        try:
            client.put(path, {**object_update_payload(obj), "uniqueConstraints": wanted})
        except CoreError as e:
            _fatal("PUT", path, e)
            return None
        print(f"update object {name} (uniqueConstraints={json.dumps(wanted)})")
        changed += 1
    for rel in model["relationships"]:
        if not rel.get("unique"):
            continue
        path = f"/api/metadata/objects/{rel['source']}/fields"
        try:
            current = _fields_by_name(client.get(path)).get(rel["fieldName"], {})
            if current.get("unique"):
                continue
            client.put(f"{path}/{rel['fieldName']}", {"unique": True})
        except CoreError as e:
            _fatal("PUT", f"{path}/{rel['fieldName']}", e)
            return None
        print(f"update field {rel['source']}.{rel['fieldName']} (unique)")
        changed += 1
    return changed


def _delete_one(client, label, path):
    """Delete one entity; print the result. Returns 'deleted', 'skipped', or None (fatal, already reported)."""
    try:
        status, _ = client.delete(path)
        print(f"delete {label} ({status})")
        return "deleted"
    except CoreError as e:
        if e.status == 404:
            print(f"skip   {label} (not found)")
            return "skipped"
        _fatal("DELETE", path, e)
        return None


def do_drop(client, model):
    deleted = 0
    skipped = 0

    # an appendOnly object keeps its relationships: Core refuses to delete one whose field sits on it, or sits in a
    # uniqueConstraints set. the rules go first, with the labels the PUT replaces; an object already gone has nothing to
    # turn off
    for obj in model["objects"]:
        if not obj.get("appendOnly") and not unique_constraints(obj):
            continue
        path = f"/api/objects/{obj['name']}"
        try:
            client.put(path, {**object_update_payload(obj, appendOnly=False), "uniqueConstraints": []})
            print(f"update object {obj['name']} (appendOnly=false, uniqueConstraints=[])")
        except CoreError as e:
            if e.status == 404:
                print(f"skip   object {obj['name']} (not found)")
                continue
            _fatal("PUT", path, e)
            return 1

    to_delete = [
        (f"relationship {rel['name']}", f"/api/relationships/{rel['name']}")
        for rel in reversed(model["relationships"])
    ] + [
        (f"object {obj['name']}", f"/api/objects/{obj['name']}")
        for obj in reversed(model["objects"])
    ]
    for label, path in to_delete:
        result = _delete_one(client, label, path)
        if result is None:
            return 1
        deleted += result == "deleted"
        skipped += result == "skipped"

    print(f"done: {deleted} deleted, {skipped} skipped")
    return 0


def _print_dry_run(model):
    for obj in model["objects"]:
        print("# POST /api/objects")
        print(json.dumps(object_payload(model, obj), indent=2, ensure_ascii=False))
    for rel in model["relationships"]:
        print("# POST /api/relationships")
        print(json.dumps(relationship_payload(rel), indent=2, ensure_ascii=False))
    for obj in model["objects"]:
        # the sets over the object's own fields went in its POST
        if unique_constraints(obj) != _own_unique_constraints(obj):
            print(f"# PUT /api/objects/{obj['name']}")
            print(json.dumps({**object_update_payload(obj), "uniqueConstraints": unique_constraints(obj)}, indent=2, ensure_ascii=False))
    for rel in model["relationships"]:
        if rel.get("unique"):
            print(f"# PUT /api/metadata/objects/{rel['source']}/fields/{rel['fieldName']}")
            print(json.dumps({"unique": True}))


def _print_drop_dry_run(model):
    for obj in model["objects"]:
        if obj.get("appendOnly") or unique_constraints(obj):
            print(f"# PUT /api/objects/{obj['name']} (appendOnly=false, uniqueConstraints=[])")
    for rel in reversed(model["relationships"]):
        print(f"# DELETE /api/relationships/{rel['name']}")
    for obj in reversed(model["objects"]):
        print(f"# DELETE /api/objects/{obj['name']}")


def default_model_path():
    return os.path.join(os.path.dirname(os.path.abspath(__file__)), "model.json")


def _parse_args(argv):
    p = argparse.ArgumentParser(description="Apply caja's model.json to a wasichai Core.")
    p.add_argument("--model", default=default_model_path())
    p.add_argument("--core", default=os.environ.get("WASICHAI_CORE", "http://localhost:8091"))
    p.add_argument("--email", default=os.environ.get("WASICHAI_EMAIL", "admin@wasichai.local"))
    p.add_argument("--password", default=os.environ.get("WASICHAI_PASSWORD", "admin"))
    p.add_argument("--dry-run", action="store_true")
    p.add_argument("--drop", action="store_true")
    p.add_argument("--validate-only", action="store_true")
    return p.parse_args(argv)


def main(argv=None):
    argv = sys.argv[1:] if argv is None else argv
    args = _parse_args(argv)

    with open(args.model, encoding="utf-8") as f:
        model = json.load(f)

    errors = validate(model)
    if errors:
        for e in errors:
            print(f"model: {args.model}: {e}")
        return 2
    if args.validate_only:
        return 0

    if args.dry_run:
        if args.drop:
            _print_drop_dry_run(model)
        else:
            _print_dry_run(model)
        return 0

    client = Client(args.core)
    try:
        client.login(args.email, args.password)
    except CoreError as e:
        _fatal("POST", "/api/auth/login", e)
        return 1

    if args.drop:
        return do_drop(client, model)
    return do_apply(client, model)


if __name__ == "__main__":
    sys.exit(main())
