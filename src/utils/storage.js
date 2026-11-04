// StockShield 사용자, 로컬 세션, 모의 주문 및 FDS 관제 스토리지 관리 유틸리티 (PostgreSQL & JWT 연동)

const USERS_STORAGE_KEY = 'stockshield_users_v2';
const SESSION_STORAGE_KEY = 'stockshield_session_v2';
const TOKENS_STORAGE_KEY = 'stockshield_tokens_v2';
const ALERTS_STORAGE_KEY = 'stockshield_fds_alerts_v2';
const API_BASE = 'http://localhost:4000/api';

// JWT 토큰 관리
export function getAuthTokens() {
  try {
    const raw = localStorage.getItem(TOKENS_STORAGE_KEY);
    return raw ? JSON.parse(raw) : null;
  } catch (e) {
    return null;
  }
}

export function setAuthTokens(tokens) {
  try {
    if (tokens) {
      localStorage.setItem(TOKENS_STORAGE_KEY, JSON.stringify(tokens));
    } else {
      localStorage.removeItem(TOKENS_STORAGE_KEY);
    }
  } catch (e) {
    // ignore
  }
}

export function clearAuthTokens() {
  try {
    localStorage.removeItem(TOKENS_STORAGE_KEY);
  } catch (e) {
    // ignore
  }
}

// 공통 API 요청 헬퍼 (JWT 토큰 자동 첨부)
async function apiFetch(endpoint, options = {}) {
  const tokens = getAuthTokens();
  const headers = {
    'Content-Type': 'application/json',
    ...(options.headers || {}),
  };
  if (tokens?.accessToken) {
    headers['Authorization'] = `Bearer ${tokens.accessToken}`;
  }

  const res = await fetch(`${API_BASE}${endpoint}`, {
    ...options,
    headers,
  });

  // 토큰 만료(401) 처리: Refresh Token으로 자동 재발급 시도 (기능 ID 16)
  if (res.status === 401 && endpoint !== '/auth/refresh' && endpoint !== '/auth/login') {
    // 1. Refresh Token이 있는 경우 토큰 갱신 시도
    if (tokens?.refreshToken) {
      try {
        const refreshRes = await fetch(`${API_BASE}/auth/refresh`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ refreshToken: tokens.refreshToken }),
        });
        if (refreshRes.ok) {
          const refreshData = await refreshRes.json();
          const newAccess = refreshData.accessToken || refreshData.tokens?.accessToken;
          const newRefresh = refreshData.refreshToken || refreshData.tokens?.refreshToken || tokens.refreshToken;
          if (newAccess) {
            setAuthTokens({ accessToken: newAccess, refreshToken: newRefresh });
            headers['Authorization'] = `Bearer ${newAccess}`;
            return await fetch(`${API_BASE}${endpoint}`, {
              ...options,
              headers,
            });
          }
        }
      } catch (e) {
        // refresh failure
      }
    }

    // 2. 만약 Refresh Token이 없거나 만료되었는데 기본 데모 세션('demo')인 경우 자동 재로그인으로 토큰 복구
    const currentSession = getActiveSession();
    if (currentSession && currentSession.id === 'demo') {
      try {
        const loginRes = await fetch(`${API_BASE}/auth/login`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ id: 'demo', password: 'password123!' }),
        });
        if (loginRes.ok) {
          const loginData = await loginRes.json();
          if (loginData.tokens?.accessToken) {
            setAuthTokens(loginData.tokens);
            headers['Authorization'] = `Bearer ${loginData.tokens.accessToken}`;
            return await fetch(`${API_BASE}${endpoint}`, {
              ...options,
              headers,
            });
          }
        }
      } catch (e) {
        // ignore
      }
    }

    // 3. 갱신 및 복구 실패 시 만료된 무효 토큰 제거 (다음 요청 오염 방지)
    clearAuthTokens();
  }

  return res;
}

export const INITIAL_DEMO_USER = {
  id: 'demo',
  name: '김투자',
  role: 'USER',
  email: 'demo@stockshield.io',
  phone: '010-1234-5678',
  accountNo: '501-12-883921',
  balance: 11243850,
  seedAmount: 10000000,
  fdsBaseAmount: 500000,
  fdsLevel: 'Level 1 안심 보안',
  isLocked: false,
  lockReason: '',
  createdAt: '2026-11-04',
  watchlist: ['005930', '000660'],
  portfolio: {
    '005930': { quantity: 3, avgPrice: 65800, name: '삼성전자' },
    '000660': { quantity: 10, avgPrice: 184000, name: 'SK하이닉스' }
  },
  orderHistory: [
    {
      id: 1730700001000,
      timestamp: '09:30:15',
      stockCode: '005930',
      stockName: '삼성전자',
      orderType: 'BUY',
      price: 57400,
      quantity: 1,
      totalCost: 57400,
      status: 'EXECUTED',
      fdsStatus: '정상 승인 (FDS 인증)',
      rejectionReason: ''
    },
    {
      id: 1730600002000,
      timestamp: '14:22:10',
      stockCode: '000660',
      stockName: 'SK하이닉스',
      orderType: 'BUY',
      price: 184000,
      quantity: 10,
      totalCost: 1840000,
      status: 'EXECUTED',
      fdsStatus: '관리자 승인 체결',
      rejectionReason: ''
    },
    {
      id: 1730500003000,
      timestamp: '11:05:40',
      stockCode: '035420',
      stockName: 'NAVER',
      orderType: 'SELL',
      price: 198000,
      quantity: 50,
      totalCost: 9900000,
      status: 'REJECTED',
      fdsStatus: 'FDS 차단 (주문 거절)',
      rejectionReason: '위험 점수 80점 초과 (종목 및 접속 이상 감지)'
    }
  ]
};

export const INITIAL_ADMIN_USER = {
  id: 'admin',
  name: '보안관제팀',
  role: 'ADMIN',
  email: 'security@stockshield.io',
  phone: '010-9999-8888',
  accountNo: '999-00-112233',
  balance: 50000000,
  seedAmount: 50000000,
  fdsBaseAmount: 500000,
  fdsLevel: 'Level 3 최고 관제',
  isLocked: false,
  lockReason: '',
  createdAt: '2026-11-04',
  watchlist: ['005930', '000660', '373220'],
  portfolio: {},
  orderHistory: []
};

export const INITIAL_ALERTS = [
  {
    id: 'ALT-1092',
    orderId: 1730600002000,
    timestamp: '10:14:02',
    userId: 'demo',
    userName: '김투자',
    userIp: '118.235.*.*',
    accountNo: '501-12-883921',
    stockCode: '000660',
    stockName: 'SK하이닉스',
    orderType: 'BUY',
    price: 184000,
    quantity: 10,
    totalAmount: 1840000,
    riskScore: 65,
    riskLevel: 'WARNING',
    ruleScores: { access: 30, amount: 35, stock: 0, velocity: 0 },
    triggers: [
      '[접속 이상] 신규 IP 및 기기 환경 접속 포착 (+30점)',
      '[금액 이상] 계좌 평균 주문액 대비 급증 (Z-Score >= 3.0) (+35점)'
    ],
    status: 'APPROVED',
    actionLog: '관리자 수동 승인 체결 완료',
    aiAnalysis: {
      summary: '회원의 통상 접속 환경과 상이한 환경에서 평소 거래 규모를 상회하는 주문이 접수되었습니다.',
      recommendation: '보류 유지 및 유선 본인확인 후 승인 권고',
      confidence: 92
    }
  },
  {
    id: 'ALT-1091',
    orderId: 1730500003000,
    timestamp: '10:02:18',
    userId: 'demo',
    userName: '김투자',
    userIp: '211.58.*.*',
    accountNo: '501-12-883921',
    stockCode: '035420',
    stockName: 'NAVER',
    orderType: 'SELL',
    price: 198000,
    quantity: 50,
    totalAmount: 9900000,
    riskScore: 85,
    riskLevel: 'CRITICAL',
    ruleScores: { access: 30, amount: 35, stock: 20, velocity: 0 },
    triggers: [
      '[접속 이상] 새벽 심야 시간대(02:15) 비정상 접속 (+30점)',
      '[금액 이상] FDS 기준금액 대비 대규모 전량 매도 (+35점)',
      '[속도/종목] 비정상 급격 투매 패턴 감지 (+20점)'
    ],
    status: 'REJECTED',
    actionLog: 'FDS 점수 85점 초과로 자동 주문 차단 및 계좌 잠금 처리',
    aiAnalysis: {
      summary: '원격지 해외 VPN 접속 후 새벽 시간대에 발생한 비정상 전량 투매입니다. 탈취된 자산의 급격한 유출 시도로 판단됩니다.',
      recommendation: '즉시 주문 차단 및 계좌 잠금(LOCKED) 유지',
      confidence: 96
    }
  }
];

export function getStoredUsers() {
  try {
    const raw = localStorage.getItem(USERS_STORAGE_KEY);
    if (!raw) {
      const initial = [INITIAL_DEMO_USER, INITIAL_ADMIN_USER];
      localStorage.setItem(USERS_STORAGE_KEY, JSON.stringify(initial));
      return initial;
    }
    const parsed = JSON.parse(raw);
    return Array.isArray(parsed) && parsed.length > 0 ? parsed : [INITIAL_DEMO_USER, INITIAL_ADMIN_USER];
  } catch (e) {
    return [INITIAL_DEMO_USER, INITIAL_ADMIN_USER];
  }
}

export function updateStoredUser(updatedUser) {
  if (!updatedUser?.id) return;
  const users = getStoredUsers();
  const idx = users.findIndex((u) => u.id === updatedUser.id);
  if (idx !== -1) {
    users[idx] = { ...users[idx], ...updatedUser };
  } else {
    users.push(updatedUser);
  }
  localStorage.setItem(USERS_STORAGE_KEY, JSON.stringify(users));

  const session = getActiveSession();
  if (session?.id === updatedUser.id) {
    localStorage.setItem(SESSION_STORAGE_KEY, JSON.stringify({ id: updatedUser.id, ...updatedUser }));
  }
}

// 아이디 중복 확인 (서버 DB 실시간 검증, 기능 ID 5)
export async function checkUserIdAvailable(id) {
  const cleanId = (id || '').trim().toLowerCase();
  if (!cleanId || cleanId.length < 4) {
    return { available: false, message: '아이디는 4자 이상이어야 합니다.' };
  }
  try {
    const res = await fetch(`${API_BASE}/auth/check-id/${cleanId}`);
    if (res.ok) {
      return await res.json();
    }
    return { available: false, message: '서버 확인 실패' };
  } catch (err) {
    // 서버 오프라인 시 로컬 체크
    const users = getStoredUsers();
    const exists = users.some((u) => u.id.toLowerCase() === cleanId);
    return {
      available: !exists,
      message: exists ? '이미 사용 중인 아이디입니다.' : '사용 가능한 아이디입니다.'
    };
  }
}

// 하위 호환용 동기 함수
export function isIdAvailable(id) {
  const cleanId = (id || '').trim().toLowerCase();
  const users = getStoredUsers();
  return !users.some((u) => u.id.toLowerCase() === cleanId);
}

// 회원가입 (BCrypt, 8자 영문/숫자/특수문자, 예수금 1,000만 원, FDS 기준금액 50만 원, 기능 ID 6~10)
export async function registerUser({ id, password, name, email, phone, seedAmount = 10000000 }) {
  const trimmedId = (id || '').trim().toLowerCase();
  
  // 비밀번호 8자 이상, 영문, 숫자, 특수문자 조합 검증 (기능 ID 7)
  const PASSWORD_REGEX = /^(?=.*[A-Za-z])(?=.*\d)(?=.*[!@#$%^&*()_+\-=\[\]{};':"\\|,.<>\/?]).{8,}$/;
  if (!PASSWORD_REGEX.test(password)) {
    return {
      success: false,
      message: '비밀번호는 8자 이상이며 영문, 숫자, 특수문자를 각각 최소 1개 이상 포함해야 합니다.'
    };
  }

  try {
    const res = await fetch(`${API_BASE}/auth/register`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        id: trimmedId,
        password,
        name: name.trim(),
        email: email.trim(),
        phone: phone ? phone.trim() : '010-0000-0000',
        seedAmount: 10000000, // 명세 규정 1,000만 원 (기능 ID 9)
      }),
    });

    const data = await res.json();
    if (!res.ok || !data.success) {
      return { success: false, message: data.message || '회원가입에 실패했습니다.' };
    }

    // 발급받은 JWT 토큰 및 사용자 정보 저장
    if (data.tokens) {
      setAuthTokens(data.tokens);
    }
    updateStoredUser(data.user);
    localStorage.setItem(SESSION_STORAGE_KEY, JSON.stringify(data.user));

    return { success: true, user: data.user, tokens: data.tokens };
  } catch (err) {
    return { success: false, message: '서버 연결에 실패했습니다: ' + err.message };
  }
}

// 로그인 (BCrypt, 5회 연속 실패 시 30분 차단, 접속 이력 저장, JWT 발급, 기능 ID 12~15)
export async function authenticateUser(id, password) {
  const cleanId = (id || '').trim().toLowerCase();
  if (!cleanId || !password) {
    return { success: false, message: '아이디와 비밀번호를 모두 입력해주세요.' };
  }

  try {
    const res = await fetch(`${API_BASE}/auth/login`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ id: cleanId, password }),
    });

    const data = await res.json();
    if (!res.ok || !data.success) {
      return {
        success: false,
        code: data.code,
        message: data.message || '로그인에 실패했습니다.'
      };
    }

    // JWT 저장
    if (data.tokens) {
      setAuthTokens(data.tokens);
    }
    updateStoredUser(data.user);
    localStorage.setItem(SESSION_STORAGE_KEY, JSON.stringify(data.user));

    return { success: true, user: data.user, tokens: data.tokens };
  } catch (err) {
    return { success: false, message: '인증 서버 연결 실패: ' + err.message };
  }
}

// 로그아웃 (Refresh 토큰 폐기 및 세션 정리, 기능 ID 17)
export async function logoutUser() {
  const tokens = getAuthTokens();
  if (tokens?.refreshToken) {
    try {
      await fetch(`${API_BASE}/auth/logout`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ refreshToken: tokens.refreshToken }),
      });
    } catch (e) {
      // ignore
    }
  }
  clearAuthTokens();
  clearActiveSession();
}

export function getActiveSession() {
  try {
    const raw = localStorage.getItem(SESSION_STORAGE_KEY);
    if (!raw) return INITIAL_DEMO_USER;
    const session = JSON.parse(raw);
    const users = getStoredUsers();
    return users.find((u) => u.id === session.id) || session || INITIAL_DEMO_USER;
  } catch (e) {
    return INITIAL_DEMO_USER;
  }
}

export function clearActiveSession() {
  try {
    localStorage.removeItem(SESSION_STORAGE_KEY);
  } catch (e) {
    // ignore
  }
}

export function switchSessionUser(userId) {
  const users = getStoredUsers();
  const found = users.find((u) => u.id === userId);
  if (found) {
    localStorage.setItem(SESSION_STORAGE_KEY, JSON.stringify(found));
    return found;
  }
  return null;
}

export async function toggleUserWatchlist(userId, stockCode) {
  try {
    const session = getActiveSession();
    const endpoint = (session && session.id === userId) ? '/me/watchlist' : `/users/${userId}/watchlist`;
    const res = await apiFetch(endpoint, {
      method: 'POST',
      body: JSON.stringify({ stockCode }),
    });
    if (res.ok) {
      const data = await res.json();
      if (data.user) {
        updateStoredUser(data.user);
        return data.user;
      }
    }
  } catch (e) {
    // fallback
  }

  // 로컬 폴백
  const users = getStoredUsers();
  const userIndex = users.findIndex((u) => u.id === userId);
  if (userIndex === -1) return null;

  const user = { ...users[userIndex] };
  const set = new Set(user.watchlist || []);
  if (set.has(stockCode)) {
    set.delete(stockCode);
  } else {
    set.add(stockCode);
  }
  user.watchlist = Array.from(set);
  updateStoredUser(user);
  return user;
}

// 모의 주문 실행 (서버 원장 트랜잭션 및 FDS 4대 정밀 룰 엔진 동기 검증, 먼저 고칠 사항 2, 3, 4, 8)
export async function executeOrder({ userId, stockCode, stockName, orderType, price, quantity }) {
  const parsedPrice = Number(price);
  const parsedQty = Number(quantity);

  // 정수 수량 검증 (기능 ID 51)
  if (!Number.isInteger(parsedQty) || parsedQty <= 0) {
    return { success: false, message: '주문 수량은 1주 이상의 정수만 가능합니다.' };
  }
  if (!Number.isFinite(parsedPrice) || parsedPrice <= 0) {
    return { success: false, message: '주문 단가가 올바르지 않습니다.' };
  }

  // 멱등키 생성 (기능 ID 54)
  const idempotencyKey = `ord-${Date.now()}-${Math.random().toString(36).slice(2, 9)}`;

  try {
    const res = await apiFetch('/orders', {
      method: 'POST',
      body: JSON.stringify({
        userId,
        stockCode,
        stockName,
        orderType,
        price: parsedPrice,
        quantity: parsedQty,
        idempotencyKey,
      }),
    });

    const data = await res.json();

    if (!res.ok || !data.success) {
      return {
        success: false,
        code: data.code,
        message: data.message || '주문 처리에 실패했습니다.',
        order: data.order,
      };
    }

    // 서버의 원장 및 주문 결과로 클라이언트 동기화
    if (data.user) {
      updateStoredUser(data.user);
    }

    // 경보 발생 시 관제 경보 갱신
    if (data.order?.status === 'HOLD' || data.order?.status === 'REJECTED') {
      const currentAlerts = getStoredAlerts();
      const newAlert = {
        id: `ALT-${Date.now().toString().slice(-4)}`,
        orderId: data.order.id,
        timestamp: data.order.timestamp || new Date().toTimeString().split(' ')[0],
        userId,
        userName: data.user?.name || userId,
        userIp: '127.0.0.1',
        accountNo: data.user?.accountNo || '',
        stockCode,
        stockName,
        orderType,
        price: parsedPrice,
        quantity: parsedQty,
        totalAmount: parsedPrice * parsedQty,
        riskScore: data.fdsResult?.riskScore || 65,
        riskLevel: data.fdsResult?.riskScore >= 80 ? 'CRITICAL' : 'WARNING',
        ruleScores: data.fdsResult?.ruleScores || {},
        triggers: data.fdsResult?.triggers || [],
        status: data.order.status,
        actionLog: data.order.status === 'HOLD' ? 'FDS 엔진 심사 보류 (관리자 승인 대기)' : 'FDS 엔진 자동 차단 및 계좌 잠금',
        aiAnalysis: {
          summary: `${stockName} ${parsedQty}주 주문에 대해 FDS 위험점수 ${data.fdsResult?.riskScore}점이 부여되었습니다.`,
          recommendation: data.order.status === 'REJECTED' ? '계좌 잠금 유지 권고' : '수동 승인 검토',
          confidence: 90
        }
      };
      localStorage.setItem(ALERTS_STORAGE_KEY, JSON.stringify([newAlert, ...currentAlerts]));
    }

    return {
      success: true,
      order: data.order,
      orderStatus: data.order?.status || data.orderStatus,
      isExecuted: data.isExecuted ?? (data.order?.status === 'EXECUTED'),
      isHold: data.isHold ?? (data.order?.status === 'HOLD'),
      isRejected: data.isRejected ?? (data.order?.status === 'REJECTED'),
      fdsResult: data.fdsResult,
      user: data.user,
      message: data.message,
    };
  } catch (err) {
    return {
      success: false,
      message: '주문 서버 통신 오류: ' + err.message,
    };
  }
}

// FDS 경보 데이터 관리
export function getStoredAlerts() {
  try {
    const raw = localStorage.getItem(ALERTS_STORAGE_KEY);
    if (!raw) {
      localStorage.setItem(ALERTS_STORAGE_KEY, JSON.stringify(INITIAL_ALERTS));
      return INITIAL_ALERTS;
    }
    const parsed = JSON.parse(raw);
    return Array.isArray(parsed) && parsed.length > 0 ? parsed : INITIAL_ALERTS;
  } catch (e) {
    return INITIAL_ALERTS;
  }
}

export function addFdsAlert(alert) {
  try {
    const alerts = getStoredAlerts();
    const updated = [alert, ...alerts];
    localStorage.setItem(ALERTS_STORAGE_KEY, JSON.stringify(updated));
    return updated;
  } catch (e) {
    return null;
  }
}

// 서버 경보 목록 조회 (관리자 전용, 기능 ID 20, 21, 92)
export async function fetchServerAlerts() {
  try {
    const res = await apiFetch('/alerts');
    if (res.status === 403) {
      return { success: false, code: 'FORBIDDEN', message: '관리자 전용 관제 화면입니다. 접근 권한이 없습니다 (403).' };
    }
    if (res.status === 401) {
      return { success: false, code: 'UNAUTHORIZED', message: '인증 토큰이 유효하지 않습니다.' };
    }
    if (res.ok) {
      const data = await res.json();
      if (data.alerts) {
        localStorage.setItem(ALERTS_STORAGE_KEY, JSON.stringify(data.alerts));
        return { success: true, alerts: data.alerts };
      }
    }
    return { success: false, message: '경보 조회 실패' };
  } catch (e) {
    return { success: true, alerts: getStoredAlerts() };
  }
}

// 관리자 경보 조치: APPROVED(승인 체결), REJECTED(주문 취소), LOCKED(잠금), UNLOCKED(잠금 해제) (먼저 고칠 사항 2, 7)
export async function updateAlertStatus(alertId, actionType, adminComment = '') {
  try {
    const res = await apiFetch(`/alerts/${alertId}/action`, {
      method: 'POST',
      body: JSON.stringify({
        actionType,
        adminComment,
      }),
    });

    const data = await res.json();
    if (!res.ok || !data.success) {
      return { success: false, message: data.message || '조치 반영 실패' };
    }

    if (data.alerts) {
      localStorage.setItem(ALERTS_STORAGE_KEY, JSON.stringify(data.alerts));
    }
    if (data.user) {
      updateStoredUser(data.user);
    }

    // 만약 잠금 해제(UNLOCKED)된 경우 계좌 잠금 플래그도 해제
    if (actionType === 'UNLOCKED') {
      const session = getActiveSession();
      if (session) {
        session.isLocked = false;
        session.lockReason = '';
        updateStoredUser(session);
      }
    }

    return { success: true, alerts: data.alerts, message: data.message };
  } catch (err) {
    return { success: false, message: '조치 서버 통신 실패: ' + err.message };
  }
}

// 계좌 잠금 및 해제 유틸리티 (기능 ID 80, 96)
export function lockUserAccount(userId, reason) {
  try {
    const users = getStoredUsers();
    const idx = users.findIndex((u) => u.id === userId);
    if (idx !== -1) {
      users[idx].isLocked = true;
      users[idx].lockReason = reason || 'FDS 이상거래 탐지';
      updateStoredUser(users[idx]);
    }
  } catch (e) {
    // ignore
  }
}

export async function unlockUserAccount(alertId, adminComment) {
  return await updateAlertStatus(alertId, 'UNLOCKED', adminComment);
}

// 원장 전수 수학적 정합성 대사 실행 (기능 ID 100, 102, 103)
export async function runServerReconciliation() {
  try {
    const res = await apiFetch('/admin/audit', {
      method: 'POST',
    });
    const data = await res.json();
    return data;
  } catch (err) {
    return {
      success: false,
      message: '원장 대사 서버 통신 실패: ' + err.message,
    };
  }
}

// 내 계좌 전용 조회 API (GET /api/me)
// [보안]: URL 파라미터 없이 검증된 JWT 토큰으로 계좌 및 원장 정보 조회
export async function fetchMyAccount() {
  try {
    const res = await apiFetch('/me');
    if (res.ok) {
      const data = await res.json();
      if (data.user) {
        updateStoredUser(data.user);
        return { success: true, user: data.user };
      }
    }
    const errData = await res.json().catch(() => ({}));
    return { success: false, status: res.status, error: errData.message || errData.code };
  } catch (err) {
    return { success: false, error: err.message };
  }
}

// 초기화 시 서버와 동기화 (내 계좌 전용 API /me 우선 호출)
export async function syncFromPostgres(userId = 'demo') {
  try {
    const tokens = getAuthTokens();
    if (!tokens?.accessToken) return;

    // 내 계좌 전용 API (/me)로 안전하게 조회
    const [uRes, aRes] = await Promise.all([
      apiFetch('/me'),
      apiFetch('/alerts'),
    ]);
    if (uRes.ok) {
      const uData = await uRes.json();
      if (uData.user) {
        updateStoredUser(uData.user);
      }
    } else if (userId) {
      // 관리자 등 특정 ID 동기화가 필요한 경우 허용된 엔드포인트 폴백
      const fallbackRes = await apiFetch(`/users/${userId}`);
      if (fallbackRes.ok) {
        const fallbackData = await fallbackRes.json();
        if (fallbackData.user) {
          updateStoredUser(fallbackData.user);
        }
      }
    }
    if (aRes.ok) {
      const aData = await aRes.json();
      if (aData.alerts?.length > 0) {
        localStorage.setItem(ALERTS_STORAGE_KEY, JSON.stringify(aData.alerts));
      }
    }
  } catch (err) {
    // fallback
  }
}

