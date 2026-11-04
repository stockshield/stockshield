// StockShield 원장 정합성 전수 대사(Reconciliation) 엔진
// 기준: 기능정의서 ADM_REC_001 (계좌별 예수금과 보유 수량이 체결 원장 합계와 맞는지 수학적 전수 검증)

import { query } from './db.mjs';

export async function runReconciliationAudit() {
  const usersRes = await query('SELECT * FROM users ORDER BY created_at ASC');
  const allOrdersRes = await query("SELECT * FROM orders WHERE status = 'EXECUTED' ORDER BY created_at ASC");
  const allPortfoliosRes = await query('SELECT * FROM portfolios');

  const discrepancies = [];
  const users = usersRes.rows;
  const executedOrders = allOrdersRes.rows;
  const portfolios = allPortfoliosRes.rows;

  for (const user of users) {
    const userOrders = executedOrders.filter((o) => o.user_id === user.id);
    const userPortfolios = portfolios.filter((p) => p.user_id === user.id);

    // 1. 예수금 대사: 초기 지원금(seed_amount) - 매수 총액 + 매도 총액
    const initialSeed = Number(user.seed_amount || 10000000);
    let calculatedBalance = initialSeed;

    for (const ord of userOrders) {
      const cost = Number(ord.total_cost);
      if (ord.order_type === 'BUY') {
        calculatedBalance -= cost;
      } else if (ord.order_type === 'SELL') {
        calculatedBalance += cost;
      }
    }

    const actualBalance = Number(user.balance);
    const balanceDiff = actualBalance - calculatedBalance;

    // 2. 보유 주식 수량 대사: 매수 수량 합 - 매도 수량 합
    const stockQtyMap = {};
    for (const ord of userOrders) {
      const qty = Number(ord.quantity);
      if (!stockQtyMap[ord.stock_code]) {
        stockQtyMap[ord.stock_code] = { expected: 0, stockName: ord.stock_name };
      }
      if (ord.order_type === 'BUY') {
        stockQtyMap[ord.stock_code].expected += qty;
      } else if (ord.order_type === 'SELL') {
        stockQtyMap[ord.stock_code].expected -= qty;
      }
    }

    const stockDiscrepancies = [];
    for (const [code, info] of Object.entries(stockQtyMap)) {
      const foundPort = userPortfolios.find((p) => p.stock_code === code);
      const actualQty = foundPort ? Number(foundPort.quantity) : 0;
      if (actualQty !== info.expected) {
        stockDiscrepancies.push({
          stockCode: code,
          stockName: info.stockName,
          expectedQuantity: info.expected,
          actualQuantity: actualQty,
          diff: actualQty - info.expected,
        });
      }
    }

    if (balanceDiff !== 0 || stockDiscrepancies.length > 0) {
      discrepancies.push({
        userId: user.id,
        userName: user.name,
        accountNo: user.account_no,
        expectedBalance: calculatedBalance,
        actualBalance,
        balanceDiff,
        stockDiscrepancies,
      });
    }
  }

  const timeStr = new Date().toTimeString().split(' ')[0];
  const totalAccounts = users.length;
  const totalOrders = executedOrders.length;
  const discrepancyCount = discrepancies.length;

  let message = '';
  if (discrepancyCount === 0) {
    message = `원장 전수 대사 완료 (${timeStr}): 총 ${totalAccounts}개 계좌 잔고 및 ${totalOrders}건 체결 원장 일치율 100.00% (오차 0원)`;
  } else {
    message = `[주의] 원장 전수 대사 완료 (${timeStr}): 총 ${totalAccounts}개 계좌 중 ${discrepancyCount}건의 원장 불일치 계좌 발견`;
  }

  const logRes = await query(
    `INSERT INTO audit_logs (audit_type, total_accounts, total_orders, discrepancies, discrepancy_details, message)
     VALUES ($1, $2, $3, $4, $5, $6)
     RETURNING *`,
    ['RECONCILIATION', totalAccounts, totalOrders, discrepancyCount, JSON.stringify(discrepancies), message]
  );

  return {
    auditLog: logRes.rows[0],
    totalAccounts,
    totalOrders,
    discrepancyCount,
    discrepancies,
    message,
    timestamp: timeStr,
  };
}
