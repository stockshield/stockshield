"""내부 AI API를 가짜 Gemini로 확인한다 (API 명세서 'API_명세서' 15·16행, NEWS_AI_001, FDS_AI_001)."""
from __future__ import annotations

import pytest
from fastapi.testclient import TestClient

from app import main, prompts
from app.llm import LlmError
from app.schemas import DetectionExplanation, NewsAnalysis


class FakeLlm:
    def __init__(self, answer=None, error: bool = False):
        self.answer = answer
        self.error = error
        self.prompts: list[str] = []

    def generate(self, system, prompt, schema):
        self.prompts.append(prompt)
        if self.error:
            raise LlmError("가짜 실패")
        return self.answer


@pytest.fixture
def client():
    yield TestClient(main.app)
    main.app.dependency_overrides.clear()


def use(llm: FakeLlm) -> FakeLlm:
    main.app.dependency_overrides[main.get_llm] = lambda: llm
    return llm


def test_summary_limits_to_three_sentences_and_clamps_score(client):
    use(FakeLlm(NewsAnalysis(summary=["첫째.", "둘째.", "셋째.", "넷째."], sentiment=1.7, reason="실적 호조")))
    res = client.post("/api/v1/internal/ai/summary",
                      json={"newsId": "n1", "newsTitl": "삼성전자 실적", "newsCont": "본문"})
    assert res.status_code == 200
    body = res.json()
    assert body["aiSmryCont"] == "첫째.\n둘째.\n셋째."
    assert body["aiSntmScr"] == 1.0   # JSON 숫자, 범위 -1.00 ~ 1.00


def test_summary_rounds_score_to_two_decimals(client):
    use(FakeLlm(NewsAnalysis(summary=["요약."], sentiment=-0.456, reason="악재")))
    res = client.post("/api/v1/internal/ai/summary", json={"newsId": "n2", "newsTitl": "t", "newsCont": "c"})
    assert res.json()["aiSntmScr"] == -0.46


def test_summary_validation_error_is_422(client):
    use(FakeLlm())
    res = client.post("/api/v1/internal/ai/summary", json={"newsId": "n3", "newsTitl": "", "newsCont": "c"})
    assert res.status_code == 422


def test_ai_failure_is_500(client):
    use(FakeLlm(error=True))
    res = client.post("/api/v1/internal/ai/summary", json={"newsId": "n4", "newsTitl": "t", "newsCont": "c"})
    assert res.status_code == 500
    assert res.json()["code"] == "AI_UNAVAILABLE"


def test_detection_reason_scrubs_personal_info(client):
    llm = use(FakeLlm(DetectionExplanation(reason="24시간 안에 세 번 나눠 520만 원 출금을 시도했습니다.",
                                           check_points=["최근 비밀번호 오류 횟수", "새 기기 로그인 여부"])))
    res = client.post("/api/v1/internal/ai/detection-reason", json={
        "dtcSeq": 7, "ruleCd": "WITHDRAW_LARGE", "rsltCd": "LOCKED",
        "facts": {"wdrAmt": 2_000_000, "dailySum": 3_200_000, "acntNo": "100000000010",
                  "connIp": "203.0.113.9", "reqHash": "abc", "nested": {"dvcId": "x", "reason": "DAILY_SUM"},
                  "ipFirstAuthTs": "2026-11-13T09:00:00+09:00"}})
    assert res.status_code == 200
    body = res.json()
    assert body["aiRsnCont"].startswith("24시간 안에")
    assert body["aiChkCont"] == "- 최근 비밀번호 오류 횟수\n- 새 기기 로그인 여부"
    sent = llm.prompts[0]
    for secret in ("100000000010", "203.0.113.9", "abc\"", "\"x\""):
        assert secret not in sent
    assert "3200000" in sent and "DAILY_SUM" in sent and "ipFirstAuthTs" in sent
    assert "대량 출금 추가 인증" in sent and "계좌 잠금" in sent


def test_detection_reason_rejects_unknown_rule(client):
    use(FakeLlm())
    res = client.post("/api/v1/internal/ai/detection-reason",
                      json={"dtcSeq": 1, "ruleCd": "SOMETHING", "rsltCd": "PASS", "facts": {}})
    assert res.status_code == 422


def test_scrub_ignores_case_and_underscores():
    assert prompts.scrub({"ACNT_NO": "1", "conn_ip": "2", "description": "ok", "trdAmt": 3}) == \
        {"description": "ok", "trdAmt": 3}


def test_health_does_not_expose_key(client):
    res = client.get("/health")
    assert res.status_code == 200
    assert set(res.json()) == {"status", "model", "keyConfigured"}
