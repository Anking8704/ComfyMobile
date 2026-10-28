"""ComfyMobile web shell for iPad and other tablets.

Serves a touch-first page at /comfymobile/ that embeds the original ComfyUI editor from the same
server. It registers no nodes and touches no models or frontend files.

Optional LAN access code: put {"token": "..."} in config.json next to this file (scripts/install-web.ps1 -Token).
When set, requests from other devices need the code once (via /comfymobile/login?token=...); this computer
itself (loopback) is never asked. An empty or missing token turns the check off. Changes apply without restart.
"""
import hmac
import json
import os

from aiohttp import web
from server import PromptServer

HERE = os.path.dirname(os.path.realpath(__file__))
STATIC = os.path.join(HERE, "static")
CONFIG = os.path.join(HERE, "config.json")
COOKIE = "comfymobile_token"
FILES = {
    "index.html": "text/html; charset=utf-8",
    "app.css": "text/css; charset=utf-8",
    "app.js": "application/javascript; charset=utf-8",
    "prompt-text.js": "application/javascript; charset=utf-8",
    "cm-core.js": "application/javascript; charset=utf-8",
    "cm-canvas.js": "application/javascript; charset=utf-8",
    "cm-prompt.js": "application/javascript; charset=utf-8",
    "cm-simple.js": "application/javascript; charset=utf-8",
    "cm-results.js": "application/javascript; charset=utf-8",
    "editor-adapter.js": "application/javascript; charset=utf-8",
    "prompt-library.json": "application/json; charset=utf-8",
    "manifest.webmanifest": "application/manifest+json; charset=utf-8",
    "icon-180.png": "image/png",
    "icon-512.png": "image/png",
    "ComfyMobile.apk": "application/vnd.android.package-archive",
}
_config_cache = {"mtime": None, "token": ""}


def _token():
    try:
        mtime = os.path.getmtime(CONFIG)
    except OSError:
        return ""
    if mtime != _config_cache["mtime"]:
        try:
            with open(CONFIG, encoding="utf-8") as f:
                _config_cache["token"] = str(json.load(f).get("token") or "").strip()
        except (OSError, ValueError):
            _config_cache["token"] = ""
        _config_cache["mtime"] = mtime
    return _config_cache["token"]


def _loopback(request):
    return (request.remote or "") in ("127.0.0.1", "::1", "localhost")


def _serve(name):
    path = os.path.join(STATIC, name)
    if name not in FILES or not os.path.isfile(path):
        raise web.HTTPNotFound()
    headers = {"Content-Type": FILES[name], "Cache-Control": "no-cache"}
    if name.endswith(".apk"):
        headers["Content-Disposition"] = 'attachment; filename="ComfyMobile.apk"'
    return web.FileResponse(path, headers=headers)


@web.middleware
async def comfymobile_access(request, handler):
    token = _token()
    if not token or _loopback(request) or request.path == "/comfymobile/login":
        return await handler(request)
    offered = request.cookies.get(COOKIE) or request.headers.get("X-ComfyMobile-Token") or ""
    if offered and hmac.compare_digest(offered.encode("utf-8"), token.encode("utf-8")):
        return await handler(request)
    return web.Response(status=401, content_type="text/html", charset="utf-8", text=(
        "<meta name=viewport content='width=device-width'><h2>需要访问口令</h2>"
        "<p>这台电脑的 ComfyUI 开启了局域网访问口令。请打开 <code>/comfymobile/login?token=口令</code> 一次，"
        "或在安卓 APP 的连接设置里填写口令。</p>"))


routes = PromptServer.instance.routes


@routes.get("/comfymobile")
async def comfymobile_redirect(request):
    raise web.HTTPFound("/comfymobile/")


@routes.get("/comfymobile/login")
async def comfymobile_login(request):
    token, offered = _token(), request.query.get("token", "")
    if token and not hmac.compare_digest(offered.encode("utf-8"), token.encode("utf-8")):
        return web.Response(status=403, content_type="text/html", charset="utf-8", text="<h2>口令不正确</h2>")
    response = web.HTTPFound("/comfymobile/")
    if token:
        response.set_cookie(COOKIE, offered, max_age=365 * 24 * 3600, httponly=True, samesite="Lax", path="/")
    raise response


@routes.get("/comfymobile/")
async def comfymobile_index(request):
    return _serve("index.html")


@routes.get("/comfymobile/{name}")
async def comfymobile_file(request):
    return _serve(request.match_info["name"])


try:
    PromptServer.instance.app.middlewares.append(comfymobile_access)
except Exception as error:  # the app is already frozen in unusual launch setups; the page still works without the code
    print(f"[ComfyMobileWeb] 无法启用局域网访问口令：{error}")

NODE_CLASS_MAPPINGS = {}
NODE_DISPLAY_NAME_MAPPINGS = {}
