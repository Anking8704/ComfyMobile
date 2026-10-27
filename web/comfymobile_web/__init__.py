"""ComfyMobile web shell for iPad and other tablets.

Serves a touch-first page at /comfymobile/ that embeds the original ComfyUI editor from the same
server. It registers no nodes and touches no models or frontend files.
"""
import os

from aiohttp import web
from server import PromptServer

STATIC = os.path.join(os.path.dirname(os.path.realpath(__file__)), "static")
FILES = {
    "index.html": "text/html; charset=utf-8",
    "app.css": "text/css; charset=utf-8",
    "app.js": "application/javascript; charset=utf-8",
    "editor-adapter.js": "application/javascript; charset=utf-8",
    "prompt-library.json": "application/json; charset=utf-8",
    "manifest.webmanifest": "application/manifest+json; charset=utf-8",
    "icon-180.png": "image/png",
    "icon-512.png": "image/png",
}


def _serve(name):
    path = os.path.join(STATIC, name)
    if name not in FILES or not os.path.isfile(path):
        raise web.HTTPNotFound()
    return web.FileResponse(path, headers={"Content-Type": FILES[name], "Cache-Control": "no-cache"})


routes = PromptServer.instance.routes


@routes.get("/comfymobile")
async def comfymobile_redirect(request):
    raise web.HTTPFound("/comfymobile/")


@routes.get("/comfymobile/")
async def comfymobile_index(request):
    return _serve("index.html")


@routes.get("/comfymobile/{name}")
async def comfymobile_file(request):
    return _serve(request.match_info["name"])


NODE_CLASS_MAPPINGS = {}
NODE_DISPLAY_NAME_MAPPINGS = {}
