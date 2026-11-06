# Redis 키

FDS 서버가 판정 결과를 쓰고, 코어뱅킹이 주문할 때 읽습니다. 값은 모두 JSON 문자열이고, **TTL로 자동 만료**됩니다.
FDS 서버가 멈추면 값이 만료되어 경고·제재가 사라질 뿐, 주문은 막히지 않습니다.

| 키 | 쓰는 쪽 | 읽는 쪽 | TTL | 용도 |
| --- | --- | --- | --- | --- |
| `risk:stock:{tckCd}` | FDS 서버 | 코어뱅킹 (**안심모드인 주문만**) | 300초 | 위험 종목 |
| `sanction:{mbrNo}` | FDS 서버 (코어뱅킹은 WARN 확인 시 삭제만) | 코어뱅킹 (**모든 주문**) | WARN 24시간 / BUY_BLOCK 300초 | 제재 상태 |
| `strike:{mbrNo}` | FDS 서버 | FDS 서버 | 86400초 (24시간) | 24시간 안의 적발 횟수 |
| `fdsack:{mbrNo}:{tckCd}` | 코어뱅킹 | 코어뱅킹 | 600초 | 같은 종목 경고를 10분 동안 다시 묻지 않기 위한 표시 |

---

## `risk:stock:{tckCd}`

시장 감시(M1~M4)에 하나라도 걸린 종목입니다. 조건이 계속되면 FDS 서버가 값을 다시 쓰면서 TTL을 300초로 갱신합니다. 조건이 끝나면 5분 뒤 자동으로 사라집니다.

| 필드 | 타입 | 설명 |
| --- | --- | --- |
| `v` | int | 형식 버전 `1` |
| `tckCd` | string | 종목코드 |
| `level` | string | `CAUTION`(주의) 또는 `WARN`(경고). 여러 규칙에 걸리면 높은 쪽 |
| `rules` | array | 걸린 규칙 목록 `[{ "id": "M1", "value": 3.4, "message": "1분 수익률이 평소보다 크게 움직였어요 (Z=3.4)" }]` |
| `updatedAt` | string | 마지막 판정 시각 |

규칙별 단계: M1 `WARN`, M2 `CAUTION`, M3 `CAUTION`, M4 `WARN`

예시: [examples/redis-risk-stock.json](examples/redis-risk-stock.json)

**코어뱅킹**: 안심모드인 회원의 주문에서 이 키가 있고, `fdsack:{mbrNo}:{tckCd}`가 없으면 주문을 실행하지 않고 경고 응답을 보냅니다. `rules[].message`를 그대로 화면 문구로 써도 됩니다.

---

## `sanction:{mbrNo}`

사용자 감시(U1·U2) 결과입니다.

| 필드 | 타입 | 설명 |
| --- | --- | --- |
| `v` | int | 형식 버전 `1` |
| `mbrNo` | int | 회원번호 |
| `level` | string | `WARN`(1차: 경고 팝업) 또는 `BUY_BLOCK`(2차: 매수 정지) |
| `rule` | string | 걸린 규칙 `U1` 또는 `U2` |
| `detectionId` | string | 탐지 ID ([fds-internal-api.md](fds-internal-api.md)와 같은 값) |
| `message` | string | 사용자에게 보여 줄 문구 |
| `until` | string | `BUY_BLOCK`일 때 정지가 풀리는 시각 |
| `createdAt` | string | 제재 시각 |

예시: [examples/redis-sanction-warn.json](examples/redis-sanction-warn.json), [examples/redis-sanction-buy-block.json](examples/redis-sanction-buy-block.json)

**FDS 서버의 단계 판단**

1. 적발되면 `INCR strike:{mbrNo}`. 값이 1이면 `EXPIRE strike:{mbrNo} 86400`.
2. `strike`가 1이면 `sanction`을 `WARN`으로 쓰고 TTL 86400초.
3. `strike`가 2 이상이면 `sanction`을 `BUY_BLOCK`으로 덮어쓰고 TTL 300초, `until` = 지금 + 5분.

**코어뱅킹**

- `BUY_BLOCK`이고 주문이 **매수**면 거절합니다. **매도는 허용**합니다.
- `WARN`이면 매수·매도 모두 주문을 실행하지 않고 경고 응답을 보냅니다. 사용자가 확인하고 다시 요청하면 주문을 처리하고 `sanction:{mbrNo}`를 **삭제**합니다.
  - 삭제할 때는 값이 여전히 `WARN`인지 확인한 뒤 지웁니다(그 사이 FDS 서버가 `BUY_BLOCK`으로 바꿨을 수 있음). Lua 스크립트나 `WATCH`/`MULTI`로 처리합니다.
- **안심모드와 상관없이** 모든 주문에서 확인합니다.

---

## Redis 장애 시

| 쪽 | 동작 |
| --- | --- |
| 코어뱅킹 | 조회 시간 제한 200ms. 실패하면 경고·제재 확인을 건너뛰고 주문을 처리합니다(경고 기능이므로 주문을 막지 않는 쪽 선택). 건너뛴 사실은 로그로 남깁니다 |
| FDS 서버 | 쓰기 실패는 재시도 3회 후 로그. 관리자 API 전송은 그대로 진행합니다 |
