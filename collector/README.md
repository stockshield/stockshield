# StockShield 시세 수집기 (collector)

한국투자증권 웹소켓 실시간 체결가를 받아 Kafka `market.tick`으로 보냅니다 (MKT_TICK_001).
메인 서버와 따로 실행하고, Redis에는 쓰지 않습니다. Redis는 메인 서버의 시세 컨슈머가 씁니다.

## 실행

먼저 인프라를 띄웁니다.

```powershell
docker compose -f infra/docker-compose.yml up -d
```

그다음 수집기를 실행합니다.

```powershell
cd collector
.\gradlew.bat bootRun
```

`infra/.env`의 `KIS_APP_KEY`·`KIS_APP_SECRET`·`KIS_ENV`·`KIS_REST_URL`·`KIS_WS_URL`을 읽습니다. 키는 로그에 남기지 않습니다.

## 수집 방식

`COLLECTOR_MODE` 환경 변수로 정합니다.

| 방식 | 설명 |
|---|---|
| `KIS` | 한국투자증권 실시간 체결가. 서비스 대상 30종목을 구독합니다 (1세션 41건 한도 안). 끊기면 5초 뒤 다시 연결합니다 |
| `REPLAY` | `data/ticks-YYYYMMDD.jsonl`(가장 최근 파일, 또는 `COLLECTOR_REPLAY_FILE`)을 원래 간격대로 다시 보냅니다. 시세 시각은 보내는 시점으로 바꾸고, 원래 체결 시각은 `orgTs`에 담습니다. `COLLECTOR_REPLAY_SPEED`로 배속을 정합니다 |
| `MOCK` | 종목마다 1초에 한 번 무작위로 움직이는 가짜 시세입니다 |

비워 두면 다음 순서로 정합니다.
1. `DEMO_MODE=true`면 `REPLAY`입니다. 재생할 파일이 없으면 `MOCK`으로 보냅니다.
2. 한국투자증권 키가 없으면 `MOCK`입니다.
3. 그 밖에는 `KIS`입니다.

`KIS`로 받은 시세는 `data/ticks-YYYYMMDD.jsonl`에 기록되어 장 외 시간 시연에 재생할 수 있습니다. `data/`는 Git에 올리지 않습니다.

## 메시지 형식 (`market.tick`, 키 = 종목·지수 코드)

```json
{"v":1,"type":"TICK","code":"005930","currentPrice":207500,"chgPrc":-500,"chgRt":-0.24,
 "trdVol":12,"accVol":6126832,"prcTs":"2026-11-13T12:32:19+09:00","src":"KIS"}
```

- `type`: `TICK`(종목) 또는 `INDEX`(지수, 값은 `idxVal`)
- `prcTs`: 시세 시각(+09:00). 주문은 이 시각으로 시세 지연(60초 초과)을 판단합니다.
- `src`: `KIS`, `REPLAY`, `MOCK`

## 테스트

```powershell
.\gradlew.bat test
```
