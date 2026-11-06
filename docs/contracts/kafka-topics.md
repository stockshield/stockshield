# Kafka 토픽

브로커 1대(KRaft), 로컬 접속은 `localhost:9092`, 컨테이너끼리는 `kafka:29092`입니다. ([infra/README.md](../../infra/README.md))

| 토픽 | 보내는 쪽 | 받는 쪽 | 메시지 키 | 파티션 | 보관 |
| --- | --- | --- | --- | --- | --- |
| `market.tick` | 시세 수집기 | FDS 서버 (시장 감시) | 종목코드 `tckCd` | 3 | 24시간 |
| `market.orderbook` | 시세 수집기 | FDS 서버 (시장 감시, M3) | 종목코드 `tckCd` | 3 | 24시간 |
| `order.log` | 코어뱅킹 서버 | FDS 서버 (사용자 감시) | 회원번호 `mbrNo` (문자열) | 3 | 24시간 |

- **메시지 키가 같으면 같은 파티션**에 들어가서 순서가 지켜집니다. 종목별 시세 순서, 회원별 주문 순서가 보장됩니다.
- FDS 서버는 **인스턴스 1개**로 세 파티션을 모두 읽는다고 가정합니다. U2(다계정)는 여러 회원의 주문을 함께 봐야 하기 때문입니다.
- 전달 보장은 **최소 1회(at-least-once)** 입니다. 같은 메시지가 두 번 올 수 있으니, 받는 쪽은 `eventId`(order.log)나 `tckCd`+`ts`(시세)로 중복을 걸러야 합니다.
- 토픽 생성: `infra/docker-compose.yml`의 `kafka-init`이 세 토픽을 위 설정(파티션 3, 보관 24시간)으로 만듭니다. 자동 생성은 꺼 두었으므로, 토픽 이름을 틀리게 쓰면 보내기가 실패합니다.

---

## `market.tick` (실시간 체결)

한국투자증권 실시간 체결가(웹소켓) 1건을 우리 형식으로 바꾼 메시지입니다. **체결 30종목**을 구독합니다.

| 필드 | 타입 | 필수 | 설명 | 예 |
| --- | --- | --- | --- | --- |
| `v` | int | ✅ | 형식 버전 | `1` |
| `tckCd` | string | ✅ | 종목코드 6자리 | `"005930"` |
| `ts` | string | ✅ | 체결 시각 (ISO 8601, +09:00) | `"2026-11-10T10:00:01.123+09:00"` |
| `curPrc` | int | ✅ | 현재가(체결가), 원 | `71200` |
| `chgPrc` | int | ✅ | 전일 대비, 원 (하락은 음수) | `900` |
| `chgRt` | number | ✅ | 전일 대비 등락률, % (소수 2자리) | `1.28` |
| `trdVol` | int | ✅ | 이번 체결 수량 | `1520` |
| `accVol` | int | ✅ | 당일 누적 거래량 | `1203344` |
| `viStatus` | string | | VI 상태: `NONE`, `STATIC`, `DYNAMIC`. M4에 사용. 한국투자증권에서 받을 수 있는지 **확인 필요** | `"NONE"` |
| `src` | string | ✅ | 데이터 출처: `KIS`(실시간), `MOCK`(가짜 시세 생성), `REPLAY`(저장된 시세 재생) | `"KIS"` |

예시: [examples/market-tick.json](examples/market-tick.json)

**FDS 서버가 하는 일**: 1분 단위로 묶어서 수익률·거래량을 계산하고 M1(1분 수익률 Z ≥ 3), M2(1분 거래량 Z ≥ 3), M4(VI)를 판정합니다. 표본이 30개 미만이면 M1·M2는 판정하지 않습니다.

---

## `market.orderbook` (실시간 호가)

한국투자증권 실시간 호가 1건입니다. **호가 10종목**만 구독합니다. FDS 서버는 M3(호가 쏠림)에만 씁니다.

| 필드 | 타입 | 필수 | 설명 | 예 |
| --- | --- | --- | --- | --- |
| `v` | int | ✅ | 형식 버전 | `1` |
| `tckCd` | string | ✅ | 종목코드 | `"005930"` |
| `ts` | string | ✅ | 호가 시각 | `"2026-11-10T10:00:01.456+09:00"` |
| `totAskQty` | int | ✅ | 총 매도 잔량 | `412300` |
| `totBidQty` | int | ✅ | 총 매수 잔량 | `2215000` |
| `asks` | array | | 매도 호가 (가까운 순, 최대 10개) `[{ "prc": 71300, "qty": 15200 }]` | |
| `bids` | array | | 매수 호가 (가까운 순, 최대 10개) | |
| `src` | string | ✅ | `KIS`, `MOCK`, `REPLAY` | `"KIS"` |

예시: [examples/market-orderbook.json](examples/market-orderbook.json)

**M3 판정**: `totBidQty ÷ totAskQty` 가 5 이상이거나 0.2 이하이면 호가 쏠림(주의)입니다. 한쪽 잔량이 0이면 판정하지 않습니다.

---

## `order.log` (사용자 주문 기록)

코어뱅킹이 주문 처리 결과를 **DB 커밋이 끝난 뒤** 보냅니다. **Kafka 응답을 기다리지 않습니다.** Kafka가 멈춰도 주문은 계속되고, 사용자 감시만 늦어집니다.

| 필드 | 타입 | 필수 | 설명 | 예 |
| --- | --- | --- | --- | --- |
| `v` | int | ✅ | 형식 버전 | `1` |
| `eventId` | string | ✅ | 이 로그의 고유 ID (UUID). 중복 처리 방지용 | `"6f1c…"` |
| `ordNo` | string | | 주문번호. 거절돼서 주문이 만들어지지 않았으면 없음 | `"20261110000123"` |
| `mbrNo` | int | ✅ | 회원번호 | `42` |
| `acntNo` | string | ✅ | 계좌번호 | `"5501-0042"` |
| `tckCd` | string | ✅ | 종목코드 | `"005930"` |
| `side` | string | ✅ | `BUY`, `SELL` | `"BUY"` |
| `qty` | int | ✅ | 주문 수량 | `10` |
| `prc` | int | ✅ | 주문 단가(시장가 체결가), 원 | `71200` |
| `result` | string | ✅ | `FILLED`(체결), `REJECTED`(거절) | `"FILLED"` |
| `rejectCode` | string | | 거절 사유 코드. 예: `INSUFFICIENT_BALANCE`, `MARKET_CLOSED`, `FDS_BUY_BLOCKED` | |
| `safeMode` | boolean | ✅ | 주문 시점 안심모드 여부 | `true` |
| `fdsAck` | boolean | ✅ | 경고를 보고 확인한 뒤 낸 주문인지 | `false` |
| `ip` | string | ✅ | 접속 IP | `"203.0.113.7"` |
| `userAgent` | string | ✅ | 접속 기기 정보 (User-Agent) | `"Mozilla/5.0 …"` |
| `ts` | string | ✅ | 주문 접수 시각 | `"2026-11-10T10:00:02.010+09:00"` |

예시: [examples/order-log.json](examples/order-log.json)

**FDS 서버가 하는 일**

- **U1 과도한 주문 반복**: 같은 `mbrNo`의 주문(`result` 상관없이)이 최근 1분 안에 10건 이상
- **U2 다계정 가장매매 의심**: `ip`+`userAgent`가 같은 서로 다른 `mbrNo` 2개 이상이 같은 `tckCd`를 5분 안에 반대 `side`로 체결(`FILLED`)
- 판정 결과는 [redis-keys.md](redis-keys.md)의 `sanction`, `strike`에 쓰고, [fds-internal-api.md](fds-internal-api.md)로 코어뱅킹에 알립니다.

> 개인정보: `ip`, `userAgent`는 내부 감시용으로만 쓰고, FDS 서버 로그에 그대로 출력하지 않습니다.
