// StockShield FDS (이상금융거래 탐지 시스템) 규칙 기반 정밀 평가 엔진
// 기준: 기능정의서 FDS_DET_001, FDS_ACT_001

import { query } from './db.mjs';

/**
 * FDS 4대 핵심 규칙 기반 정밀 위험 점수 계산
 * @param {Object} params
 * @param {string} params.userId
 * @param {number} params.totalCost
 * @param {string} params.stockCode
 * @param {number} params.userBalance
 * @param {string} params.ipAddress
 * @param {string} params.userAgent
 * @param {number} [params.stockVolumeRatio] 종목 거래량 급등 비율 (기본 1.0)
 * @returns {Promise<{
 *   riskScore: number,
 *   ruleScores: { access: number, amount: number, stock: number, velocity: number },
 *   triggers: string[],
 *   verdict: 'PASS' | 'HOLD' | 'BLOCK',
 *   riskLevel: 'CAUTION' | 'WARNING' | 'CRITICAL'
 * }>}
 */
export async function evaluateOrderFds({
  userId,
  totalCost,
  stockCode,
  userBalance,
  ipAddress = '127.0.0.1',
  userAgent = '',
  stockVolumeRatio = 1.0,
}) {
  const triggers = [];
  const ruleScores = {
    access: 0,
    amount: 0,
    stock: 0,
    velocity: 0,
  };

  // -------------------------------------------------------------
  // 규칙 1: 접속 이상 판단 (+30점)
  // 처음 보는 IP·기기 또는 새벽(00:00~06:00 KST) 접속
  // -------------------------------------------------------------
  const now = new Date();
  // 한국 시간(KST) 기준 시간 계산
  const kstHours = (now.getUTCHours() + 9) % 24;
  const isDawn = kstHours >= 0 && kstHours < 6;

  const accessHistory = await query(
    'SELECT ip_address, user_agent FROM access_logs WHERE user_id = $1 ORDER BY created_at DESC LIMIT 50',
    [userId]
  );

  const isKnownIp = accessHistory.rows.some((r) => r.ip_address === ipAddress);
  const isKnownDevice = userAgent ? accessHistory.rows.some((r) => r.user_agent === userAgent) : true;
  const isSuspiciousAccess = (!isKnownIp && accessHistory.rows.length > 0) || (!isKnownDevice && accessHistory.rows.length > 0) || isDawn;

  if (isSuspiciousAccess) {
    ruleScores.access = 30;
    if (isDawn) {
      triggers.push(`[접속 이상] 심야/새벽 시간대(${kstHours}시) 거래 접속 포착 (+30점)`);
    } else {
      triggers.push(`[접속 이상] 미등록 신규 IP(${ipAddress}) 또는 낯선 기기 환경 접속 감지 (+30점)`);
    }
  }

  // -------------------------------------------------------------
  // 규칙 2: 금액 이상 판단 (+35점)
  // 주문 금액이 계좌 평균 주문 금액 대비 Z-Score 3.0 이상이거나,
  // 기준 주문금액(50만원) 대비 급증
  // -------------------------------------------------------------
  const pastOrdersRes = await query(
    'SELECT total_cost FROM orders WHERE user_id = $1 AND status = $2',
    [userId, 'EXECUTED']
  );

  let isAmountOutlier = false;
  if (pastOrdersRes.rows.length >= 3) {
    const costs = pastOrdersRes.rows.map((r) => Number(r.total_cost));
    const mean = costs.reduce((a, b) => a + b, 0) / costs.length;
    const variance = costs.reduce((a, b) => a + Math.pow(b - mean, 2), 0) / costs.length;
    const stdDev = Math.sqrt(variance) || 1;
    const zScore = (totalCost - mean) / stdDev;

    if (zScore >= 3.0) {
      isAmountOutlier = true;
      triggers.push(`[금액 이상] 계좌 평균 대비 비정상 급증 (Z-Score: ${zScore.toFixed(2)} >= 3.0) (+35점)`);
    }
  } else {
    // 과거 체결 이력이 부족한 경우 계좌의 FDS 기준 주문금액(기본 50만원)의 10배인 500만원 초과 시 이상 감지
    const userRow = await query('SELECT fds_base_amount FROM users WHERE id = $1', [userId]);
    const baseAmount = Number(userRow.rows[0]?.fds_base_amount || 500000);
    if (totalCost >= baseAmount * 10) {
      isAmountOutlier = true;
      triggers.push(`[금액 이상] FDS 기준 주문금액(₩${baseAmount.toLocaleString()}) 대비 10배 초과 대량 주문 (+35점)`);
    }
  }

  if (isAmountOutlier) {
    ruleScores.amount = 35;
  }

  // -------------------------------------------------------------
  // 규칙 3: 종목 이상 판단 (+25점)
  // 거래량이 급등한 종목(stockVolumeRatio >= 2.0 또는 등락폭 큼)에 예수금의 50% 이상을 일시 주문
  // -------------------------------------------------------------
  const isVolumeSurge = stockVolumeRatio >= 2.0 || totalCost >= 30000000;
  const isHeavyWeight = userBalance > 0 && totalCost >= userBalance * 0.5;

  if (isVolumeSurge && isHeavyWeight) {
    ruleScores.stock = 25;
    triggers.push(`[종목 이상] 급등/변동성 종목에 보유 예수금의 50% 이상 집중 주문 (+25점)`);
  }

  // -------------------------------------------------------------
  // 규칙 4: 속도 이상 판단 (+20점)
  // 최근 1분(60초) 동안 주문이 5건 이상인 경우 (초단타 매크로)
  // -------------------------------------------------------------
  const recentOrdersRes = await query(
    `SELECT count(*) FROM orders
     WHERE user_id = $1 AND created_at >= NOW() - INTERVAL '60 seconds'`,
    [userId]
  );
  const recentOrderCount = Number(recentOrdersRes.rows[0]?.count || 0);

  if (recentOrderCount >= 5) {
    ruleScores.velocity = 20;
    triggers.push(`[속도 이상] 최근 1분 내 ${recentOrderCount}건 초고속 주문 집중 (매크로 의심) (+20점)`);
  }

  // -------------------------------------------------------------
  // 총점 및 판정 경계 (기능 ID 73)
  // 0~49: 통과 (PASS)
  // 50~79: 보류 (HOLD)
  // 80 이상: 차단 (BLOCK)
  // -------------------------------------------------------------
  const riskScore = ruleScores.access + ruleScores.amount + ruleScores.stock + ruleScores.velocity;

  let verdict = 'PASS';
  let riskLevel = 'CAUTION';

  if (riskScore >= 80) {
    verdict = 'BLOCK';
    riskLevel = 'CRITICAL';
  } else if (riskScore >= 50) {
    verdict = 'HOLD';
    riskLevel = 'WARNING';
  } else if (riskScore > 0) {
    verdict = 'PASS';
    riskLevel = 'CAUTION';
  }

  return {
    riskScore,
    ruleScores,
    triggers,
    verdict,
    riskLevel,
  };
}
