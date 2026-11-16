# 부하 테스트 동안 Redis 시세(price:{tckCd})를 5초마다 지금 시각으로 다시 씀. 로컬 전용
# 장 밖에는 수집기 시세가 들어오지 않아 60초가 지나면 주문이 PRICE_STALE로 거절되기 때문 (TRD_ORD_001 #2)
# 형식은 PriceCache.putPrice와 같게 씀 (대기 목록 #56)
# 실행(저장소 루트, 따로 연 창에서): powershell -ExecutionPolicy Bypass -File backend\load-test\price-feed.ps1
# 멈출 때는 Ctrl+C
param(
    [string]$Container = 'stockshield-redis',
    [int]$IntervalSec = 5
)

# order-withdraw.js의 STOCKS와 같은 종목. 값이 바뀌면 사고팔 때 예수금이 움직이므로 고정
$prices = [ordered]@{
    '005930' = 70000
    '000660' = 180000
    '005380' = 250000
    '105560' = 80000
    '068270' = 170000
}

while ($true) {
    $ts = (Get-Date).ToString("yyyy-MM-dd'T'HH:mm:ss.fffzzz")
    $lines = foreach ($code in $prices.Keys) {
        $json = '{"tckCd":"' + $code + '","currentPrice":' + $prices[$code] + ',"prcTs":"' + $ts + '","chgPrc":0,"chgRt":0.00,"totalTrdVol":0}'
        "SET price:$code '$json'"
    }
    # 컨테이너의 REDISCLI_AUTH로 비밀번호를 넣으므로 여기서 비밀번호를 다루지 않음
    $out = $lines | docker exec -i $Container redis-cli
    if ($LASTEXITCODE -ne 0 -or ($out | Where-Object { $_ -ne 'OK' })) {
        Write-Warning "시세를 쓰지 못함: $out"
    } else {
        Write-Host "$ts 시세 $($prices.Count)종목 갱신"
    }
    Start-Sleep -Seconds $IntervalSec
}
