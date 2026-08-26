"""Shared concurrency and retry controls for local model services."""

from __future__ import annotations

import threading
import time
from typing import Any, Callable, Dict, Optional, TypeVar

try:
    import httpx
except ImportError:  # pragma: no cover - optional dependency
    httpx = None  # type: ignore[assignment]


T = TypeVar("T")
RetryPredicate = Callable[[Exception], bool]


def is_transient_service_error(exc: Exception) -> bool:
    """Return whether a local HTTP service call is safe to retry."""

    if httpx is None:
        return False
    if isinstance(exc, (httpx.TimeoutException, httpx.NetworkError)):
        return True
    if isinstance(exc, httpx.HTTPStatusError):
        status_code = exc.response.status_code
        return status_code == 429 or status_code >= 500
    return False


class LocalServiceGate:
    """Bound concurrent calls and retry transient local-service failures."""

    def __init__(
        self,
        name: str,
        max_concurrency: int,
        max_attempts: int = 3,
        retry_delay: float = 0.5,
        retry_if: Optional[RetryPredicate] = None,
    ) -> None:
        if max_concurrency < 1:
            raise ValueError("max_concurrency must be at least 1")
        if max_attempts < 1:
            raise ValueError("max_attempts must be at least 1")
        if retry_delay < 0:
            raise ValueError("retry_delay must not be negative")
        self.name = name
        self.max_concurrency = max_concurrency
        self.max_attempts = max_attempts
        self.retry_delay = retry_delay
        self.retry_if = retry_if or is_transient_service_error
        self._semaphore = threading.BoundedSemaphore(max_concurrency)
        self._lock = threading.Lock()
        self._active_calls = 0
        self._peak_active_calls = 0
        self._total_attempts = 0
        self._retry_count = 0
        self._failure_count = 0

    def run(self, operation: Callable[[], T]) -> T:
        last_error: Exception | None = None
        for attempt in range(1, self.max_attempts + 1):
            try:
                with self._semaphore:
                    self._enter_call()
                    try:
                        return operation()
                    finally:
                        self._leave_call()
            except Exception as exc:
                last_error = exc
                if attempt >= self.max_attempts or not self.retry_if(exc):
                    with self._lock:
                        self._failure_count += 1
                    raise
                with self._lock:
                    self._retry_count += 1
                if self.retry_delay:
                    time.sleep(self.retry_delay * (2 ** (attempt - 1)))
        assert last_error is not None
        raise last_error

    def _enter_call(self) -> None:
        with self._lock:
            self._active_calls += 1
            self._total_attempts += 1
            self._peak_active_calls = max(
                self._peak_active_calls,
                self._active_calls,
            )

    def _leave_call(self) -> None:
        with self._lock:
            self._active_calls -= 1

    def snapshot(self) -> Dict[str, Any]:
        with self._lock:
            return {
                "name": self.name,
                "max_concurrency": self.max_concurrency,
                "max_attempts": self.max_attempts,
                "retry_delay_seconds": self.retry_delay,
                "active_calls": self._active_calls,
                "peak_active_calls": self._peak_active_calls,
                "total_attempts": self._total_attempts,
                "retry_count": self._retry_count,
                "failure_count": self._failure_count,
            }


class LocalServiceController:
    """Shared gates used by all case workers in one process."""

    def __init__(
        self,
        llm_concurrency: int = 2,
        corenlp_concurrency: int = 2,
        max_attempts: int = 3,
        retry_delay: float = 0.5,
    ) -> None:
        self.llm = LocalServiceGate(
            "llm",
            llm_concurrency,
            max_attempts=max_attempts,
            retry_delay=retry_delay,
        )
        self.corenlp = LocalServiceGate(
            "corenlp",
            corenlp_concurrency,
            max_attempts=max_attempts,
            retry_delay=retry_delay,
        )

    def snapshot(self) -> Dict[str, Dict[str, Any]]:
        return {
            "llm": self.llm.snapshot(),
            "corenlp": self.corenlp.snapshot(),
        }
