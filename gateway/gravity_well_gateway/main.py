"""FastAPI + WebSocket application entrypoint for Gravity Well ACP Gateway."""

from __future__ import annotations

import asyncio
from contextlib import asynccontextmanager
import json
import socket
import time
from typing import Any, MutableMapping

from fastapi import Depends, FastAPI, File, Query, Request, Response, UploadFile, WebSocket, WebSocketDisconnect
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from pydantic import BaseModel, Field
from starlette.exceptions import HTTPException as StarletteHTTPException

from .auth import AuthGateASGIWrapper, AuthManager, require_auth
from .config import (
    ACP_PROTOCOL_VERSION,
    GATEWAY_VERSION,
    SERVICE_NAME,
    STATUS_TO_ERROR_CODE,
    GatewayConfig,
    GatewayError,
    utc_now_iso,
    validate_session_id,
)
from .replay import ReplayCatchup, ReplayHub, ReplaySubscription
from .storage import MAX_ATTACHMENT_BYTES, StorageManager
from .supervisor import SessionSupervisor


class CreateSessionRequest(BaseModel):
    cwd: str | None = None
    add_dirs: list[str] = Field(default_factory=list)
    title: str | None = None
    model: str | None = None
    thought_level: str | None = None
    mode: str | None = None
    agent: str | None = None
    sandbox: bool = False


class ResumeSessionRequest(BaseModel):
    cwd: str | None = None
    load_history: bool = False
    add_dirs: list[str] = Field(default_factory=list)
    sandbox: bool = False


class ForkSessionRequest(BaseModel):
    cwd: str | None = None


class RenameSessionRequest(BaseModel):
    title: str


class UpdateConfigRequest(BaseModel):
    model: str | None = None
    thought_level: str | None = None
    mode: str | None = None


class SubmitPromptRequest(BaseModel):
    text: str | None = None
    parts: list[dict[str, Any]] | None = None
    attachment_ids: list[str] = Field(default_factory=list)
    enqueue_if_busy: bool = True
    auto_resume: bool = False
    cwd: str | None = None


class EnqueueRequest(BaseModel):
    text: str
    attachment_ids: list[str] = Field(default_factory=list)


class EditQueueItemRequest(BaseModel):
    text: str


class SendNowRequest(BaseModel):
    interrupt: bool = True


class ResolvePermissionRequest(BaseModel):
    decision: str
    option_id: str | None = None


class ResolveQuestionRequest(BaseModel):
    action: str
    answers: dict[str, Any] = Field(default_factory=dict)


def _init_runtime_state(fastapi_app: FastAPI, explicit_config: GatewayConfig | None) -> None:
    if getattr(fastapi_app.state, "initialized", False):
        return
    cfg = explicit_config or GatewayConfig.from_env()
    auth = AuthManager(cfg)
    replay = ReplayHub(
        session_maxlen=cfg.session_ring_maxlen,
        global_maxlen=cfg.global_ring_maxlen,
    )
    storage = StorageManager(cfg)
    supervisor = SessionSupervisor(cfg, replay, storage)

    fastapi_app.state.config = cfg
    fastapi_app.state.auth = auth
    fastapi_app.state.replay = replay
    fastapi_app.state.storage = storage
    fastapi_app.state.supervisor = supervisor
    fastapi_app.state.started_mono = time.monotonic()
    fastapi_app.state.initialized = True


def _parse_optional_after(websocket: WebSocket) -> int | None:
    raw = websocket.query_params.get("after")
    if raw is None or raw == "":
        return None
    try:
        val = int(raw)
    except ValueError:
        return None
    return max(0, val)


async def _run_ws_stream(
    websocket: WebSocket,
    supervisor: SessionSupervisor,
    sub: ReplaySubscription,
    catchup: ReplayCatchup,
    *,
    allow_subscribe_filter: bool,
) -> None:
    await websocket.accept()
    last_sent_cursor = 0
    try:
        await websocket.send_json(
            {
                "frame": "hello",
                "gateway_version": GATEWAY_VERSION,
                "agy_version": supervisor.get_agy_version(),
                "server_time": utc_now_iso(),
                "latest_cursor": catchup.latest_cursor,
                "capabilities": supervisor.get_capabilities(),
            }
        )

        if catchup.after is not None:
            if catchup.truncated:
                await websocket.send_json(
                    {
                        "frame": "resync_required",
                        "after": catchup.after,
                        "min_cursor_retained": catchup.min_cursor_retained,
                        "latest_cursor": catchup.latest_cursor,
                    }
                )
                last_sent_cursor = catchup.latest_cursor
            else:
                for ev in catchup.events:
                    await websocket.send_json(ev)
                    last_sent_cursor = max(last_sent_cursor, int(ev["cursor"]))
                await websocket.send_json(
                    {
                        "frame": "replay_complete",
                        "after": catchup.after,
                        "replayed_count": len(catchup.events),
                        "latest_cursor": catchup.latest_cursor,
                    }
                )
                last_sent_cursor = max(last_sent_cursor, catchup.latest_cursor)
        else:
            last_sent_cursor = catchup.latest_cursor

        async def _sender_loop() -> None:
            nonlocal last_sent_cursor
            while True:
                ev = await sub.queue.get()
                ev_cursor = int(ev.get("cursor") or 0)
                if ev_cursor <= last_sent_cursor:
                    continue
                if not sub.matches(ev):
                    continue
                last_sent_cursor = ev_cursor
                await websocket.send_json(ev)

        async def _receiver_loop() -> None:
            while True:
                raw_text = await websocket.receive_text()
                try:
                    frame_obj = json.loads(raw_text)
                except json.JSONDecodeError:
                    await websocket.send_json(
                        {
                            "frame": "error",
                            "code": "bad_request",
                            "message": "Invalid JSON WebSocket frame.",
                        }
                    )
                    continue
                if not isinstance(frame_obj, dict):
                    await websocket.send_json(
                        {
                            "frame": "error",
                            "code": "bad_request",
                            "message": "WebSocket frame must be a JSON object.",
                        }
                    )
                    continue

                ftype = frame_obj.get("frame")
                if ftype == "ping":
                    await websocket.send_json({"frame": "pong", "ts": utc_now_iso()})
                elif ftype == "hello":
                    continue
                elif ftype == "subscribe" and allow_subscribe_filter:
                    sids = frame_obj.get("session_ids")
                    if sids is None:
                        sub.session_filter = None
                    elif isinstance(sids, list) and all(isinstance(x, str) for x in sids):
                        sub.session_filter = set(sids)
                    else:
                        await websocket.send_json(
                            {
                                "frame": "error",
                                "code": "bad_request",
                                "message": "`session_ids` must be a list of strings.",
                            }
                        )
                else:
                    await websocket.send_json(
                        {
                            "frame": "error",
                            "code": "bad_request",
                            "message": f"Unsupported WebSocket client frame: {ftype!r}",
                        }
                    )

        sender_task = asyncio.create_task(_sender_loop())
        try:
            await _receiver_loop()
        finally:
            sender_task.cancel()
            current = asyncio.current_task()
            if current is None or current.cancelling() == 0:
                try:
                    await sender_task
                except (asyncio.CancelledError, WebSocketDisconnect, RuntimeError):
                    pass
    except WebSocketDisconnect:
        pass
    finally:
        sub.close()


def create_fastapi_app(config: GatewayConfig | None = None) -> FastAPI:
    """Create the inner FastAPI application with all core routes and error handlers."""

    @asynccontextmanager
    async def lifespan(fastapi_app: FastAPI):
        _init_runtime_state(fastapi_app, config)
        try:
            yield
        finally:
            sup: SessionSupervisor | None = getattr(fastapi_app.state, "supervisor", None)
            if sup is not None:
                await sup.shutdown()
            fastapi_app.state.initialized = False

    fastapi_app = FastAPI(
        title="Gravity Well Gateway",
        version=GATEWAY_VERSION,
        lifespan=lifespan,
    )

    @fastapi_app.exception_handler(GatewayError)
    async def _handle_gateway_error(_req: Request, exc: GatewayError) -> JSONResponse:
        return JSONResponse(status_code=exc.status, content=exc.to_dict())

    @fastapi_app.exception_handler(RequestValidationError)
    async def _handle_validation_error(_req: Request, exc: RequestValidationError) -> JSONResponse:
        errs = exc.errors()
        for err in errs:
            if str(err.get("type", "")).startswith("json_invalid"):
                ge = GatewayError(400, "bad_request", "Malformed JSON request body.")
                return JSONResponse(status_code=400, content=ge.to_dict())
        sanitized = [
            {
                "loc": list(e.get("loc", ())),
                "msg": str(e.get("msg", "")),
                "type": str(e.get("type", "")),
            }
            for e in errs
        ]
        ge = GatewayError(
            422,
            "validation_error",
            "Request schema validation failed.",
            details={"errors": sanitized},
        )
        return JSONResponse(status_code=422, content=ge.to_dict())

    @fastapi_app.exception_handler(StarletteHTTPException)
    async def _handle_http_exc(_req: Request, exc: StarletteHTTPException) -> JSONResponse:
        code = STATUS_TO_ERROR_CODE.get(exc.status_code, "bad_request")
        ge = GatewayError(exc.status_code, code, str(exc.detail))
        return JSONResponse(status_code=exc.status_code, content=ge.to_dict())

    # ── 2. Authentication & Bootstrap Endpoints ──────────────────────────────

    @fastapi_app.get("/api/health")
    async def get_health(request: Request) -> dict[str, Any]:
        _init_runtime_state(request.app, config)
        sup: SessionSupervisor = request.app.state.supervisor
        uptime = max(0, int(time.monotonic() - float(request.app.state.started_mono)))
        return {
            "status": "ok",
            "service": SERVICE_NAME,
            "gateway_version": GATEWAY_VERSION,
            "agy_version": sup.get_agy_version(),
            "protocol_version": ACP_PROTOCOL_VERSION,
            "auth_required": True,
            "uptime_seconds": uptime,
        }

    @fastapi_app.get("/api/info", dependencies=[Depends(require_auth)])
    async def get_info(request: Request) -> dict[str, Any]:
        cfg: GatewayConfig = request.app.state.config
        sup: SessionSupervisor = request.app.state.supervisor
        return {
            "gateway_version": GATEWAY_VERSION,
            "agy_version": sup.get_agy_version(),
            "acp_protocol_version": ACP_PROTOCOL_VERSION,
            "hostname": socket.gethostname(),
            "default_cwd": str(cfg.default_cwd),
            "capabilities": sup.get_capabilities(),
            "default_config_options": sup.get_default_config_options(),
        }

    @fastapi_app.post("/api/ws-ticket", status_code=201, dependencies=[Depends(require_auth)])
    async def create_ws_ticket(request: Request) -> dict[str, Any]:
        auth_mgr: AuthManager = request.app.state.auth
        return auth_mgr.mint_ws_ticket()

    # ── 3. Sessions & ACP Supervisor ─────────────────────────────────────────

    @fastapi_app.get("/api/sessions", dependencies=[Depends(require_auth)])
    async def list_sessions(
        request: Request,
        status: str = Query(default="all"),
        q: str | None = Query(default=None),
        cwd: str | None = Query(default=None),
        limit: int = Query(default=50, ge=1, le=200),
        offset: int = Query(default=0, ge=0),
    ) -> dict[str, Any]:
        sup: SessionSupervisor = request.app.state.supervisor
        return sup.list_sessions(status=status, q=q, cwd=cwd, limit=limit, offset=offset)

    @fastapi_app.post("/api/sessions", status_code=201, dependencies=[Depends(require_auth)])
    async def create_session(request: Request, body: CreateSessionRequest) -> dict[str, Any]:
        sup: SessionSupervisor = request.app.state.supervisor
        return await sup.create_session(
            cwd=body.cwd,
            add_dirs=body.add_dirs,
            title=body.title,
            model=body.model,
            thought_level=body.thought_level,
            mode=body.mode,
            agent=body.agent,
            sandbox=body.sandbox,
        )

    @fastapi_app.get("/api/sessions/{session_id}", dependencies=[Depends(require_auth)])
    async def get_session(request: Request, session_id: str) -> dict[str, Any]:
        sup: SessionSupervisor = request.app.state.supervisor
        return sup.get_session(session_id)

    @fastapi_app.post("/api/sessions/{session_id}/resume", dependencies=[Depends(require_auth)])
    async def resume_session(
        request: Request,
        session_id: str,
        body: ResumeSessionRequest | None = None,
    ) -> dict[str, Any]:
        sup: SessionSupervisor = request.app.state.supervisor
        req = body or ResumeSessionRequest()
        return await sup.resume_session(
            session_id=session_id,
            cwd=req.cwd,
            load_history=req.load_history,
            add_dirs=req.add_dirs,
            sandbox=req.sandbox,
        )

    @fastapi_app.post("/api/sessions/{session_id}/fork", dependencies=[Depends(require_auth)])
    async def fork_session(
        request: Request,
        session_id: str,
        body: ForkSessionRequest | None = None,
    ) -> dict[str, Any]:
        sup: SessionSupervisor = request.app.state.supervisor
        return await sup.fork_session(session_id=session_id, cwd=body.cwd if body else None)

    @fastapi_app.patch("/api/sessions/{session_id}", dependencies=[Depends(require_auth)])
    async def rename_session(
        request: Request,
        session_id: str,
        body: RenameSessionRequest,
    ) -> dict[str, Any]:
        sup: SessionSupervisor = request.app.state.supervisor
        return sup.rename_session(session_id=session_id, title=body.title)

    @fastapi_app.patch("/api/sessions/{session_id}/config", dependencies=[Depends(require_auth)])
    async def update_session_config(
        request: Request,
        session_id: str,
        body: UpdateConfigRequest,
    ) -> dict[str, Any]:
        sup: SessionSupervisor = request.app.state.supervisor
        return await sup.update_session_config(
            session_id=session_id,
            model=body.model,
            thought_level=body.thought_level,
            mode=body.mode,
        )

    @fastapi_app.delete("/api/sessions/{session_id}", dependencies=[Depends(require_auth)])
    async def close_session(request: Request, session_id: str) -> dict[str, Any]:
        sup: SessionSupervisor = request.app.state.supervisor
        return await sup.close_session(session_id=session_id)

    @fastapi_app.post(
        "/api/sessions/{session_id}/prompt",
        status_code=202,
        dependencies=[Depends(require_auth)],
    )
    async def submit_prompt(
        request: Request,
        session_id: str,
        body: SubmitPromptRequest,
    ) -> dict[str, Any]:
        sup: SessionSupervisor = request.app.state.supervisor
        return await sup.submit_prompt(
            session_id=session_id,
            text=body.text,
            parts=body.parts,
            attachment_ids=body.attachment_ids,
            enqueue_if_busy=body.enqueue_if_busy,
            auto_resume=body.auto_resume,
            cwd=body.cwd,
        )

    @fastapi_app.post(
        "/api/sessions/{session_id}/cancel",
        status_code=202,
        dependencies=[Depends(require_auth)],
    )
    async def cancel_turn(request: Request, session_id: str) -> dict[str, Any]:
        sup: SessionSupervisor = request.app.state.supervisor
        return await sup.cancel_turn(session_id=session_id)

    # ── 4. Follow-Up Queue, Permissions, Questions, Files & Artifacts ────────

    @fastapi_app.get("/api/sessions/{session_id}/queue", dependencies=[Depends(require_auth)])
    async def get_queue(request: Request, session_id: str) -> dict[str, Any]:
        sup: SessionSupervisor = request.app.state.supervisor
        return sup.get_queue(session_id=session_id)

    @fastapi_app.post(
        "/api/sessions/{session_id}/queue",
        status_code=201,
        dependencies=[Depends(require_auth)],
    )
    async def enqueue_item(
        request: Request,
        session_id: str,
        body: EnqueueRequest,
    ) -> dict[str, Any]:
        sup: SessionSupervisor = request.app.state.supervisor
        return sup.enqueue_item(
            session_id=session_id,
            text=body.text,
            attachment_ids=body.attachment_ids,
        )

    @fastapi_app.patch(
        "/api/sessions/{session_id}/queue/{item_id}",
        dependencies=[Depends(require_auth)],
    )
    async def edit_queue_item(
        request: Request,
        session_id: str,
        item_id: str,
        body: EditQueueItemRequest,
    ) -> dict[str, Any]:
        sup: SessionSupervisor = request.app.state.supervisor
        return sup.edit_queue_item(session_id=session_id, item_id=item_id, text=body.text)

    @fastapi_app.delete(
        "/api/sessions/{session_id}/queue/{item_id}",
        dependencies=[Depends(require_auth)],
    )
    async def delete_queue_item(
        request: Request,
        session_id: str,
        item_id: str,
    ) -> dict[str, Any]:
        sup: SessionSupervisor = request.app.state.supervisor
        return sup.delete_queue_item(session_id=session_id, item_id=item_id)

    @fastapi_app.post(
        "/api/sessions/{session_id}/queue/{item_id}/send-now",
        status_code=202,
        dependencies=[Depends(require_auth)],
    )
    async def send_queue_item_now(
        request: Request,
        session_id: str,
        item_id: str,
        body: SendNowRequest | None = None,
    ) -> dict[str, Any]:
        sup: SessionSupervisor = request.app.state.supervisor
        interrupt = True if body is None else body.interrupt
        return await sup.send_queue_item_now(
            session_id=session_id,
            item_id=item_id,
            interrupt=interrupt,
        )

    @fastapi_app.post(
        "/api/sessions/{session_id}/permissions/{request_id}",
        dependencies=[Depends(require_auth)],
    )
    async def resolve_permission(
        request: Request,
        session_id: str,
        request_id: str,
        body: ResolvePermissionRequest,
    ) -> dict[str, Any]:
        sup: SessionSupervisor = request.app.state.supervisor
        return sup.resolve_permission(
            session_id=session_id,
            request_id=request_id,
            decision=body.decision,
            option_id=body.option_id,
        )

    @fastapi_app.get("/api/permissions/rules", dependencies=[Depends(require_auth)])
    async def list_permission_rules(
        request: Request,
        workspace: str | None = Query(default=None),
    ) -> dict[str, Any]:
        sup: SessionSupervisor = request.app.state.supervisor
        return {"items": sup.list_permission_rules(workspace=workspace)}

    @fastapi_app.delete(
        "/api/permissions/rules/{rule_id}",
        dependencies=[Depends(require_auth)],
    )
    async def revoke_permission_rule(request: Request, rule_id: str) -> dict[str, Any]:
        sup: SessionSupervisor = request.app.state.supervisor
        return sup.revoke_permission_rule(rule_id=rule_id)

    @fastapi_app.post(
        "/api/sessions/{session_id}/questions/{request_id}",
        dependencies=[Depends(require_auth)],
    )
    async def resolve_question(
        request: Request,
        session_id: str,
        request_id: str,
        body: ResolveQuestionRequest,
    ) -> dict[str, Any]:
        sup: SessionSupervisor = request.app.state.supervisor
        return sup.resolve_question(
            session_id=session_id,
            request_id=request_id,
            action=body.action,
            answers=body.answers,
        )

    @fastapi_app.post(
        "/api/sessions/{session_id}/attachments",
        status_code=201,
        dependencies=[Depends(require_auth)],
    )
    async def upload_attachment(
        request: Request,
        session_id: str,
        file: UploadFile = File(...),
    ) -> dict[str, Any]:
        sup: SessionSupervisor = request.app.state.supervisor
        storage: StorageManager = request.app.state.storage
        # Verify session exists (live or archived)
        sup.get_session(session_id)

        chunks: list[bytes] = []
        total = 0
        while True:
            chunk = await file.read(65536)
            if not chunk:
                break
            total += len(chunk)
            if total > MAX_ATTACHMENT_BYTES:
                raise GatewayError(
                    413,
                    "payload_too_large",
                    "Attachment exceeds 10 MiB limit.",
                )
            chunks.append(chunk)
        return storage.save_attachment(
            session_id=session_id,
            filename=file.filename or "attachment",
            declared_content_type=file.content_type,
            data=b"".join(chunks),
        )

    @fastapi_app.get("/api/sessions/{session_id}/files", dependencies=[Depends(require_auth)])
    async def search_workspace_files(
        request: Request,
        session_id: str,
        q: str = Query(default=""),
        limit: int = Query(default=30, ge=1, le=100),
    ) -> dict[str, Any]:
        sup: SessionSupervisor = request.app.state.supervisor
        storage: StorageManager = request.app.state.storage
        roots = sup.get_session_roots(session_id)
        items = storage.search_workspace_files(roots=roots, q=q, limit=limit)
        return {"items": items}

    @fastapi_app.get("/api/sessions/{session_id}/artifacts", dependencies=[Depends(require_auth)])
    async def list_artifacts(request: Request, session_id: str) -> dict[str, Any]:
        sup: SessionSupervisor = request.app.state.supervisor
        storage: StorageManager = request.app.state.storage
        items = storage.list_brain_artifacts(session_id)
        if items is None:
            sup.get_session(session_id)
            items = []
        return {"session_id": session_id, "items": items}

    @fastapi_app.get(
        "/api/sessions/{session_id}/artifacts/{artifact_path:path}",
        dependencies=[Depends(require_auth)],
    )
    async def get_artifact(
        request: Request,
        session_id: str,
        artifact_path: str,
        raw: bool = Query(default=False),
    ) -> Response:
        storage: StorageManager = request.app.state.storage
        content, media_type = storage.read_brain_artifact(
            session_id=session_id,
            artifact_path=artifact_path,
            raw=raw,
        )
        return Response(
            content=content,
            media_type=media_type,
            headers={"X-Content-Type-Options": "nosniff"},
        )

    @fastapi_app.get("/api/sessions/{session_id}/events", dependencies=[Depends(require_auth)])
    async def get_session_events(
        request: Request,
        session_id: str,
        after: int = Query(default=0, ge=0),
        limit: int = Query(default=500, ge=1, le=2000),
    ) -> dict[str, Any]:
        validate_session_id(session_id)
        sup: SessionSupervisor = request.app.state.supervisor
        replay: ReplayHub = request.app.state.replay
        if replay.session_seq(session_id) == 0:
            sup.get_session(session_id)
        return replay.get_session_events_http(session_id=session_id, after=after, limit=limit)

    # ── 5. WebSocket Protocol ────────────────────────────────────────────────

    @fastapi_app.websocket("/api/ws")
    async def ws_multiplexed(websocket: WebSocket) -> None:
        if not websocket.scope.get("gw_ws_authenticated"):
            auth_mgr: AuthManager = websocket.app.state.auth
            auth_mgr.authenticate_websocket_scope(websocket.scope)
        replay: ReplayHub = websocket.app.state.replay
        sup: SessionSupervisor = websocket.app.state.supervisor
        after = _parse_optional_after(websocket)
        sub, catchup = replay.subscribe_global(after=after)
        await _run_ws_stream(
            websocket,
            sup,
            sub,
            catchup,
            allow_subscribe_filter=True,
        )

    @fastapi_app.websocket("/api/sessions/{session_id}/ws")
    async def ws_single_session(websocket: WebSocket, session_id: str) -> None:
        if not websocket.scope.get("gw_ws_authenticated"):
            auth_mgr: AuthManager = websocket.app.state.auth
            auth_mgr.authenticate_websocket_scope(websocket.scope)
        sup: SessionSupervisor = websocket.app.state.supervisor
        replay: ReplayHub = websocket.app.state.replay
        try:
            validate_session_id(session_id)
            sup.get_session(session_id)
        except GatewayError as exc:
            code = 4403 if exc.status == 403 else 4404
            await websocket.close(code=code, reason=exc.message[:120])
            return
        after = _parse_optional_after(websocket)
        sub, catchup = replay.subscribe_session(session_id=session_id, after=after)
        await _run_ws_stream(
            websocket,
            sup,
            sub,
            catchup,
            allow_subscribe_filter=False,
        )

    # ── 6. Storage (`?mode=ro` SQLite FTS5) ──────────────────────────────────

    @fastapi_app.get("/api/storage/conversations", dependencies=[Depends(require_auth)])
    async def search_storage_conversations(
        request: Request,
        q: str | None = Query(default=None),
        cwd: str | None = Query(default=None),
        limit: int = Query(default=50, ge=1, le=200),
        offset: int = Query(default=0, ge=0),
    ) -> dict[str, Any]:
        storage: StorageManager = request.app.state.storage
        return storage.search_conversations(q=q, cwd=cwd, limit=limit, offset=offset)

    return fastapi_app


class GatewayASGIApp:
    """ASGI root wrapping FastAPI with pre-upgrade WebSocket 401 and query-token guards."""

    def __init__(self, config: GatewayConfig | None = None) -> None:
        self._explicit_config = config
        self.fastapi = create_fastapi_app(config=config)
        self._wrapped = AuthGateASGIWrapper(self.fastapi, self._ensure_and_get_auth)

    def _ensure_and_get_auth(self) -> AuthManager:
        _init_runtime_state(self.fastapi, self._explicit_config)
        return self.fastapi.state.auth

    @property
    def state(self) -> Any:
        return self.fastapi.state

    async def __call__(self, scope: MutableMapping[str, Any], receive: Any, send: Any) -> None:
        if scope.get("type") == "lifespan":
            await self.fastapi(scope, receive, send)
            return
        _init_runtime_state(self.fastapi, self._explicit_config)
        await self._wrapped(scope, receive, send)


def create_app(config: GatewayConfig | None = None) -> GatewayASGIApp:
    """Create a hermetic or env-configured ASGI gateway application."""
    return GatewayASGIApp(config=config)


app = create_app()
