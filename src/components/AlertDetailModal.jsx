import React, { useState } from 'react';
import { X, ShieldAlert, AlertTriangle, CheckCircle, XCircle, Lock, Unlock, Bot, RefreshCw, Cpu, User, FileText, ArrowRight } from 'lucide-react';

export default function AlertDetailModal({ alert, isOpen, onClose, onAction }) {
  const [adminComment, setAdminComment] = useState('');
  const [isAiReanalyzing, setIsAiReanalyzing] = useState(false);
  const [currentAi, setCurrentAi] = useState(alert?.aiAnalysis);

  if (!isOpen || !alert) return null;

  const isHold = alert.status === 'HOLD';
  const isApproved = alert.status === 'APPROVED';
  const isRejected = alert.status === 'REJECTED';
  const isLocked = alert.status === 'LOCKED';

  const handleAiRefresh = () => {
    setIsAiReanalyzing(true);
    setTimeout(() => {
      setIsAiReanalyzing(false);
      setCurrentAi({
        summary: `[재분석 완료] ${alert.stockName} 주문에 대한 2차 심층 분석 결과입니다. 단시간 내 반복 진입 및 호가 집중도가 95% 이상으로 여전히 매크로 패턴이 강하게 유지되고 있습니다.`,
        recommendation: alert.riskScore >= 90 ? '즉시 주문 거절 및 계좌 잠금 지속 권고' : '소명 접수 후 조건부 수동 승인 권고',
        confidence: 96
      });
    }, 900);
  };

  const handleActionClick = (actionType) => {
    if (onAction) {
      onAction(alert.id, actionType, adminComment);
    }
    setAdminComment('');
    onClose();
  };

  const aiData = currentAi || alert.aiAnalysis || {
    summary: '비정상적 고속 주문 또는 금액 급증으로 FDS 룰 엔진에 의해 탐지되었습니다.',
    recommendation: '관리자 수동 심사 권고',
    confidence: 85
  };

  return (
    <div className="modal-overlay" onClick={onClose} role="dialog" aria-modal="true">
      <div className="modal-card alert-detail-modal" onClick={(e) => e.stopPropagation()}>
        {/* 헤더 */}
        <div className="modal-header">
          <div className="modal-title-wrap">
            <div className={`icon-badge ${alert.riskLevel === 'CRITICAL' ? 'bg-red' : alert.riskLevel === 'WARNING' ? 'bg-amber' : 'bg-blue'}`}>
              <ShieldAlert size={22} />
            </div>
            <div>
              <div className="alert-meta-line">
                <span className="alert-id-tag">{alert.id}</span>
                <span className={`risk-badge ${alert.riskLevel.toLowerCase()}`}>
                  {alert.riskLevel} {alert.riskScore}점
                </span>
                <span className="timestamp-text">{alert.timestamp} 탐지</span>
              </div>
              <h3>FDS 이상거래 정밀 분석 및 관리자 조치</h3>
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

        <div className="modal-body-scroll">
          {/* 피의 계좌 및 주문 상세 정보 */}
          <div className="alert-grid-section">
            <div className="profile-card">
              <div className="card-head">
                <User size={15} />
                <strong>피의 계좌 프로파일</strong>
              </div>
              <div className="profile-kv-list">
                <div className="kv-row">
                  <span>사용자 ID</span>
                  <strong>{alert.userId}</strong>
                </div>
                <div className="kv-row">
                  <span>고객명 (마스킹)</span>
                  <strong>{alert.userName}</strong>
                </div>
                <div className="kv-row">
                  <span>계좌번호</span>
                  <code className="masked-code">{alert.accountNo}</code>
                </div>
                <div className="kv-row">
                  <span>접속 IP (마스킹)</span>
                  <code className="masked-code">{alert.userIp}</code>
                </div>
              </div>
            </div>

            <div className="profile-card">
              <div className="card-head">
                <FileText size={15} />
                <strong>의심 주문 상세</strong>
              </div>
              <div className="profile-kv-list">
                <div className="kv-row">
                  <span>대상 종목</span>
                  <strong>{alert.stockName} <small className="code-sub">({alert.stockCode})</small></strong>
                </div>
                <div className="kv-row">
                  <span>주문 유형</span>
                  <strong className={alert.orderType === 'BUY' ? 'text-red' : 'text-blue'}>
                    {alert.orderType === 'BUY' ? '매수 (BUY)' : '매도 (SELL)'}
                  </strong>
                </div>
                <div className="kv-row">
                  <span>주문 단가 / 수량</span>
                  <strong>₩{alert.price?.toLocaleString()} / {alert.quantity?.toLocaleString()}주</strong>
                </div>
                <div className="kv-row">
                  <span>총 주문 금액</span>
                  <strong className="text-large">₩{alert.totalAmount?.toLocaleString()}</strong>
                </div>
              </div>
            </div>
          </div>

          {/* 탐지된 이상 징후 (Triggers) & 4대 룰 점수 내역 */}
          <div className="triggers-card">
            <div className="card-head" style={{ justifyContent: 'space-between' }}>
              <div style={{ display: 'flex', alignItems: 'center', gap: '6px' }}>
                <AlertTriangle size={15} className="text-amber" />
                <strong>엔진 탐지 이상 징후 (4대 룰 정밀 채점 내역)</strong>
              </div>
              <span className="risk-score-pill" style={{ fontSize: '11px', background: '#3b2520', color: '#ff7070', padding: '2px 8px', borderRadius: '4px', fontWeight: 700 }}>
                합계 {alert.riskScore}점
              </span>
            </div>

            {/* 4대 룰 점수 배점 현황 */}
            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(4, 1fr)', gap: '8px', margin: '10px 0', padding: '10px', background: '#121c2b', borderRadius: '6px', fontSize: '11px' }}>
              <div style={{ textAlign: 'center' }}>
                <div style={{ color: '#889bb0' }}>접속 이상 (30점)</div>
                <strong style={{ color: alert.ruleScores?.access > 0 ? '#ff7b7b' : '#4cd49e' }}>+{alert.ruleScores?.access || 0}점</strong>
              </div>
              <div style={{ textAlign: 'center' }}>
                <div style={{ color: '#889bb0' }}>금액 이상 (35점)</div>
                <strong style={{ color: alert.ruleScores?.amount > 0 ? '#ff7b7b' : '#4cd49e' }}>+{alert.ruleScores?.amount || 0}점</strong>
              </div>
              <div style={{ textAlign: 'center' }}>
                <div style={{ color: '#889bb0' }}>종목 이상 (25점)</div>
                <strong style={{ color: alert.ruleScores?.stock > 0 ? '#ff7b7b' : '#4cd49e' }}>+{alert.ruleScores?.stock || 0}점</strong>
              </div>
              <div style={{ textAlign: 'center' }}>
                <div style={{ color: '#889bb0' }}>속도 이상 (20점)</div>
                <strong style={{ color: alert.ruleScores?.velocity > 0 ? '#ff7b7b' : '#4cd49e' }}>+{alert.ruleScores?.velocity || 0}점</strong>
              </div>
            </div>

            <ul className="trigger-list">
              {alert.triggers?.map((trig, idx) => (
                <li key={idx} className="trigger-item">
                  <span className="trigger-bullet">Trigger {idx + 1}</span>
                  <span className="trigger-desc">{trig}</span>
                </li>
              ))}
            </ul>
          </div>

          {/* StockShield AI 종합 분석 카드 */}
          <div className="ai-analysis-card">
            <div className="ai-card-head">
              <div className="ai-title">
                <Bot size={18} className="text-cyan" />
                <strong>StockShield AI Copilot 종합 소견</strong>
                <span className="ai-badge">Claude 3.7 Sonnet 기반 FDS AI</span>
              </div>
              <button
                type="button"
                className={`ai-refresh-btn ${isAiReanalyzing ? 'rotating' : ''}`}
                onClick={handleAiRefresh}
                disabled={isAiReanalyzing}
                title="AI 재분석 요청"
              >
                <RefreshCw size={13} />
                <span>{isAiReanalyzing ? '재분석 중...' : '재분석'}</span>
              </button>
            </div>

            <div className="ai-content-box">
              <p className="ai-summary-text">{aiData.summary}</p>
              <div className="ai-recommendation-box">
                <span className="rec-label">권고 조치:</span>
                <strong className="rec-value">{aiData.recommendation}</strong>
                <span className="confidence-pill">AI 신뢰도 {aiData.confidence}%</span>
              </div>
            </div>
          </div>

          {/* 관리자 감사 로그 / 현재 상태 */}
          <div className="action-history-card">
            <span className="history-label">현재 조치 상태:</span>
            <span className={`status-pill ${alert.status.toLowerCase()}`}>
              {alert.status === 'HOLD' ? '보류 (관리자 심사 대기)' :
               alert.status === 'APPROVED' ? '승인 (체결 완료)' :
               alert.status === 'REJECTED' ? '차단/거절 완료' :
               alert.status === 'UNLOCKED' ? '잠금 해제 완료' : '계좌 잠금 동결'}
            </span>
            <p className="action-log-text">{alert.actionLog}</p>
          </div>

          {/* 관리자 코멘트 및 잠금 해제 사유 입력 */}
          {(isHold || isLocked) && (
            <div className="admin-comment-section" style={{ marginBottom: '20px' }}>
              <label htmlFor="admin-reason">
                {isLocked ? '계좌 잠금 해제 사유 (필수 입력):' : '관리자 심사 의견 및 처리 사유:'}
              </label>
              <input
                id="admin-reason"
                type="text"
                placeholder={isLocked ? '예: 고객 유선 본인확인 및 이상거래 소명 완료' : '예: 고객 유선 본인 확인 완료로 승인 / 매크로 행위 확인으로 거절'}
                value={adminComment}
                onChange={(e) => setAdminComment(e.target.value)}
              />
            </div>
          )}
        </div>

        {/* 조치 액션 버튼 바 */}
        <div className="modal-footer modal-actions-between">
          <div className="left-actions">
            {isLocked ? (
              <button
                type="button"
                className="btn-action unlock"
                onClick={() => {
                  if (!adminComment.trim()) {
                    alert('계좌 잠금을 해제하려면 해제 사유를 반드시 입력해야 합니다.');
                    return;
                  }
                  handleActionClick('UNLOCKED');
                }}
              >
                <Unlock size={14} /> 계좌 잠금 해제 (원장 정상화)
              </button>
            ) : (
              <button
                type="button"
                className="btn-action lock"
                onClick={() => handleActionClick('LOCKED')}
              >
                <Lock size={14} /> 계좌 긴급 잠금
              </button>
            )}
          </div>

          <div className="right-actions">
            {isHold && (
              <>
                <button
                  type="button"
                  className="btn-action reject"
                  onClick={() => handleActionClick('REJECTED')}
                >
                  <XCircle size={14} /> 주문 최종 거절
                </button>
                <button
                  type="button"
                  className="btn-action approve"
                  onClick={() => handleActionClick('APPROVED')}
                >
                  <CheckCircle size={14} /> 수동 승인 (체결 진행)
                </button>
              </>
            )}
            <button
              type="button"
              className="btn-secondary"
              onClick={onClose}
              title="창 닫기"
            >
              <X size={15} strokeWidth={2.3} />
              <span>닫기</span>
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}
