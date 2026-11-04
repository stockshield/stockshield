// StockShield Enterprise REST API Route Handlers (기능정의서 100% 완전 구현)

import { query, pool } from './db.mjs';
import {
  validatePassword,
  hashPassword,
  comparePassword,
  generateTokens,
  verifyRefreshToken,
  requireAuth,
} from './auth.mjs';
import { evaluateOrderFds } from './fdsEngine.mjs';
import { runReconciliationAudit } from './auditEngine.mjs';

function formatUser(userRow, watchRows = [], portRows = [], orderRows = []) {
  if (!userRow) return null;
  return {
    id: userRow.id,
    name: userRow.name,
    role: userRow.role || 'USER',
    email: userRow.email || '',
    phone: userRow.phone || '',
    accountNo: userRow.account_no,
    balance: Number(userRow.balance),
    seedAmount: Number(userRow.seed_amount),
    fdsBaseAmount: Number(userRow.fds_base_amount || 500000),
    fdsLevel: userRow.fds_level || 'Level 1 안심 보안',
    isLocked: Boolean(userRow.is_locked),
    lockReason: userRow.lock_reason || '',
    createdAt: userRow.created_at,
    watchlist: watchRows.map((r) => r.stock_code),
    portfolio: Object.fromEntries(
      portRows
        .filter((r) => r.quantity > 0)
        .map((r) => [
          r.stock_code,
          {
            quantity: Number(r.quantity),
            avgPrice: Number(r.avg_price),
            name: r.stock_name,
          },
        ])
    ),
    orderHistory: orderRows.map((r) => ({
      id: Number(r.id),
      timestamp: r.timestamp,
      stockCode: r.stock_code,
      stockName: r.stock_name,
      orderType: r.order_type,
      price: Number(r.price),
      quantity: Number(r.quantity),
      totalCost: Number(r.total_cost),
      status: r.status,
      fdsStatus: r.fds_status,
      rejectionReason: r.rejection_reason || '',
      holdExpiresAt: r.hold_expires_at,
    })),
  };
}

function formatOrder(r) {
  if (!r) return null;
  return {
    id: Number(r.id),
    userId: r.user_id,
    timestamp: r.timestamp,
    stockCode: r.stock_code,
    stockName: r.stock_name,
    orderType: r.order_type,
    price: Number(r.price),
    quantity: Number(r.quantity),
    totalCost: Number(r.total_cost),
    status: r.status,
    fdsStatus: r.fds_status,
    rejectionReason: r.rejection_reason || '',
    holdExpiresAt: r.hold_expires_at,
  };
}

function formatAlert(r) {
  if (!r) return null;
  return {
    id: r.id,
    orderId: r.order_id ? Number(r.order_id) : null,
    timestamp: r.timestamp,
    userId: r.user_id,
    userName: r.user_name,
    userIp: r.user_ip,
    accountNo: r.account_no,
    stockCode: r.stock_code,
    stockName: r.stock_name,
    orderType: r.order_type,
    price: Number(r.price),
    quantity: Number(r.quantity),
    totalAmount: Number(r.total_amount),
    riskLevel: r.risk_level,
    riskScore: Number(r.risk_score),
    ruleScores: r.rule_scores || {},
    triggers: Array.isArray(r.triggers) ? r.triggers : [],
    status: r.status,
    actionLog: r.action_log || '',
    aiAnalysis: r.ai_analysis || {},
    createdAt: r.created_at,
  };
}

export function registerRoutes(app) {
  // -------------------------------------------------------------
  // 1. 아이디 중복 확인 (기능 ID 5: USR_AUTH_001)
  // -------------------------------------------------------------
  app.get('/api/auth/check-id/:id', async (req, res) => {
    try {
      const { id } = req.params;
      const cleanId = (id || '').trim().toLowerCase();
      if (!cleanId || cleanId.length < 4) {
        return res.json({ available: false, message: '아이디는 4자 이상이어야 합니다.' });
      }
      const check = await query('SELECT id FROM users WHERE LOWER(id) = $1', [cleanId]);
      res.json({
        available: check.rows.length === 0,
        message: check.rows.length === 0 ? '사용 가능한 아이디입니다.' : '이미 사용 중인 아이디입니다.',
      });
    } catch (err) {
      res.status(500).json({ error: err.message });
    }
  });

  // -------------------------------------------------------------
  // 2. 회원가입 (기능 ID 6, 7, 8, 9, 10: USR_AUTH_001)
  // -------------------------------------------------------------
  app.post('/api/auth/register', async (req, res) => {
    try {
      const { id, password, name, email, phone, seedAmount = 10000000 } = req.body;
      const cleanId = (id || '').trim().toLowerCase();

      if (!cleanId || cleanId.length < 4) {
        return res.status(400).json({ success: false, message: '아이디는 4자 이상이어야 합니다.' });
      }

      // 비밀번호 규칙 검증: 8자 이상, 영문·숫자·특수문자 조합 (기능 ID 7)
      if (!validatePassword(password)) {
        return res.status(400).json({
          success: false,
          message: '비밀번호는 8자 이상이며, 영문, 숫자, 특수문자를 각각 1개 이상 포함해야 합니다.',
        });
      }

      // 중복 확인
      const existing = await query('SELECT id FROM users WHERE LOWER(id) = $1', [cleanId]);
      if (existing.rows.length > 0) {
        return res.status(409).json({ success: false, message: '이미 사용 중인 아이디입니다.' });
      }

      // BCrypt 비밀번호 암호화 (기능 ID 8)
      const passwordHash = hashPassword(password);

      // 계좌번호 발급 및 1,000만원 기본 예수금 계좌 생성 (기능 ID 9, 10)
      const accountNo = `501-12-${Math.floor(100000 + Math.random() * 900000)}`;
      const seed = Number(seedAmount) || 10000000;

      const insertRes = await query(
        `INSERT INTO users (id, password_hash, name, role, email, phone, account_no, balance, seed_amount, fds_base_amount, fds_level)
         VALUES ($1, $2, $3, 'USER', $4, $5, $6, $7, $8, 500000, 'Level 1 안심 보안')
         RETURNING *`,
        [cleanId, passwordHash, name.trim(), email || '', phone || '', accountNo, seed, seed]
      );

      // 기본 관심 종목 등록
      await query(
        `INSERT INTO watchlists (user_id, stock_code) VALUES ($1, '005930'), ($1, '000660') ON CONFLICT DO NOTHING`,
        [cleanId]
      );

      // JWT Access(30분) / Refresh(7일) 토큰 발급 (기능 ID 12)
      const user = insertRes.rows[0];
      const tokens = generateTokens(user);

      // Refresh Token DB 보관
      await query(
        `INSERT INTO refresh_tokens (token, user_id, expires_at)
         VALUES ($1, $2, NOW() + INTERVAL '7 days')`,
        [tokens.refreshToken, cleanId]
      );

      const formatted = formatUser(user, [{ stock_code: '005930' }, { stock_code: '000660' }], [], []);
      res.json({
        success: true,
        user: formatted,
        tokens,
        message: '회원가입 및 모의투자 계좌 개설이 완료되었습니다.',
      });
    } catch (err) {
      console.error('[Auth Register Error]:', err);
      res.status(500).json({ success: false, message: '회원가입 처리 실패: ' + err.message });
    }
  });

  // -------------------------------------------------------------
  // 3. 로그인 (기능 ID 12, 13, 14, 15: USR_AUTH_002)
  // -------------------------------------------------------------
  app.post('/api/auth/login', async (req, res) => {
    try {
      const rawId = req.body.id || req.body.userId;
      const { password } = req.body;
      const cleanId = (rawId || '').trim().toLowerCase();
      const clientIp = req.headers['x-forwarded-for'] || req.socket.remoteAddress || '127.0.0.1';
      const userAgent = req.headers['user-agent'] || 'Unknown Client';

      if (!cleanId || !password) {
        return res.status(400).json({ success: false, message: '아이디와 비밀번호를 입력해주세요.' });
      }

      const userRes = await query('SELECT * FROM users WHERE LOWER(id) = $1', [cleanId]);
      if (userRes.rows.length === 0) {
        return res.status(401).json({ success: false, message: '아이디 또는 비밀번호가 올바르지 않습니다.' });
      }

      const user = userRes.rows[0];

      // 5회 로그인 실패 시 30분 잠금 검증 (기능 ID 15)
      if (user.locked_until && new Date(user.locked_until) > new Date()) {
        const remainingMinutes = Math.ceil((new Date(user.locked_until) - new Date()) / 60000);
        return res.status(403).json({
          success: false,
          code: 'LOGIN_LOCKED',
          message: `비밀번호 5회 연속 오류로 인해 계정이 일시 잠겼습니다. (${remainingMinutes}분 후 해제)`,
        });
      }

      // BCrypt 비밀번호 비교 (기능 ID 8)
      const isMatch = comparePassword(password, user.password_hash);
      if (!isMatch) {
        const newFailCount = Number(user.failed_login_count || 0) + 1;
        if (newFailCount >= 5) {
          await query(
            `UPDATE users
             SET failed_login_count = $1, locked_until = NOW() + INTERVAL '30 minutes'
             WHERE id = $2`,
            [newFailCount, user.id]
          );
          return res.status(403).json({
            success: false,
            code: 'LOGIN_LOCKED',
            message: '비밀번호를 5회 연속 틀려 30분 동안 로그인이 제한됩니다.',
          });
        } else {
          await query('UPDATE users SET failed_login_count = $1 WHERE id = $2', [newFailCount, user.id]);
          return res.status(401).json({
            success: false,
            message: `비밀번호가 올바르지 않습니다. (실패 ${newFailCount}/5회)`,
          });
        }
      }

      // 로그인 성공: 실패 횟수 리셋 및 접속 정보 기록 (기능 ID 13, 14)
      await query(
        'UPDATE users SET failed_login_count = 0, locked_until = NULL WHERE id = $1',
        [user.id]
      );
      await query(
        'INSERT INTO access_logs (user_id, ip_address, user_agent) VALUES ($1, $2, $3)',
        [user.id, clientIp, userAgent]
      );

      // JWT 발급 (기능 ID 12)
      const tokens = generateTokens(user);
      await query(
        `INSERT INTO refresh_tokens (token, user_id, expires_at)
         VALUES ($1, $2, NOW() + INTERVAL '7 days')`,
        [tokens.refreshToken, user.id]
      );

      const [watchRes, portRes, orderRes] = await Promise.all([
        query('SELECT stock_code FROM watchlists WHERE user_id = $1', [user.id]),
        query('SELECT * FROM portfolios WHERE user_id = $1', [user.id]),
        query('SELECT * FROM orders WHERE user_id = $1 ORDER BY created_at DESC, id DESC', [user.id]),
      ]);

      const formatted = formatUser(user, watchRes.rows, portRes.rows, orderRes.rows);
      res.json({ success: true, user: formatted, tokens });
    } catch (err) {
      console.error('[Auth Login Error]:', err);
      res.status(500).json({ success: false, message: '로그인 오류: ' + err.message });
    }
  });

  // -------------------------------------------------------------
  // 4. 토큰 재발급 (기능 ID 16: USR_AUTH_002)
  // -------------------------------------------------------------
  app.post('/api/auth/refresh', async (req, res) => {
    try {
      const { refreshToken } = req.body;
      if (!refreshToken) {
        return res.status(400).json({ success: false, message: 'Refresh Token이 필요합니다.' });
      }

      const decoded = verifyRefreshToken(refreshToken);
      if (!decoded) {
        return res.status(401).json({ success: false, message: '만료되었거나 유효하지 않은 Refresh Token입니다.' });
      }

      const dbTokenRes = await query(
        'SELECT * FROM refresh_tokens WHERE token = $1 AND expires_at > NOW()',
        [refreshToken]
      );
      if (dbTokenRes.rows.length === 0) {
        return res.status(401).json({ success: false, message: '유효한 세션이 아닙니다. 다시 로그인해주세요.' });
      }

      const userRes = await query('SELECT * FROM users WHERE id = $1', [decoded.id]);
      if (userRes.rows.length === 0) {
        return res.status(401).json({ success: false, message: '사용자를 찾을 수 없습니다.' });
      }

      const user = userRes.rows[0];
      const newTokens = generateTokens(user);

      // 기존 토큰 대체
      await query('DELETE FROM refresh_tokens WHERE token = $1', [refreshToken]);
      await query(
        `INSERT INTO refresh_tokens (token, user_id, expires_at)
         VALUES ($1, $2, NOW() + INTERVAL '7 days')`,
        [newTokens.refreshToken, user.id]
      );

      res.json({ success: true, tokens: newTokens });
    } catch (err) {
      res.status(500).json({ success: false, message: err.message });
    }
  });

  // -------------------------------------------------------------
  // 5. 로그아웃 (기능 ID 17: USR_AUTH_002)
  // -------------------------------------------------------------
  app.post('/api/auth/logout', async (req, res) => {
    try {
      const { refreshToken } = req.body;
      if (refreshToken) {
        await query('DELETE FROM refresh_tokens WHERE token = $1', [refreshToken]);
      }
      res.json({ success: true, message: '안전하게 로그아웃되었습니다.' });
    } catch (err) {
      res.status(500).json({ success: false, message: err.message });
    }
  });

  // -------------------------------------------------------------
  // 6. 사용자 프로필 및 원장 데이터 조회
  // -------------------------------------------------------------
  // -------------------------------------------------------------
  // 계좌 및 원장 정보 조회 헬퍼 함수
  // -------------------------------------------------------------
  async function getFormattedUserAccount(targetId) {
    const userRes = await query('SELECT * FROM users WHERE id = $1', [targetId]);
    if (userRes.rows.length === 0) {
      return null;
    }
    const [watchRes, portRes, orderRes] = await Promise.all([
      query('SELECT stock_code FROM watchlists WHERE user_id = $1', [targetId]),
      query('SELECT * FROM portfolios WHERE user_id = $1', [targetId]),
      query('SELECT * FROM orders WHERE user_id = $1 ORDER BY created_at DESC, id DESC', [targetId]),
    ]);
    return formatUser(userRes.rows[0], watchRes.rows, portRes.rows, orderRes.rows);
  }

  // -------------------------------------------------------------
  // 내 계좌 전용 조회 (GET /api/me)
  // [보안 핵심]: URL 파라미터 없이 검증된 JWT 토큰의 사용자 ID로만 계좌 조회 (타인 계좌 접근 원천 차단)
  // -------------------------------------------------------------
  app.get('/api/me', requireAuth(), async (req, res) => {
    try {
      const myId = req.user.id;
      const formatted = await getFormattedUserAccount(myId);
      if (!formatted) {
        return res.status(404).json({ success: false, message: '사용자를 찾을 수 없습니다.' });
      }
      res.json({ success: true, user: formatted });
    } catch (err) {
      res.status(500).json({ success: false, message: err.message });
    }
  });

  // -------------------------------------------------------------
  // 6. 사용자 프로필 및 원장 데이터 조회 (GET /api/users/:id)
  // [보안 검증]:
  // 1. 토큰 없거나 만료 시 401 차단 (requireAuth)
  // 2. 토큰 ID와 요청 ID가 다르고 관리자가 아니면 403 차단
  // 3. 본인 또는 관리자 권한일 때만 정상 조회
  // -------------------------------------------------------------
  app.get('/api/users/:id', requireAuth(), async (req, res) => {
    try {
      const { id } = req.params;

      // 타인 계좌 접근 검증: 관리자(ADMIN)만 타인 계좌 조회 허용
      if (req.user.id !== id && req.user.role !== 'ADMIN') {
        return res.status(403).json({
          success: false,
          code: 'FORBIDDEN_ACCOUNT_ACCESS',
          message: '본인 소유의 계좌만 조회할 수 있습니다. 타인 계좌에 대한 접근 권한(403)이 없습니다.',
        });
      }

      const formatted = await getFormattedUserAccount(id);
      if (!formatted) {
        return res.status(404).json({ success: false, message: '사용자를 찾을 수 없습니다.' });
      }
      res.json({ success: true, user: formatted });
    } catch (err) {
      res.status(500).json({ success: false, message: err.message });
    }
  });

  // -------------------------------------------------------------
  // 7. 관심종목 토글 헬퍼 및 라우트
  // -------------------------------------------------------------
  async function toggleWatchlistForUser(targetId, stockCode) {
    const check = await query('SELECT * FROM watchlists WHERE user_id = $1 AND stock_code = $2', [targetId, stockCode]);
    if (check.rows.length > 0) {
      await query('DELETE FROM watchlists WHERE user_id = $1 AND stock_code = $2', [targetId, stockCode]);
    } else {
      await query('INSERT INTO watchlists (user_id, stock_code) VALUES ($1, $2)', [targetId, stockCode]);
    }
    return await getFormattedUserAccount(targetId);
  }

  // 내 관심종목 전용 변경 (POST /api/me/watchlist)
  // [보안 핵심]: URL 파라미터 없이 JWT 토큰 소유자의 관심종목만 변경 처리
  app.post('/api/me/watchlist', requireAuth(), async (req, res) => {
    try {
      const myId = req.user.id;
      const { stockCode } = req.body;
      if (!stockCode) return res.status(400).json({ success: false, error: 'stockCode가 필요합니다.' });

      const formatted = await toggleWatchlistForUser(myId, stockCode);
      res.json({ success: true, user: formatted });
    } catch (err) {
      res.status(500).json({ success: false, error: err.message });
    }
  });

  // 사용자별 관심종목 변경 (POST /api/users/:id/watchlist)
  // [보안 검증]:
  // 1. 토큰 없거나 만료 시 401 차단 (requireAuth)
  // 2. 토큰 ID와 요청 ID가 다르고 관리자가 아니면 403 차단
  // 3. 본인 또는 관리자 권한일 때만 정상 변경 처리
  app.post('/api/users/:id/watchlist', requireAuth(), async (req, res) => {
    try {
      const { id } = req.params;
      const { stockCode } = req.body;
      if (!stockCode) return res.status(400).json({ success: false, error: 'stockCode가 필요합니다.' });

      if (req.user.id !== id && req.user.role !== 'ADMIN') {
        return res.status(403).json({
          success: false,
          code: 'FORBIDDEN_ACCOUNT_ACCESS',
          error: '본인 소유의 관심종목만 수정할 수 있습니다. 타인 계좌에 대한 접근 권한(403)이 없습니다.',
        });
      }

      const formatted = await toggleWatchlistForUser(id, stockCode);
      res.json({ success: true, user: formatted });
    } catch (err) {
      res.status(500).json({ success: false, error: err.message });
    }
  });

  // -------------------------------------------------------------
  // 8. 모의 주문 실행 (기능 ID 51~62, 73, 74, 75, 77)
  // [보안 강화]: JWT 인증(requireAuth) 및 계좌 소유권 검사 강제
  // -------------------------------------------------------------
  app.post('/api/orders', requireAuth(), async (req, res) => {
    const client = await pool.connect();
    try {
      const {
        userId: requestedUserId,
        stockCode,
        stockName,
        orderType,
        price,
        quantity,
        idempotencyKey,
      } = req.body;

      // 0. 인증 사용자 식별 및 계좌 소유권 검사 (타인 계좌 도용 차단)
      const authUserId = req.user.id;
      if (requestedUserId && requestedUserId !== authUserId && req.user.role !== 'ADMIN') {
        return res.status(403).json({
          success: false,
          code: 'FORBIDDEN_ACCOUNT_ACCESS',
          message: '본인 소유의 계좌에 대해서만 주문을 요청할 수 있습니다. (타인 계좌 도용 차단)',
        });
      }
      const userId = authUserId; // 항상 JWT 토큰으로 검증된 사용자 ID 강제 적용

      // 1. 주문 유형 유효성 검사 (UNKNOWN 등 임의 주문 유형 차단)
      if (!orderType || !['BUY', 'SELL'].includes(orderType)) {
        return res.status(400).json({
          success: false,
          code: 'INVALID_ORDER_TYPE',
          message: '올바른 주문 유형(BUY, SELL)이 아닙니다. UNKNOWN 등 유효하지 않은 주문 유형은 거절됩니다.',
        });
      }

      const clientIp = req.headers['x-forwarded-for'] || req.socket.remoteAddress || '127.0.0.1';
      const userAgent = req.headers['user-agent'] || 'Unknown Client';

      // 2. 멱등키 중복 주문 검사 (기능 ID 54)
      if (idempotencyKey) {
        const existingOrder = await query('SELECT * FROM orders WHERE idempotency_key = $1', [idempotencyKey]);
        if (existingOrder.rows.length > 0) {
          const ord = existingOrder.rows[0];
          const userRes = await query('SELECT * FROM users WHERE id = $1', [userId]);
          return res.json({
            success: true,
            isDuplicate: true,
            order: formatOrder(ord),
            user: formatUser(userRes.rows[0]),
            message: `[중복 방지] 멱등키(${idempotencyKey})로 이미 처리된 주문 결과를 반환합니다.`,
          });
        }
      }

      // 2. 수량 및 가격 유효성 검사 (정수, 양수 필수)
      const numPrice = Number(price);
      const numQty = Number(quantity);
      if (!Number.isInteger(numQty) || numQty <= 0) {
        return res.status(400).json({ success: false, message: '주문 수량은 1 이상의 정수여야 합니다. (0.5주 등 불가)' });
      }
      if (!Number.isFinite(numPrice) || numPrice <= 0) {
        return res.status(400).json({ success: false, message: '주문 가격이 올바르지 않습니다.' });
      }
      const totalCost = numPrice * numQty;

      // 3. 장 운영 시간 검사 (기능 ID 53: 평일 09:00~15:30 KST)
      // UTC 시간에 정확히 9시간을 더해 KST 기준 Date 객체 생성 (요일 및 시간대 왜곡 방지)
      const now = new Date();
      const kstTime = new Date(now.getTime() + (9 * 60 * 60 * 1000));
      const kstDay = kstTime.getUTCDay(); // 0: 일, 1: 월, 2: 화, 3: 수, 4: 목, 5: 금, 6: 토
      const kstHour = kstTime.getUTCHours();
      const kstMinute = kstTime.getUTCMinutes();
      const timeNum = kstHour * 100 + kstMinute;
      const isWeekday = kstDay >= 1 && kstDay <= 5;
      const isMarketOpen = isWeekday && timeNum >= 900 && timeNum <= 1530;

      // 시연/개발 예외는 서버 환경설정(ALLOW_OFF_HOURS=true)으로만 제한하며, 클라이언트 요청 body로 임의 우회 불가
      const serverAllowsOffHours = process.env.ALLOW_OFF_HOURS === 'true';
      if (!isMarketOpen && !serverAllowsOffHours) {
        return res.status(400).json({
          success: false,
          code: 'MARKET_CLOSED',
          message: 'KRX 정규장 운영 시간(평일 09:00~15:30 KST)이 아닙니다. 장외 시간에는 주문 접수가 거절됩니다.',
        });
      }

      await client.query('BEGIN');

      // 4. 계좌 락 및 사용자 조회 (FOR UPDATE - 기능 ID 60)
      const userRes = await client.query('SELECT * FROM users WHERE id = $1 FOR UPDATE', [userId]);
      if (userRes.rows.length === 0) {
        await client.query('ROLLBACK');
        return res.status(404).json({ success: false, message: '사용자를 찾을 수 없습니다.' });
      }
      const user = userRes.rows[0];

      // 잠긴 계좌 주문 차단 (기능 ID 62)
      if (user.is_locked) {
        await client.query('ROLLBACK');
        return res.status(403).json({
          success: false,
          code: 'ACCOUNT_LOCKED',
          message: `보안을 위해 거래가 잠긴 계좌입니다. 주문이 거절되었습니다. (사유: ${user.lock_reason || 'FDS 위험 조치'})`,
        });
      }

      // 5. 잔고 및 보유 수량 검증 (기능 ID 52, 74: 잔고 초과는 점수와 무관하게 즉시 거절)
      const portRes = await client.query(
        'SELECT * FROM portfolios WHERE user_id = $1 AND stock_code = $2 FOR UPDATE',
        [userId, stockCode]
      );
      const currentHolding = portRes.rows[0] || { quantity: 0, avg_price: 0 };

      if (orderType === 'BUY') {
        if (Number(user.balance) < totalCost) {
          await client.query('ROLLBACK');
          return res.status(400).json({ success: false, code: 'INSUFFICIENT_BALANCE', message: '주문 가능 예수금이 부족합니다. (잔고 초과 거절)' });
        }
      } else if (orderType === 'SELL') {
        if (currentHolding.quantity < numQty) {
          await client.query('ROLLBACK');
          return res.status(400).json({ success: false, code: 'INSUFFICIENT_HOLDINGS', message: '보유 주식 수량이 부족합니다. (보유량 초과 거절)' });
        }
      } else {
        await client.query('ROLLBACK');
        return res.status(400).json({ success: false, code: 'INVALID_ORDER_TYPE', message: '올바른 주문 유형(BUY, SELL)이 아닙니다.' });
      }

      // 6. FDS 정밀 4대 규칙 판정 (기능 ID 69~75)
      const fdsResult = await evaluateOrderFds({
        userId,
        totalCost,
        stockCode,
        userBalance: Number(user.balance),
        ipAddress: clientIp,
        userAgent,
      });

      let orderStatus = 'EXECUTED';
      let fdsStatus = '정상 승인 (FDS 인증)';
      let rejectionReason = '';
      let holdExpiresAt = null;

      if (fdsResult.verdict === 'BLOCK') {
        // 80점 이상: 주문 차단 및 계좌 즉시 잠금 (기능 ID 79, 80)
        orderStatus = 'REJECTED';
        fdsStatus = 'FDS 차단 (주문 거절)';
        rejectionReason = `FDS 위험 점수 ${fdsResult.riskScore}점 초과로 자동 주문 차단`;
        await client.query(
          `UPDATE users SET is_locked = TRUE, lock_reason = $1 WHERE id = $2`,
          ['FDS 위험 점수 80점 초과에 따른 자동 거래 잠금', userId]
        );
      } else if (fdsResult.verdict === 'HOLD') {
        // 50~79점: 주문 보류 및 30분 만료 설정 (기능 ID 77, 78)
        orderStatus = 'HOLD';
        fdsStatus = 'FDS 심사 보류 (검토 중)';
        rejectionReason = `FDS 위험 점수 ${fdsResult.riskScore}점 감지 (관리자 심사 대기)`;
        holdExpiresAt = new Date(Date.now() + 30 * 60 * 1000); // 30분 후
      }

      // 7. 정상 체결(EXECUTED)인 경우에만 원장 및 예수금 즉시 차감/증가 (기능 ID 58)
      if (orderStatus === 'EXECUTED') {
        if (orderType === 'BUY') {
          const newBalance = Number(user.balance) - totalCost;
          const oldQty = currentHolding.quantity;
          const oldCost = oldQty * Number(currentHolding.avg_price);
          const newQty = oldQty + numQty;
          const newAvgPrice = Math.round((oldCost + totalCost) / newQty);

          await client.query('UPDATE users SET balance = $1 WHERE id = $2', [newBalance, userId]);
          await client.query(
            `INSERT INTO portfolios (user_id, stock_code, stock_name, quantity, avg_price, updated_at)
             VALUES ($1, $2, $3, $4, $5, CURRENT_TIMESTAMP)
             ON CONFLICT (user_id, stock_code) DO UPDATE
             SET quantity = EXCLUDED.quantity, avg_price = EXCLUDED.avg_price, updated_at = CURRENT_TIMESTAMP`,
            [userId, stockCode, stockName, newQty, newAvgPrice]
          );
        } else if (orderType === 'SELL') {
          const newBalance = Number(user.balance) + totalCost;
          const newQty = currentHolding.quantity - numQty;

          await client.query('UPDATE users SET balance = $1 WHERE id = $2', [newBalance, userId]);
          if (newQty <= 0) {
            await client.query('DELETE FROM portfolios WHERE user_id = $1 AND stock_code = $2', [userId, stockCode]);
          } else {
            await client.query(
              'UPDATE portfolios SET quantity = $1, updated_at = CURRENT_TIMESTAMP WHERE user_id = $2 AND stock_code = $3',
              [newQty, userId, stockCode]
            );
          }
        } else {
          await client.query('ROLLBACK');
          return res.status(400).json({ success: false, code: 'INVALID_ORDER_TYPE', message: '지원하지 않는 주문 유형입니다.' });
        }
      }

      // 8. 주문 원장 INSERT (기능 ID 59)
      const timestamp = new Date().toTimeString().split(' ')[0];
      const orderInsert = await client.query(
        `INSERT INTO orders (user_id, idempotency_key, timestamp, stock_code, stock_name, order_type, price, quantity, total_cost, status, fds_status, rejection_reason, hold_expires_at)
         VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13)
         RETURNING *`,
        [userId, idempotencyKey || null, timestamp, stockCode, stockName, orderType, numPrice, numQty, totalCost, orderStatus, fdsStatus, rejectionReason, holdExpiresAt]
      );
      const insertedOrder = orderInsert.rows[0];

      // 9. 보류 또는 차단인 경우 FDS 경보(fds_alerts) 생성 및 주문과 직접 연결 (먼저 고칠 사항 2)
      if (orderStatus === 'HOLD' || orderStatus === 'REJECTED') {
        const alertId = `ALT-${Date.now().toString().slice(-4)}`;
        const aiAnalysis = {
          summary: `회원(${userId})의 ${stockName} 주문이 FDS 점수 ${fdsResult.riskScore}점으로 감지되었습니다. 주요 사유: ${fdsResult.triggers.join(', ')}`,
          recommendation: orderStatus === 'REJECTED' ? '위험 등급 초과로 계좌 잠금 유지 권고' : '유선 본인확인 후 수동 승인 권고',
          confidence: 90,
        };

        await client.query(
          `INSERT INTO fds_alerts (id, order_id, timestamp, user_id, user_name, user_ip, account_no, stock_code, stock_name, order_type, price, quantity, total_amount, risk_level, risk_score, rule_scores, triggers, status, action_log, ai_analysis)
           VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13, $14, $15, $16, $17, $18, $19, $20)`,
          [
            alertId,
            insertedOrder.id,
            timestamp,
            userId,
            user.name,
            clientIp,
            user.account_no,
            stockCode,
            stockName,
            orderType,
            numPrice,
            numQty,
            totalCost,
            fdsResult.riskLevel,
            fdsResult.riskScore,
            JSON.stringify(fdsResult.ruleScores),
            JSON.stringify(fdsResult.triggers),
            orderStatus === 'HOLD' ? 'HOLD' : 'REJECTED',
            `${new Date().toISOString()} FDS ${orderStatus} 자동 등록`,
            JSON.stringify(aiAnalysis),
          ]
        );
      }

      await client.query('COMMIT');

      // 갱신된 사용자 상태 응답
      const [finalUserRes, watchRes, finalPortRes, finalOrderRes] = await Promise.all([
        query('SELECT * FROM users WHERE id = $1', [userId]),
        query('SELECT stock_code FROM watchlists WHERE user_id = $1', [userId]),
        query('SELECT * FROM portfolios WHERE user_id = $1', [userId]),
        query('SELECT * FROM orders WHERE user_id = $1 ORDER BY created_at DESC, id DESC', [userId]),
      ]);

      const formatted = formatUser(finalUserRes.rows[0], watchRes.rows, finalPortRes.rows, finalOrderRes.rows);
      res.json({
        success: true, // 주문 요청 및 DB 기록 성공 (체결 여부는 isExecuted/orderStatus로 구분)
        order: formatOrder(insertedOrder),
        orderStatus,
        user: formatted,
        isHold: orderStatus === 'HOLD',
        isRejected: orderStatus === 'REJECTED',
        isExecuted: orderStatus === 'EXECUTED',
        message: orderStatus === 'HOLD'
          ? '확인 중인 주문입니다 (FDS 심사 보류 - 관리자 승인 대기)'
          : orderStatus === 'REJECTED'
          ? '보안을 위해 거래가 차단되고 계좌가 잠겼습니다 (FDS 위험 점수 초과)'
          : `${stockName} ${numQty}주 ${orderType === 'BUY' ? '매수' : '매도'} 주문이 정상 체결되었습니다.`,
      });
    } catch (err) {
      await client.query('ROLLBACK');
      console.error('[Order Execution Error]:', err);
      res.status(500).json({ success: false, message: '주문 처리 중 오류 발생: ' + err.message });
    } finally {
      client.release();
    }
  });

  // -------------------------------------------------------------
  // 9. 관리자 전용: FDS 경보 목록 조회 (기능 ID 19, 20, 21, 92)
  // -------------------------------------------------------------
  app.get('/api/alerts', requireAuth('ADMIN'), async (req, res) => {
    try {
      const result = await query('SELECT * FROM fds_alerts ORDER BY created_at DESC');
      res.json({ success: true, alerts: result.rows.map(formatAlert) });
    } catch (err) {
      res.status(500).json({ success: false, message: err.message });
    }
  });

  // -------------------------------------------------------------
  // 10. 관리자 전용: 보류 주문 승인/거절 및 계좌 잠금/해제 (기능 ID 81, 95, 96)
  // 먼저 고칠 사항 2: 승인 시 같은 트랜잭션에서 원장 체결 반영!
  // -------------------------------------------------------------
  app.post('/api/alerts/:id/action', requireAuth('ADMIN'), async (req, res) => {
    const client = await pool.connect();
    try {
      const { id } = req.params;
      const actionType = req.body.actionType || req.body.action || req.body.status;
      const adminComment = req.body.adminComment || req.body.reason || req.body.comment || '';
      const adminId = req.user.id;
      const now = new Date();
      const timeStr = now.toTimeString().split(' ')[0];

      if (!['APPROVED', 'REJECTED', 'LOCKED', 'UNLOCKED'].includes(actionType)) {
        return res.status(400).json({ success: false, message: '올바른 조치 유형(APPROVED, REJECTED, LOCKED, UNLOCKED)이 아닙니다.' });
      }

      await client.query('BEGIN');

      const alertRes = await client.query('SELECT * FROM fds_alerts WHERE id = $1 FOR UPDATE', [id]);
      if (alertRes.rows.length === 0) {
        await client.query('ROLLBACK');
        return res.status(404).json({ success: false, message: '해당 경보를 찾을 수 없습니다.' });
      }
      const alertRow = alertRes.rows[0];

      let actionDesc = '';
      if (actionType === 'APPROVED') {
        actionDesc = `관리자(${adminId}) 수동 승인 완료 (${timeStr}) - 사유: ${adminComment || '정상 거래 판정'}`;
      } else if (actionType === 'REJECTED') {
        actionDesc = `관리자(${adminId}) 주문 최종 거절 (${timeStr}) - 사유: ${adminComment || '위험 주문 차단'}`;
      } else if (actionType === 'LOCKED') {
        actionDesc = `관리자(${adminId}) 계좌 긴급 잠금 (${timeStr}) - 사유: ${adminComment || '이상거래 혐의 동결'}`;
      } else if (actionType === 'UNLOCKED') {
        actionDesc = `관리자(${adminId}) 계좌 잠금 해제 (${timeStr}) - 사유: ${adminComment || '소명 완료 및 정상 확인'}`;
      }

      // 조치 1: 보류 주문 승인 (APPROVED) - 오직 HOLD 상태의 주문만 승인 가능
      if (actionType === 'APPROVED') {
        if (!alertRow.order_id) {
          await client.query('ROLLBACK');
          return res.status(400).json({ success: false, message: '경보에 연결된 주문 번호가 없습니다.' });
        }

        const ordRes = await client.query('SELECT * FROM orders WHERE id = $1 FOR UPDATE', [alertRow.order_id]);
        if (ordRes.rows.length === 0) {
          await client.query('ROLLBACK');
          return res.status(404).json({ success: false, message: '연결된 주문 원장을 찾을 수 없습니다.' });
        }
        const ord = ordRes.rows[0];

        // 멱등성 검사: 이미 체결 완료된 주문
        if (ord.status === 'EXECUTED' && alertRow.status === 'APPROVED') {
          await client.query('ROLLBACK');
          const allAlerts = await query('SELECT * FROM fds_alerts ORDER BY created_at DESC');
          return res.json({
            success: true,
            isIdempotent: true,
            message: '이미 승인 및 체결 처리가 완료된 주문입니다.',
            alerts: allAlerts.rows.map(formatAlert),
          });
        }

        // 상태 전이 규칙 검증: 오직 HOLD 상태만 승인 가능
        if (ord.status === 'REJECTED') {
          await client.query('ROLLBACK');
          return res.status(409).json({ success: false, code: 'INVALID_ORDER_STATE', message: '이미 거절/차단(REJECTED)된 주문은 승인할 수 없습니다.' });
        }
        if (ord.status === 'CANCELLED') {
          await client.query('ROLLBACK');
          return res.status(409).json({ success: false, code: 'INVALID_ORDER_STATE', message: '30분 시간 초과로 만료 취소(CANCELLED)된 주문은 승인할 수 없습니다.' });
        }
        if (ord.status !== 'HOLD') {
          await client.query('ROLLBACK');
          return res.status(409).json({ success: false, code: 'INVALID_ORDER_STATE', message: `현재 상태(${ord.status})의 주문은 승인할 수 없습니다. (심사 보류[HOLD] 주문만 승인 가능)` });
        }

        // 잔고 및 보유량 검증 후 원장 체결
        const uRes = await client.query('SELECT * FROM users WHERE id = $1 FOR UPDATE', [ord.user_id]);
        const u = uRes.rows[0];
        const pRes = await client.query('SELECT * FROM portfolios WHERE user_id = $1 AND stock_code = $2 FOR UPDATE', [
          ord.user_id,
          ord.stock_code,
        ]);
        const curHolding = pRes.rows[0] || { quantity: 0, avg_price: 0 };
        const cost = Number(ord.total_cost);
        const qty = Number(ord.quantity);

        if (ord.order_type === 'BUY') {
          if (Number(u.balance) < cost) {
            await client.query('ROLLBACK');
            return res.status(400).json({ success: false, message: '승인 시점 예수금이 부족하여 체결할 수 없습니다.' });
          }
          const newBal = Number(u.balance) - cost;
          const newQty = curHolding.quantity + qty;
          const newAvg = Math.round((curHolding.quantity * Number(curHolding.avg_price) + cost) / newQty);

          await client.query('UPDATE users SET balance = $1 WHERE id = $2', [newBal, u.id]);
          await client.query(
            `INSERT INTO portfolios (user_id, stock_code, stock_name, quantity, avg_price, updated_at)
             VALUES ($1, $2, $3, $4, $5, CURRENT_TIMESTAMP)
             ON CONFLICT (user_id, stock_code) DO UPDATE
             SET quantity = EXCLUDED.quantity, avg_price = EXCLUDED.avg_price, updated_at = CURRENT_TIMESTAMP`,
            [u.id, ord.stock_code, ord.stock_name, newQty, newAvg]
          );
        } else if (ord.order_type === 'SELL') {
          if (curHolding.quantity < qty) {
            await client.query('ROLLBACK');
            return res.status(400).json({ success: false, message: '승인 시점 보유 주식이 부족하여 체결할 수 없습니다.' });
          }
          const newBal = Number(u.balance) + cost;
          const newQty = curHolding.quantity - qty;
          await client.query('UPDATE users SET balance = $1 WHERE id = $2', [newBal, u.id]);
          if (newQty <= 0) {
            await client.query('DELETE FROM portfolios WHERE user_id = $1 AND stock_code = $2', [u.id, ord.stock_code]);
          } else {
            await client.query('UPDATE portfolios SET quantity = $1, updated_at = CURRENT_TIMESTAMP WHERE user_id = $2 AND stock_code = $3', [
              newQty,
              u.id,
              ord.stock_code,
            ]);
          }
        } else {
          await client.query('ROLLBACK');
          return res.status(400).json({ success: false, message: '올바른 주문 유형(BUY, SELL)이 아닙니다.' });
        }

        // 오직 status = 'HOLD'인 주문만 EXECUTED로 원자적 갱신
        const updateOrd = await client.query(
          `UPDATE orders
           SET status = 'EXECUTED', fds_status = '관리자 승인 체결', rejection_reason = ''
           WHERE id = $1 AND status = 'HOLD'
           RETURNING id`,
          [ord.id]
        );
        if (updateOrd.rows.length === 0) {
          await client.query('ROLLBACK');
          return res.status(409).json({ success: false, code: 'STATE_CONFLICT', message: '주문 상태 전이 충돌: HOLD 상태가 아니어서 승인할 수 없습니다.' });
        }

        await client.query('UPDATE fds_alerts SET status = $1, action_log = $2 WHERE id = $3', ['APPROVED', actionDesc, id]);
      }

      // 조치 2: 보류 주문 거절 (REJECTED) - 오직 HOLD 상태만 거절 가능하며, 체결 완료(EXECUTED) 주문 덮어쓰기 엄격 차단!
      if (actionType === 'REJECTED') {
        if (!alertRow.order_id) {
          await client.query('ROLLBACK');
          return res.status(400).json({ success: false, message: '경보에 연결된 주문 번호가 없습니다.' });
        }

        const ordRes = await client.query('SELECT * FROM orders WHERE id = $1 FOR UPDATE', [alertRow.order_id]);
        if (ordRes.rows.length === 0) {
          await client.query('ROLLBACK');
          return res.status(404).json({ success: false, message: '연결된 주문 원장을 찾을 수 없습니다.' });
        }
        const ord = ordRes.rows[0];

        // 멱등성 검사: 이미 거절 처리된 주문
        if (ord.status === 'REJECTED' && alertRow.status === 'REJECTED') {
          await client.query('ROLLBACK');
          const allAlerts = await query('SELECT * FROM fds_alerts ORDER BY created_at DESC');
          return res.json({
            success: true,
            isIdempotent: true,
            message: '이미 거절 처리 완료된 주문입니다.',
            alerts: allAlerts.rows.map(formatAlert),
          });
        }

        // [P1 결함 수정] 이미 체결 완료(EXECUTED)된 주문은 절대 거절 상태로 덮어쓸 수 없음! (원장 무결성 보장)
        if (ord.status === 'EXECUTED') {
          await client.query('ROLLBACK');
          return res.status(409).json({
            success: false,
            code: 'CANNOT_REJECT_EXECUTED',
            message: '이미 체결 완료(EXECUTED)되어 원장에 반영된 주문은 거절 상태로 덮어쓸 수 없습니다. (원장 정합성 불일치 방지)',
          });
        }
        if (ord.status === 'CANCELLED') {
          await client.query('ROLLBACK');
          return res.status(409).json({
            success: false,
            code: 'CANNOT_REJECT_CANCELLED',
            message: '30분 만료 또는 기 취소(CANCELLED)된 주문은 거절할 수 없습니다.',
          });
        }
        if (ord.status !== 'HOLD') {
          await client.query('ROLLBACK');
          return res.status(409).json({
            success: false,
            code: 'INVALID_ORDER_STATE',
            message: `현재 상태(${ord.status})의 주문은 거절할 수 없습니다. (심사 보류[HOLD] 주문만 거절 가능)`,
          });
        }

        // 오직 status = 'HOLD'인 주문만 REJECTED로 갱신
        const updateOrd = await client.query(
          `UPDATE orders
           SET status = 'REJECTED', fds_status = '관리자 심사 거절 (주문 취소)', rejection_reason = $1
           WHERE id = $2 AND status = 'HOLD'
           RETURNING id`,
          [adminComment || '관리자 수동 심사에 의한 주문 거절', ord.id]
        );
        if (updateOrd.rows.length === 0) {
          await client.query('ROLLBACK');
          return res.status(409).json({ success: false, code: 'STATE_CONFLICT', message: '주문 상태 전이 충돌: HOLD 상태가 아니어서 거절할 수 없습니다.' });
        }

        await client.query('UPDATE fds_alerts SET status = $1, action_log = $2 WHERE id = $3', ['REJECTED', actionDesc, id]);
      }

      // 조치 3: 계좌 긴급 잠금 (LOCKED)
      if (actionType === 'LOCKED') {
        if (alertRow.user_id) {
          await client.query('UPDATE users SET is_locked = TRUE, lock_reason = $1 WHERE id = $2', [
            adminComment || 'FDS 위험 감지에 따른 계좌 긴급 잠금',
            alertRow.user_id,
          ]);
        }
        await client.query('UPDATE fds_alerts SET status = $1, action_log = $2 WHERE id = $3', ['LOCKED', actionDesc, id]);
      }

      // 조치 4: 계좌 잠금 해제 (UNLOCKED) (기능 ID 96)
      if (actionType === 'UNLOCKED') {
        if (!adminComment.trim()) {
          await client.query('ROLLBACK');
          return res.status(400).json({ success: false, message: '계좌 잠금 해제 시 소명 및 해제 사유 입력은 필수입니다.' });
        }
        if (alertRow.user_id) {
          await client.query('UPDATE users SET is_locked = FALSE, lock_reason = $1 WHERE id = $2', ['', alertRow.user_id]);
        }
        await client.query('UPDATE fds_alerts SET status = $1, action_log = $2 WHERE id = $3', ['UNLOCKED', actionDesc, id]);
      }

      // 조치 이력 누적 테이블 기록 (기능 ID 81)
      await client.query(
        `INSERT INTO fds_action_logs (alert_id, order_id, action_type, admin_id, reason)
         VALUES ($1, $2, $3, $4, $5)`,
        [id, alertRow.order_id || null, actionType, adminId, adminComment || actionDesc]
      );

      await client.query('COMMIT');

      const allAlerts = await query('SELECT * FROM fds_alerts ORDER BY created_at DESC');
      res.json({
        success: true,
        message: `${actionType} 조치가 정상적으로 원장 및 DB에 반영되었습니다.`,
        alerts: allAlerts.rows.map(formatAlert),
      });
    } catch (err) {
      await client.query('ROLLBACK');
      console.error('[Admin Action Error]:', err);
      res.status(500).json({ success: false, message: '조치 처리 실패: ' + err.message });
    } finally {
      client.release();
    }
  });

  // -------------------------------------------------------------
  // 11. 관리자 전용: 수학적 원장 전수 대사 실행 (기능 ID 100, 102, 103)
  // -------------------------------------------------------------
  app.post('/api/admin/audit', requireAuth('ADMIN'), async (req, res) => {
    try {
      const auditResult = await runReconciliationAudit();
      res.json({ success: true, ...auditResult });
    } catch (err) {
      console.error('[Admin Audit Error]:', err);
      res.status(500).json({ success: false, message: '전수 대사 실패: ' + err.message });
    }
  });

  // -------------------------------------------------------------
  // 12. 30분 보류 주문 자동 만료 스케줄러 (기능 ID 78)
  // -------------------------------------------------------------
  setInterval(async () => {
    try {
      const expiredRes = await query(
        `UPDATE orders
         SET status = 'CANCELLED', fds_status = '30분 심사 만료로 자동 취소', rejection_reason = '30분 내 미처리'
         WHERE status = 'HOLD' AND hold_expires_at IS NOT NULL AND hold_expires_at < NOW()
         RETURNING id`
      );
      if (expiredRes.rows.length > 0) {
        console.log(`[FDS Cleaner] 30분 초과 보류 주문 ${expiredRes.rows.length}건 자동 취소 처리 완료`);
      }
    } catch (e) {
      // ignore
    }
  }, 60000); // 1분 주기
}
