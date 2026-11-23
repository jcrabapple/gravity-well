"""Monotonic global cursor, per-session seq ring buffers, and live WebSocket pub/sub hub."""

from __future__ import annotations

import asyncio
from collections import deque
from dataclasses import dataclass, field
from typing import Any

from .config import utc_now_iso


@dataclass(eq=False)
class ReplaySubscription:
    """Active live event subscription backed by an asyncio.Queue."""

    hub: ReplayHub
    queue: asyncio.Queue[dict[str, Any]] = field(default_factory=asyncio.Queue)
    session_filter: set[str] | None = None
    single_session_id: str | None = None

    def matches(self, event: dict[str, Any]) -> bool:
        sid = event.get("session_id")
        if self.single_session_id is not None:
            return sid == self.single_session_id
        if self.session_filter is not None and sid is not None:
            return sid in self.session_filter
        return True

    def close(self) -> None:
        self.hub.unsubscribe(self)


@dataclass(frozen=True)
class ReplayCatchup:
    """Atomic snapshot of ring-buffer catch-up state at subscription or HTTP poll time."""

    after: int | None
    truncated: bool
    min_cursor_retained: int
    latest_cursor: int
    events: list[dict[str, Any]]


class ReplayHub:
    """Manages global monotonic cursor, per-session seq ring buffers, and WS broadcast."""

    def __init__(
        self,
        session_maxlen: int = 2000,
        global_maxlen: int = 4000,
    ) -> None:
        if session_maxlen < 1 or global_maxlen < 1:
            raise ValueError("Ring buffer maxlen must be >= 1")
        self._session_maxlen = session_maxlen
        self._global_maxlen = global_maxlen
        self._cursor: int = 0
        self._global_buf: deque[dict[str, Any]] = deque(maxlen=global_maxlen)
        self._global_last_evicted_cursor: int = 0

        self._session_seq: dict[str, int] = {}
        self._session_bufs: dict[str, deque[dict[str, Any]]] = {}
        self._session_last_evicted_cursor: dict[str, int] = {}
        self._session_last_cursor: dict[str, int] = {}

        self._subscribers: set[ReplaySubscription] = set()

    @property
    def latest_cursor(self) -> int:
        return self._cursor

    def session_seq(self, session_id: str) -> int:
        return self._session_seq.get(session_id, 0)

    def session_cursor(self, session_id: str) -> int:
        return self._session_last_cursor.get(session_id, 0)

    def emit(
        self,
        session_id: str | None,
        event_type: str,
        payload: dict[str, Any],
    ) -> dict[str, Any]:
        """Assign monotonic cursor + per-session seq, store in ring buffers, and broadcast."""
        self._cursor += 1
        cursor = self._cursor

        if session_id is not None:
            seq = self._session_seq.get(session_id, 0) + 1
            self._session_seq[session_id] = seq
            self._session_last_cursor[session_id] = cursor
        else:
            seq = 0

        event: dict[str, Any] = {
            "frame": "event",
            "cursor": cursor,
            "seq": seq,
            "session_id": session_id,
            "type": event_type,
            "ts": utc_now_iso(),
            "payload": payload,
        }

        if len(self._global_buf) == self._global_maxlen:
            self._global_last_evicted_cursor = self._global_buf[0]["cursor"]
        self._global_buf.append(event)

        if session_id is not None:
            sbuf = self._session_bufs.get(session_id)
            if sbuf is None:
                sbuf = deque(maxlen=self._session_maxlen)
                self._session_bufs[session_id] = sbuf
            if len(sbuf) == self._session_maxlen:
                self._session_last_evicted_cursor[session_id] = sbuf[0]["cursor"]
            sbuf.append(event)

        for sub in list(self._subscribers):
            if sub.matches(event):
                sub.queue.put_nowait(event)

        return event

    def _compute_catchup(
        self,
        buf: deque[dict[str, Any]],
        last_evicted_cursor: int,
        after: int | None,
        latest_cursor: int,
        limit: int | None = None,
    ) -> ReplayCatchup:
        min_retained = buf[0]["cursor"] if buf else 0
        if after is None:
            return ReplayCatchup(
                after=None,
                truncated=False,
                min_cursor_retained=min_retained,
                latest_cursor=latest_cursor,
                events=[],
            )
        truncated = after > 0 and last_evicted_cursor > after
        if truncated:
            return ReplayCatchup(
                after=after,
                truncated=True,
                min_cursor_retained=min_retained,
                latest_cursor=latest_cursor,
                events=[],
            )
        events = [ev for ev in buf if ev["cursor"] > after]
        if limit is not None:
            events = events[:limit]
        return ReplayCatchup(
            after=after,
            truncated=False,
            min_cursor_retained=min_retained,
            latest_cursor=latest_cursor,
            events=events,
        )

    def get_session_events_http(
        self,
        session_id: str,
        after: int = 0,
        limit: int = 500,
    ) -> dict[str, Any]:
        """Return HTTP catch-up response for GET /api/sessions/{session_id}/events."""
        sbuf = self._session_bufs.get(session_id)
        if sbuf is None:
            sbuf = deque()
        last_evicted = self._session_last_evicted_cursor.get(session_id, 0)
        latest = sbuf[-1]["cursor"] if sbuf else self._session_last_cursor.get(session_id, self._cursor)
        catchup = self._compute_catchup(
            sbuf,
            last_evicted_cursor=last_evicted,
            after=after,
            latest_cursor=latest,
            limit=max(1, min(limit, 2000)),
        )
        return {
            "session_id": session_id,
            "after": after,
            "min_cursor_retained": catchup.min_cursor_retained,
            "latest_cursor": catchup.latest_cursor,
            "truncated": catchup.truncated,
            "events": catchup.events,
        }

    def subscribe_global(
        self,
        after: int | None = None,
        session_ids: set[str] | None = None,
    ) -> tuple[ReplaySubscription, ReplayCatchup]:
        """Atomically register a global WS subscriber and compute its replay catch-up."""
        sub = ReplaySubscription(hub=self, session_filter=session_ids)
        self._subscribers.add(sub)
        catchup = self._compute_catchup(
            self._global_buf,
            last_evicted_cursor=self._global_last_evicted_cursor,
            after=after,
            latest_cursor=self._cursor,
        )
        if session_ids is not None and catchup.events:
            filtered = [ev for ev in catchup.events if sub.matches(ev)]
            catchup = ReplayCatchup(
                after=catchup.after,
                truncated=catchup.truncated,
                min_cursor_retained=catchup.min_cursor_retained,
                latest_cursor=catchup.latest_cursor,
                events=filtered,
            )
        return sub, catchup

    def subscribe_session(
        self,
        session_id: str,
        after: int | None = None,
    ) -> tuple[ReplaySubscription, ReplayCatchup]:
        """Atomically register a single-session WS subscriber and compute its replay catch-up."""
        sub = ReplaySubscription(hub=self, single_session_id=session_id)
        self._subscribers.add(sub)
        sbuf = self._session_bufs.get(session_id)
        if sbuf is None:
            sbuf = deque()
        last_evicted = self._session_last_evicted_cursor.get(session_id, 0)
        latest = sbuf[-1]["cursor"] if sbuf else self._session_last_cursor.get(session_id, self._cursor)
        catchup = self._compute_catchup(
            sbuf,
            last_evicted_cursor=last_evicted,
            after=after,
            latest_cursor=latest,
        )
        return sub, catchup

    def unsubscribe(self, sub: ReplaySubscription) -> None:
        self._subscribers.discard(sub)
