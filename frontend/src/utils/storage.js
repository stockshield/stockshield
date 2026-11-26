// StockShield API 연동 및 세션/어댑터 유틸리티 (최신 백엔드 /api/v1 명세 기준)

const SESSION_STORAGE_KEY = 'stockshield_v1_session';
const TOKENS_STORAGE_KEY = 'stockshield_v1_tokens';
const DEVICE_ID_STORAGE_KEY = 'stockshield_v1_device_id';
const WATCHLIST_PREFIX = 'stockshield_v1_watchlist_';

// 1-1. 서버 주소를 환경설정으로 분리 (VITE_API_BASE_URL 우선, 기본값 '/api/v1')
export const API_BASE = (import.meta.env?.VITE_API_BASE_URL || '/api/v1').replace(/\/$/, '');
export const WS_BASE = import.meta.env?.VITE_WS_URL || '/ws';

// X-Device-Id 헤더용 브라우저 기기 UUID 생성/유지 (8-4-4-4-12 16진수 형식 필수)
const UUID_REGEX = /^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$/;

function generateUuid() {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID();
  }
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
    const r = (Math.random() * 16) | 0;
    const v = c === 'x' ? r : (r & 0x3) | 0x8;
    return v.toString(16);
  });
}

export function getDeviceId() {
  try {
    let deviceId = localStorage.getItem(DEVICE_ID_STORAGE_KEY);
    if (!deviceId || !UUID_REGEX.test(deviceId)) {
      deviceId = generateUuid();
      localStorage.setItem(DEVICE_ID_STORAGE_KEY, deviceId);
    }
    return deviceId;
  } catch (e) {
    return generateUuid();
  }
}

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

// 1-3. 로컬 예시 데이터를 제거하고 실제 서버 응답만 사용
export const INITIAL_DEMO_USER = null;
export const INITIAL_ADMIN_USER = null;
export const INITIAL_ALERTS = [];

export function getActiveSession() {
  try {
    const tokens = getAuthTokens();
    if (!tokens?.accessToken) return null;
    const raw = localStorage.getItem(SESSION_STORAGE_KEY);
    if (!raw) return null;
    return JSON.parse(raw);
  } catch (e) {
    return null;
  }
}

export function setActiveSession(user) {
  try {
    if (user?.id) {
      localStorage.setItem(SESSION_STORAGE_KEY, JSON.stringify(user));
    } else {
      localStorage.removeItem(SESSION_STORAGE_KEY);
    }
  } catch (e) {
    // ignore
  }
}

export function clearActiveSession() {
  try {
    localStorage.removeItem(SESSION_STORAGE_KEY);
  } catch (e) {
    // ignore
  }
}

export function updateStoredUser(updatedUser) {
  if (!updatedUser?.id) return;
  const current = getActiveSession();
  const merged = current && current.id === updatedUser.id
    ? { ...current, ...updatedUser }
    : updatedUser;
  setActiveSession(merged);
}

// 에러 코드별 기본 안내 문구 (백엔드 ErrorCode.java 기준)
export const ERROR_MESSAGES = {
  INVALID_REQUEST: '입력값을 확인해 주세요.',
  AUTH_CODE_MISMATCH: '인증 코드가 틀렸습니다.',
  UNAUTHORIZED: '로그인이 필요합니다.',
  LOGIN_FAILED: '아이디 또는 비밀번호가 올바르지 않습니다.',
  ACCESS_DENIED: '접근 권한이 없습니다.',
  STOCK_NOT_FOUND: '종목을 찾을 수 없습니다.',
  RESOURCE_NOT_FOUND: '요청한 정보를 찾을 수 없습니다.',
  DETECTION_NOT_FOUND: '탐지 기록을 찾을 수 없습니다.',
  IDEMPOTENCY_KEY_REUSED: '같은 요청 키로 다른 내용을 보낼 수 없습니다. 새로 요청해 주세요.',
  ALREADY_CHECKED: '이미 처리되었습니다.',
  SAME_STATUS: '이미 처리되었습니다.',
  RECONCILE_RUNNING: '이미 원장 대사가 실행 중입니다.',
  LGN_ID_DUPLICATED: '이미 사용 중인 아이디입니다.',
  AI_RETRY_NOT_ALLOWED: 'AI 설명이 실패한 건만 다시 요청할 수 있습니다.',
  AUTH_CODE_EXPIRED: '인증 코드가 만료되었습니다. 처음부터 다시 진행하세요.',
  MARKET_CLOSED: '주문 가능 시간(휴장일 제외 평일 09:00~15:20)이 아닙니다.',
  PRICE_STALE: '시세가 지연되어 지금은 주문할 수 없습니다.',
  INSUFFICIENT_BALANCE: '예수금이 부족합니다.',
  INSUFFICIENT_HOLDING: '보유 수량이 부족합니다.',
  WDR_PWD_MISMATCH: '출금 비밀번호가 틀렸습니다.',
  ACCOUNT_LOCKED: '계좌가 잠겨 있습니다. 관리자에게 문의하세요.',
  WDR_PWD_LOCKED: '출금 비밀번호를 5회 틀려 계좌가 잠겼습니다. 관리자에게 문의하세요.',
  LOGIN_LOCKED: '비밀번호를 5회 틀려 30분 동안 로그인할 수 없습니다.',
  WDR_PWD_REQUIRED: '큰 금액 출금이라 출금 비밀번호가 필요합니다.',
  TOO_MANY_REQUESTS: '잠시 후 다시 시도하세요.',
  INTERNAL_ERROR: '일시적인 오류입니다.',
  LOCK_TIMEOUT: '요청이 몰려 처리하지 못했습니다. 잠시 후 다시 시도하세요.',
  PRICE_UNAVAILABLE: '시세를 불러올 수 없어 지금은 주문할 수 없습니다.',
  AUTH_UNAVAILABLE: '지금은 인증할 수 없습니다.',
  SERVICE_UNAVAILABLE: '서비스 점검 중입니다.',
};

// 공통 에러 응답 파싱 ({ code, message, detail, traceId })
export async function parseApiResponse(res) {
  let body = null;
  const text = await res.text();
  if (text) {
    try {
      body = JSON.parse(text);
    } catch (e) {
      body = null;
    }
  }
  const traceId = body?.traceId || res.headers.get('X-Trace-Id') || null;
  const replayed = res.headers.get('Idempotency-Replayed') === 'true';
  const retryAfter = res.headers.get('Retry-After');

  if (res.ok) {
    return {
      ok: true,
      status: res.status,
      data: body,
      traceId,
      replayed,
    };
  }

  const code = body?.code || (res.status === 401 ? 'UNAUTHORIZED' : res.status === 403 ? 'ACCESS_DENIED' : 'INTERNAL_ERROR');
  const message = body?.message || ERROR_MESSAGES[code] || `요청 처리에 실패했습니다 (${res.status}).`;
  const detail = body?.detail || {};

  return {
    ok: false,
    status: res.status,
    code,
    message,
    detail,
    traceId,
    replayed,
    retryAfter,
    data: body,
  };
}

// 1-2. 토큰 첨부·재발급·오류 처리 공통화
let refreshPending = null;

// Access Token 재발급 (REST 401 및 WebSocket STOMP 인증 만료 공용, 동시 중복 요청 방지)
export async function refreshAccessToken() {
  const currentTokens = getAuthTokens();
  if (!currentTokens?.refreshToken) {
    clearAuthTokens();
    clearActiveSession();
    return false;
  }
  if (!refreshPending) {
    refreshPending = (async () => {
      try {
        const refreshRes = await fetch(`${API_BASE}/auth/refresh`, {
          method: 'POST',
          headers: {
            'Content-Type': 'application/json',
            'X-Device-Id': getDeviceId(),
          },
          body: JSON.stringify({ refreshToken: currentTokens.refreshToken }),
        });
        if (!refreshRes.ok) {
          clearAuthTokens();
          clearActiveSession();
          return false;
        }
        const refreshData = await refreshRes.json();
        if (refreshData?.accessToken) {
          setAuthTokens({
            ...currentTokens,
            accessToken: refreshData.accessToken,
          });
          return true;
        }
        clearAuthTokens();
        clearActiveSession();
        return false;
      } catch (e) {
        return false;
      }
    })().finally(() => {
      refreshPending = null;
    });
  }
  return refreshPending;
}

export async function apiFetch(endpoint, options = {}) {
  const path = endpoint.startsWith('/') ? endpoint : `/${endpoint}`;
  const url = `${API_BASE}${path}`;
  const skipAuth = Boolean(options.skipAuth);

  const makeRequest = () => {
    const tokens = getAuthTokens();
    const headers = {
      'Content-Type': 'application/json',
      'X-Device-Id': getDeviceId(),
      ...(options.headers || {}),
    };
    if (!skipAuth && tokens?.accessToken) {
      headers['Authorization'] = `Bearer ${tokens.accessToken}`;
    }
    const fetchOpts = { ...options, headers };
    delete fetchOpts.skipAuth;
    return fetch(url, fetchOpts);
  };

  let res = await makeRequest();

  // 401 응답 시 Refresh Token이 있고 로그인/리프레시 요청이 아니면 재발급 시도
  // LOGIN_FAILED는 아이디/비밀번호 불일치이므로 토큰 재발급 대상이 아님
  if (
    res.status === 401 &&
    !skipAuth &&
    path !== '/auth/login' &&
    path !== '/auth/refresh'
  ) {
    const refreshed = await refreshAccessToken();
    if (refreshed) {
      res = await makeRequest();
    }
  }

  return res;
}

export async function apiRequest(endpoint, options = {}) {
  const res = await apiFetch(endpoint, options);
  return parseApiResponse(res);
}

// ============================================================================
// 1-4. 백엔드 응답 → 기존 화면 데이터 이름 변환 어댑터 (Adapters)
// ============================================================================

export function normalizeRole(roleCd) {
  if (!roleCd) return 'USER';
  return roleCd === 'ROLE_ADMIN' || roleCd === 'ADMIN' ? 'ADMIN' : 'USER';
}

function getLocalWatchlist(lgnId) {
  if (!lgnId) return ['005930', '000660'];
  try {
    const raw = localStorage.getItem(`${WATCHLIST_PREFIX}${lgnId}`);
    if (!raw) return ['005930', '000660'];
    const parsed = JSON.parse(raw);
    return Array.isArray(parsed) ? parsed : ['005930', '000660'];
  } catch (e) {
    return ['005930', '000660'];
  }
}

function setLocalWatchlist(lgnId, list) {
  if (!lgnId) return;
  try {
    localStorage.setItem(`${WATCHLIST_PREFIX}${lgnId}`, JSON.stringify(list));
  } catch (e) {
    // ignore
  }
}

// 주문 1건 변환: 백엔드 StockOrder / GET /accounts/me/orders 항목 -> 프론트 orderHistory 항목
export function adaptOrder(raw) {
  if (!raw) return null;
  const ordStatCd = raw.ordStatCd || raw.status || 'FILLED';
  const status = ordStatCd === 'FILLED' ? 'EXECUTED' : ordStatCd;
  const ordQty = Number(raw.ordQty ?? raw.quantity ?? 0);
  const ordPrc = raw.ordPrc != null ? Number(raw.ordPrc) : Number(raw.price ?? 0);
  const trdAmt = raw.trdAmt != null ? Number(raw.trdAmt) : Number(raw.totalCost ?? ordPrc * ordQty);
  const rjctRsnCd = raw.rjctRsnCd || null;
  const rjctRsnMsg = raw.rjctRsnMsg || raw.rejectionReason || (rjctRsnCd ? ERROR_MESSAGES[rjctRsnCd] : '') || '';

  return {
    id: raw.ordNo || raw.id,
    ordNo: raw.ordNo || raw.id,
    timestamp: raw.regTs || raw.timestamp || '',
    regTs: raw.regTs || raw.timestamp || '',
    stockCode: raw.tckCd || raw.stockCode || '',
    tckCd: raw.tckCd || raw.stockCode || '',
    stockName: raw.tckNm || raw.stockName || raw.tckCd || '',
    tckNm: raw.tckNm || raw.stockName || raw.tckCd || '',
    orderType: raw.ordDvCd || raw.orderType || 'BUY',
    ordDvCd: raw.ordDvCd || raw.orderType || 'BUY',
    price: ordPrc,
    ordPrc,
    quantity: ordQty,
    ordQty,
    totalCost: trdAmt,
    trdAmt,
    status,
    ordStatCd,
    fdsStatus: status === 'EXECUTED' ? '체결 완료 (FILLED)' : `주문 거절 (${rjctRsnCd || 'REJECTED'})`,
    rejectionReason: rjctRsnMsg,
    rjctRsnCd,
    rjctRsnMsg,
  };
}

// 보유 종목 목록 변환: GET /accounts/me/holdings 배열 -> 기존 화면 portfolio 객체 & holdingsList 배열
export function adaptHoldings(holdingsArray = []) {
  const portfolio = {};
  const holdingsList = [];

  if (!Array.isArray(holdingsArray)) {
    return { portfolio, holdingsList };
  }

  holdingsArray.forEach((h) => {
    const code = h.tckCd || h.stockCode;
    const qty = Number(h.hldQty ?? h.quantity ?? 0);
    if (!code || qty <= 0) return;

    const avgPrice = Number(h.avgBuyPrc ?? h.avgPrice ?? 0);
    const currentPrice = h.currentPrice != null ? Number(h.currentPrice) : null;
    const evalAmt = Number(h.evalAmt ?? (currentPrice != null ? currentPrice * qty : Math.round(avgPrice * qty)));
    const evalPnl = Number(h.evalPnl ?? 0);
    const pnlRt = Number(h.pnlRt ?? 0);
    const priceDelayed = Boolean(h.priceDelayed);
    const name = h.tckNm || h.name || code;

    const item = {
      tckCd: code,
      stockCode: code,
      tckNm: name,
      name,
      hldQty: qty,
      quantity: qty,
      avgBuyPrc: avgPrice,
      avgPrice,
      currentPrice,
      evalAmt,
      evalPnl,
      pnlRt,
      priceDelayed,
    };

    portfolio[code] = item;
    holdingsList.push(item);
  });

  return { portfolio, holdingsList };
}

// 회원 정보 + 계좌 요약 + 보유 종목 + 주문 내역 -> 기존 화면 currentUser 객체로 통합 변환
export function adaptUserAccount({ me, summary, holdings, ordersPage, existingUser = null }) {
  const lgnId = me?.lgnId || existingUser?.lgnId || existingUser?.id || '';
  const mbrNm = me?.mbrNm || existingUser?.mbrNm || existingUser?.name || lgnId;
  const roleCd = me?.roleCd || existingUser?.roleCd || (existingUser?.role === 'ADMIN' ? 'ROLE_ADMIN' : 'ROLE_USER');
  const role = normalizeRole(roleCd);

  // 관리자는 계좌가 없고 거래하지 않음 (직무 분리)
  if (role === 'ADMIN') {
    return {
      id: lgnId,
      lgnId,
      name: mbrNm,
      mbrNm,
      role: 'ADMIN',
      roleCd: 'ROLE_ADMIN',
      accountNo: null,
      acntNo: null,
      acntStatCd: null,
      balance: 0,
      dpsBal: 0,
      totalBuyAmt: 0,
      totalEvalAmt: 0,
      totalAssetAmt: 0,
      totalPnl: 0,
      yieldRt: 0,
      priceDelayed: false,
      isLocked: false,
      lockReason: null,
      fdsLevel: 'FDS 관리자',
      watchlist: getLocalWatchlist(lgnId),
      portfolio: {},
      holdingsList: [],
      orderHistory: [],
      ordersPage: { content: [], page: 0, size: 20, totalElements: 0, totalPages: 0 },
    };
  }

  const acntNo = summary?.acntNo ?? existingUser?.acntNo ?? existingUser?.accountNo ?? '';
  const acntStatCd = summary?.acntStatCd ?? existingUser?.acntStatCd ?? 'ACTIVE';
  const isLocked = acntStatCd === 'LOCKED' || acntStatCd === 'TEMP_LOCKED';
  const lockRsn = summary?.lockRsn ?? existingUser?.lockRsn ?? existingUser?.lockReason ?? (
    acntStatCd === 'TEMP_LOCKED'
      ? '출금 비밀번호 5회 오류로 임시 잠김'
      : acntStatCd === 'LOCKED'
      ? '관리자 잠금'
      : null
  );

  const dpsBal = Number(summary?.dpsBal ?? existingUser?.dpsBal ?? existingUser?.balance ?? 0);
  const totalBuyAmt = Number(summary?.totalBuyAmt ?? existingUser?.totalBuyAmt ?? 0);
  const totalEvalAmt = Number(summary?.totalEvalAmt ?? existingUser?.totalEvalAmt ?? 0);
  const totalAssetAmt = Number(summary?.totalAssetAmt ?? (dpsBal + totalEvalAmt));
  const totalPnl = Number(summary?.totalPnl ?? (totalEvalAmt - totalBuyAmt));
  const yieldRt = Number(summary?.yieldRt ?? 0);
  const priceDelayed = Boolean(summary?.priceDelayed ?? existingUser?.priceDelayed ?? false);

  const { portfolio, holdingsList } = holdings != null
    ? adaptHoldings(holdings)
    : { portfolio: existingUser?.portfolio || {}, holdingsList: existingUser?.holdingsList || [] };

  const rawOrders = Array.isArray(ordersPage?.content)
    ? ordersPage.content
    : Array.isArray(ordersPage)
    ? ordersPage
    : null;
  const orderHistory = rawOrders != null
    ? rawOrders.map(adaptOrder).filter(Boolean)
    : (existingUser?.orderHistory || []);

  return {
    id: lgnId,
    lgnId,
    name: mbrNm,
    mbrNm,
    role: 'USER',
    roleCd: 'ROLE_USER',
    accountNo: acntNo,
    acntNo,
    acntStatCd,
    balance: dpsBal,
    dpsBal,
    totalBuyAmt,
    totalEvalAmt,
    totalAssetAmt,
    totalPnl,
    yieldRt,
    priceDelayed,
    isLocked,
    lockReason: lockRsn || '',
    lockRsn,
    fdsLevel: isLocked
      ? (acntStatCd === 'TEMP_LOCKED' ? '임시 잠금 (TEMP_LOCKED)' : '계좌 잠금 (LOCKED)')
      : '정상 (ACTIVE)',
    watchlist: getLocalWatchlist(lgnId),
    portfolio,
    holdingsList,
    orderHistory,
    ordersPage: ordersPage && typeof ordersPage === 'object' && !Array.isArray(ordersPage)
      ? { ...ordersPage, content: orderHistory }
      : { content: orderHistory, page: 0, size: 20, totalElements: orderHistory.length, totalPages: 1 },
  };
}

// ============================================================================
// 회원 정보 및 내 계좌 조회 연결 (GET /members/me -> GET /accounts/me/summary, holdings, orders)
// ============================================================================

export async function fetchMyMember() {
  const res = await apiRequest('/members/me');
  if (!res.ok) {
    return {
      success: false,
      status: res.status,
      code: res.code,
      message: res.message,
    };
  }
  return {
    success: true,
    member: res.data,
  };
}

export async function fetchAccountSummary() {
  return apiRequest('/accounts/me/summary');
}

export async function fetchAccountHoldings() {
  return apiRequest('/accounts/me/holdings');
}

export async function fetchAccountOrders({ page = 0, size = 20, ordStatCd = null } = {}) {
  const params = new URLSearchParams({ page: String(page), size: String(size) });
  if (ordStatCd && (ordStatCd === 'FILLED' || ordStatCd === 'REJECTED')) {
    params.set('ordStatCd', ordStatCd);
  }
  return apiRequest(`/accounts/me/orders?${params.toString()}`);
}

// 로그인 직후 및 새로고침 시: 내 회원 정보(/members/me) -> (일반회원인 경우) 내 계좌 요약/보유종목/주문내역 조회
export async function fetchMyAccount() {
  try {
    const tokens = getAuthTokens();
    if (!tokens?.accessToken) {
      return { success: false, status: 401, code: 'UNAUTHORIZED', error: '로그인이 필요합니다.' };
    }

    const meRes = await apiRequest('/members/me');
    if (!meRes.ok) {
      if (meRes.status === 401) {
        clearAuthTokens();
        clearActiveSession();
      }
      return {
        success: false,
        status: meRes.status,
        code: meRes.code,
        error: meRes.message,
      };
    }

    const me = meRes.data;
    const role = normalizeRole(me.roleCd);

    // 관리자는 계좌 조회 API(/accounts/me/**) 호출 대상이 아님 (ROLE_USER 전용)
    if (role === 'ADMIN') {
      const adminUser = adaptUserAccount({ me, existingUser: getActiveSession() });
      updateStoredUser(adminUser);
      return { success: true, user: adminUser };
    }

    // 일반회원은 내 계좌 요약, 보유 종목, 주문 내역을 병렬 조회
    const [summaryRes, holdingsRes, ordersRes] = await Promise.all([
      fetchAccountSummary(),
      fetchAccountHoldings(),
      fetchAccountOrders({ page: 0, size: 20 }),
    ]);

    if (!summaryRes.ok) {
      return {
        success: false,
        status: summaryRes.status,
        code: summaryRes.code,
        error: summaryRes.message,
      };
    }

    const user = adaptUserAccount({
      me,
      summary: summaryRes.data,
      holdings: holdingsRes.ok ? holdingsRes.data : [],
      ordersPage: ordersRes.ok ? ordersRes.data : null,
      existingUser: getActiveSession(),
    });

    updateStoredUser(user);
    return { success: true, user };
  } catch (err) {
    return {
      success: false,
      code: 'NETWORK_ERROR',
      error: '서버에 연결할 수 없습니다: ' + err.message,
    };
  }
}

export async function syncFromPostgres() {
  return fetchMyAccount();
}

// ============================================================================
// 회원가입 · 로그인 · 새 접속 인증 · 로그아웃 API (/api/v1/members, /api/v1/auth)
// ============================================================================

// 아이디 중복 확인: GET /api/v1/members/check-id?lgnId=... -> { isAvailable }
// 로컬 폴백 없이 서버 응답만 신뢰
export async function checkUserIdAvailable(id) {
  const cleanId = (id || '').trim();
  if (!/^[a-z][a-z0-9]{3,15}$/.test(cleanId)) {
    return {
      available: false,
      isAvailable: false,
      code: 'INVALID_REQUEST',
      message: '아이디는 영문 소문자로 시작하는 영문 소문자·숫자 4~16자여야 합니다.',
    };
  }
  try {
    const res = await apiRequest(`/members/check-id?lgnId=${encodeURIComponent(cleanId)}`, {
      method: 'GET',
      skipAuth: true,
    });
    if (res.ok && res.data) {
      const available = Boolean(res.data.isAvailable);
      return {
        available,
        isAvailable: available,
        message: available ? '사용 가능한 아이디입니다.' : '이미 사용 중인 아이디입니다.',
      };
    }
    return {
      available: false,
      isAvailable: false,
      code: res.code,
      message: res.message || '아이디 중복 확인에 실패했습니다.',
    };
  } catch (err) {
    return {
      available: false,
      isAvailable: false,
      code: 'NETWORK_ERROR',
      message: '서버에 연결할 수 없습니다.',
    };
  }
}

// 가입 이메일 인증 코드 발송: POST /api/v1/members/email-code { emlAddr } -> 200 { authId, ttlSec }
export async function sendSignupEmailCode(emlAddr) {
  const cleanEmail = (emlAddr || '').trim();
  if (!cleanEmail) {
    return { success: false, message: '이메일 주소를 입력해 주세요.' };
  }
  try {
    const res = await apiRequest('/members/email-code', {
      method: 'POST',
      skipAuth: true,
      body: JSON.stringify({ emlAddr: cleanEmail }),
    });
    if (!res.ok) {
      return {
        success: false,
        status: res.status,
        code: res.code,
        message: res.message,
      };
    }
    return {
      success: true,
      authId: res.data.authId,
      ttlSec: Number(res.data.ttlSec ?? 300),
    };
  } catch (err) {
    return {
      success: false,
      code: 'NETWORK_ERROR',
      message: '인증 메일 요청 중 서버 연결에 실패했습니다.',
    };
  }
}

// 회원가입: POST /api/v1/members -> 201 { mbrNo, acntNo, initDpsAmt, dpsBal }
export async function registerUser({
  id,
  lgnId,
  password,
  lgnPwd,
  name,
  mbrNm,
  email,
  emlAddr,
  authId,
  authCode,
  wdrPwd,
  initPkgCd,
  seedAmount,
}) {
  const finalLgnId = (lgnId ?? id ?? '').trim();
  const finalPwd = lgnPwd ?? password ?? '';
  const finalName = (mbrNm ?? name ?? '').trim();
  const finalEmail = (emlAddr ?? email ?? '').trim();
  const finalPkg = initPkgCd || (
    seedAmount === 50000000 ? 'PKG_50M' :
    seedAmount === 30000000 ? 'PKG_30M' : 'PKG_10M'
  );

  try {
    const res = await apiRequest('/members', {
      method: 'POST',
      skipAuth: true,
      body: JSON.stringify({
        lgnId: finalLgnId,
        lgnPwd: finalPwd,
        mbrNm: finalName,
        emlAddr: finalEmail,
        authId: (authId || '').trim(),
        authCode: (authCode || '').trim(),
        wdrPwd: (wdrPwd || '').trim(),
        initPkgCd: finalPkg,
      }),
    });

    if (!res.ok) {
      return {
        success: false,
        status: res.status,
        code: res.code,
        detail: res.detail,
        remainCnt: res.detail?.remainCnt,
        message: res.message || '회원가입에 실패했습니다.',
      };
    }

    return {
      success: true,
      registered: res.data, // { mbrNo, acntNo, initDpsAmt, dpsBal }
    };
  } catch (err) {
    return {
      success: false,
      code: 'NETWORK_ERROR',
      message: '회원가입 서버 연결에 실패했습니다: ' + err.message,
    };
  }
}

// 로그인: POST /api/v1/auth/login { lgnId, lgnPwd } (헤더 X-Device-Id 자동 포함)
// - 200 OK: { accessToken, refreshToken, roleCd } -> 이어서 fetchMyAccount() 호출
// - 202 Accepted: { authRequired: true, authId, mskEmlAddr } -> 새 접속 추가 인증 화면으로 분기
export async function authenticateUser(id, password) {
  const cleanId = (id || '').trim().toLowerCase();
  if (!cleanId || !password) {
    return { success: false, message: '아이디와 비밀번호를 모두 입력해 주세요.' };
  }

  try {
    const res = await apiRequest('/auth/login', {
      method: 'POST',
      skipAuth: true,
      body: JSON.stringify({ lgnId: cleanId, lgnPwd: password }),
    });

    if (!res.ok) {
      return {
        success: false,
        status: res.status,
        code: res.code,
        detail: res.detail,
        message: res.message || '로그인에 실패했습니다.',
      };
    }

    // 202 Accepted: 새로운 IP 또는 기기에서 로그인하여 이메일 추가 인증이 필요한 경우
    if (res.status === 202 || res.data?.authRequired) {
      return {
        success: false,
        authRequired: true,
        authId: res.data.authId,
        mskEmlAddr: res.data.mskEmlAddr,
        ttlSec: 300,
        message: '새로운 접속 환경이 감지되어 이메일 인증이 필요합니다.',
      };
    }

    // 200 OK: 토큰 저장 후 내 회원 정보 및 내 계좌 조회
    const tokens = {
      accessToken: res.data.accessToken,
      refreshToken: res.data.refreshToken ?? null,
      roleCd: res.data.roleCd,
    };
    setAuthTokens(tokens);

    const accountRes = await fetchMyAccount();
    if (!accountRes.success) {
      clearAuthTokens();
      clearActiveSession();
      return {
        success: false,
        code: accountRes.code,
        message: accountRes.error || '회원 및 계좌 정보를 불러오지 못했습니다.',
      };
    }

    return {
      success: true,
      user: accountRes.user,
      tokens,
    };
  } catch (err) {
    return {
      success: false,
      code: 'NETWORK_ERROR',
      message: '인증 서버에 연결할 수 없습니다: ' + err.message,
    };
  }
}

// 새 기기 로그인 이메일 인증 확인: POST /api/v1/auth/email-verify { authId, authCode }
export async function verifyNewDeviceLogin(authId, authCode) {
  try {
    const res = await apiRequest('/auth/email-verify', {
      method: 'POST',
      skipAuth: true,
      body: JSON.stringify({
        authId: (authId || '').trim(),
        authCode: (authCode || '').trim(),
      }),
    });

    if (!res.ok) {
      return {
        success: false,
        status: res.status,
        code: res.code,
        detail: res.detail,
        remainCnt: res.detail?.remainCnt,
        message: res.message,
      };
    }

    const tokens = {
      accessToken: res.data.accessToken,
      refreshToken: res.data.refreshToken ?? null,
      roleCd: res.data.roleCd,
    };
    setAuthTokens(tokens);

    const accountRes = await fetchMyAccount();
    if (!accountRes.success) {
      clearAuthTokens();
      clearActiveSession();
      return {
        success: false,
        code: accountRes.code,
        message: accountRes.error || '회원 및 계좌 정보를 불러오지 못했습니다.',
      };
    }

    return {
      success: true,
      user: accountRes.user,
      tokens,
    };
  } catch (err) {
    return {
      success: false,
      code: 'NETWORK_ERROR',
      message: '인증 확인 중 서버 연결에 실패했습니다: ' + err.message,
    };
  }
}

// 새 기기 로그인 인증 코드 재발송: POST /api/v1/auth/email-resend { authId } -> { ttlSec }
export async function resendNewDeviceEmailCode(authId) {
  try {
    const res = await apiRequest('/auth/email-resend', {
      method: 'POST',
      skipAuth: true,
      body: JSON.stringify({ authId: (authId || '').trim() }),
    });
    if (!res.ok) {
      return {
        success: false,
        status: res.status,
        code: res.code,
        message: res.message,
      };
    }
    return {
      success: true,
      ttlSec: Number(res.data?.ttlSec ?? 0),
    };
  } catch (err) {
    return {
      success: false,
      code: 'NETWORK_ERROR',
      message: '인증 코드 재발송 중 서버 연결에 실패했습니다.',
    };
  }
}

// 로그아웃: POST /api/v1/auth/logout { refreshToken }
export async function logoutUser() {
  const tokens = getAuthTokens();
  if (tokens?.accessToken) {
    try {
      await apiFetch('/auth/logout', {
        method: 'POST',
        body: JSON.stringify({ refreshToken: tokens.refreshToken ?? null }),
      });
    } catch (e) {
      // ignore network error on logout
    }
  }
  clearAuthTokens();
  clearActiveSession();
}

// 관심종목 토글 (새 백엔드에는 별도 관심종목 테이블이 없으므로 계정별 로컬 설정으로 유지)
export async function toggleUserWatchlist(userId, stockCode) {
  const session = getActiveSession();
  if (!session) return null;
  const list = new Set(getLocalWatchlist(session.id));
  if (list.has(stockCode)) {
    list.delete(stockCode);
  } else {
    list.add(stockCode);
  }
  const updatedList = Array.from(list);
  setLocalWatchlist(session.id, updatedList);
  const updatedUser = { ...session, watchlist: updatedList };
  updateStoredUser(updatedUser);
  return updatedUser;
}

// ============================================================================
// 주문 API (/api/v1/orders) - 3단계에서도 바로 사용 가능하도록 최신 명세로 연결
// ============================================================================

const PENDING_ORDER_KEY = 'stockshield_v1_pending_order';

export async function executeOrder({ stockCode, tckCd, stockName, orderType, ordDvCd, quantity, ordQty }) {
  const finalTckCd = (tckCd || stockCode || '').trim();
  const finalOrdDvCd = (ordDvCd || orderType || 'BUY').trim();
  const parsedQty = Number(ordQty ?? quantity);

  if (!Number.isInteger(parsedQty) || parsedQty <= 0) {
    return { success: false, message: '주문 수량은 1주 이상의 정수만 가능합니다.' };
  }

  // 통신 실패나 503(LOCK_TIMEOUT 등) 후 재시도할 때 같은 멱등키(UUID)를 유지
  const requestSignature = JSON.stringify([finalTckCd, finalOrdDvCd, parsedQty]);
  let idmpKey;
  try {
    const pending = JSON.parse(sessionStorage.getItem(PENDING_ORDER_KEY) || 'null');
    idmpKey = pending?.signature === requestSignature && UUID_REGEX.test(pending?.key)
      ? pending.key
      : generateUuid();
    sessionStorage.setItem(PENDING_ORDER_KEY, JSON.stringify({ signature: requestSignature, key: idmpKey }));
  } catch (e) {
    idmpKey = generateUuid();
  }

  try {
    const res = await apiRequest('/orders', {
      method: 'POST',
      body: JSON.stringify({
        tckCd: finalTckCd,
        ordDvCd: finalOrdDvCd,
        ordQty: parsedQty,
        idmpKey,
      }),
    });

    // 503(LOCK_TIMEOUT, PRICE_UNAVAILABLE, SERVICE_UNAVAILABLE)은 서버에 아무것도 저장되지 않았으므로 같은 키로 재시도 가능,
    // 그 외(201 체결, 422/423 저장된 거절, 400/409 등)는 멱등키가 소모되었거나 새 키가 필요하므로 제거
    if (res.status !== 503) {
      try {
        sessionStorage.removeItem(PENDING_ORDER_KEY);
      } catch (e) {
        // ignore
      }
    }

    if (!res.ok) {
      // 422 / 423 업무 거절의 경우 detail에 ordNo, ordStatCd: 'REJECTED'가 포함되어 저장됨
      const isSavedRejection = res.status === 422 || res.status === 423;
      if (isSavedRejection) {
        await fetchMyAccount();
      }
      const rejectedOrder = res.detail?.ordNo
        ? adaptOrder({
            ordNo: res.detail.ordNo,
            tckCd: finalTckCd,
            tckNm: stockName || finalTckCd,
            ordDvCd: finalOrdDvCd,
            ordQty: parsedQty,
            ordStatCd: res.detail.ordStatCd || 'REJECTED',
            rjctRsnCd: res.code,
            rjctRsnMsg: res.message,
          })
        : null;

      return {
        success: false,
        status: res.status,
        code: res.code,
        message: res.message,
        detail: res.detail,
        traceId: res.traceId,
        replayed: res.replayed,
        isRejected: isSavedRejection,
        isAccountLocked: res.code === 'ACCOUNT_LOCKED',
        isPriceStale: res.code === 'PRICE_STALE' || res.code === 'PRICE_UNAVAILABLE',
        order: rejectedOrder,
        user: getActiveSession(),
      };
    }

    // 201 Created 체결 완료 -> 최신 계좌 요약/보유종목/주문내역 다시 동기화
    const filledOrder = adaptOrder({
      ...res.data,
      tckNm: stockName || res.data?.tckCd,
    });
    const accountSync = await fetchMyAccount();
    const updatedUser = accountSync.success ? accountSync.user : getActiveSession();

    return {
      success: true,
      status: res.status,
      order: filledOrder,
      orderStatus: 'EXECUTED',
      isExecuted: true,
      isHold: false,
      isRejected: false,
      replayed: res.replayed,
      traceId: res.traceId,
      user: updatedUser,
      message: `${stockName || finalTckCd} ${parsedQty}주 ${finalOrdDvCd === 'BUY' ? '매수' : '매도'} 체결되었습니다.`,
    };
  } catch (err) {
    // 네트워크 실패 시 sessionStorage의 idmpKey를 유지해 재시도 시 중복 주문 방지
    return {
      success: false,
      code: 'NETWORK_ERROR',
      message: '주문 서버 통신 오류 (다시 시도하면 동일 주문 키로 안전하게 재요청됩니다): ' + err.message,
    };
  }
}

// ============================================================================
// 5. 모의 출금 API (/api/v1/accounts/me/withdrawals)
// ============================================================================

const PENDING_WITHDRAW_KEY = 'stockshield_v1_pending_withdrawal';

export function adaptWithdrawal(raw) {
  if (!raw) return null;
  const wdrStatCd = raw.wdrStatCd || 'DONE';
  const rjctRsnCd = raw.rjctRsnCd || null;
  const rjctRsnMsg = raw.rjctRsnMsg || (rjctRsnCd ? ERROR_MESSAGES[rjctRsnCd] : '') || '';
  return {
    wdrNo: raw.wdrNo,
    regTs: raw.regTs || '',
    wdrAmt: Number(raw.wdrAmt ?? 0),
    rcvBankNm: raw.rcvBankNm || '',
    mskRcvAcntNo: raw.mskRcvAcntNo || '',
    addAuthYn: raw.addAuthYn || 'N',
    wdrStatCd,
    rjctRsnCd,
    rjctRsnMsg,
    postDpsBal: raw.postDpsBal != null ? Number(raw.postDpsBal) : null,
  };
}

// 모의 출금 요청: POST /api/v1/accounts/me/withdrawals
// - 201 Created: { wdrNo, wdrAmt, dpsBal, addAuthYn, wdrStatCd: 'DONE', regTs }
// - 428 Precondition Required (WDR_PWD_REQUIRED): 출금 비밀번호 필요 (같은 idmpKey를 유지한 채 wdrPwd를 포함해 재요청)
// - 422 / 423: 저장된 거절 (INSUFFICIENT_BALANCE, WDR_PWD_MISMATCH, ACCOUNT_LOCKED, WDR_PWD_LOCKED)
export async function executeWithdrawal({ wdrAmt, rcvBankNm, rcvAcntNo, wdrPwd = null }) {
  const parsedAmt = Number(wdrAmt);
  const cleanBank = (rcvBankNm || '').trim();
  const cleanAcnt = (rcvAcntNo || '').replace(/-/g, '').trim();

  if (!Number.isInteger(parsedAmt) || parsedAmt < 1) {
    return { success: false, message: '출금 금액은 1원 이상의 정수여야 합니다.' };
  }
  if (!cleanBank || cleanBank.length > 50) {
    return { success: false, message: '입금 은행을 확인해 주세요.' };
  }
  if (!/^\d{10,16}$/.test(cleanAcnt)) {
    return { success: false, message: '입금 계좌번호는 숫자 10~16자리여야 합니다.' };
  }
  if (wdrPwd != null && wdrPwd !== '' && !/^\d{6}$/.test(String(wdrPwd))) {
    return { success: false, message: '출금 비밀번호는 숫자 6자리여야 합니다.' };
  }

  // 금액·은행·계좌 서명이 같으면 428 추가 인증이나 통신 실패 재시도 시 동일 idmpKey 유지
  const requestSignature = JSON.stringify([parsedAmt, cleanBank, cleanAcnt]);
  let idmpKey;
  try {
    const pending = JSON.parse(sessionStorage.getItem(PENDING_WITHDRAW_KEY) || 'null');
    idmpKey = pending?.signature === requestSignature && UUID_REGEX.test(pending?.key)
      ? pending.key
      : generateUuid();
    sessionStorage.setItem(PENDING_WITHDRAW_KEY, JSON.stringify({ signature: requestSignature, key: idmpKey }));
  } catch (e) {
    idmpKey = generateUuid();
  }

  try {
    const payload = {
      wdrAmt: parsedAmt,
      rcvBankNm: cleanBank,
      rcvAcntNo: cleanAcnt,
      idmpKey,
    };
    if (wdrPwd) {
      payload.wdrPwd = String(wdrPwd);
    }

    const res = await apiRequest('/accounts/me/withdrawals', {
      method: 'POST',
      body: JSON.stringify(payload),
    });

    // 428(비밀번호 필요)과 503(일시 장애)은 동일 idmpKey를 유지해야 하며,
    // 그 외(201 완료, 422/423 저장된 거절, 400/409 등)는 멱등키가 소모되었으므로 제거
    if (res.status !== 428 && res.status !== 503) {
      try {
        sessionStorage.removeItem(PENDING_WITHDRAW_KEY);
      } catch (e) {
        // ignore
      }
    }

    if (res.status === 428 || res.code === 'WDR_PWD_REQUIRED') {
      return {
        success: false,
        passwordRequired: true,
        status: 428,
        code: 'WDR_PWD_REQUIRED',
        message: res.message || ERROR_MESSAGES.WDR_PWD_REQUIRED,
        detail: res.detail || {}, // { reason, dailySum, threshold }
        traceId: res.traceId,
      };
    }

    if (!res.ok) {
      const isSavedRejection = res.status === 422 || res.status === 423;
      if (isSavedRejection) {
        await fetchMyAccount();
      }
      return {
        success: false,
        status: res.status,
        code: res.code,
        message: res.message,
        detail: res.detail || {}, // { wdrNo, wdrStatCd, remainCnt }
        remainCnt: res.detail?.remainCnt,
        isRejected: isSavedRejection,
        isAccountLocked: res.code === 'ACCOUNT_LOCKED' || res.code === 'WDR_PWD_LOCKED',
        traceId: res.traceId,
        replayed: res.replayed,
        user: getActiveSession(),
      };
    }

    // 201 Created: 출금 완료 -> 최신 계좌 정보 다시 동기화
    const accountSync = await fetchMyAccount();
    const updatedUser = accountSync.success ? accountSync.user : getActiveSession();

    return {
      success: true,
      status: res.status,
      withdrawal: adaptWithdrawal({
        ...res.data,
        rcvBankNm: cleanBank,
        mskRcvAcntNo: '*'.repeat(Math.max(0, cleanAcnt.length - 4)) + cleanAcnt.slice(-4),
        postDpsBal: res.data?.dpsBal,
      }),
      replayed: res.replayed,
      traceId: res.traceId,
      user: updatedUser,
      message: `₩${parsedAmt.toLocaleString('ko-KR')} 출금이 완료되었습니다.`,
    };
  } catch (err) {
    return {
      success: false,
      code: 'NETWORK_ERROR',
      message: '출금 서버 통신 오류 (다시 시도하면 동일 출금 키로 안전하게 재요청됩니다): ' + err.message,
    };
  }
}

export async function fetchWithdrawalHistory({ page = 0, size = 20 } = {}) {
  const params = new URLSearchParams({ page: String(page), size: String(size) });
  const res = await apiRequest(`/accounts/me/withdrawals?${params.toString()}`);
  if (!res.ok) {
    return {
      success: false,
      status: res.status,
      code: res.code,
      message: res.message,
      content: [],
      page: 0,
      size,
      totalElements: 0,
      totalPages: 0,
    };
  }
  const content = Array.isArray(res.data?.content)
    ? res.data.content.map(adaptWithdrawal).filter(Boolean)
    : [];
  return {
    success: true,
    ...res.data,
    content,
  };
}

// ============================================================================
// 4. 시세 · 종목 검색 · 위험 종목 조회 API (/api/v1/market)
// ============================================================================

export async function fetchMarketSummary() {
  const res = await apiRequest('/market/summary', { skipAuth: true });
  if (!res.ok) {
    return { success: false, status: res.status, code: res.code, message: res.message };
  }
  return { success: true, data: res.data };
}

export async function fetchStockDetail(tckCd, signal) {
  const res = await apiRequest(`/market/stocks/${encodeURIComponent(tckCd)}`, {
    signal,
    skipAuth: true,
  });
  if (!res.ok) {
    return { success: false, status: res.status, code: res.code, message: res.message };
  }
  return { success: true, data: res.data };
}

export async function fetchStockPeriodCandles(tckCd, interval = '1일', signal) {
  const apiInterval =
    interval === 'D' || interval === '1일'
      ? 'D'
      : interval === 'W' || interval === '1주'
      ? 'W'
      : 'M';
  const res = await apiRequest(
    `/market/stocks/${encodeURIComponent(tckCd)}/candles?interval=${encodeURIComponent(apiInterval)}`,
    {
      signal,
      skipAuth: true,
    }
  );
  if (!res.ok) {
    return { success: false, status: res.status, code: res.code, message: res.message, candles: [] };
  }
  const candles = Array.isArray(res.data?.candles)
    ? res.data.candles
    : Array.isArray(res.data)
    ? res.data
    : [];
  return { success: true, candles };
}

export async function fetchStockNews(tckCd, signal) {
  const res = await apiRequest(`/news/stocks/${encodeURIComponent(tckCd)}`, {
    signal,
    skipAuth: true,
  });
  if (!res.ok) {
    return { success: false, status: res.status, code: res.code, message: res.message, items: [] };
  }
  return { success: true, items: Array.isArray(res.data) ? res.data : [] };
}

export async function fetchStockOrderbook(tckCd, signal) {
  const res = await apiRequest(`/market/stocks/${encodeURIComponent(tckCd)}/orderbook`, {
    signal,
    skipAuth: true,
  });
  if (!res.ok) {
    return { success: false, status: res.status, code: res.code, message: res.message, data: null };
  }
  return { success: true, data: res.data };
}

export async function searchMarketStocks(keyword) {
  const q = (keyword || '').trim();
  if (!q) return { success: true, items: [] };
  const res = await apiRequest(`/market/stocks/search?keyword=${encodeURIComponent(q)}`, {
    skipAuth: true,
  });
  if (!res.ok) {
    return { success: false, status: res.status, code: res.code, message: res.message, items: [] };
  }
  return { success: true, items: Array.isArray(res.data) ? res.data : [] };
}

export async function fetchRiskStocks() {
  const res = await apiRequest('/market/risk-stocks', { skipAuth: true });
  if (!res.ok) {
    return { success: false, status: res.status, code: res.code, message: res.message, items: [] };
  }
  return { success: true, items: Array.isArray(res.data) ? res.data : [] };
}

// ============================================================================
// 6. 관리자 FDS 탐지 · AI 사유 재요청 · 계좌 잠금/해제 · 원장 대사 API (/api/v1/admin)
// ============================================================================

export async function fetchAdminDetections({ ruleCd = null, chkYn = null, page = 0, size = 20 } = {}) {
  const params = new URLSearchParams({ page: String(page), size: String(size) });
  if (ruleCd && ruleCd !== 'ALL') params.set('ruleCd', ruleCd);
  if (chkYn && chkYn !== 'ALL') params.set('chkYn', chkYn);
  const res = await apiRequest(`/admin/detections?${params.toString()}`);
  if (!res.ok) {
    return {
      success: false,
      status: res.status,
      code: res.code,
      message: res.message,
      content: [],
      page: 0,
      size,
      totalElements: 0,
      totalPages: 0,
    };
  }
  return {
    success: true,
    ...res.data,
    content: Array.isArray(res.data?.content) ? res.data.content : [],
  };
}

export async function fetchAdminDetectionDetail(dtcSeq) {
  const res = await apiRequest(`/admin/detections/${dtcSeq}`);
  if (!res.ok) {
    return {
      success: false,
      status: res.status,
      code: res.code,
      message: res.message,
    };
  }
  return {
    success: true,
    detail: res.data,
  };
}

export async function checkAdminDetection(dtcSeq, actRsnCont) {
  const res = await apiRequest(`/admin/detections/${dtcSeq}/check`, {
    method: 'PATCH',
    body: JSON.stringify({ actRsnCont: (actRsnCont || '').trim() }),
  });
  if (!res.ok) {
    return {
      success: false,
      status: res.status,
      code: res.code,
      message: res.message,
    };
  }
  return {
    success: true,
    data: res.data, // { dtcSeq, chkYn: 'Y' }
  };
}

export async function retryAdminAiReason(dtcSeq) {
  const res = await apiRequest(`/admin/detections/${dtcSeq}/ai-reason`, {
    method: 'POST',
  });
  if (!res.ok) {
    return {
      success: false,
      status: res.status,
      code: res.code,
      message: res.message,
    };
  }
  return {
    success: true,
    data: res.data, // { dtcSeq, aiStatCd: 'REQUESTED' }
  };
}

export async function changeAdminAccountStatus(acntNo, acntStatCd, actRsnCont) {
  const res = await apiRequest(`/admin/accounts/${encodeURIComponent(acntNo)}/status`, {
    method: 'PUT',
    body: JSON.stringify({
      acntStatCd,
      actRsnCont: (actRsnCont || '').trim(),
    }),
  });
  if (!res.ok) {
    return {
      success: false,
      status: res.status,
      code: res.code,
      message: res.message,
    };
  }
  return {
    success: true,
    data: res.data, // { acntNo, acntStatCd }
  };
}

export async function runServerReconciliation() {
  const res = await apiRequest('/admin/reconcile', { method: 'POST' });
  if (!res.ok) {
    return {
      success: false,
      status: res.status,
      code: res.code,
      message: res.message,
    };
  }
  return {
    success: true,
    ...res.data, // { rcclRunNo, rcclDt, runStatCd, totAcntCnt, diffAcntCnt, failRsnCont }
  };
}

export async function fetchAdminReconcileHistory({ page = 0, size = 20 } = {}) {
  const params = new URLSearchParams({ page: String(page), size: String(size) });
  const res = await apiRequest(`/admin/reconcile?${params.toString()}`);
  if (!res.ok) {
    return {
      success: false,
      status: res.status,
      code: res.code,
      message: res.message,
      content: [],
      page: 0,
      size,
      totalElements: 0,
      totalPages: 0,
    };
  }
  return {
    success: true,
    ...res.data,
    content: Array.isArray(res.data?.content) ? res.data.content : [],
  };
}

export async function fetchAdminReconcileDetail(rcclRunNo, diffOnly = true) {
  const res = await apiRequest(`/admin/reconcile/${rcclRunNo}?diffOnly=${diffOnly ? 'true' : 'false'}`);
  if (!res.ok) {
    return {
      success: false,
      status: res.status,
      code: res.code,
      message: res.message,
    };
  }
  return {
    success: true,
    detail: res.data, // { rcclRunNo, rcclDt, runStatCd, results: [{ acntNo, chkItemCd, tckCd, expVal, actlVal, diffYn }] }
  };
}

// 하위 호환 export
export function getStoredUsers() {
  const session = getActiveSession();
  return session ? [session] : [];
}

export function isIdAvailable() {
  return true;
}

export function switchSessionUser() {
  return getActiveSession();
}

export function getStoredAlerts() {
  return [];
}

export function addFdsAlert() {
  return [];
}

export async function fetchServerAlerts() {
  const res = await fetchAdminDetections({ page: 0, size: 20 });
  return {
    success: res.success,
    status: res.status,
    code: res.code,
    message: res.message,
    alerts: res.content || [],
  };
}

export async function updateAlertStatus(dtcSeq, actionType, adminComment = '') {
  if (actionType === 'CHECK') {
    return checkAdminDetection(dtcSeq, adminComment);
  }
  return { success: false, message: '지원하지 않는 조치입니다.' };
}

export function lockUserAccount() {}

export async function unlockUserAccount(acntNo, adminComment) {
  return changeAdminAccountStatus(acntNo, 'ACTIVE', adminComment);
}

/**
 * ISO-8601 시각 문자열을 KST 일반 날짜 형식(YYYY-MM-DD HH:mm:ss)으로 변환합니다.
 */
export function formatDateTime(ts) {
  if (!ts) return '-';
  const d = new Date(ts);
  if (Number.isNaN(d.getTime())) return String(ts);
  const pad = (n) => String(n).padStart(2, '0');
  const kst = new Date(d.getTime() + 9 * 60 * 60 * 1000);
  return `${kst.getUTCFullYear()}-${pad(kst.getUTCMonth() + 1)}-${pad(kst.getUTCDate())} ${pad(kst.getUTCHours())}:${pad(kst.getUTCMinutes())}:${pad(kst.getUTCSeconds())}`;
}
