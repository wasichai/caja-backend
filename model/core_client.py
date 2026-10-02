"""Minimal wasichai Core REST client shared by apply.py and the importers.

Stdlib only. Taken from wasichai's examples/gis-sample/perene/apply.py, plus list_all() to page
through records.
"""
import json
import urllib.error
import urllib.parse
import urllib.request

PAGE_SIZE = 200  # Core's max page size


class CoreError(Exception):
    def __init__(self, status, body):
        super().__init__(f"{status}: {body}")
        self.status = status
        self.body = body


class Client:
    def __init__(self, base_url):
        self.base_url = base_url.rstrip("/")
        self.token = None

    def _call(self, method, path, body=None, use_auth=True):
        url = self.base_url + path
        headers = {"Content-Type": "application/json"}
        if use_auth and self.token:
            headers["Authorization"] = f"Bearer {self.token}"
        data = json.dumps(body).encode("utf-8") if body is not None else None
        req = urllib.request.Request(url, data=data, method=method, headers=headers)
        try:
            with urllib.request.urlopen(req) as resp:
                status = resp.status
                raw = resp.read()
        except urllib.error.HTTPError as e:
            status = e.code
            raw = e.read()
            if method == "GET" and status == 404:
                return status, None
            text = raw.decode("utf-8", errors="replace") if raw else ""
            raise CoreError(status, text)
        except (urllib.error.URLError, ConnectionError, OSError) as e:
            reason = getattr(e, "reason", e)
            raise CoreError(f"connection failed: {reason}", "")
        text = raw.decode("utf-8") if raw else ""
        parsed = json.loads(text) if text else None
        return status, parsed

    def login(self, email, password):
        _, result = self._call("POST", "/api/auth/login", {"email": email, "password": password}, use_auth=False)
        if not isinstance(result, dict) or "token" not in result:
            raise CoreError("no token in response", "")
        self.token = result["token"]
        return self.token

    def get(self, path):
        _, parsed = self._call("GET", path)
        return parsed

    def post(self, path, body):
        return self._call("POST", path, body)

    def put(self, path, body):
        return self._call("PUT", path, body)

    def delete(self, path):
        return self._call("DELETE", path)

    def list_all(self, object_name, **filters):
        """Every record of an object, following Core's pages. Filters are equality filters."""
        records = []
        page = 0
        while True:
            query = urllib.parse.urlencode({"page": page, "size": PAGE_SIZE, "sort": "id", **filters})
            data = self.get(f"/api/objects/{object_name}/records?{query}")
            if data is None:
                raise CoreError(404, f"object {object_name} does not exist")
            records.extend(data.get("content", []))
            page += 1
            if page >= data.get("totalPages", 0):
                return records
