# StockShield 시세 수집기 (collector)

한국투자증권 웹소켓으로 실시간 체결가와 코스피·코스닥 지수를 받아 Kafka `market.tick`으로 보냅니다 (MKT_TICK_001).
Spring Boot 3.3.5, Java 17. 메인 서버와 따로 실행하고, Redis와 DB에는 쓰지 않습니다. Redis·DB는 메인 서버의 시세 컨슈머가 씁니다.

## 받는 시세

| 종류 | 대상 | 한국투자증권 TR |
|---|---|---|
| 종목 체결가 | 서비스 대상 30종목 (코스피 시가총액 상위 20 + 코스닥 상위 10, 2026-11-10 기준) | `H0STCNT0` |
| 지수 | 코스피(`0001`), 코스닥(`1001`) | `H0UPCNT0` |

- 웹소켓 구독은 30 + 2 = 32건으로, 한 세션 한도 41건 안입니다. 종목 목록은 `src/main/resources/application.yml`에 있습니다.
- 접속키는 REST `/oauth2/Approval`로 받습니다. 앱 키·시크릿·접속키는 로그에 남기지 않습니다.
- 연결이 끊기면 5초 뒤 다시 연결합니다. 서버가 보내는 PINGPONG은 그대로 돌려보냅니다.
- 호가는 수집기가 받지 않습니다. 메인 서버가 화면 요청이 올 때 한국투자증권 REST로 조회합니다.

## 실행 (내 PC)

먼저 인프라를 띄웁니다.

```powershell
docker compose -f infra/docker-compose.yml up -d
```

그다음 수집기를 실행합니다.

```powershell
cd collector
.\gradlew.bat bootRun
```

`infra/.env`의 `KIS_APP_KEY`·`KIS_APP_SECRET`·`KIS_ENV`·`KIS_REST_URL`·`KIS_WS_URL`을 읽습니다.

> **같은 한국투자증권 키로 수집기를 두 곳에서 동시에 띄우지 않습니다.** 배포 서버가 켜져 있으면 내 PC에서는 `COLLECTOR_MODE=MOCK`으로 실행합니다.

## 수집 방식

`COLLECTOR_MODE` 환경 변수로 정합니다.

| 방식 | 설명 |
|---|---|
| `KIS` | 한국투자증권 실시간 체결가와 지수 |
| `REPLAY` | `data/ticks-YYYYMMDD.jsonl`(가장 최근 파일, 또는 `COLLECTOR_REPLAY_FILE`)을 원래 간격대로 다시 보냅니다. 시세 시각은 보내는 시점으로 바꾸고, 원래 체결 시각은 `orgTs`에 담습니다. 점심시간처럼 긴 공백은 10초로 줄입니다. `COLLECTOR_REPLAY_SPEED`로 배속을 정합니다 |
| `MOCK` | 종목과 지수마다 1초에 한 번 무작위로 움직이는 가짜 시세입니다 |

비워 두면 다음 순서로 정합니다. 배포용 compose는 기본값을 `KIS`로 넣습니다.
1. `DEMO_MODE=true`면 `REPLAY`입니다. 재생할 파일이 없으면 `MOCK`으로 보냅니다.
2. 한국투자증권 키가 없으면 `MOCK`입니다.
3. 그 밖에는 `KIS`입니다.

`KIS`로 받은 시세는 `data/ticks-YYYYMMDD.jsonl`에 기록되어 장 외 시간 시연에 재생할 수 있습니다(`COLLECTOR_RECORD=false`면 기록하지 않음). `data/`는 Git에 올리지 않습니다.

## 메시지 형식 (`market.tick`, 키 = 종목·지수 코드)

종목:

```json
{"v":1,"type":"TICK","code":"005930","currentPrice":207500,"chgPrc":-500,"chgRt":-0.24,
 "trdVol":12,"accVol":6126832,"prcTs":"2026-11-13T12:32:19+09:00","src":"KIS"}
```

지수:

```json
{"v":1,"type":"INDEX","code":"0001","idxVal":5075.75,"idxChg":12.30,"chgRt":0.24,
 "accVol":312456,"prcTs":"2026-11-19T10:13:05+09:00","src":"KIS"}
```

- `type`: `TICK`(종목) 또는 `INDEX`(지수)
- 종목은 `currentPrice`(원, 정수)와 `chgPrc`, 지수는 `idxVal`과 `idxChg`(전일 대비 포인트, 소수)를 씁니다.
- `prcTs`: 시세 시각(+09:00). 메인 서버는 이 시각으로 시세 지연(60초 초과)을 판단합니다.
- `orgTs`: 재생 시세의 원래 체결 시각 (`REPLAY`일 때만)
- `src`: `KIS`, `REPLAY`, `MOCK`
- 값이 없는 필드는 보내지 않습니다.

## 배포

`infra/docker-compose.prod.yml`의 `collector` 서비스가 `Dockerfile`로 빌드합니다(eclipse-temurin 17, 일반 사용자로 실행).
기록 파일은 `collector-data` 볼륨(`/app/data`)에 남습니다. 서버를 다시 만들어도 볼륨을 지우지 않으면 유지됩니다.

```bash
docker compose -f infra/docker-compose.prod.yml logs -f collector     # "시세 수집 방식: KIS", 종목 30개·지수 2개 구독 확인
docker compose -f infra/docker-compose.prod.yml cp collector:/app/data ./data   # 기록 파일 꺼내기
```

## 테스트

```powershell
.\gradlew.bat test
```

한국투자증권 체결·지수 메시지 해석(`KisTickParser`)을 확인합니다.
