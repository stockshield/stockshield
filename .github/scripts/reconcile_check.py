"""정기 대사 결과 확인 (ADM_REC_001 #4, reconcile.yml에서 실행).

GET {BASE_URL}/api/v1/internal/reconcile/status?rcclDt=YYYYMMDD 를 X-Internal-Token 헤더로 부르고 판정한다.
- 성공(0): runStatCd가 DONE이고 diffAcntCnt가 0
- 건너뜀(0): 서버에 연결하지 못함(EC2가 꺼져 있음 — 평일 16:30 자동 종료 뒤·발표 전 중지 기간·예약이 늦어진 날).
  요약에 '서버 꺼짐, 확인 못 함'만 남기고 실패 메일을 보내지 않는다
- 실패(1): 서버가 답했는데 불일치 계좌가 있음, FAILED, 아직 RUNNING, 그날 정기 대사 기록 없음, 401·400·5xx
결과 표는 GITHUB_STEP_SUMMARY(있으면)에 쓴다. 토큰 값은 어디에도 출력하지 않는다.

환경 변수
- BASE_URL, INTERNAL_TOKEN
- RCCL_DT: 확인할 날짜(YYYYMMDD). 있으면 이 날짜를 본다(수동 실행)
- RCCL_TARGET: RCCL_DT가 없을 때 today(16:20 실행) 또는 prev-weekday(직전 평일, 아침 08:50 실행).
  서버 정기 대사는 휴장일과 상관없이 평일마다 돌므로 직전 평일이면 된다(월요일 아침이면 금요일).
  today라도 실행 시각(KST)이 16:00 전이거나 주말이면 아직 그날 대사가 없으므로 직전 평일을 본다
  (16:20 예약이 밀려 다음 날 아침에 돌면 다음 날짜를 보고 '기록 없음'으로 거짓 실패하던 문제)
- GITHUB_OUTPUT(있으면)에 answered(서버가 답했는지)·rccl_dt·verdict·run_no·stat·tot·diff를 쓴다.
  워크플로가 실패 이슈를 만들 때 쓴다(계좌번호는 이 API에 없어 이슈에도 들어가지 않는다)
"""
import json
import os
import socket
import sys
import urllib.error
import urllib.request
from datetime import date, datetime, timedelta, timezone

KST = timezone(timedelta(hours=9))


def prev_weekday(d: date) -> date:
    d -= timedelta(days=1)
    while d.weekday() >= 5:      # 토(5)·일(6)이면 금요일까지 거슬러 감
        d -= timedelta(days=1)
    return d


RECONCILE_HOUR = 16


def target_date(now: datetime) -> str:
    explicit = os.environ.get("RCCL_DT", "").strip()
    if explicit:
        return explicit
    today = now.date()
    if os.environ.get("RCCL_TARGET", "today").strip() == "prev-weekday":
        return prev_weekday(today).strftime("%Y%m%d")
    if today.weekday() >= 5 or now.hour < RECONCILE_HOUR:
        return prev_weekday(today).strftime("%Y%m%d")
    return today.strftime("%Y%m%d")


def output(**kv):
    path = os.environ.get("GITHUB_OUTPUT")
    if not path:
        return
    with open(path, "a", encoding="utf-8") as f:
        for k, v in kv.items():
            f.write(f"{k}={'' if v is None else str(v).replace(chr(10), ' ')}" + "\n")


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
    date_str = target_date(datetime.now(KST))
    head = ["### 정기 대사 확인", ""]
    if not base or not token:
        summary(head + ["BASE_URL 또는 INTERNAL_TOKEN이 비어 있어 확인하지 못했습니다."])
        return 1
    if len(date_str) != 8 or not date_str.isdigit():
        summary(head + [f"날짜 형식이 YYYYMMDD가 아닙니다: {date_str}"])
        return 1

    url = f"{base}/api/v1/internal/reconcile/status?rcclDt={date_str}"
    req = urllib.request.Request(url, headers={"X-Internal-Token": token, "Accept": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=20) as res:
            body = json.loads(res.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        # 서버가 답했다: 토큰·날짜·서버 오류는 실패
        reason = {401: "토큰이 맞지 않습니다(INTERNAL_TOKEN 확인)", 400: "날짜 형식 오류"}.get(e.code, "서버 오류")
        summary(head + [f"❌ HTTP {e.code}: {reason} · 대상일 {date_str}"])
        output(answered="true", rccl_dt=date_str, verdict=f"HTTP {e.code} {reason}")
        return 1
    except (urllib.error.URLError, TimeoutError, socket.timeout, ConnectionError) as e:
        # 서버에 닿지 못했다: EC2가 꺼져 있는 시간. 실패로 치지 않는다
        summary(head + [f"⏭️ 서버 꺼짐, 확인 못 함 · 대상일 {date_str} · {type(e).__name__}",
                        "", "평일 아침 08:50 실행이 직전 평일 대사를 다시 확인합니다."])
        output(answered="false", rccl_dt=date_str)
        return 0
    except ValueError:
        summary(head + [f"❌ 서버 응답을 해석하지 못했습니다 · 대상일 {date_str}"])
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

    summary(head + [
        "| 대상일 | 실행 번호 | 상태 | 전체 계좌 | 불일치 계좌 | 판정 |",
        "|---|---|---|---|---|---|",
        f"| {date_str} | {body.get('rcclRunNo')} | {stat} | {tot} | {diff} | {'✅' if ok else '❌'} {verdict} |",
    ])
    output(answered="true", rccl_dt=date_str, verdict=verdict, run_no=body.get("rcclRunNo"), stat=stat, tot=tot, diff=diff)
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
