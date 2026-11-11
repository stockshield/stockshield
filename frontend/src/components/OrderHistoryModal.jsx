import React, { useState } from 'react';
import { X, Clock, ArrowUpRight, ArrowDownLeft, ShieldCheck, AlertTriangle, XCircle, CheckCircle2, Filter } from 'lucide-react';

export default function OrderHistoryModal({ isOpen, onClose, currentUser, onSelectStock }) {
  const [filter, setFilter] = useState('ALL'); // ALL, EXECUTED, HOLD, REJECTED

  if (!isOpen) return null;

  const orders = currentUser?.orderHistory || [];

  const filteredOrders = orders.filter((o) => {
    if (filter === 'ALL') return true;
    if (filter === 'EXECUTED') return o.status === 'EXECUTED' || (!o.status && o.fdsStatus?.includes('승인'));
    if (filter === 'HOLD') return o.status === 'HOLD' || o.fdsStatus?.includes('보류');
    if (filter === 'REJECTED') return o.status === 'REJECTED' || o.fdsStatus?.includes('거절') || o.fdsStatus?.includes('차단');
    return true;
  });

  const executedCount = orders.filter((o) => o.status === 'EXECUTED' || (!o.status && o.fdsStatus?.includes('승인'))).length;
  const holdCount = orders.filter((o) => o.status === 'HOLD' || o.fdsStatus?.includes('보류')).length;
  const rejectedCount = orders.filter((o) => o.status === 'REJECTED' || o.fdsStatus?.includes('거절')).length;

  const totalTradingVolume = orders
    .filter((o) => o.status === 'EXECUTED' || (!o.status && o.fdsStatus?.includes('승인')))
    .reduce((acc, cur) => acc + (cur.totalCost || 0), 0);

  return (
    <div className="modal-overlay" onClick={onClose} role="dialog" aria-modal="true">
      <div className="modal-card order-history-modal" onClick={(e) => e.stopPropagation()}>
        <div className="modal-header">
          <div className="modal-title-wrap">
            <div className="icon-badge">
              <Clock size={20} className="primary-icon" />
            </div>
            <div>
              <h3>주문 및 체결 내역</h3>
              <p className="modal-subtitle">
                {currentUser?.name} 님 ({currentUser?.accountNo || '501-12-883921'})의 실시간 모의 주문 및 FDS 심사 기록
              </p>
            </div>
          </div>
          <button
            type="button"
            className="close-btn"
            onClick={onClose}
            aria-label="창 닫기"
            title="창 닫기 (ESC)"
          >
            <X size={20} strokeWidth={2.3} />
          </button>
        </div>

        {/* 요약 KPI 카드 */}
        <div className="order-stats-grid">
          <div className="stat-card">
            <span className="stat-label">총 주문</span>
            <strong className="stat-value">{orders.length}건</strong>
          </div>
          <div className="stat-card">
            <span className="stat-label">체결 완료</span>
            <strong className="stat-value text-green">{executedCount}건</strong>
          </div>
          <div className="stat-card">
            <span className="stat-label">FDS 심사 보류</span>
            <strong className="stat-value text-amber">{holdCount}건</strong>
          </div>
          <div className="stat-card">
            <span className="stat-label">총 체결 금액</span>
            <strong className="stat-value">₩{totalTradingVolume.toLocaleString()}</strong>
          </div>
        </div>

        {/* 필터 탭 */}
        <div className="order-filter-bar">
          <div className="filter-tabs">
            <button
              type="button"
              className={`filter-tab ${filter === 'ALL' ? 'active' : ''}`}
              onClick={() => setFilter('ALL')}
            >
              전체 ({orders.length})
            </button>
            <button
              type="button"
              className={`filter-tab ${filter === 'EXECUTED' ? 'active' : ''}`}
              onClick={() => setFilter('EXECUTED')}
            >
              <CheckCircle2 size={13} /> 체결 완료 ({executedCount})
            </button>
            <button
              type="button"
              className={`filter-tab ${filter === 'HOLD' ? 'active' : ''}`}
              onClick={() => setFilter('HOLD')}
            >
              <AlertTriangle size={13} /> FDS 보류 ({holdCount})
            </button>
            <button
              type="button"
              className={`filter-tab ${filter === 'REJECTED' ? 'active' : ''}`}
              onClick={() => setFilter('REJECTED')}
            >
              <XCircle size={13} /> 차단/거절 ({rejectedCount})
            </button>
          </div>
        </div>

        {/* 주문 내역 목록 테이블 */}
        <div className="order-table-wrap">
          {filteredOrders.length === 0 ? (
            <div className="empty-order-state">
              <Clock size={36} className="empty-icon" />
              <p>해당 조건의 주문 내역이 없습니다.</p>
            </div>
          ) : (
            <table className="order-table">
              <thead>
                <tr>
                  <th>주문 시각</th>
                  <th>종목</th>
                  <th>구분</th>
                  <th>주문 단가</th>
                  <th>수량</th>
                  <th>총 주문 금액</th>
                  <th>체결 및 FDS 상태</th>
                  <th>비고 / 거절 사유</th>
                </tr>
              </thead>
              <tbody>
                {filteredOrders.map((ord) => {
                  const isBuy = ord.orderType === 'BUY';
                  const isHold = ord.status === 'HOLD' || ord.fdsStatus?.includes('보류');
                  const isRejected = ord.status === 'REJECTED' || ord.fdsStatus?.includes('거절') || ord.fdsStatus?.includes('차단');

                  return (
                    <tr key={ord.id} className={isHold ? 'row-hold' : isRejected ? 'row-rejected' : ''}>
                      <td className="time-col">{ord.timestamp}</td>
                      <td>
                        <button
                          type="button"
                          className="stock-link-btn"
                          onClick={() => {
                            if (onSelectStock) onSelectStock(ord.stockCode);
                            onClose();
                          }}
                          title="차트로 이동"
                        >
                          <strong>{ord.stockName}</strong>
                          <span className="code-sub">{ord.stockCode}</span>
                        </button>
                      </td>
                      <td>
                        <span className={`type-badge ${isBuy ? 'buy' : 'sell'}`}>
                          {isBuy ? <ArrowUpRight size={12} /> : <ArrowDownLeft size={12} />}
                          {isBuy ? '매수' : '매도'}
                        </span>
                      </td>
                      <td className="price-col">₩{ord.price?.toLocaleString()}</td>
                      <td className="qty-col">{ord.quantity?.toLocaleString()}주</td>
                      <td className="total-col">
                        <strong>₩{ord.totalCost?.toLocaleString()}</strong>
                      </td>
                      <td>
                        {isHold ? (
                          <span className="status-pill hold">
                            <AlertTriangle size={12} /> FDS 심사 보류
                          </span>
                        ) : isRejected ? (
                          <span className="status-pill rejected">
                            <XCircle size={12} /> 주문 차단/거절
                          </span>
                        ) : (
                          <span className="status-pill executed">
                            <ShieldCheck size={12} /> 체결 완료 (정상)
                          </span>
                        )}
                      </td>
                      <td className="reason-col">
                        {ord.rejectionReason ? (
                          <span className="reason-text text-amber">{ord.rejectionReason}</span>
                        ) : (
                          <span className="reason-text text-muted">정상 승인</span>
                        )}
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          )}
        </div>

        <div className="modal-footer">
          <div className="security-notice">
            <ShieldCheck size={14} className="text-blue" />
            <span>모든 주문은 실시간 FDS 이상거래 탐지 엔진의 사전 보안 검증을 거쳐 안전하게 기록됩니다.</span>
          </div>
          <button type="button" className="btn-secondary" onClick={onClose} title="창 닫기">
            <X size={15} strokeWidth={2.3} />
            <span>닫기</span>
          </button>
        </div>
      </div>
    </div>
  );
}
