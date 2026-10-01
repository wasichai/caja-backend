"""A scripted fake wasichai Core for the tests: metadata routes plus in-memory records.

Adapted from wasichai's examples/gis-sample/perene/test_apply.py. Test helper only.
"""
import json
import re
import threading
import urllib.parse
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

RECORDS = re.compile(r"^/api/objects/([a-z0-9_]+)/records$")
RECORD = re.compile(r"^/api/objects/([a-z0-9_]+)/records/([0-9a-f-]+)$")
FIELDS = re.compile(r"^/api/metadata/objects/([a-z0-9_]+)/fields$")
ROLE = re.compile(r"^/api/roles/([A-Z0-9_]+)$")
ROLE_PERMISSIONS = re.compile(r"^/api/roles/([A-Z0-9_]+)/permissions$")


class FakeCoreHandler(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass  # keep test output quiet

    def _body(self):
        length = int(self.headers.get("Content-Length", 0))
        raw = self.rfile.read(length) if length else b""
        return json.loads(raw) if raw else None

    def _handle(self, method):
        server = self.server
        body = self._body()
        with server.lock:
            server.requests.append((method, self.path, self.headers.get("Authorization"), body))
            status, payload = server.script(method, self.path, body)
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.end_headers()
        if payload is not None:
            self.wfile.write(json.dumps(payload).encode("utf-8"))

    def do_GET(self):
        self._handle("GET")

    def do_POST(self):
        self._handle("POST")

    def do_PUT(self):
        self._handle("PUT")

    def do_DELETE(self):
        self._handle("DELETE")


class FakeCore:
    """existing_objects/existing_relationships mark names that 409 on create; records live in memory."""

    def __init__(self, existing_objects=(), existing_relationships=(), fail_on_post_object=None,
                 fail_put=False, fail_put_status=500, login_response=None, fail_on_record=None, existing_fields=None,
                 fail_on_update=None, fail_on_delete=None, fail_on_role=None):
        self.existing_objects = set(existing_objects)
        self.existing_fields = existing_fields or {}  # object name -> list of {"name", "enumOptions"?}
        self.existing_relationships = set(existing_relationships)
        self.fail_on_post_object = fail_on_post_object  # object name -> triggers 500
        self.fail_put = fail_put  # PUT .../fields/... -> fail_put_status
        self.fail_put_status = fail_put_status
        self.login_response = login_response  # override the default {"token": "t"}
        self.fail_on_record = fail_on_record  # object name -> its record POSTs answer 400
        self.fail_on_update = fail_on_update  # object name -> its record PUTs answer 400
        self.fail_on_delete = fail_on_delete  # object name -> its record DELETEs answer 409
        self.fail_on_role = fail_on_role  # role name -> its POST /api/roles answers 500
        self.records = {}  # object name -> list of {"id", "attributes"}
        self.roles = {}  # role name -> {"name", "label", "permissions": [{"objectName", "action", "allowed"}]}
        self.requests = []
        self.server = ThreadingHTTPServer(("127.0.0.1", 0), FakeCoreHandler)
        self.server.requests = self.requests
        self.server.script = self._script
        self.server.lock = threading.Lock()
        self.port = self.server.server_address[1]
        self.base_url = f"http://127.0.0.1:{self.port}"
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    def stop(self):
        self.server.shutdown()
        self.server.server_close()

    def add_record(self, object_name, attributes, geometries=None):
        record = {"id": str(uuid.uuid4()), "attributes": dict(attributes)}
        if geometries is not None:
            record["geometries"] = dict(geometries)
        self.records.setdefault(object_name, []).append(record)
        return record

    def _records(self, method, path, query, body):
        name = RECORDS.match(path).group(1)
        if method == "POST":
            if self.fail_on_record == name:
                return 400, {"detail": "Invalid option", "errors": [{"field": "uso", "message": "boom-record"}]}
            return 201, self.add_record(name, body["attributes"], body.get("geometries"))
        params = {k: v[0] for k, v in urllib.parse.parse_qs(query).items()}
        page = int(params.pop("page", 0))
        size = int(params.pop("size", 25))
        params.pop("sort", None)
        params.pop("dir", None)
        rows = [r for r in self.records.get(name, []) if all(str(r["attributes"].get(k)) == v for k, v in params.items())]
        total_pages = (len(rows) + size - 1) // size
        return 200, {
            "content": rows[page * size:(page + 1) * size],
            "page": page,
            "size": size,
            "totalElements": len(rows),
            "totalPages": total_pages,
        }

    def _update(self, path, body):
        """A record's PUT: Core replaces every field with what is sent."""
        name, record_id = RECORD.match(path).groups()
        if self.fail_on_update == name:
            return 400, {"detail": "Invalid option", "errors": [{"field": "tipo_via", "message": "boom-update"}]}
        record = next((r for r in self.records.get(name, []) if r["id"] == record_id), None)
        if record is None:
            return 404, {"detail": "not found"}
        record["attributes"] = dict(body["attributes"])
        return 200, record

    def _delete(self, path):
        """A record's DELETE: it leaves the records."""
        name, record_id = RECORD.match(path).groups()
        if self.fail_on_delete == name:
            return 409, {"detail": "In use", "errors": [{"field": "id", "message": "boom-delete"}]}
        records = self.records.get(name, [])
        if not any(r["id"] == record_id for r in records):
            return 404, {"detail": "not found"}
        self.records[name] = [r for r in records if r["id"] != record_id]
        return 204, None

    def _roles(self, method, path, body):
        """Core's role routes, addressed by name: list, create, relabel and replace the permissions."""
        if path == "/api/roles" and method == "GET":
            return 200, list(self.roles.values())
        if path == "/api/roles" and method == "POST":
            name = body["name"]
            if name == self.fail_on_role:
                return 500, {"message": "boom-role"}
            if name in self.roles:
                return 409, {"detail": f"Role '{name}' already exists"}
            self.roles[name] = {"name": name, "label": body["label"], "permissions": []}
            return 201, self.roles[name]
        match = ROLE_PERMISSIONS.match(path) or ROLE.match(path)
        role = self.roles.get(match.group(1)) if match and method == "PUT" else None
        if role is None:
            return 404, {"detail": "not found"}
        if ROLE_PERMISSIONS.match(path):
            role["permissions"] = [{**p, "allowed": p.get("allowed", True)} for p in body["permissions"]]
        else:
            role["label"] = body.get("label", role["label"])
        return 200, role

    def _script(self, method, full_path, body):
        path, _, query = full_path.partition("?")
        if path == "/api/roles" or path.startswith("/api/roles/"):
            return self._roles(method, path, body)
        if path == "/api/auth/login" and method == "POST":
            if self.login_response is not None:
                return 200, self.login_response
            return 200, {"token": "t"}
        if RECORDS.match(path) and method in ("GET", "POST"):
            return self._records(method, path, query, body)
        if RECORD.match(path) and method == "PUT":
            return self._update(path, body)
        if RECORD.match(path) and method == "DELETE":
            return self._delete(path)
        if path == "/api/objects" and method == "GET":
            return 200, [{"name": n} for n in self.existing_objects]
        if path == "/api/objects" and method == "POST":
            name = body["name"]
            if self.fail_on_post_object and name == self.fail_on_post_object:
                return 500, {"message": "boom"}
            if name in self.existing_objects:
                return 409, {"message": "exists"}
            return 201, {"name": name}
        if path == "/api/relationships" and method == "POST":
            name = body["name"]
            if name in self.existing_relationships:
                return 409, {"message": "exists"}
            return 201, {"name": name}
        fields = FIELDS.match(path)
        if fields and method == "GET":
            return 200, self.existing_fields.get(fields.group(1), [])
        if fields and method == "POST":
            return 201, {"name": body["name"]}
        if path.startswith("/api/metadata/objects/") and method == "PUT":
            if self.fail_put:
                return self.fail_put_status, {"message": "boom-put"}
            return 200, {"required": True}
        if path.startswith("/api/metadata/objects/") and "/fields/" in path and method == "DELETE":
            return 204, None
        if path.startswith("/api/relationships/") and method == "DELETE":
            return 204, None
        if path.startswith("/api/objects/") and method == "DELETE":
            return 204, None
        return 500, {"message": "boom"}
