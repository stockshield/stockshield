-- StockShield Enterprise PostgreSQL Schema & Seed Data (기능정의서 100% 준수)

-- 1. Users Table
CREATE TABLE IF NOT EXISTS users (
  id VARCHAR(50) PRIMARY KEY,
  password_hash VARCHAR(255) NOT NULL,
  name VARCHAR(100) NOT NULL,
  role VARCHAR(20) NOT NULL DEFAULT 'USER', -- 'USER', 'ADMIN'
  email VARCHAR(150),
  phone VARCHAR(50),
  account_no VARCHAR(50) UNIQUE NOT NULL,
  balance BIGINT NOT NULL DEFAULT 10000000,
  seed_amount BIGINT NOT NULL DEFAULT 10000000,
  fds_base_amount BIGINT NOT NULL DEFAULT 500000, -- 기능 ID 10: FDS 기준 주문 금액 기본값 50만원
  fds_level VARCHAR(50) DEFAULT 'Level 1 안심 보안',
  is_locked BOOLEAN DEFAULT FALSE,
  lock_reason TEXT DEFAULT '',
  failed_login_count INTEGER DEFAULT 0, -- 기능 ID 15: 5회 실패 시 잠금
  locked_until TIMESTAMP WITH TIME ZONE, -- 30분 잠금 만료 시각
  created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- 2. Refresh Tokens Table (기능 ID 12, 16, 17: JWT Refresh Token 7일 보관)
CREATE TABLE IF NOT EXISTS refresh_tokens (
  token TEXT PRIMARY KEY,
  user_id VARCHAR(50) REFERENCES users(id) ON DELETE CASCADE,
  expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
  created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- 3. Access Logs Table (기능 ID 13, 14: 접속 IP, User-Agent, 시각 기록 및 FDS 접속이상 연계)
CREATE TABLE IF NOT EXISTS access_logs (
  id BIGSERIAL PRIMARY KEY,
  user_id VARCHAR(50) REFERENCES users(id) ON DELETE CASCADE,
  ip_address VARCHAR(50) NOT NULL,
  user_agent TEXT NOT NULL,
  created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- 4. Watchlists Table
CREATE TABLE IF NOT EXISTS watchlists (
  user_id VARCHAR(50) REFERENCES users(id) ON DELETE CASCADE,
  stock_code VARCHAR(10) NOT NULL,
  created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (user_id, stock_code)
);

-- 5. Portfolios Table (보유 주식 원장)
CREATE TABLE IF NOT EXISTS portfolios (
  user_id VARCHAR(50) REFERENCES users(id) ON DELETE CASCADE,
  stock_code VARCHAR(10) NOT NULL,
  stock_name VARCHAR(100) NOT NULL,
  quantity INTEGER NOT NULL DEFAULT 0,
  avg_price BIGINT NOT NULL DEFAULT 0,
  updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (user_id, stock_code)
);

-- 6. Orders Table (주문 및 체결 원장)
CREATE TABLE IF NOT EXISTS orders (
  id BIGSERIAL PRIMARY KEY,
  user_id VARCHAR(50) REFERENCES users(id) ON DELETE CASCADE,
  idempotency_key VARCHAR(100) UNIQUE, -- 기능 ID 54: 중복 주문 방지 멱등키
  timestamp VARCHAR(20) NOT NULL,
  stock_code VARCHAR(10) NOT NULL,
  stock_name VARCHAR(100) NOT NULL,
  order_type VARCHAR(10) NOT NULL, -- BUY, SELL
  price BIGINT NOT NULL,
  quantity INTEGER NOT NULL,
  total_cost BIGINT NOT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'EXECUTED', -- 'EXECUTED', 'HOLD', 'REJECTED', 'CANCELLED'
  fds_status VARCHAR(100) DEFAULT '정상 승인 (FDS 인증)',
  rejection_reason TEXT DEFAULT '',
  hold_expires_at TIMESTAMP WITH TIME ZONE, -- 기능 ID 78: 30분 보류 만료 시각
  created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- 7. FDS Alerts Table (FDS 이상거래 탐지 경보)
CREATE TABLE IF NOT EXISTS fds_alerts (
  id VARCHAR(50) PRIMARY KEY,
  order_id BIGINT REFERENCES orders(id) ON DELETE SET NULL, -- 먼저 고칠 사항 2: 주문과 경보 직접 연결
  timestamp VARCHAR(20) NOT NULL,
  user_id VARCHAR(50) REFERENCES users(id) ON DELETE CASCADE,
  user_name VARCHAR(100),
  user_ip VARCHAR(50),
  account_no VARCHAR(50),
  stock_code VARCHAR(10) NOT NULL,
  stock_name VARCHAR(100) NOT NULL,
  order_type VARCHAR(10) NOT NULL,
  price BIGINT NOT NULL,
  quantity INTEGER NOT NULL,
  total_amount BIGINT NOT NULL,
  risk_level VARCHAR(20) NOT NULL, -- 'CRITICAL', 'WARNING', 'CAUTION'
  risk_score INTEGER NOT NULL DEFAULT 0, -- 기능 ID 73: 0~49 통과, 50~79 보류, 80이상 차단
  rule_scores JSONB DEFAULT '{}'::jsonb, -- 기능 ID 75: 규칙별 점수 기록 (접속, 금액, 종목, 속도)
  triggers JSONB DEFAULT '[]'::jsonb,
  status VARCHAR(20) NOT NULL DEFAULT 'HOLD', -- 'HOLD', 'APPROVED', 'REJECTED', 'LOCKED'
  action_log TEXT DEFAULT '',
  ai_analysis JSONB DEFAULT '{}'::jsonb,
  created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- 8. FDS Action Logs Table (기능 ID 81: 조치 이력 누적 테이블 - 처리자, 시각, 사유)
CREATE TABLE IF NOT EXISTS fds_action_logs (
  id BIGSERIAL PRIMARY KEY,
  alert_id VARCHAR(50) REFERENCES fds_alerts(id) ON DELETE CASCADE,
  order_id BIGINT REFERENCES orders(id) ON DELETE SET NULL,
  action_type VARCHAR(20) NOT NULL, -- 'HOLD', 'APPROVED', 'REJECTED', 'LOCKED', 'UNLOCKED'
  admin_id VARCHAR(50) NOT NULL,
  reason TEXT NOT NULL,
  created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- 9. Audit Logs Table (기능 ID 100, 102, 103: 원장 전수 대사 및 불일치 상세)
CREATE TABLE IF NOT EXISTS audit_logs (
  id BIGSERIAL PRIMARY KEY,
  audit_type VARCHAR(50) NOT NULL,
  total_accounts INTEGER NOT NULL,
  total_orders INTEGER NOT NULL,
  discrepancies INTEGER NOT NULL DEFAULT 0,
  discrepancy_details JSONB DEFAULT '[]'::jsonb,
  message TEXT NOT NULL,
  created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- 10. Minute Candles Table (기능 ID 30: 1분 분봉 DB 저장)
CREATE TABLE IF NOT EXISTS minute_candles (
  stock_code VARCHAR(10) NOT NULL,
  time_bucket TIMESTAMP WITH TIME ZONE NOT NULL,
  open_price BIGINT NOT NULL,
  high_price BIGINT NOT NULL,
  low_price BIGINT NOT NULL,
  close_price BIGINT NOT NULL,
  volume BIGINT NOT NULL,
  PRIMARY KEY (stock_code, time_bucket)
);

-- 11. Stock Stats Table (기능 ID 28, 29: 종목별 거래량 EWMA 평균 및 표준편차)
CREATE TABLE IF NOT EXISTS stock_stats (
  stock_code VARCHAR(10) PRIMARY KEY,
  ewma_volume DOUBLE PRECISION DEFAULT 0,
  std_volume DOUBLE PRECISION DEFAULT 0,
  last_updated TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- 초기 시드 데이터 삽입
INSERT INTO users (id, password_hash, name, role, email, phone, account_no, balance, seed_amount, fds_base_amount, fds_level, is_locked, lock_reason)
VALUES 
  ('demo', '$2b$10$Sq8cA9R5BDXK9MWnFkGaE.TWZp9lBxJls9IiPT0OtyqxtbpIv07aC', '김투자', 'USER', 'demo@stockshield.io', '010-1234-5678', '501-12-883921', 13083850, 10000000, 500000, 'Level 1 안심 보안', FALSE, ''),
  ('admin', '$2b$10$Sq8cA9R5BDXK9MWnFkGaE.TWZp9lBxJls9IiPT0OtyqxtbpIv07aC', '보안관제팀', 'ADMIN', 'security@stockshield.io', '010-9999-8888', '999-00-112233', 50000000, 50000000, 500000, 'Level 3 최고 관제', FALSE, '')
ON CONFLICT (id) DO UPDATE
SET password_hash = EXCLUDED.password_hash, role = EXCLUDED.role, fds_base_amount = EXCLUDED.fds_base_amount;

-- demo 초기 접속 이력 시드 (기능 ID 13, 14: IP/User-Agent 등록으로 정상 접속 학습)
INSERT INTO access_logs (user_id, ip_address, user_agent)
VALUES 
  ('demo', '127.0.0.1', 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36'),
  ('admin', '127.0.0.1', 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36');

INSERT INTO watchlists (user_id, stock_code)
VALUES 
  ('demo', '005930'),
  ('demo', '000660'),
  ('admin', '005930'),
  ('admin', '000660'),
  ('admin', '373220')
ON CONFLICT (user_id, stock_code) DO NOTHING;

INSERT INTO portfolios (user_id, stock_code, stock_name, quantity, avg_price)
VALUES 
  ('demo', '005930', '삼성전자', 3, 65800)
ON CONFLICT (user_id, stock_code) DO UPDATE 
SET quantity = EXCLUDED.quantity, avg_price = EXCLUDED.avg_price;

-- 초기 3대 주문
INSERT INTO orders (id, user_id, idempotency_key, timestamp, stock_code, stock_name, order_type, price, quantity, total_cost, status, fds_status, rejection_reason)
VALUES 
  (1730700001000, 'demo', 'seed-idemp-1', '09:30:15', '005930', '삼성전자', 'BUY', 57400, 1, 57400, 'EXECUTED', '정상 승인 (FDS 인증)', ''),
  (1730600002000, 'demo', 'seed-idemp-2', '14:22:10', '000660', 'SK하이닉스', 'BUY', 184000, 10, 1840000, 'HOLD', 'FDS 심사 보류 (검토 중)', '금액 이상 판정 (기준 대비 급증)'),
  (1730500003000, 'demo', 'seed-idemp-3', '11:05:40', '035420', 'NAVER', 'SELL', 198000, 50, 9900000, 'REJECTED', 'FDS 차단 (주문 거절)', '위험 점수 80점 초과 (종목 및 접속 이상 감지)')
ON CONFLICT (id) DO NOTHING;

-- 초기 FDS 경보 (주문 ID와 직접 연결)
INSERT INTO fds_alerts (id, order_id, timestamp, user_id, user_name, user_ip, account_no, stock_code, stock_name, order_type, price, quantity, total_amount, risk_level, risk_score, rule_scores, triggers, status, action_log, ai_analysis)
VALUES 
  (
    'ALT-1092', 
    1730600002000,
    '10:14:02', 
    'demo', 
    '김투자', 
    '118.235.*.*', 
    '501-12-883921', 
    '000660', 
    'SK하이닉스', 
    'BUY', 
    184000, 
    10, 
    1840000, 
    'WARNING', 
    65, 
    '{"access": 30, "amount": 35, "stock": 0, "velocity": 0}'::jsonb,
    '["[접속 이상] 신규 IP 및 기기 환경 접속 포착 (+30점)", "[금액 이상] 계좌 평균 주문액 대비 급증 (Z-Score >= 3.0) (+35점)"]'::jsonb, 
    'HOLD', 
    '2026-11-04 10:14:02 FDS 엔진에 의해 자동 심사 보류 (총점 65점)', 
    '{"summary": "회원의 통상 접속 환경과 상이한 환경에서 평소 거래 규모를 상회하는 주문이 접수되었습니다. 계정 도용 가능성이 일부 의심됩니다.", "recommendation": "보류 유지 및 유선 본인확인 후 승인 권고", "confidence": 92}'::jsonb
  ),
  (
    'ALT-1091', 
    1730500003000,
    '10:02:18', 
    'demo', 
    '김투자', 
    '211.58.*.*', 
    '501-12-883921', 
    '035420', 
    'NAVER', 
    'SELL', 
    198000, 
    50, 
    9900000, 
    'CRITICAL', 
    85, 
    '{"access": 30, "amount": 35, "stock": 20, "velocity": 0}'::jsonb,
    '["[접속 이상] 새벽 심야 시간대(02:15) 비정상 접속 (+30점)", "[금액 이상] FDS 기준금액 대비 대규모 전량 매도 (+35점)", "[속도/종목] 비정상 급격 투매 패턴 감지 (+20점)"]'::jsonb, 
    'REJECTED', 
    '2026-11-04 10:02:18 FDS 점수 85점 초과로 자동 주문 차단 처리', 
    '{"summary": "원격지 해외 VPN 접속 후 새벽 시간대에 발생한 비정상 전량 투매입니다. 탈취된 자산의 급격한 유출 시도로 판단됩니다.", "recommendation": "즉시 주문 차단 및 계좌 잠금(LOCKED) 유지", "confidence": 96}'::jsonb
  )
ON CONFLICT (id) DO NOTHING;
