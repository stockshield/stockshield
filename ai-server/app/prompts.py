"""프롬프트와 개인정보 걸러 내기.

- 뉴스 요약(NEWS_AI_001): 3문장 이내 요약, -1.0~1.0 감성 점수, 근거 한 줄
- 탐지 사유(FDS_AI_001): 판단은 이미 규칙이 했고, AI는 그 이유를 관리자가 읽기 쉽게 정리만 한다
"""
from __future__ import annotations

import json
import re
from typing import Any

NEWS_SYSTEM = (
    "너는 증권 뉴스 요약 도우미다. 기사 내용만 근거로 한국어로 답한다. "
    "기사에 없는 사실, 투자 권유, 매수·매도 추천은 쓰지 않는다. "
    "summary는 핵심을 담은 문장 1~3개, sentiment는 이 기사가 관련 종목에 주는 영향을 "
    "-1.0(매우 부정)부터 1.0(매우 긍정) 사이 숫자로, reason은 그 점수의 근거 한 줄이다."
)

DETECTION_SYSTEM = (
    "너는 증권사 이상거래탐지(FDS) 관제 화면의 설명 도우미다. "
    "탐지 여부와 결과는 이미 규칙이 정했으니 바꾸거나 판단하지 말고, 주어진 사실만으로 왜 탐지됐는지 설명한다. "
    "reason은 관리자가 읽기 쉬운 한국어 2~3문장, check_points는 관리자가 확인할 점 1~3개다. "
    "주어지지 않은 개인정보나 사실을 지어내지 않는다."
)

RULE_NAMES = {
    "LOGIN_NEW_ACCESS": "새 접속 본인 인증(처음 보는 IP 또는 기기로 로그인)",
    "WITHDRAW_LARGE": "대량 출금 추가 인증(1회 또는 24시간 누적 500만 원 이상 출금)",
    "TRADE_NEW_IP": "새 IP 직후 거래(새 IP 인증 후 24시간 안의 매매, 또는 신뢰 목록에 없는 IP의 매매)",
}

RESULT_NAMES = {
    "PENDING": "인증 대기",
    "PASS": "인증 통과",
    "FAIL": "인증 실패 또는 만료",
    "LOCKED": "비밀번호 5회 오류로 계좌 잠금",
    "LOGGED": "기록만 함",
}

# FDS_AI_001 #3: 회원번호, 아이디, 계좌번호, IP, 기기 정보와 출금 요청 내용 해시는 보내지 않는다.
# 표준 용어(테이블 정의서 '표준 용어' 시트)의 컬럼·필드 이름을 기준으로, 대소문자와 _ 를 무시하고 비교한다.
_SENSITIVE_KEYS = {
    "mbrno", "lgnid", "mbrnm", "emladdr", "acntno", "rcvacntno", "rcvbanknm",
    "connip", "ip", "dvcid", "useragt", "useragent", "reqhash", "wdrpwd", "lgnpwd",
    "ordno", "wdrno", "idmpkey", "reqidmpkey",
}


def _norm(key: str) -> str:
    return re.sub(r"[^a-z0-9]", "", key.lower())


def scrub(facts: dict[str, Any]) -> dict[str, Any]:
    """메인 서버가 이미 빼고 보내지만, 한 번 더 개인정보 키를 지운다(중첩 포함)."""
    clean: dict[str, Any] = {}
    for key, value in facts.items():
        if _norm(str(key)) in _SENSITIVE_KEYS:
            continue
        clean[key] = scrub(value) if isinstance(value, dict) else value
    return clean


def news_prompt(title: str, content: str, max_chars: int = 6000) -> str:
    body = content if len(content) <= max_chars else content[:max_chars] + " …(이하 생략)"
    return f"[기사 제목]\n{title}\n\n[기사 본문]\n{body}"


# 탐지 상세(dtl_data) 필드의 뜻. 모델이 값을 잘못 읽지 않게 함께 보낸다 (FDS ①②③ 탐지 기록 형식)
FACT_GLOSSARY = {
    "wdrAmt": "이번 출금 요청 금액(원)",
    "dailySum": "이번 출금을 뺀 최근 24시간 완료 출금 합계(원). 대량 출금 판단은 dailySum + wdrAmt로 한다",
    "threshold": "대량 출금 기준 금액(원)",
    "reason": "판단 사유. SINGLE=1회 금액이 기준 이상, DAILY_SUM=24시간 누적이 기준 이상, "
              "NEW_IP_AFTER_AUTH=새 IP 인증 후 24시간 안 거래, IP_NOT_TRUSTED=신뢰 목록에 없는 IP의 거래",
    "pwdProvided": "출금 비밀번호를 함께 보냈는지",
    "failCntAfter": "이번 요청 뒤 출금 비밀번호 연속 오류 횟수(5회면 잠금)",
    "newIp": "처음 보는 IP인지",
    "newDevice": "처음 보는 기기인지",
    "ipFirstAuthTs": "주문 IP를 처음 인증한 시각",
    "ordTs": "주문 시각",
    "trdAmt": "체결 금액(원)",
    "ordDvCd": "주문 구분(BUY=매수, SELL=매도)",
    "tckCd": "종목코드",
}


def detection_prompt(rule_cd: str, rslt_cd: str, facts: dict[str, Any]) -> str:
    glossary = "\n".join(f"- {k}: {v}" for k, v in FACT_GLOSSARY.items() if k in facts)
    return (
        f"[탐지 규칙] {RULE_NAMES.get(rule_cd, rule_cd)}\n"
        f"[결과] {RESULT_NAMES.get(rslt_cd, rslt_cd)}\n"
        f"[상세 사실(JSON)]\n{json.dumps(facts, ensure_ascii=False, indent=2)}\n"
        + (f"[필드 뜻]\n{glossary}" if glossary else "")
    )
