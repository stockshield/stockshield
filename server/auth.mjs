// StockShield JWT 및 비밀번호 보안 인증 모듈
// 기준: 기능정의서 USR_AUTH_001, USR_AUTH_002, USR_AUTH_003

import jwt from 'jsonwebtoken';
import bcrypt from 'bcryptjs';
import dotenv from 'dotenv';
import { query } from './db.mjs';

dotenv.config();

const JWT_SECRET = process.env.JWT_SECRET || 'stockshield-secret-access-token-key-2026';
const JWT_REFRESH_SECRET = process.env.JWT_REFRESH_SECRET || 'stockshield-secret-refresh-token-key-2026';

// 8자 이상, 영문·숫자·특수문자 포함 검증 (기능 ID 7)
export const PASSWORD_REGEX = /^(?=.*[A-Za-z])(?=.*\d)(?=.*[!@#$%^&*()_+\-=[\]{};':"\\|,.<>/?]).{8,}$/;

export function validatePassword(password) {
  if (!password || typeof password !== 'string') return false;
  return PASSWORD_REGEX.test(password);
}

export function hashPassword(plainPassword) {
  return bcrypt.hashSync(plainPassword, 10);
}

export function comparePassword(plainPassword, hashedPassword) {
  if (!plainPassword || !hashedPassword) return false;
  return bcrypt.compareSync(plainPassword, hashedPassword);
}

export function generateTokens(user) {
  const payload = {
    id: user.id,
    name: user.name,
    role: user.role || 'USER',
    accountNo: user.account_no || user.accountNo,
  };

  // Access Token (30분 만료)
  const accessToken = jwt.sign(payload, JWT_SECRET, { expiresIn: '30m' });
  // Refresh Token (7일 만료)
  const refreshToken = jwt.sign({ id: user.id }, JWT_REFRESH_SECRET, { expiresIn: '7d' });

  return { accessToken, refreshToken };
}

export function verifyAccessToken(token) {
  try {
    return jwt.verify(token, JWT_SECRET);
  } catch (err) {
    return null;
  }
}

export function verifyRefreshToken(token) {
  try {
    return jwt.verify(token, JWT_REFRESH_SECRET);
  } catch (err) {
    return null;
  }
}

/**
 * 인증 및 권한 검증 미들웨어
 * @param {'USER' | 'ADMIN' | null} requiredRole 필요한 최소 권한
 */
export function requireAuth(requiredRole = null) {
  return async (req, res, next) => {
    const authHeader = req.headers.authorization;
    if (!authHeader || !authHeader.startsWith('Bearer ')) {
      return res.status(401).json({
        success: false,
        code: 'UNAUTHORIZED',
        message: '인증 토큰이 제공되지 않았습니다. 로그인 후 다시 시도해주세요.',
      });
    }

    const token = authHeader.split(' ')[1];
    const decoded = verifyAccessToken(token);

    if (!decoded) {
      return res.status(401).json({
        success: false,
        code: 'TOKEN_EXPIRED',
        message: '인증 토큰이 유효하지 않거나 만료되었습니다. 토큰을 갱신해주세요.',
      });
    }

    // DB 최신 계정 상태 조회 (잠금 여부 및 역할 확인)
    const userRes = await query('SELECT id, role, is_locked, lock_reason FROM users WHERE id = $1', [decoded.id]);
    if (userRes.rows.length === 0) {
      return res.status(401).json({ success: false, code: 'USER_NOT_FOUND', message: '존재하지 않는 회원입니다.' });
    }

    const dbUser = userRes.rows[0];

    // 관리자 전용 API 접근 제어 (기능 ID 19, 20, 21)
    if (requiredRole === 'ADMIN' && dbUser.role !== 'ADMIN') {
      return res.status(403).json({
        success: false,
        code: 'FORBIDDEN',
        message: '관리자 전용 관제 화면/API입니다. 접근 권한(403)이 없습니다.',
      });
    }

    req.user = {
      ...decoded,
      role: dbUser.role,
      isLocked: dbUser.is_locked,
      lockReason: dbUser.lock_reason,
    };

    next();
  };
}
