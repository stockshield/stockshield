# 주문 경고·차단 응답 (코어뱅킹 → 프런트엔드) — 초안

> **초안입니다.** 코어뱅킹(조성우)과 프런트엔드(한승희)가 확정합니다. FDS 서버는 이 응답을 직접 만들지 않지만, 문구와 규칙 ID가 FDS 쪽 Redis 값에서 오기 때문에 함께 정리했습니다.

## 흐름

1. 프런트가 주문을 보냅니다. `fdsAck: false`
2. 코어뱅킹이 [redis-keys.md](redis-keys.md) 순서대로 확인합니다.
   - 매수인데 `BUY_BLOCK` → **403**, 주문 실행 안 함
   - `sanction` WARN, 또는 (안심모드 + `risk:stock` 있음 + 10분 안에 확인한 적 없음) → **409**, 주문 실행 안 함
   - 해당 없음 → 평소대로 주문 처리
3. 409를 받은 프런트는 경고 팝업을 띄웁니다. 사용자가 확인하면 **같은 내용 + `fdsAck: true`** 로 다시 보냅니다.
4. 코어뱅킹은 `fdsAck: true` 주문을 처리합니다. 그다음 `fdsack:{mbrNo}:{tckCd}`(10분)를 쓰고, WARN 제재라면 `sanction`을 지웁니다.

## 주문 요청 (추가 필드만)

```
POST /api/orders
{ …기존 주문 필드…, "idempotencyKey": "uuid", "fdsAck": false }
```

409를 받고 다시 보낼 때도 **같은 `idempotencyKey`** 를 씁니다. 첫 요청에서는 주문이 만들어지지 않았기 때문에 중복이 아닙니다.

## 409 경고 응답

```json
{
  "code": "FDS_WARNING",
  "ackRequired": true,
  "warnings": [
    {
      "type": "MARKET",
      "level": "WARN",
      "tckCd": "005930",
      "rules": ["M1"],
      "message": "1분 수익률이 평소보다 크게 움직였어요 (Z=3.4)"
    }
  ]
}
```

| 필드 | 설명 |
| --- | --- |
| `warnings[].type` | `MARKET`(위험 종목, 안심모드일 때만) 또는 `USER`(1차 제재, 항상) |
| `warnings[].level` | `CAUTION`(주의, 노란색) 또는 `WARN`(경고, 주황색) |
| `warnings[].rules` | 규칙 ID. 화면에 "자세히 보기"로 보여 줄 수 있음 |
| `warnings[].message` | 팝업에 그대로 쓰는 문구 |

## 403 매수 정지 응답

```json
{
  "code": "FDS_BUY_BLOCKED",
  "until": "2026-11-10T10:12:30+09:00",
  "remainingSec": 243,
  "message": "짧은 시간 동안 비정상적인 주문이 반복되어 매수가 5분간 제한되었어요. 매도는 가능해요."
}
```

## 화면에서 할 일 (한승희)

- 회원 설정에 **안심모드 켜기/끄기** (기본 꺼짐)
- 409: 경고 팝업, `level`에 맞는 색, 확인 버튼 → `fdsAck: true`로 재요청, 취소 버튼 → 주문 취소
- 403: 매수 버튼 비활성화 + 남은 시간 카운트다운, 매도는 그대로
