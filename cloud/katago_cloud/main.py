from __future__ import annotations

import asyncio
import hashlib
import hmac
import json
import logging
import os
import re
import time
import uuid
from contextlib import asynccontextmanager
from pathlib import Path
from typing import Literal

from fastapi import Depends, FastAPI, Header, HTTPException, status
from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator

logging.basicConfig(level=os.getenv("LOG_LEVEL", "INFO"))
log = logging.getLogger("katago-cloud")

KATAGO_BIN = os.getenv("KATAGO_BIN", "katago")
KATAGO_CONFIG = Path(os.getenv("KATAGO_CONFIG", "/data/analysis.cfg"))
KATAGO_MODEL = Path(os.getenv("KATAGO_MODEL", "/models/model.bin.gz"))
API_TOKEN = os.getenv("KATAGO_API_TOKEN", "")
MAX_CONCURRENT = max(1, int(os.getenv("KATAGO_MAX_CONCURRENT", "1")))
DEFAULT_MAX_VISITS = max(1, int(os.getenv("KATAGO_DEFAULT_MAX_VISITS", "1200")))
HARD_MAX_VISITS = max(DEFAULT_MAX_VISITS, int(os.getenv("KATAGO_HARD_MAX_VISITS", "100000")))
PROCESS_START_TIMEOUT = 30
MAX_LINE_BYTES = 2 * 1024 * 1024
COORD_RE = re.compile(r"^[A-HJ-Z][1-9][0-9]?$", re.IGNORECASE)


class AnalyzeRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")
    request_id: str = Field(default_factory=lambda: str(uuid.uuid4()), min_length=1, max_length=80)
    board_x_size: int = Field(default=19, ge=2, le=25)
    board_y_size: int = Field(default=19, ge=2, le=25)
    rules: Literal["chinese", "japanese", "korean", "aga", "tromp-taylor", "new-zealand", "stone-scoring"] = "chinese"
    komi: float = Field(default=7.5, ge=-100.0, le=100.0)
    to_move: Literal["B", "W"] = "B"
    moves: list[tuple[Literal["B", "W"], str]] = Field(default_factory=list, max_length=2000)
    initial_stones: list[tuple[Literal["B", "W"], str]] = Field(default_factory=list, max_length=625)
    max_visits: int | None = Field(default=None, ge=1)
    include_ownership: bool = False
    include_policy: bool = False

    @model_validator(mode="after")
    def validate_history(self):
        for index in range(1, len(self.moves)):
            if self.moves[index][0] == self.moves[index - 1][0]:
                raise ValueError("Move colors must alternate in the supplied history")
        if self.moves:
            expected = "W" if self.moves[-1][0] == "B" else "B"
            if self.to_move != expected:
                raise ValueError("to_move does not match the supplied move history")
        for _, coord in [*self.moves, *self.initial_stones]:
            if coord.lower() == "pass":
                continue
            col = ord(coord[0].upper()) - ord("A") - (1 if coord[0].upper() > "I" else 0)
            row = int(coord[1:])
            if col >= self.board_x_size or row > self.board_y_size:
                raise ValueError(f"Coordinate {coord} is outside the board")
        return self

    @field_validator("moves")
    @classmethod
    def validate_moves(cls, value: list[tuple[str, str]]) -> list[tuple[str, str]]:
        for _, coord in value:
            if coord.lower() != "pass" and not COORD_RE.fullmatch(coord):
                raise ValueError(f"Invalid move coordinate: {coord}")
        return value

    @field_validator("initial_stones")
    @classmethod
    def validate_setup_stones(cls, value: list[tuple[str, str]]) -> list[tuple[str, str]]:
        for _, coord in value:
            if not COORD_RE.fullmatch(coord):
                raise ValueError(f"Invalid setup-stone coordinate: {coord}")
        return value


class EngineUnavailable(Exception):
    pass


class KataGoProcess:
    """One persistent analysis-mode KataGo process; requests are serialized."""

    def __init__(self) -> None:
        self.process: asyncio.subprocess.Process | None = None
        self.lock = asyncio.Lock()
        self.start_lock = asyncio.Lock()
        self.stderr_task: asyncio.Task | None = None
        self.model_sha256: str | None = None
        self.started_at: float | None = None

    async def start(self) -> None:
        async with self.start_lock:
            if self.process and self.process.returncode is None:
                return
            await self._start_locked()

    async def _start_locked(self) -> None:
        if not KATAGO_CONFIG.is_file():
            raise EngineUnavailable(f"Missing KataGo config: {KATAGO_CONFIG}")
        if not KATAGO_MODEL.is_file():
            raise EngineUnavailable(f"Missing KataGo model: {KATAGO_MODEL}")
        try:
            self.process = await asyncio.wait_for(
                asyncio.create_subprocess_exec(
                    KATAGO_BIN, "analysis", "-config", str(KATAGO_CONFIG),
                    "-model", str(KATAGO_MODEL),
                    stdin=asyncio.subprocess.PIPE,
                    stdout=asyncio.subprocess.PIPE,
                    stderr=asyncio.subprocess.PIPE,
                    limit=MAX_LINE_BYTES,
                ),
                timeout=PROCESS_START_TIMEOUT,
            )
        except (OSError, asyncio.TimeoutError) as exc:
            self.process = None
            raise EngineUnavailable(f"Could not start KataGo: {exc}") from exc
        self.stderr_task = asyncio.create_task(self._drain_stderr(self.process))
        self.started_at = time.monotonic()
        log.info("KataGo analysis process started")

    async def _drain_stderr(self, process: asyncio.subprocess.Process) -> None:
        assert process.stderr is not None
        while True:
            line = await process.stderr.readline()
            if not line:
                return
            log.info("katago: %s", line.decode("utf-8", "replace").rstrip()[:1000])

    async def stop(self) -> None:
        process, self.process = self.process, None
        if process and process.returncode is None:
            process.terminate()
            try:
                await asyncio.wait_for(process.wait(), timeout=5)
            except asyncio.TimeoutError:
                process.kill()
                await process.wait()
        if self.stderr_task:
            self.stderr_task.cancel()
            self.stderr_task = None

    async def analyze(self, payload: dict, timeout: float) -> dict:
        async with self.lock:
            await self.start()
            process = self.process
            if not process or not process.stdin or not process.stdout:
                raise EngineUnavailable("KataGo process is not ready")
            request_id = payload["id"]
            try:
                process.stdin.write((json.dumps(payload, separators=(",", ":")) + "\n").encode())
                await process.stdin.drain()
                deadline = time.monotonic() + timeout
                while True:
                    remaining = deadline - time.monotonic()
                    if remaining <= 0:
                        raise asyncio.TimeoutError()
                    line = await asyncio.wait_for(process.stdout.readline(), timeout=remaining)
                    if not line:
                        raise EngineUnavailable("KataGo closed its output stream")
                    if len(line) > MAX_LINE_BYTES:
                        raise EngineUnavailable("KataGo response exceeded the line-size limit")
                    try:
                        item = json.loads(line)
                    except json.JSONDecodeError:
                        log.warning("Ignoring non-JSON KataGo output: %s", line[:200])
                        continue
                    if str(item.get("id")) != request_id:
                        log.warning("Ignoring stale KataGo response id=%s", item.get("id"))
                        continue
                    if item.get("error"):
                        raise EngineUnavailable(f"KataGo analysis error: {item['error']}")
                    if item.get("isDuringSearch") is False:
                        return item
            except (asyncio.TimeoutError, EngineUnavailable, asyncio.CancelledError):
                await self.stop()
                raise
            except Exception:
                await self.stop()
                raise


engine = KataGoProcess()
semaphore = asyncio.Semaphore(MAX_CONCURRENT)


def model_sha256() -> str:
    if engine.model_sha256 is None:
        digest = hashlib.sha256()
        with KATAGO_MODEL.open("rb") as handle:
            for chunk in iter(lambda: handle.read(1024 * 1024), b""):
                digest.update(chunk)
        engine.model_sha256 = digest.hexdigest()
    return engine.model_sha256


async def require_token(authorization: str | None = Header(default=None)) -> None:
    if not API_TOKEN:
        raise HTTPException(status_code=503, detail="Cloud API is not configured with an access token")
    supplied = authorization.removeprefix("Bearer ").strip() if authorization else ""
    if not hmac.compare_digest(supplied, API_TOKEN):
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Invalid bearer token")


@asynccontextmanager
async def lifespan(_: FastAPI):
    yield
    await engine.stop()


app = FastAPI(
    title="KataGo Cloud Engine",
    version="0.1.0",
    description="A private, authenticated KataGo analysis service.",
    lifespan=lifespan,
)


@app.get("/v1/healthz")
async def healthz() -> dict:
    return {"status": "ok", "service": "katago-cloud-engine", "protocol_version": 1}


@app.get("/v1/readyz", dependencies=[Depends(require_token)])
async def readyz() -> dict:
    try:
        async with engine.lock:
            await engine.start()
            checksum = model_sha256()
    except (EngineUnavailable, OSError) as exc:
        raise HTTPException(status_code=503, detail=str(exc)) from exc
    return {
        "status": "ready",
        "engine": "KataGo analysis",
        "model_sha256": checksum,
        "max_concurrent": MAX_CONCURRENT,
        "backend": "as-built KataGo backend; see deployment metadata",
    }


@app.post("/v1/analyze", dependencies=[Depends(require_token)])
async def analyze(request: AnalyzeRequest) -> dict:
    max_visits = request.max_visits or DEFAULT_MAX_VISITS
    if max_visits > HARD_MAX_VISITS:
        raise HTTPException(status_code=422, detail=f"max_visits exceeds server limit ({HARD_MAX_VISITS})")
    if request.moves:
        expected_to_move = "W" if request.moves[-1][0] == "B" else "B"
        if request.to_move != expected_to_move:
            raise HTTPException(status_code=422, detail=f"to_move must be {expected_to_move} after supplied move history")

    request_id = request.request_id
    payload = {
        "id": request_id,
        "boardXSize": request.board_x_size,
        "boardYSize": request.board_y_size,
        "rules": request.rules,
        "komi": request.komi,
        "initialStones": [list(stone) for stone in request.initial_stones],
        "moves": [list(move) for move in request.moves],
        "initialPlayer": request.moves[0][0] if request.moves else request.to_move,
        "includeOwnership": request.include_ownership,
        "includePolicy": request.include_policy,
    }
    payload["maxVisits"] = max_visits
    timeout = min(600.0, max(60.0, max_visits / 100.0 + 30.0))
    budget = {"max_visits": max_visits}

    started = time.monotonic()
    try:
        try:
            await asyncio.wait_for(semaphore.acquire(), timeout=20.0)
        except asyncio.TimeoutError as exc:
            raise HTTPException(status_code=429, detail="Cloud analysis queue is full; retry shortly") from exc
        try:
            result = await engine.analyze(payload, timeout)
        finally:
            semaphore.release()
        move_infos = result.get("moveInfos") or []
        best = min(move_infos, key=lambda item: item.get("order", 10**9), default=None)
        elapsed_ms = round((time.monotonic() - started) * 1000)
        return {
            "protocol_version": 1,
            "request_id": request_id,
            "source": "cloud",
            "engine": "KataGo",
            "model_sha256": model_sha256(),
            "search_budget": budget,
            "completed_visits": result.get("rootInfo", {}).get("visits"),
            "elapsed_ms": elapsed_ms,
            "best_move": best.get("move") if best else None,
            "best_move_info": best,
            "move_infos": move_infos,
            "root_info": result.get("rootInfo"),
            "ownership": result.get("ownership"),
            "policy": result.get("policy"),
            "raw": result,
        }
    except asyncio.TimeoutError as exc:
        raise HTTPException(status_code=504, detail="KataGo search timed out; worker was restarted") from exc
    except EngineUnavailable as exc:
        log.exception("KataGo worker unavailable")
        raise HTTPException(status_code=503, detail=str(exc)) from exc
