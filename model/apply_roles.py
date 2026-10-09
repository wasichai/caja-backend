#!/usr/bin/env python3
"""Crea o sincroniza en un wasichai Core los roles de caja que declara model/roles.json.

Cada rol dice, por objeto del modelo, sus acciones: READ, CREATE, UPDATE, DELETE o una que el modelo declare para ese
objeto (ANULAR_AJENO sobre recibo). Un rol que falta se crea (POST /api/roles) y recibe sus permisos (PUT
/api/roles/{name}/permissions); la acción declarada la da apply.py antes. Uno que ya existe queda con los permisos de
roles.json, ni uno más ni uno menos: un permiso que se le dio a mano en el admin se pierde en la siguiente corrida.
Su etiqueta se rehace si difiere. Es idempotente: la segunda corrida no escribe nada ("done: 0 created, 0 updated,
4 skipped").

roles.json se valida antes de llamar a core: los nombres que core acepta, las acciones que conoce y los objetos que
model.json tiene. ADMIN no se declara: core lo deja pasar todo.

Correr: python3 apply_roles.py [--dry-run] [--core URL] [--email E] [--password P]
Salida: 0 si va bien, 1 si core rechaza algo o no responde, 2 si roles.json no es válido, o si roles.json o
model.json faltan o no son JSON.
"""
import argparse
import json
import os
import re
import sys

from core_client import Client, CoreError

HERE = os.path.dirname(os.path.abspath(__file__))
# las de core (wasichai.core.common.Actions) que se dan sobre un objeto
ACTIONS = ("READ", "CREATE", "UPDATE", "DELETE")
# AdminService.createRole
ROLE_NAME = re.compile(r"^[A-Z][A-Z0-9_]{1,48}$")


def validate(roles, model):
    """Los errores de roles.json frente a model.json; vacía si es válido."""
    errors = []
    # las acciones que cada objeto acepta: las de core y las que el modelo declara para él
    objects = {o["name"]: ACTIONS + tuple(a["name"] for a in o.get("actions", [])) for o in model.get("objects", [])}
    seen = set()
    for role in roles.get("roles", []):
        name = role.get("name", "")
        if not isinstance(name, str) or not ROLE_NAME.fullmatch(name):
            errors.append(f"role '{name}': name must match {ROLE_NAME.pattern}")
        if name in seen:
            errors.append(f"role {name}: duplicate role name")
        seen.add(name)
        if not role.get("label"):
            errors.append(f"role {name}: label is missing")
        for obj, actions in role.get("permisos", {}).items():
            if obj not in objects:
                errors.append(f"role {name}: object '{obj}' is not in model.json")
            for action in actions:
                if obj in objects and action not in objects[obj]:
                    errors.append(f"role {name}: action '{action}' on {obj} must be one of {', '.join(objects[obj])}")
    return errors


def permissions_payload(role):
    """El cuerpo de PUT /api/roles/{name}/permissions, en el orden de roles.json."""
    return {"permissions": [{"objectName": obj, "action": action}
                            for obj, actions in role["permisos"].items() for action in actions]}


def _granted(permissions):
    """Lo que un rol de core concede, como pares (objeto, acción)."""
    return {(p.get("objectName"), p["action"]) for p in permissions or [] if p.get("allowed", True)}


def _wanted(role):
    return {(p["objectName"], p["action"]) for p in permissions_payload(role)["permissions"]}


def _fatal(method, path, err):
    print(f"error {method} {path} -> {err.status}\n{err.body}", file=sys.stderr)


def do_apply(client, roles):
    created = updated = skipped = 0
    try:
        existing = {r["name"]: r for r in client.get("/api/roles") or []}
    except CoreError as e:
        _fatal("GET", "/api/roles", e)
        return 1
    for role in roles["roles"]:
        name = role["name"]
        current = existing.get(name)
        writes = []
        if current is None:
            writes.append(("POST", "/api/roles", {"name": name, "label": role["label"]}, f"create role {name}"))
            writes.append(("PUT", f"/api/roles/{name}/permissions", permissions_payload(role), None))
        else:
            if current.get("label") != role["label"]:
                writes.append(("PUT", f"/api/roles/{name}", {"label": role["label"]}, f"update role {name} (label)"))
            if _granted(current.get("permissions")) != _wanted(role):
                writes.append(("PUT", f"/api/roles/{name}/permissions", permissions_payload(role),
                               f"update role {name} (permissions)"))
        for method, path, body, message in writes:
            try:
                (client.post if method == "POST" else client.put)(path, body)
            except CoreError as e:
                _fatal(method, path, e)
                return 1
            if message:
                print(message)
        if current is None:
            created += 1
        elif writes:
            updated += 1
        else:
            print(f"skip   role {name} (up to date)")
            skipped += 1
    print(f"done: {created} created, {updated} updated, {skipped} skipped")
    return 0


def _print_dry_run(roles):
    for role in roles["roles"]:
        print("# POST /api/roles")
        print(json.dumps({"name": role["name"], "label": role["label"]}, indent=2, ensure_ascii=False))
        print(f"# PUT /api/roles/{role['name']}/permissions")
        print(json.dumps(permissions_payload(role), indent=2, ensure_ascii=False))


def _parse_args(argv):
    p = argparse.ArgumentParser(description="Crea o sincroniza los roles de caja (roles.json) en wasichai Core.")
    p.add_argument("--roles", default=os.path.join(HERE, "roles.json"))
    p.add_argument("--model", default=os.path.join(HERE, "model.json"))
    p.add_argument("--core", default=os.environ.get("WASICHAI_CORE", "http://localhost:8091"))
    p.add_argument("--email", default=os.environ.get("WASICHAI_EMAIL", "admin@wasichai.local"))
    p.add_argument("--password", default=os.environ.get("WASICHAI_PASSWORD", "admin"))
    p.add_argument("--dry-run", action="store_true", help="imprime lo que mandaría a un core vacío; no llama a core")
    return p.parse_args(argv)


def _load(path):
    """El json de `path`, o None tras decir en stderr por qué no se pudo leer (sin traza)."""
    try:
        with open(path, encoding="utf-8") as f:
            return json.load(f)
    except OSError as e:
        print(f"error: {path}: {e.strerror or e}", file=sys.stderr)
    except ValueError as e:
        # json.JSONDecodeError y UnicodeDecodeError son ValueError
        print(f"error: {path}: no es JSON válido: {e}", file=sys.stderr)
    return None


def main(argv=None):
    args = _parse_args(sys.argv[1:] if argv is None else argv)
    roles = _load(args.roles)
    model = _load(args.model)
    if roles is None or model is None:
        return 2
    errors = validate(roles, model)
    if errors:
        for e in errors:
            print(f"roles: {args.roles}: {e}")
        return 2
    if args.dry_run:
        _print_dry_run(roles)
        return 0
    client = Client(args.core)
    try:
        client.login(args.email, args.password)
    except CoreError as e:
        _fatal("POST", "/api/auth/login", e)
        return 1
    return do_apply(client, roles)


if __name__ == "__main__":
    sys.exit(main())
