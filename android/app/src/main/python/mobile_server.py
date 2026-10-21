"""Run Fyndkartan's existing FastAPI application inside the Android app."""

import os
import threading
from pathlib import Path

_server = None
_thread = None


def start(runtime_dir: str, database_path: str) -> None:
    """Start the local-only API once. Android owns and persists the SQLite file."""
    global _server, _thread
    if _thread is not None and _thread.is_alive():
        return

    runtime = Path(runtime_dir)
    database = Path(database_path)
    database.parent.mkdir(parents=True, exist_ok=True)
    os.environ["FYNDKARTAN_MOBILE_MODE"] = "1"
    os.environ["DB_PATH"] = str(database)
    os.environ.pop("DATABASE_URL", None)

    # The API package is bundled by Chaquopy; web assets and writable data live in
    # Android's private app directory, not in the APK's read-only asset archive.
    from api import config

    config.BASE_DIR = runtime
    config.DB_PATH = database

    from api.main import app
    import uvicorn

    configuration = uvicorn.Config(
        app,
        host="127.0.0.1",
        port=8700,
        log_level="error",
        http="h11",
        lifespan="on",
        access_log=False,
    )
    _server = uvicorn.Server(configuration)
    _thread = threading.Thread(target=_server.run, name="fyndkartan-api", daemon=True)
    _thread.start()
