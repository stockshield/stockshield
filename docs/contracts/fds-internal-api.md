# FDS 서버 → 코어뱅킹 내부 API

FDS 서버가 사용자 감시(U1·U2)에서 적발한 내역을 코어뱅킹에 보냅니다. 코어뱅킹은 탐지 테이블에 기록하고 관리자 화면에 보여 줍니다.
**제재 집행은 Redis로 이미 되고 있으므로**, 이 API가 실패해도 매수 정지는 동작합니다. 이 API는 기록과 관리자 알림용입니다.

## 요청

```
POST /api/internal/fds/detections
Content-Type: application/json
X-Internal-Key: ${FDS_INTERNAL_KEY}
```

- `X-Internal-Key`는 FDS 서버와 코어뱅킹이 같은 값을 환경 변수로 갖습니다. 저장소에 올리지 않습니다.
- 이 경로는 외부(프런트엔드)에서 호출할 수 없게 막습니다. 키가 틀리면 401입니다.

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `detectionId` | string | ✅ | 탐지 ID (UUID). 같은 ID가 다시 오면 중복으로 처리 |
| `mbrNo` | int | ✅ | 회원번호 |
| `acntNo` | string | ✅ | 계좌번호 |
| `rule` | string | ✅ | `U1`(과도한 주문 반복), `U2`(다계정 가장매매 의심) |
| `action` | string | ✅ | 이번에 집행한 단계: `WARN` 또는 `BUY_BLOCK` |
| `strike` | int | ✅ | 24시간 안의 적발 횟수 (1, 2, …) |
| `blockedUntil` | string | | `BUY_BLOCK`일 때 정지가 풀리는 시각 |
| `detail` | object | ✅ | 규칙별 근거 (아래) |
| `evidenceEventIds` | array | ✅ | 근거가 된 `order.log`의 `eventId` 목록 (최대 50개) |
| `detectedAt` | string | ✅ | 탐지 시각 |

`detail` 예시

- U1: `{ "windowSec": 60, "orderCount": 14, "threshold": 10 }`
- U2: `{ "windowSec": 300, "tckCd": "005930", "deviceKey": "ip+userAgent 해시", "relatedMbrNos": [42, 57], "sides": { "42": "BUY", "57": "SELL" } }`

예시: [examples/detection-request-u1.json](examples/detection-request-u1.json), [examples/detection-request-u2.json](examples/detection-request-u2.json)

> U2는 관련 회원마다 요청을 한 건씩 보냅니다(각자 `detectionId`가 다름). `detail.relatedMbrNos`로 서로 연결된 것을 알 수 있습니다.

## 응답

| 상태 | 언제 | 본문 |
| --- | --- | --- |
| `201 Created` | 새로 기록함 | `{ "detectionId": "…", "saved": true }` |
| `200 OK` | 같은 `detectionId`가 이미 있음 (중복) | `{ "detectionId": "…", "saved": false }` |
| `400 Bad Request` | 필수 필드 누락·형식 오류 | `{ "code": "INVALID_REQUEST", "message": "…" }` |
| `401 Unauthorized` | `X-Internal-Key`가 없거나 틀림 | `{ "code": "UNAUTHORIZED" }` |
| `5xx` | 코어뱅킹 오류 | |

## 재시도 (FDS 서버)

- `5xx`나 연결 실패면 1초, 2초, 4초 간격으로 **최대 3번** 다시 보냅니다. `detectionId`가 같으므로 중복 기록되지 않습니다.
- 그래도 실패하면 FDS 서버 로그에 남기고 넘어갑니다(제재는 Redis로 이미 집행됨).
- `400`, `401`은 다시 보내지 않습니다. 형식이나 키 설정 문제이기 때문입니다.

## 코어뱅킹 탐지 테이블 제안 (조성우 님 확정 필요)

테이블정의서의 기존 `fds_result`(주문 1건당 판정 결과)는 v3 구조와 맞지 않아, 사용자 감시 기록용 테이블을 새로 두는 것을 제안합니다.

| 컬럼 | 타입 | 설명 |
| --- | --- | --- |
| `detection_id` | VARCHAR(36) PK | 탐지 ID |
| `mbr_no` | BIGINT FK | 회원번호 |
| `acnt_no` | VARCHAR(20) | 계좌번호 |
| `rule_cd` | VARCHAR(10) | `U1`, `U2` |
| `action_cd` | VARCHAR(20) | `WARN`, `BUY_BLOCK` |
| `strike_no` | INTEGER | 24시간 안의 적발 횟수 |
| `blocked_until` | TIMESTAMP | 매수 정지 해제 시각 |
| `detail_json` | JSONB | 규칙별 근거 + 근거 eventId 목록 |
| `detected_at` | TIMESTAMP | 탐지 시각 |
| `reg_ts` | TIMESTAMP | 기록 시각 |

## (선택) 시장 감시 기록

관리자 화면에 위험 종목 이력을 보여 주고 싶으면 `POST /api/internal/fds/market-alerts`를 같은 방식으로 추가할 수 있습니다. 필수 범위가 아니라서 3주차에 여유가 있으면 정합니다.
