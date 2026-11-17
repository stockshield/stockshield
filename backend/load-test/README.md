# 주문·출금 부하 테스트 (k6)

SYS_CORE_001 #1(주문·출금 95%가 300ms 이내)을 동시 사용자 50명으로 확인한다(SYS_CORE_001 구현방안). 로컬 전용이고 CI에서는 돌지 않는다.

| 파일 | 내용 |
|---|---|
| `seed.sql` | 부하 테스트 회원 50명(`loadtest01`~`loadtest50`), 계좌(PKG_10M), 신뢰 접속(127.0.0.1·::1). 여러 번 돌려도 된다 |
| `price-feed.ps1` | 측정 동안 Redis `price:{tckCd}` 5종목을 5초마다 지금 시각으로 다시 쓴다(장 밖에도 PRICE_STALE이 나지 않게) |
| `order-withdraw.js` | 사용자마다 자기 계좌로 매수 → 매도 → 10원 출금을 반복. 앞 20초는 데우기, 이어서 60초 측정 |
| `verify.sql` | 측정 뒤 loadtest 계좌의 예수금·보유 수량을 원장 대사와 같은 기준으로 확인 |

## 준비

1. k6 설치: `winget install k6 --source winget` (확인: `k6 version`)
2. Docker Desktop을 켜고 `docker compose -f infra/docker-compose.yml up -d`
3. 회원 넣기(처음 한 번, 저장소 루트에서)

```powershell
docker cp backend\load-test\seed.sql stockshield-postgres:/tmp/seed.sql
docker exec stockshield-postgres sh -c 'psql -U $POSTGRES_USER -d $POSTGRES_DB -v ON_ERROR_STOP=1 -f /tmp/seed.sql'
```

## 실행

창 세 개를 쓴다. IDE·브라우저는 닫고 돌린다(같은 PC에서 서버·DB·k6가 함께 돌아 결과가 실제보다 나쁘게 나온다).

창 1, 시세 공급(저장소 루트):

```powershell
powershell -ExecutionPolicy Bypass -File backend\load-test\price-feed.ps1
```

창 2, 서버(`backend`). 장 밖에도 주문을 받게 시연 모드로 띄우고, 뉴스 수집은 끈다.

```powershell
New-Item -ItemType Directory -Force build\load-test | Out-Null
.\gradlew.bat bootRun --args="--stockshield.demo-mode=true --stockshield.news.enabled=false --stockshield.news.interval=PT10M --stockshield.news.initial-delay=PT1M" *> build\load-test\bootrun.log
```

DB 연결 수를 바꿔 볼 때는 `--spring.datasource.hikari.maximum-pool-size=N`을 같은 `--args` 안에 붙인다.

창 3, k6(저장소 루트):

```powershell
k6 run --summary-export=backend\build\load-test\result.json backend\load-test\order-withdraw.js
```

| 환경 변수 | 기본값 | 뜻 |
|---|---|---|
| `THINK_SEC` | 1 | 요청 사이 쉬는 초. 0이면 쉬지 않고 연달아 보낸다 |
| `DURATION` | 60s | 측정 구간 길이 |
| `BASE_URL` | http://127.0.0.1:8080 | 서버 주소. `localhost`로 바꾸면 ::1로 붙을 수 있다 |

예: `k6 run -e THINK_SEC=0 ...`

끝에 `thresholds`가 모두 통과(✓)면 주문·출금 각각 95%가 300ms 이내다. 측정 구간(`scenario:main`)만 판정하고 데우기 구간은 뺀다.

## 확인

```powershell
docker cp backend\load-test\verify.sql stockshield-postgres:/tmp/verify.sql
docker exec stockshield-postgres sh -c 'psql -U $POSTGRES_USER -d $POSTGRES_DB -v ON_ERROR_STOP=1 -f /tmp/verify.sql'
```

`dps_mismatch`가 0이고 보유 수량 불일치 행이 없으면 부하 중에도 원장이 맞다.

## 주의

- `seed.sql`은 소유자 계정으로 DB에 직접 넣는다. 운영 DB에는 돌리지 않는다.
- `price-feed.ps1`은 실제 시세 키를 가짜 값으로 덮어쓴다. 수집기가 돌고 있는 동안에는 쓰지 않는다.
- 앱 계정에 DELETE 권한이 없어 넣은 데이터는 지우지 않는다. 다시 돌리면 같은 회원·계좌를 그대로 쓴다.
