"""내부 AI API 요청·응답 형식 (API 명세서 'API_명세서' 15·16행)."""
from __future__ import annotations

from decimal import Decimal
from typing import Annotated, Any

from pydantic import BaseModel, ConfigDict, Field, PlainSerializer


class SummaryRequest(BaseModel):
    """뉴스 요약 요청: 수집한 원본 기사 (NEWS_AI_001)"""
    model_config = ConfigDict(extra="ignore")
    newsId: str = Field(min_length=1, max_length=50)
    newsTitl: str = Field(min_length=1, max_length=500)
    newsCont: str = Field(min_length=1)


class SummaryResponse(BaseModel):
    aiSmryCont: str
    # 소수 2자리 Decimal을 JSON 숫자로 보낸다 (pydantic 기본값은 문자열). 메인 서버는 BigDecimal로 받는다
    aiSntmScr: Annotated[Decimal, PlainSerializer(float, return_type=float, when_used="json")]


class DetectionReasonRequest(BaseModel):
    """탐지 사유 설명 요청 (FDS_AI_001). facts에는 개인정보가 없어야 하고, 있으면 서버가 지운다"""
    model_config = ConfigDict(extra="ignore")
    dtcSeq: int
    ruleCd: str = Field(pattern="^(LOGIN_NEW_ACCESS|WITHDRAW_LARGE|TRADE_NEW_IP)$")
    rsltCd: str = Field(pattern="^(PENDING|PASS|FAIL|LOCKED|LOGGED)$")
    facts: dict[str, Any] = Field(default_factory=dict)


class DetectionReasonResponse(BaseModel):
    aiRsnCont: str
    aiChkCont: str


# ---------------------------------------------------------------- Gemini에게 받는 구조화 출력

class NewsAnalysis(BaseModel):
    """Gemini 출력: 3문장 이내 요약, 감성 점수, 근거 한 줄"""
    summary: list[str] = Field(description="기사 핵심을 담은 한국어 문장 1~3개")
    sentiment: float = Field(description="-1.0(매우 부정) ~ 1.0(매우 긍정)")
    reason: str = Field(description="감성 점수의 근거 한 줄")


class DetectionExplanation(BaseModel):
    """Gemini 출력: 탐지 사유 설명(2~3문장)과 관리자가 확인할 점"""
    reason: str = Field(description="관리자가 읽기 쉬운 탐지 사유 설명, 한국어 2~3문장")
    check_points: list[str] = Field(description="관리자가 확인할 점 1~3개, 한국어")
