import React, { useState, useEffect } from 'react';
import { Shield, ShieldAlert, AlertTriangle, CheckCircle, XCircle, Lock, Unlock, Eye, Search, Filter, RefreshCw, ArrowLeft, Activity, Bell, FileSpreadsheet, Bot, CheckCircle2, AlertOctagon } from 'lucide-react';
import { fetchServerAlerts, updateAlertStatus, runServerReconciliation } from '../utils/storage';
import AlertDetailModal from './AlertDetailModal';

export default function AdminFdsPanel({ onBackToTrading, currentUser, onNotify, lastQuoteTime }) {
  const [alerts, setAlerts] = useState([]);
  const [selectedAlert, setSelectedAlert] = useState(null);
  const [riskFilter, setRiskFilter] = useState('ALL'); // ALL, CRITICAL, WARNING, CAUTION
  const [statusFilter, setStatusFilter] = useState('ALL'); // ALL, HOLD, APPROVED, REJECTED, LOCKED, UNLOCKED
  const [searchQuery, setSearchQuery] = useState('');
  const [isAuditing, setIsAuditing] = useState(false);
  const [auditResult, setAuditResult] = useState(null);
  const [auditMessage, setAuditMessage] = useState('원장 전수 대사 대기 중 (실시간 원장 대사 즉시 실행 버튼 클릭)');
  const [feedLatencyMs, setFeedLatencyMs] = useState(26);

  // 실시간 시세 지연 경고 계산 (1분 이상 갱신 없을 시 경고, 기능 ID 98)
  const isFeedDelayed = lastQuoteTime && (Date.now() - lastQuoteTime > 60000);

  const loadAlerts = async () => {
    const res = await fetchServerAlerts();
    if (res.success && res.alerts) {
      setAlerts(res.alerts);
    }
  };

  useEffect(() => {
    loadAlerts();
    const timer = setInterval(loadAlerts, 3000);
    return () => clearInterval(timer);
  }, []);

  const handleAction = async (alertId, actionType, adminComment) => {
    const res = await updateAlertStatus(alertId, actionType, adminComment);
    if (res.success) {
      if (res.alerts) setAlerts(res.alerts);
      else loadAlerts();
      const actionName =
        actionType === 'APPROVED' ? '수동 승인 (원장 체결)' :
        actionType === 'REJECTED' ? '주문 거절 (취소)' :
        actionType === 'UNLOCKED' ? '계좌 잠금 해제' : '계좌 잠금';
      if (onNotify) {
        onNotify(`[FDS 관제] 경보 ${alertId}에 대해 ${actionName} 조치가 성공적으로 원장에 반영되었습니다.`);
      }
    } else {
      alert(`[조치 실패] ${res.message || '서버 오류'}`);
    }
  };

  const handleManualAudit = async () => {
    setIsAuditing(true);
    try {
      const data = await runServerReconciliation();
      setIsAuditing(false);
      if (data.success) {
        setAuditResult(data);
        const count = data.discrepancyCount || 0;
        if (count === 0) {
          setAuditMessage(`수동 전수 대사 완료 (${data.timestamp}): 총 ${data.totalAccounts}개 계좌, 체결 주문 ${data.totalOrders}건 — 원장 일치율 100.00% (오차 0원)`);
        } else {
          setAuditMessage(`[주의] 전수 대사 완료 (${data.timestamp}): 총 ${data.totalAccounts}개 계좌 중 ${count}건의 원장 불일치 계좌 발견`);
        }
        if (onNotify) {
          onNotify(data.message);
        }
      } else {
        setAuditMessage('원장 대사 실패: ' + (data.message || '오류'));
      }
    } catch (err) {
      setIsAuditing(false);
      setAuditMessage('원장 대사 통신 오류: ' + err.message);
    }
  };

  const filteredAlerts = alerts.filter((a) => {
    if (riskFilter !== 'ALL' && a.riskLevel !== riskFilter) return false;
    if (statusFilter !== 'ALL' && a.status !== statusFilter) return false;
    if (searchQuery.trim()) {
      const q = searchQuery.toLowerCase();
      const matchId = a.id?.toLowerCase().includes(q);
      const matchStock = a.stockName?.toLowerCase().includes(q) || a.stockCode?.includes(q);
      const matchUser = a.userId?.toLowerCase().includes(q) || a.accountNo?.includes(q);
      if (!matchId && !matchStock && !matchUser) return false;
    }
    return true;
  });

  const totalCount = alerts.length;
  const holdCount = alerts.filter((a) => a.status === 'HOLD').length;
  const criticalCount = alerts.filter((a) => a.riskLevel === 'CRITICAL').length;
  const lockedCount = alerts.filter((a) => a.status === 'LOCKED').length;

  return (
    <div className="admin-fds-view">
      {/* 관리자 관제 최상단 헤더 */}
      <header className="admin-header">
        <div className="admin-header-left">
          <div className="admin-brand">
            <div className="admin-logo-badge">
              <ShieldAlert size={22} />
            </div>
            <div>
              <div className="admin-title-row">
                <h2>StockShield FDS 관제 센터</h2>
                <span className="live-pulse-badge">
                  <span className="pulse-dot green" />
                  실시간 관제 중 (LIVE)
                </span>
                {isFeedDelayed ? (
                  <span className="feed-status-badge text-warning" style={{ background: '#451a1a', color: '#ff7070', borderColor: '#ff4444' }}>
                    <AlertTriangle size={13} style={{ display: 'inline', marginRight: 4 }} />
                    시세 피드 지연: <strong>60초 초과 경고</strong>
                  </span>
                ) : (
                  <span className="feed-status-badge">
                    시세 피드 지연: <strong>{feedLatencyMs}ms</strong> (정상)
                  </span>
                )}
              </div>
              <p className="admin-subtext">
                초고속 매크로·계정 탈취·허수성 주문 등 자본시장 이상거래 실시간 탐지 및 AI 차단 시스템
              </p>
            </div>
          </div>
        </div>

        <div className="admin-header-right">
          <button
            type="button"
            className="btn-trading-switch"
            onClick={onBackToTrading}
          >
            <ArrowLeft size={16} />
            <span>실시간 트레이딩 대시보드로 돌아가기</span>
          </button>
        </div>
      </header>

      {/* 관제 지표 요약 카드 그리드 */}
      <div className="admin-kpi-grid">
        <div className="admin-kpi-card">
          <div className="kpi-icon-wrap bg-blue">
            <Activity size={20} />
          </div>
          <div className="kpi-info">
            <span className="kpi-title">금일 총 주문 모니터링</span>
            <strong className="kpi-num">1,428건</strong>
            <span className="kpi-trend text-muted">정상 처리율 98.7%</span>
          </div>
        </div>

        <div className="admin-kpi-card">
          <div className="kpi-icon-wrap bg-red">
            <ShieldAlert size={20} />
          </div>
          <div className="kpi-info">
            <span className="kpi-title">FDS 탐지 경보</span>
            <strong className="kpi-num text-red">{totalCount}건</strong>
            <span className="kpi-trend text-red">위험 등급 {criticalCount}건 포함</span>
          </div>
        </div>

        <div className="admin-kpi-card highlight-card">
          <div className="kpi-icon-wrap bg-amber">
            <AlertTriangle size={20} />
          </div>
          <div className="kpi-info">
            <span className="kpi-title">심사 보류 대기 주문</span>
            <strong className="kpi-num text-amber">{holdCount}건</strong>
            <span className="kpi-trend text-amber">
              {holdCount > 0 ? '⚠️ 관리자 승인 대기 중' : '모든 경보 처리 완료'}
            </span>
          </div>
        </div>

        <div className="admin-kpi-card">
          <div className="kpi-icon-wrap bg-purple">
            <Lock size={20} />
          </div>
          <div className="kpi-info">
            <span className="kpi-title">계좌 긴급 잠금 조치</span>
            <strong className="kpi-num text-purple">{lockedCount}개 계좌</strong>
            <span className="kpi-trend text-muted">탈취 의심 계좌 동결</span>
          </div>
        </div>
      </div>

      {/* 원장 정합성 점검 배너 */}
      <div className="integrity-banner">
        <div className="integrity-left">
          <div className="audit-icon-wrap">
            <CheckCircle2 size={18} className={auditResult?.discrepancyCount > 0 ? 'text-amber' : 'text-green'} />
          </div>
          <div>
            <span className="audit-label">원장 무결성 및 정합성 점검 (실시간 수학적 전수 대사):</span>
            <span className="audit-desc">{auditMessage}</span>
          </div>
        </div>
        <button
          type="button"
          className="btn-manual-audit"
          onClick={handleManualAudit}
          disabled={isAuditing}
        >
          <RefreshCw size={13} className={isAuditing ? 'rotating' : ''} />
          <span>{isAuditing ? '전수 대사 검증 중...' : '원장 전수 대사 즉시 실행'}</span>
        </button>
      </div>

      {/* 원장 불일치 계좌 발견 시 상세 내역 표시 */}
      {auditResult?.discrepancies?.length > 0 && (
        <div style={{ background: '#26171b', border: '1px solid #702e38', borderRadius: '8px', padding: '14px 18px', margin: '0 0 16px 0' }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: '8px', color: '#ff7888', fontWeight: 700, fontSize: '13px', marginBottom: '8px' }}>
            <AlertOctagon size={16} />
            <span>원장 정합성 불일치 상세 분석 ({auditResult.discrepancyCount}건)</span>
          </div>
          <div style={{ display: 'flex', flexDirection: 'column', gap: '8px', fontSize: '12px' }}>
            {auditResult.discrepancies.map((d, i) => (
              <div key={i} style={{ background: '#171118', padding: '10px 14px', borderRadius: '6px', borderLeft: '3px solid #ff5566' }}>
                <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: '4px' }}>
                  <strong>계좌: {d.accountNo} ({d.userName} · @{d.userId})</strong>
                  <span style={{ color: '#ff9090' }}>예수금 오차: ₩{d.balanceDiff?.toLocaleString()} (실제: ₩{d.actualBalance?.toLocaleString()} / 대사: ₩{d.expectedBalance?.toLocaleString()})</span>
                </div>
                {d.stockDiscrepancies?.length > 0 && (
                  <div style={{ color: '#c7b0b5', fontSize: '11px' }}>
                    보유주식 오차: {d.stockDiscrepancies.map((s, si) => `${s.stockName}(${s.stockCode}) 오차 ${s.diff}주 (실제: ${s.actualQuantity}주 / 체결원장: ${s.expectedQuantity}주)`).join(', ')}
                  </div>
                )}
              </div>
            ))}
          </div>
        </div>
      )}

      {/* 실시간 경보 스트림 & 필터 바 */}
      <div className="admin-main-section">
        <div className="table-controls-bar">
          <div className="control-group">
            <div className="search-box">
              <Search size={15} />
              <input
                type="text"
                placeholder="경보ID, 계좌번호, 종목명, 사용자 검색..."
                value={searchQuery}
                onChange={(e) => setSearchQuery(e.target.value)}
              />
            </div>

            <div className="filter-pill-group">
              <span className="pill-group-label">위험 등급:</span>
              {['ALL', 'CRITICAL', 'WARNING', 'CAUTION'].map((lvl) => (
                <button
                  key={lvl}
                  type="button"
                  className={`pill-btn ${riskFilter === lvl ? 'active' : ''}`}
                  onClick={() => setRiskFilter(lvl)}
                >
                  {lvl === 'ALL' ? '전체' : lvl}
                </button>
              ))}
            </div>

            <div className="filter-pill-group">
              <span className="pill-group-label">조치 상태:</span>
              {['ALL', 'HOLD', 'APPROVED', 'REJECTED', 'LOCKED'].map((st) => (
                <button
                  key={st}
                  type="button"
                  className={`pill-btn ${statusFilter === st ? 'active' : ''}`}
                  onClick={() => setStatusFilter(st)}
                >
                  {st === 'ALL' ? '전체' :
                   st === 'HOLD' ? '보류' :
                   st === 'APPROVED' ? '승인' :
                   st === 'REJECTED' ? '거절' : '계좌잠금'}
                </button>
              ))}
            </div>
          </div>
        </div>

        {/* FDS 경보 목록 테이블 */}
        <div className="admin-table-wrap">
          <table className="admin-table">
            <thead>
              <tr>
                <th>경보 ID</th>
                <th>발생 시각</th>
                <th>계좌 / 사용자</th>
                <th>대상 종목</th>
                <th>주문 내역</th>
                <th>총 주문 금액</th>
                <th>위험 점수</th>
                <th>주요 이상 징후 (Triggers)</th>
                <th>조치 상태</th>
                <th>관리자 조치</th>
              </tr>
            </thead>
            <tbody>
              {filteredAlerts.length === 0 ? (
                <tr>
                  <td colSpan="10" className="empty-table-cell">
                    조건에 해당하는 FDS 탐지 경보가 없습니다.
                  </td>
                </tr>
              ) : (
                filteredAlerts.map((a) => {
                  const isHold = a.status === 'HOLD';
                  const isCritical = a.riskLevel === 'CRITICAL';

                  return (
                    <tr key={a.id} className={isHold ? 'row-highlight-hold' : ''}>
                      <td>
                        <span className="alert-code">#{a.id}</span>
                      </td>
                      <td className="time-col">{a.timestamp}</td>
                      <td>
                        <div className="user-cell">
                          <strong>{a.userName}</strong>
                          <span className="acc-sub">{a.accountNo}</span>
                        </div>
                      </td>
                      <td>
                        <strong>{a.stockName}</strong>
                        <span className="code-sub">{a.stockCode}</span>
                      </td>
                      <td>
                        <span className={`type-tag ${a.orderType === 'BUY' ? 'buy' : 'sell'}`}>
                          {a.orderType === 'BUY' ? '매수' : '매도'}
                        </span>
                        <span className="qty-tag">{a.quantity?.toLocaleString()}주 @ ₩{a.price?.toLocaleString()}</span>
                      </td>
                      <td className="amount-col">
                        <strong>₩{a.totalAmount?.toLocaleString()}</strong>
                      </td>
                      <td>
                        <div className="risk-score-wrap">
                          <span className={`risk-badge-mini ${a.riskLevel.toLowerCase()}`}>
                            {a.riskScore}점
                          </span>
                          <div className="risk-progress-bar">
                            <div
                              className={`risk-fill ${a.riskLevel.toLowerCase()}`}
                              style={{ width: `${a.riskScore}%` }}
                            />
                          </div>
                        </div>
                      </td>
                      <td className="triggers-cell">
                        <span className="trigger-snippet" title={a.triggers?.join('\n')}>
                          {a.triggers?.[0] || '이상 거래 의심'}
                        </span>
                      </td>
                      <td>
                        <span className={`status-pill ${a.status.toLowerCase()}`}>
                          {a.status === 'HOLD' ? '심사 보류' :
                           a.status === 'APPROVED' ? '승인 체결' :
                           a.status === 'REJECTED' ? '주문 차단' : '계좌 잠금'}
                        </span>
                      </td>
                      <td>
                        <button
                          type="button"
                          className={`btn-table-action ${isHold ? 'btn-urgent' : ''}`}
                          onClick={() => setSelectedAlert(a)}
                        >
                          <Bot size={13} />
                          <span>{isHold ? 'AI 분석 및 조치' : '상세 보기'}</span>
                        </button>
                      </td>
                    </tr>
                  );
                })
              )}
            </tbody>
          </table>
        </div>
      </div>

      {/* 경보 상세 및 AI 분석/조치 모달 (SCR-06) */}
      <AlertDetailModal
        alert={selectedAlert}
        isOpen={Boolean(selectedAlert)}
        onClose={() => setSelectedAlert(null)}
        onAction={handleAction}
      />
    </div>
  );
}
