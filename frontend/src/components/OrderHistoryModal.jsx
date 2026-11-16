import React, { useState, useEffect } from 'react';
import {
  X,
  Clock,
  ArrowUpRight,
  ArrowDownLeft,
  ShieldCheck,
  XCircle,
  CheckCircle2,
  RefreshCw,
  ChevronLeft,
  ChevronRight,
} from 'lucide-react';
import { fetchAccountOrders, adaptOrder } from '../utils/storage';

export default function OrderHistoryModal({ isOpen, onClose, currentUser, onSelectStock }) {
  const [filter, setFilter] = useState('ALL'); // ALL, FILLED, REJECTED
  const [page, setPage] = useState(0);
  const [size] = useState(20);
  const [orders, setOrders] = useState([]);
  const [totalElements, setTotalElements] = useState(0);
  const [totalPages, setTotalPages] = useState(0);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');

  const loadOrders = async (targetPage = page, targetFilter = filter) => {
    if (!currentUser || currentUser.role === 'ADMIN') return;
    setLoading(true);
    setError('');
    try {
      const ordStatCd = targetFilter === 'ALL' ? null : targetFilter;
      const res = await fetchAccountOrders({ page: targetPage, size, ordStatCd });
      if (res.ok && res.data) {
        const list = Array.isArray(res.data.content)
          ? res.data.content.map(adaptOrder).filter(Boolean)
          : [];
        setOrders(list);
        setTotalElements(Number(res.data.totalElements ?? list.length));
        setTotalPages(Number(res.data.totalPages ?? 1));
      } else {
        setError(res.message || '주문 내역을 불러오지 못했습니다.');
      }
    } catch (err) {
      setError('주문 내역 조회 중 서버 통신 오류가 발생했습니다.');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    if (!isOpen) return;
    setPage(0);
    loadOrders(0, filter);
  }, [isOpen, filter, currentUser?.id]);

  if (!isOpen) return null;

  const filledCount = orders.filter((o) => o.ordStatCd === 'FILLED' || o.status === 'EXECUTED').length;
  const rejectedCount = orders.filter((o) => o.ordStatCd === 'REJECTED' || o.status === 'REJECTED').length;
  const totalTradingVolume = orders
    .filter((o) => o.ordStatCd === 'FILLED' || o.status === 'EXECUTED')
    .reduce((acc, cur) => acc + Number(cur.trdAmt ?? cur.totalCost ?? 0), 0);

  const handlePageChange = (nextPage) => {
    if (nextPage < 0 || nextPage >= Math.max(1, totalPages)) return;
    setPage(nextPage);
    loadOrders(nextPage, filter);
  };

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
                {currentUser?.name} 님 ({currentUser?.accountNo || '-'})의 시장가 주문 및 거절 사유 기록
              </p>
            </div>
          </div>
          <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
            <button
              type="button"
              className="btn-secondary"
              style={{ padding: '6px 10px', fontSize: '12px' }}
              onClick={() => loadOrders(page, filter)}
              disabled={loading}
              title="새로고침"
            >
              <RefreshCw size={14} className={loading ? 'rotating' : ''} />
            </button>
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
        </div>

        {/* 요약 KPI 카드 */}
        <div className="order-stats-grid">
          <div className="stat-card">
            <span className="stat-label">조회 조건 총 주문</span>
            <strong className="stat-value">{totalElements.toLocaleString()}건</strong>
          </div>
          <div className="stat-card">
            <span className="stat-label">현재 페이지 체결(FILLED)</span>
            <strong className="stat-value text-green">{filledCount}건</strong>
          </div>
          <div className="stat-card">
            <span className="stat-label">현재 페이지 거절(REJECTED)</span>
            <strong className="stat-value text-red">{rejectedCount}건</strong>
          </div>
          <div className="stat-card">
            <span className="stat-label">현재 페이지 체결 금액</span>
            <strong className="stat-value">₩{totalTradingVolume.toLocaleString('ko-KR')}</strong>
          </div>
        </div>

        {/* 필터 탭 (백엔드 명세: 전체 / FILLED / REJECTED) */}
        <div className="order-filter-bar">
          <div className="filter-tabs">
            <button
              type="button"
              className={`filter-tab ${filter === 'ALL' ? 'active' : ''}`}
              onClick={() => setFilter('ALL')}
            >
              전체
            </button>
            <button
              type="button"
              className={`filter-tab ${filter === 'FILLED' ? 'active' : ''}`}
              onClick={() => setFilter('FILLED')}
            >
              <CheckCircle2 size={13} /> 체결 완료 (FILLED)
            </button>
            <button
              type="button"
              className={`filter-tab ${filter === 'REJECTED' ? 'active' : ''}`}
              onClick={() => setFilter('REJECTED')}
            >
              <XCircle size={13} /> 주문 거절 (REJECTED)
            </button>
          </div>
        </div>

        {error && (
          <div style={{ margin: '0 20px 12px', padding: '10px 14px', background: '#3b161c', border: '1px solid #ff4d64', borderRadius: '6px', color: '#ff8596', fontSize: '12px' }}>
            {error}
          </div>
        )}

        {/* 주문 내역 목록 테이블 */}
        <div className="order-table-wrap">
          {loading ? (
            <div className="empty-order-state">
              <RefreshCw size={32} className="empty-icon rotating" />
              <p>주문 내역을 불러오는 중입니다...</p>
            </div>
          ) : orders.length === 0 ? (
            <div className="empty-order-state">
              <Clock size={36} className="empty-icon" />
              <p>해당 조건의 주문 내역이 없습니다.</p>
            </div>
          ) : (
            <table className="order-table">
              <thead>
                <tr>
                  <th>주문번호 / 시각</th>
                  <th>종목</th>
                  <th>구분</th>
                  <th>체결 단가</th>
                  <th>주문 수량</th>
                  <th>체결 금액</th>
                  <th>주문 상태</th>
                  <th>거절 사유 코드 / 메시지</th>
                </tr>
              </thead>
              <tbody>
                {orders.map((ord) => {
                  const isBuy = (ord.ordDvCd || ord.orderType) === 'BUY';
                  const isRejected = ord.ordStatCd === 'REJECTED' || ord.status === 'REJECTED';

                  return (
                    <tr key={ord.ordNo || ord.id} className={isRejected ? 'row-rejected' : ''}>
                      <td className="time-col">
                        <div style={{ fontWeight: 600, color: '#cbd8ea', fontSize: '11px' }}>{ord.ordNo || ord.id}</div>
                        <div style={{ fontSize: '11px', color: '#7e92ab' }}>{ord.regTs || ord.timestamp}</div>
                      </td>
                      <td>
                        <button
                          type="button"
                          className="stock-link-btn"
                          onClick={() => {
                            if (onSelectStock) onSelectStock(ord.tckCd || ord.stockCode);
                            onClose();
                          }}
                          title="차트로 이동"
                        >
                          <strong>{ord.tckNm || ord.stockName}</strong>
                          <span className="code-sub">{ord.tckCd || ord.stockCode}</span>
                        </button>
                      </td>
                      <td>
                        <span className={`type-badge ${isBuy ? 'buy' : 'sell'}`}>
                          {isBuy ? <ArrowUpRight size={12} /> : <ArrowDownLeft size={12} />}
                          {isBuy ? '매수' : '매도'}
                        </span>
                      </td>
                      <td className="price-col">
                        {ord.ordPrc ? `₩${Number(ord.ordPrc).toLocaleString('ko-KR')}` : '-'}
                      </td>
                      <td className="qty-col">{Number(ord.ordQty ?? ord.quantity ?? 0).toLocaleString('ko-KR')}주</td>
                      <td className="total-col">
                        <strong>
                          {ord.trdAmt ? `₩${Number(ord.trdAmt).toLocaleString('ko-KR')}` : '-'}
                        </strong>
                      </td>
                      <td>
                        {isRejected ? (
                          <span className="status-pill rejected">
                            <XCircle size={12} /> 거절 (REJECTED)
                          </span>
                        ) : (
                          <span className="status-pill executed">
                            <ShieldCheck size={12} /> 체결 (FILLED)
                          </span>
                        )}
                      </td>
                      <td className="reason-col">
                        {isRejected ? (
                          <div>
                            <strong style={{ color: '#ff7b89', fontSize: '11px', display: 'block' }}>
                              {ord.rjctRsnCd || 'REJECTED'}
                            </strong>
                            <span className="reason-text text-amber">
                              {ord.rjctRsnMsg || ord.rejectionReason || '주문 거절'}
                            </span>
                          </div>
                        ) : (
                          <span className="reason-text text-muted">정상 시장가 체결</span>
                        )}
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          )}
        </div>

        <div className="modal-footer" style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
            <button
              type="button"
              className="btn-secondary"
              disabled={page <= 0 || loading}
              onClick={() => handlePageChange(page - 1)}
              style={{ padding: '5px 10px', fontSize: '12px' }}
            >
              <ChevronLeft size={14} /> 이전
            </button>
            <span style={{ fontSize: '12px', color: '#9cb3d0' }}>
              {totalPages > 0 ? `${page + 1} / ${totalPages} 페이지` : '1 / 1 페이지'}
            </span>
            <button
              type="button"
              className="btn-secondary"
              disabled={page + 1 >= totalPages || loading}
              onClick={() => handlePageChange(page + 1)}
              style={{ padding: '5px 10px', fontSize: '12px' }}
            >
              다음 <ChevronRight size={14} />
            </button>
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
