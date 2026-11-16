"""정기 대사 결과 확인 (ADM_REC_001 #4, reconcile.yml에서 실행).

GET {BASE_URL}/api/v1/internal/reconcile/status?rcclDt=YYYYMMDD 를 X-Internal-Token 헤더로 부르고 판정한다.
- 성공(0): runStatCd가 DONE이고 diffAcntCnt가 0
- 실패(1): 불일치 계좌가 있음, FAILED, 아직 RUNNING, 그날 정기 대사 기록 없음, 응답 오류
결과 표는 GITHUB_STEP_SUMMARY(있으면)에 쓴다. 토큰 값은 어디에도 출력하지 않는다.

환경 변수: BASE_URL, INTERNAL_TOKEN, RCCL_DT(비우면 오늘, KST)
"""
import json
import os
import sys
import urllib.error
import urllib.request
from datetime import datetime, timedelta, timezone

KST = timezone(timedelta(hours=9))


def summary(lines):
    path = os.environ.get("GITHUB_STEP_SUMMARY")
    text = "\n".join(lines) + "\n"
    if path:
        with open(path, "a", encoding="utf-8") as f:
            f.write(text)
    print(text)


def main():
    base = os.environ.get("BASE_URL", "").rstrip("/")
    token = os.environ.get("INTERNAL_TOKEN", "")
    date = os.environ.get("RCCL_DT", "").strip() or datetime.now(KST).strftime("%Y%m%d")
    if not base or not token:
        summary(["### 정기 대사 확인", "", "BASE_URL 또는 INTERNAL_TOKEN이 비어 있어 확인하지 못했습니다."])
        return 1
    if len(date) != 8 or not date.isdigit():
        summary(["### 정기 대사 확인", "", f"날짜 형식이 YYYYMMDD가 아닙니다: {date}"])
        return 1

    url = f"{base}/api/v1/internal/reconcile/status?rcclDt={date}"
    req = urllib.request.Request(url, headers={"X-Internal-Token": token, "Accept": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=20) as res:
            body = json.loads(res.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        reason = {401: "토큰이 맞지 않습니다(INTERNAL_TOKEN 확인)", 400: "날짜 형식 오류"}.get(e.code, "서버 오류")
        summary(["### 정기 대사 확인", "", f"HTTP {e.code}: {reason} · 대상일 {date}"])
        return 1
    except (urllib.error.URLError, TimeoutError, ValueError) as e:
        summary(["### 정기 대사 확인", "", f"서버에 연결하지 못했습니다: {type(e).__name__} · {base}"])
        return 1

    stat = body.get("runStatCd")
    diff = body.get("diffAcntCnt")
    tot = body.get("totAcntCnt")
    if stat is None:
        verdict, ok = "그날 정기 대사 기록이 없습니다(16:00 실행 여부·서버 상태 확인)", False
    elif stat == "RUNNING":
        verdict, ok = "아직 실행 중입니다(16:00 시작 뒤 재시도 포함 끝났어야 함)", False
    elif stat == "FAILED":
        verdict, ok = "정기 대사가 실패했습니다(관리자 화면 대사 이력의 실패 사유 확인)", False
    elif stat == "DONE" and diff == 0:
        verdict, ok = "이상 없음", True
    elif stat == "DONE":
        verdict, ok = f"불일치 계좌 {diff}개가 있습니다(관리자 화면 대사 상세 확인)", False
    else:
        verdict, ok = f"알 수 없는 상태 {stat}", False

    summary([
        "### 정기 대사 확인",
        "",
        "| 대상일 | 실행 번호 | 상태 | 전체 계좌 | 불일치 계좌 | 판정 |",
        "|---|---|---|---|---|---|",
        f"| {date} | {body.get('rcclRunNo')} | {stat} | {tot} | {diff} | {'✅' if ok else '❌'} {verdict} |",
    ])
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
