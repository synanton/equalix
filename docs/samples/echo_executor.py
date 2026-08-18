#!/usr/bin/env python3
"""Minimal Equalix worker: POST /tasks/{id}/execute → POST Equalix /complete."""

from __future__ import annotations

import base64
import json
import os
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

EQUALIX_URL = os.environ.get("EQUALIX_URL", "http://localhost:8080").rstrip("/")
API_KEY = os.environ.get("EQUALIX_API_KEY", "changeme")
PORT = int(os.environ.get("EXECUTOR_PORT", "9090"))


def read_u32(buf: bytes, offset: int) -> int:
    return int.from_bytes(buf[offset : offset + 4], "big")


def parse_envelope(body: bytes) -> tuple[bytes, bytes, bytes]:
    if len(body) < 24:
        raise ValueError(f"envelope too short: {len(body)} bytes")
    task_id = body[0:16]
    payload_len = read_u32(body, 16)
    payload_start = 20
    payload_end = payload_start + payload_len
    if payload_end + 4 > len(body):
        raise ValueError("payload length exceeds body")
    payload = body[payload_start:payload_end]
    prev_len = read_u32(body, payload_end)
    prev_start = payload_end + 4
    previous = body[prev_start : prev_start + prev_len]
    return task_id, payload, previous


def complete(task_id: str, payload: bytes, previous: bytes) -> None:
    result = b"echo:" + payload
    if previous:
        result += b"|prev:" + previous
    body = json.dumps(
        {"success": True, "result": base64.b64encode(result).decode("ascii")}
    ).encode("utf-8")
    request = Request(
        f"{EQUALIX_URL}/api/v1/tasks/{task_id}/complete",
        data=body,
        method="POST",
        headers={
            "Content-Type": "application/json",
            "X-API-Key": API_KEY,
        },
    )
    try:
        with urlopen(request, timeout=10) as response:
            response.read()
    except HTTPError as error:
        sys.stderr.write(f"complete {task_id} HTTP {error.code}: {error.read()!r}\n")
    except URLError as error:
        sys.stderr.write(f"complete {task_id} failed: {error}\n")


class Handler(BaseHTTPRequestHandler):
    def log_message(self, format: str, *args: object) -> None:
        sys.stderr.write("%s - %s\n" % (self.address_string(), format % args))

    def do_POST(self) -> None:  # noqa: N802
        prefix = "/tasks/"
        suffix = "/execute"
        if not (self.path.startswith(prefix) and self.path.endswith(suffix)):
            self.send_error(404)
            return
        task_id = self.path[len(prefix) : -len(suffix)]
        length = int(self.headers.get("Content-Length", "0"))
        body = self.rfile.read(length)
        try:
            _, payload, previous = parse_envelope(body)
        except ValueError as error:
            sys.stderr.write(f"bad envelope for {task_id}: {error}\n")
            self.send_error(400, str(error))
            return
        self.send_response(202)
        self.send_header("Content-Length", "0")
        self.end_headers()
        complete(task_id, payload, previous)


def main() -> None:
    server = ThreadingHTTPServer(("0.0.0.0", PORT), Handler)
    sys.stderr.write(
        f"echo executor on :{PORT} → {EQUALIX_URL} (key={API_KEY[:4]}…)\n"
    )
    server.serve_forever()


if __name__ == "__main__":
    main()
