"""Authenticated FastAPI endpoint for final framework extraction results."""

from __future__ import annotations

import hmac
import os
import threading
from contextlib import asynccontextmanager
from pathlib import Path
from typing import Any

from fastapi import FastAPI, HTTPException, Request, Security, status
from fastapi.concurrency import run_in_threadpool
from fastapi.encoders import jsonable_encoder
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from fastapi.security import APIKeyHeader
from pydantic import BaseModel, ConfigDict, Field

from datagraph_bank.models import IllegalBehaviorCase
from datagraph_bank.workflow import BankCaseWorkflow


PROJECT_ROOT = Path(__file__).resolve().parents[1]
api_key_header = APIKeyHeader(name="X-API-Key", auto_error=False)


class FrameworkExtractionRequest(BaseModel):
    """Contents of the three required and supported optional case files."""

    model_config = ConfigDict(extra="forbid")

    basic_info: dict[str, Any]
    customers: list[dict[str, Any]]
    analysis_texts: dict[str, str | list[str]]
    accounts: list[dict[str, Any]] = Field(default_factory=list)
    evidences: list[dict[str, Any]] = Field(default_factory=list)
    other_entities: list[dict[str, Any]] = Field(default_factory=list)
    feature_analysis: dict[str, Any] | list[Any] = Field(default_factory=dict)


class ErrorResponse(BaseModel):
    error: str
    message: str
    details: Any | None = None


def _error_response(
    status_code: int,
    error: str,
    message: str,
    details: Any | None = None,
) -> JSONResponse:
    return JSONResponse(
        status_code=status_code,
        content=jsonable_encoder(
            ErrorResponse(error=error, message=message, details=details)
        ),
    )


def _build_workflow() -> BankCaseWorkflow:
    return BankCaseWorkflow(
        kb_path=PROJECT_ROOT / "data" / "kb" / "risk_event_knowledge_base.json",
        output_root=PROJECT_ROOT / "output",
        llm_enabled=os.getenv("FRAMEWORK_DISABLE_LLM", "0") != "1",
        corenlp_enabled=os.getenv("FRAMEWORK_DISABLE_CORENLP", "0") != "1",
        corenlp_url=os.getenv("CORENLP_URL") or None,
        prompt_dir=PROJECT_ROOT / "data" / "prompts",
        llm_concurrency=int(os.getenv("FRAMEWORK_LLM_CONCURRENCY", "2")),
        corenlp_concurrency=int(os.getenv("FRAMEWORK_CORENLP_CONCURRENCY", "2")),
    )


def create_app(
    workflow: BankCaseWorkflow | None = None,
    *,
    api_key: str | None = None,
) -> FastAPI:
    """Create the API; injected workflows keep endpoint tests offline."""

    configured_api_key = (
        api_key if api_key is not None else os.getenv("FRAMEWORK_API_KEY", "xianpsb_with_ecust")
    ).strip()
    owns_workflow = workflow is None

    @asynccontextmanager
    async def lifespan(app: FastAPI):
        if not configured_api_key:
            raise RuntimeError(
                "必须设置 FRAMEWORK_API_KEY，服务拒绝无鉴权启动"
            )
        app.state.workflow = workflow or _build_workflow()
        # EventExtractor and RelationshipExtractor contain per-case counters.
        # Serialize whole-case runs to prevent IDs leaking across requests.
        app.state.extraction_lock = threading.Lock()
        try:
            yield
        finally:
            if owns_workflow:
                app.state.workflow.close()

    app = FastAPI(
        title="案例框架抽取服务",
        version="1.0.0",
        description=(
            "提交 basic_info、customers、analysis_texts 和可选案例字段，"
            "只返回最终 IllegalBehaviorCase，不保存输入或中间结果。"
        ),
        lifespan=lifespan,
    )

    @app.exception_handler(RequestValidationError)
    async def validation_error_handler(
        _request: Request,
        exc: RequestValidationError,
    ) -> JSONResponse:
        return _error_response(
            status.HTTP_422_UNPROCESSABLE_ENTITY,
            "INVALID_CASE_INPUT",
            "请求体格式不符合案例输入协议",
            exc.errors(),
        )

    @app.exception_handler(HTTPException)
    async def http_error_handler(_request: Request, exc: HTTPException) -> JSONResponse:
        error = "UNAUTHORIZED" if exc.status_code == 401 else "HTTP_ERROR"
        return _error_response(exc.status_code, error, str(exc.detail))

    def verify_api_key(
        supplied_api_key: str | None = Security(api_key_header),
    ) -> None:
        if supplied_api_key is None or not hmac.compare_digest(
            supplied_api_key,
            configured_api_key,
        ):
            raise HTTPException(
                status_code=status.HTTP_401_UNAUTHORIZED,
                detail="API Key 无效",
            )

    def run_extraction(
        app_instance: FastAPI,
        payload: dict[str, Any],
    ) -> IllegalBehaviorCase:
        payload["analysis_texts"] = {
            name: "\n".join(text) if isinstance(text, list) else text
            for name, text in payload["analysis_texts"].items()
        }
        with app_instance.state.extraction_lock:
            return app_instance.state.workflow.extract(payload)

    @app.get("/health", summary="服务存活检查")
    async def health() -> dict[str, str]:
        return {"status": "ok"}

    @app.post(
        "/v1/framework-extraction",
        response_model=IllegalBehaviorCase,
        responses={
            401: {"model": ErrorResponse},
            422: {"model": ErrorResponse},
            500: {"model": ErrorResponse},
        },
        summary="提取一个案例的最终框架",
    )
    async def framework_extraction(
        payload: FrameworkExtractionRequest,
        request: Request,
        _authorized: None = Security(verify_api_key),
    ) -> IllegalBehaviorCase | JSONResponse:
        try:
            return await run_in_threadpool(
                run_extraction,
                request.app,
                payload.model_dump(mode="python"),
            )
        except (TypeError, ValueError) as exc:
            return _error_response(
                status.HTTP_422_UNPROCESSABLE_ENTITY,
                "INVALID_CASE_INPUT",
                str(exc),
            )
        except Exception:
            return _error_response(
                status.HTTP_500_INTERNAL_SERVER_ERROR,
                "EXTRACTION_FAILED",
                "框架抽取失败，请联系服务维护人员",
            )

    return app


app = create_app()
