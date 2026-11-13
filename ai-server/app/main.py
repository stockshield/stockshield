"""StockShield AI 서버 (FastAPI + Gemini).

내부 API만 둔다. 메인 서버(같은 Docker 네트워크)만 부르고 외부에 열지 않는다 (API 명세서 인가 'Internal (IP)').
- POST /api/v1/internal/ai/summary           뉴스 3줄 요약·감성 점수 (NEWS_AI_001, 'API_명세서' 15행)
- POST /api/v1/internal/ai/detection-reason  탐지 사유 설명·확인할 점 (FDS_AI_001, 'API_명세서' 16행)
- GET  /health                               상태 확인 (키 값은 내보내지 않음)
AI를 쓸 수 없으면 500으로 응답하고, 메인 서버는 뉴스는 다음 배치에서 다시, 탐지 설명은 FAILED(관리자 재요청 가능)로 처리한다.
"""
from __future__ import annotations

import logging
from decimal import ROUND_HALF_UP, Decimal
from functools import lru_cache

from fastapi import Depends, FastAPI, Request
from fastapi.responses import JSONResponse

from . import prompts
from .config import Settings, load_settings
from .llm import GeminiClient, LlmClient, LlmError
from .schemas import (DetectionExplanation, DetectionReasonRequest, DetectionReasonResponse, NewsAnalysis,
                      SummaryRequest, SummaryResponse)

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")
log = logging.getLogger("ai-server")

app = FastAPI(title="StockShield AI 서버", version="0.1.0")


@lru_cache
def get_settings() -> Settings:
    return load_settings()


@lru_cache
def _gemini() -> GeminiClient:
    return GeminiClient(get_settings())


def get_llm() -> LlmClient:
    return _gemini()


@app.exception_handler(LlmError)
async def llm_error_handler(_: Request, e: LlmError) -> JSONResponse:
    return JSONResponse(status_code=500, content={"code": "AI_UNAVAILABLE", "message": str(e)})


@app.get("/health")
def health(settings: Settings = Depends(get_settings)) -> dict:
    return {"status": "UP", "model": settings.gemini_model, "keyConfigured": settings.has_key}


@app.post("/api/v1/internal/ai/summary", response_model=SummaryResponse)
def summarize(req: SummaryRequest, llm: LlmClient = Depends(get_llm)) -> SummaryResponse:
    result = llm.generate(prompts.NEWS_SYSTEM, prompts.news_prompt(req.newsTitl, req.newsCont), NewsAnalysis)
    sentences = [s.strip() for s in result.summary if s and s.strip()][:3]
    if not sentences:
        raise LlmError("요약 문장이 비어 있습니다")
    score = max(-1.0, min(1.0, float(result.sentiment)))
    log.info("뉴스 요약 완료 newsId=%s", req.newsId)
    return SummaryResponse(
        aiSmryCont=_limit("\n".join(sentences), 1000),
        aiSntmScr=Decimal(str(score)).quantize(Decimal("0.01"), rounding=ROUND_HALF_UP),
    )


@app.post("/api/v1/internal/ai/detection-reason", response_model=DetectionReasonResponse)
def explain_detection(req: DetectionReasonRequest, llm: LlmClient = Depends(get_llm)) -> DetectionReasonResponse:
    facts = prompts.scrub(req.facts)
    result = llm.generate(prompts.DETECTION_SYSTEM, prompts.detection_prompt(req.ruleCd, req.rsltCd, facts),
                          DetectionExplanation)
    if not result.reason.strip():
        raise LlmError("사유 설명이 비어 있습니다")
    checks = [c.strip() for c in result.check_points if c and c.strip()][:3]
    log.info("탐지 사유 설명 완료 dtcSeq=%s rule=%s", req.dtcSeq, req.ruleCd)
    return DetectionReasonResponse(
        aiRsnCont=_limit(result.reason.strip(), 1000),
        aiChkCont=_limit("\n".join(f"- {c}" for c in checks), 1000),
    )


def _limit(text: str, max_len: int) -> str:
    """DB 컬럼(VARCHAR(1000))을 넘지 않게 자른다"""
    return text if len(text) <= max_len else text[: max_len - 1] + "…"
