// 동시 사용자 50명 주문·출금 부하 (SYS_CORE_001 #1·구현방안)
// 목표: 주문·출금 각각 95%가 300ms 이내. 미리 seed.sql로 회원 50명을 넣고 price-feed.ps1을 띄워 둠
//
// 실행(저장소 루트):
//   k6 run --summary-export=backend\build\load-test\결과.json backend\load-test\order-withdraw.js
// 환경 변수(-e 이름=값):
//   BASE_URL   기본 http://127.0.0.1:8080 (seed.sql의 신뢰 접속 IP와 맞추려고 localhost 대신 IP)
//   THINK_SEC  요청 사이 쉬는 초, 기본 1. 0이면 사용자마다 요청을 쉬지 않고 연달아 보냄
//   DURATION   측정 구간 길이, 기본 60s
import http from 'k6/http';
import { check, sleep, fail } from 'k6';
import { randomBytes } from 'k6/crypto';
import { Counter } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://127.0.0.1:8080';
const THINK_SEC = Number(__ENV.THINK_SEC === undefined ? 1 : __ENV.THINK_SEC);
const DURATION = __ENV.DURATION || '60s';
const USERS = 50;
const PASSWORD = __ENV.LOAD_PASSWORD || 'Loadtest!2026';
// price-feed.ps1이 시세를 쓰는 종목과 같아야 함
const STOCKS = ['005930', '000660', '005380', '105560', '068270'];

const unexpected = new Counter('unexpected_status');

export const options = {
    setupTimeout: '120s',
    scenarios: {
        // JIT·연결 풀이 데워지기 전 응답은 측정에서 뺌
        warmup: {
            executor: 'constant-vus',
            vus: 10,
            duration: '20s',
            gracefulStop: '5s',
        },
        main: {
            executor: 'constant-vus',
            vus: USERS,
            duration: DURATION,
            startTime: '25s',
        },
    },
    thresholds: {
        'http_req_duration{scenario:main,api:order}': ['p(95)<300'],
        'http_req_duration{scenario:main,api:withdraw}': ['p(95)<300'],
        'checks{scenario:main}': ['rate>0.99'],
    },
    summaryTrendStats: ['avg', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

function uuidv4() {
    const b = new Uint8Array(randomBytes(16));
    b[6] = (b[6] & 0x0f) | 0x40;
    b[8] = (b[8] & 0x3f) | 0x80;
    const h = Array.from(b, (x) => x.toString(16).padStart(2, '0')).join('');
    return `${h.slice(0, 8)}-${h.slice(8, 12)}-${h.slice(12, 16)}-${h.slice(16, 20)}-${h.slice(20)}`;
}

function two(i) {
    return String(i).padStart(2, '0');
}

export function setup() {
    const users = [];
    for (let i = 1; i <= USERS; i++) {
        // seed.sql의 dvc_id와 같은 값
        const dvcId = `5f0c0000-0000-4000-8000-0000000000${two(i)}`;
        const res = http.post(`${BASE_URL}/api/v1/auth/login`,
            JSON.stringify({ lgnId: `loadtest${two(i)}`, lgnPwd: PASSWORD }),
            { headers: { 'Content-Type': 'application/json', 'X-Device-Id': dvcId }, tags: { api: 'login' } });
        if (res.status !== 200) {
            // 202면 서버가 본 IP가 seed.sql의 신뢰 접속 IP와 다름
            fail(`loadtest${two(i)} 로그인 ${res.status}: ${res.body}`);
        }
        users.push({ token: res.json('accessToken'), dvcId });
    }
    return { users };
}

function post(path, body, user, api) {
    const res = http.post(`${BASE_URL}${path}`, JSON.stringify(body), {
        headers: {
            'Content-Type': 'application/json',
            Authorization: `Bearer ${user.token}`,
            'X-Device-Id': user.dvcId,
        },
        tags: { api },
    });
    const ok = check(res, { [`${api} 201`]: (r) => r.status === 201 });
    if (!ok) {
        unexpected.add(1, { api, status: String(res.status) });
        // 실패가 쏟아져도 콘솔이 넘치지 않게 VU마다 앞 몇 번만 찍음
        if (__ITER < 3) {
            console.warn(`${api} ${res.status} ${res.body}`);
        }
    }
    return res;
}

function think() {
    if (THINK_SEC > 0) {
        sleep(THINK_SEC);
    }
}

// 사용자마다 자기 계좌로 매수 → 매도 → 소액 출금. 같은 가격에 사고팔아 예수금이 거의 그대로라 여러 번 돌려도 잔고 부족이 나지 않음
export default function (data) {
    const user = data.users[(__VU - 1) % USERS];
    const tckCd = STOCKS[__ITER % STOCKS.length];

    post('/api/v1/orders', { tckCd, ordDvCd: 'BUY', ordQty: 1, idmpKey: uuidv4() }, user, 'order');
    think();
    post('/api/v1/orders', { tckCd, ordDvCd: 'SELL', ordQty: 1, idmpKey: uuidv4() }, user, 'order');
    think();
    // 10원: 24시간 누적이 대량 출금 기준(500만 원)에 닿지 않게 해 428 없이 201만 나오게 함 (FDS_WDR_001 #1)
    post('/api/v1/accounts/me/withdrawals',
        { wdrAmt: 10, rcvBankNm: '테스트은행', rcvAcntNo: '110-123-456789', idmpKey: uuidv4() }, user, 'withdraw');
    think();
}
