import React, { useState, useEffect } from 'react';
import {
  X,
  ShieldAlert,
  AlertTriangle,
  CheckCircle,
  Lock,
  Unlock,
  Bot,
  RefreshCw,
  User,
  FileText,
} from 'lucide-react';
import {
  fetchAdminDetectionDetail,
  checkAdminDetection,
  retryAdminAiReason,
  changeAdminAccountStatus,
  formatDateTime,
} from '../utils/storage';

const RULE_LABELS = {
  LOGIN_NEW_ACCESS: '새 기기·IP 로그인',
  WITHDRAW_LARGE: '대량 출금',
  TRADE_NEW_IP: '새 IP 직후 주문',
};

const RESULT_LABELS = {
  PENDING: '인증 대기 (PENDING)',
  PASS: '추가 인증 통과 (PASS)',
  FAIL: '추가 인증 실패 (FAIL)',
  LOCKED: '계좌 긴급 잠금 (LOCKED)',
  LOGGED: '이상 징후 기록 (LOGGED)',
};

export default function AlertDetailModal({
  alert,
  isOpen,
  onClose,
  onUpdated,
  onNotify,
}) {
  const [detail, setDetail] = useState(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [actRsnCont, setActRsnCont] = useState('');
  const [isChecking, setIsChecking] = useState(false);
  const [isChangingAcnt, setIsChangingAcnt] = useState(false);
  const [isRetryingAi, setIsRetryingAi] = useState(false);

  const dtcSeq = alert?.dtcSeq ?? alert?.id;

  const loadDetail = async () => {
    if (!dtcSeq) return;
    setLoading(true);
    setError('');
    try {
      const res = await fetchAdminDetectionDetail(dtcSeq);
      if (res.success && res.detail) {
        setDetail(res.detail);
      } else {
        setError(res.message || '탐지 상세 정보를 불러오지 못했습니다.');
      }
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    if (!isOpen || !dtcSeq) {
      setDetail(null);
      setActRsnCont('');
      setError('');
      return;
    }
    loadDetail();
  }, [isOpen, dtcSeq]);

  // 부모에서 STOMP(/topic/admin/detections)로 AI 상태 변경을 받으면 자동 갱신
  useEffect(() => {
    if (isOpen && dtcSeq && alert?.aiStatCd && detail && alert.aiStatCd !== detail.aiStatCd) {
      loadDetail();
    }
  }, [alert?.aiStatCd]);

  if (!isOpen || !alert) return null;

  const d = detail || alert;
  const chkYn = d.chkYn || 'N';
  const aiStatCd = d.aiStatCd || 'REQUESTED';
  const acntNo = d.acntNo || null;
  const acntStatCd = d.acntStatCd || null;
  const isAccountLocked = acntStatCd === 'LOCKED' || acntStatCd === 'TEMP_LOCKED';
  const isEmergency = d.rsltCd === 'LOCKED';

  // 1) 탐지 확인 완료 처리: PATCH /api/v1/admin/detections/{dtcSeq}/check { actRsnCont }
  const handleCheckComplete = async () => {
    setError('');
    const memo = actRsnCont.trim();
    if (!memo) {
      setError('탐지 확인 완료 메모(조치 사유)를 입력해 주세요.');
      return;
    }
    setIsChecking(true);
    try {
      const res = await checkAdminDetection(dtcSeq, memo);
      if (res.success) {
        if (onNotify) {
          onNotify(`[FDS 관제] 탐지 #${dtcSeq} 확인 완료(chkYn=Y) 처리되었습니다.`);
        }
        await loadDetail();
        if (onUpdated) onUpdated();
      } else {
        setError(res.message || '탐지 확인 처리에 실패했습니다.');
      }
    } finally {
      setIsChecking(false);
    }
  };

  // 2) 계좌 잠금 / 해제: PUT /api/v1/admin/accounts/{acntNo}/status { acntStatCd, actRsnCont }
  const handleAccountStatusChange = async (targetStatCd) => {
    if (!acntNo) return;
    setError('');
    const memo = actRsnCont.trim();
    if (!memo) {
      setError(
        targetStatCd === 'ACTIVE'
          ? '계좌 잠금 해제 사유(메모)를 반드시 입력해 주세요.'
          : '계좌 잠금 사유(메모)를 반드시 입력해 주세요.'
      );
      return;
    }
    setIsChangingAcnt(true);
    try {
      const res = await changeAdminAccountStatus(acntNo, targetStatCd, memo);
      if (res.success) {
        if (onNotify) {
          onNotify(
            `[계좌 조치] 계좌 ${acntNo} 상태가 ${targetStatCd === 'ACTIVE' ? '정상(ACTIVE)' : '잠금(LOCKED)'}(으)로 변경되었습니다.`
          );
        }
        await loadDetail();
        if (onUpdated) onUpdated();
      } else {
        setError(res.message || '계좌 상태 변경에 실패했습니다.');
      }
    } finally {
      setIsChangingAcnt(false);
    }
  };

  // 3) AI 설명 재요청: POST /api/v1/admin/detections/{dtcSeq}/ai-reason (aiStatCd === 'FAILED'일 때만 허용)
  const handleAiRetry = async () => {
    if (aiStatCd !== 'FAILED') return;
    setError('');
    setIsRetryingAi(true);
    try {
      const res = await retryAdminAiReason(dtcSeq);
      if (res.success) {
        setDetail((prev) => (prev ? { ...prev, aiStatCd: 'REQUESTED' } : prev));
        if (onNotify) {
          onNotify(`[AI 분석] 탐지 #${dtcSeq}에 대한 AI 사유 설명을 재요청했습니다.`);
        }
        if (onUpdated) onUpdated();
      } else {
        setError(res.message || 'AI 설명 재요청에 실패했습니다.');
      }
    } finally {
      setIsRetryingAi(false);
    }
  };

  return (
    <div className="modal-overlay" onClick={onClose} role="dialog" aria-modal="true">
      <div className="modal-card alert-detail-modal" onClick={(e) => e.stopPropagation()}>
        {/* 헤더 */}
        <div className="modal-header">
          <div className="modal-title-wrap">
            <div className={`icon-badge ${isEmergency ? 'bg-red' : 'bg-amber'}`}>
              <ShieldAlert size={22} />
            </div>
            <div>
              <div className="alert-meta-line">
                <span className="alert-id-tag">탐지 #{dtcSeq}</span>
                <span className={`risk-badge ${isEmergency ? 'critical' : 'warning'}`}>
                  {d.rsltCd}
                </span>
                <span className="timestamp-text">{formatDateTime(d.regTs)}</span>
              </div>
              <h3>{RULE_LABELS[d.ruleCd] || d.ruleCd}</h3>
            </div>
          </div>
          <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
            <button
              type="button"
              className="btn-secondary"
              style={{ padding: '6px 10px', fontSize: '12px' }}
              onClick={loadDetail}
              disabled={loading}
              title="상세 정보 새로고침"
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

        <div className="modal-body-scroll">
          {error && (
            <div style={{ background: '#3b161c', border: '1px solid #ff4d64', borderRadius: '6px', padding: '10px 14px', marginBottom: '14px', color: '#ff8596', fontSize: '12.5px' }}>
              {error}
            </div>
          )}

          {/* 탐지 대상 계좌 및 접속/연관 거래 상세 */}
          <div className="alert-grid-section">
            <div className="profile-card">
              <div className="card-head">
                <User size={15} />
                <strong>탐지 계좌 및 접속 정보</strong>
              </div>
              <div className="profile-kv-list">
                <div className="kv-row">
                  <span>마스킹 회원 ID</span>
                  <strong>{alert.mskLgnId || '-'}</strong>
                </div>
                <div className="kv-row">
                  <span>계좌번호</span>
                  <code className="masked-code">{acntNo || '해당 없음 (로그인 탐지)'}</code>
                </div>
                <div className="kv-row">
                  <span>현재 계좌 상태</span>
                  <strong style={{ color: isAccountLocked ? '#ff6b7b' : '#4cd49e' }}>
                    {acntStatCd || '-'}
                  </strong>
                </div>
                <div className="kv-row">
                  <span>접속 IP (마스킹)</span>
                  <code className="masked-code">{d.mskConnIp || '-'}</code>
                </div>
                <div className="kv-row">
                  <span>User-Agent</span>
                  <span style={{ fontSize: '11px', color: '#8fa0b5', maxWidth: '210px', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }} title={d.userAgt}>
                    {d.userAgt || '-'}
                  </span>
                </div>
              </div>
            </div>

            <div className="profile-card">
              <div className="card-head">
                <FileText size={15} />
                <strong>탐지 규칙 및 연관 거래</strong>
              </div>
              <div className="profile-kv-list">
                <div className="kv-row">
                  <span>탐지 규칙 코드</span>
                  <strong>{d.ruleCd}</strong>
                </div>
                <div className="kv-row">
                  <span>판정 결과</span>
                  <strong className={isEmergency ? 'text-red' : 'text-amber'}>
                    {RESULT_LABELS[d.rsltCd] || d.rsltCd}
                  </strong>
                </div>
                <div className="kv-row">
                  <span>연관 주문번호</span>
                  <strong>{d.ordNo || '-'}</strong>
                </div>
                <div className="kv-row">
                  <span>연관 출금번호</span>
                  <strong>{d.wdrNo || '-'}</strong>
                </div>
                <div className="kv-row">
                  <span>관리자 확인 상태</span>
                  <strong style={{ color: chkYn === 'Y' ? '#4cd49e' : '#f1cc62' }}>
                    {chkYn === 'Y' ? '확인 완료 (Y)' : '미확인 (N)'}
                  </strong>
                </div>
              </div>
            </div>
          </div>

          {/* 탐지 상세 데이터 (dtlData JSONB) */}
          <div className="triggers-card">
            <div className="card-head" style={{ justifyContent: 'space-between' }}>
              <div style={{ display: 'flex', alignItems: 'center', gap: '6px' }}>
                <AlertTriangle size={15} className="text-amber" />
                <strong>탐지 상세 데이터 (dtlData)</strong>
              </div>
              <span className="risk-score-pill" style={{ fontSize: '11px', background: '#223044', color: '#9cb3d0', padding: '2px 8px', borderRadius: '4px', fontWeight: 600 }}>
                {d.ruleCd}
              </span>
            </div>

            {d.dtlData && typeof d.dtlData === 'object' ? (
              <div style={{ display: 'grid', gridTemplateColumns: 'repeat(2, 1fr)', gap: '8px', marginTop: '10px', padding: '12px', background: '#121c2b', borderRadius: '6px', fontSize: '12px' }}>
                {Object.entries(d.dtlData).map(([k, v]) => (
                  <div key={k} style={{ display: 'flex', justifyContent: 'space-between', borderBottom: '1px solid #1e2d42', paddingBottom: '4px' }}>
                    <span style={{ color: '#889bb0' }}>{k}</span>
                    <strong style={{ color: '#e2edf8' }}>
                      {typeof v === 'number' ? v.toLocaleString('ko-KR') : String(v ?? '-')}
                    </strong>
                  </div>
                ))}
              </div>
            ) : (
              <p style={{ fontSize: '12px', color: '#8fa0b5', marginTop: '8px' }}>상세 데이터가 없습니다.</p>
            )}
          </div>

          {/* AI 탐지 사유 설명 및 점검 권고 (REQUESTED / DONE / FAILED) */}
          <div className="ai-analysis-card">
            <div className="ai-card-head">
              <div className="ai-title">
                <Bot size={18} className="text-cyan" />
                <strong>FDS AI 사유 설명 및 확인 권고</strong>
                <span
                  className="ai-badge"
                  style={{
                    background:
                      aiStatCd === 'DONE'
                        ? '#173b2e'
                        : aiStatCd === 'FAILED'
                        ? '#3b161c'
                        : '#25354e',
                    color:
                      aiStatCd === 'DONE'
                        ? '#4cd49e'
                        : aiStatCd === 'FAILED'
                        ? '#ff7b89'
                        : '#9cb3d0',
                  }}
                >
                  AI 상태: {aiStatCd}
                </span>
              </div>
              <button
                type="button"
                className={`ai-refresh-btn ${isRetryingAi ? 'rotating' : ''}`}
                onClick={handleAiRetry}
                disabled={aiStatCd !== 'FAILED' || isRetryingAi}
                title={
                  aiStatCd === 'FAILED'
                    ? 'AI 사유 설명 재요청 (POST /admin/detections/{dtcSeq}/ai-reason)'
                    : 'AI 설명이 FAILED 상태일 때만 재요청할 수 있습니다'
                }
              >
                <RefreshCw size={13} />
                <span>{isRetryingAi ? '재요청 중...' : 'AI 설명 재요청'}</span>
              </button>
            </div>

            <div className="ai-content-box">
              {aiStatCd === 'DONE' ? (
                <>
                  <p className="ai-summary-text">
                    {d.aiRsnCont || 'AI 사유 설명이 비어 있습니다.'}
                  </p>
                  <div className="ai-recommendation-box">
                    <span className="rec-label">관리자 확인 사항:</span>
                    <strong className="rec-value">
                      {d.aiChkCont || '관련 거래 및 접속 이력을 확인하세요.'}
                    </strong>
                  </div>
                </>
              ) : aiStatCd === 'FAILED' ? (
                <div style={{ color: '#ff8596', fontSize: '12.5px', padding: '6px 0' }}>
                  AI 서버 응답 지연 또는 오류로 설명 생성에 실패했습니다 (FAILED). 우측 상단 <strong>[AI 설명 재요청]</strong> 버튼을 눌러 다시 요청할 수 있습니다.
                </div>
              ) : (
                <div style={{ color: '#9cb3d0', fontSize: '12.5px', padding: '6px 0' }}>
                  AI 서버에서 탐지 사유 설명을 생성하는 중입니다 (REQUESTED). 완료 시 실시간으로 반영됩니다.
                </div>
              )}
            </div>
          </div>

          {/* 관리자 확인 메모 / 계좌 잠금·해제 사유 입력 */}
          <div className="admin-comment-section" style={{ marginTop: '14px', marginBottom: '12px' }}>
            <label htmlFor="admin-reason">
              관리자 확인 메모 / 계좌 잠금·해제 사유 (필수 입력):
            </label>
            <input
              id="admin-reason"
              type="text"
              placeholder="예: 본인 통화 확인 완료로 이상 없음 확인 / 비정상 출금 시도로 계좌 잠금 조치"
              value={actRsnCont}
              onChange={(e) => setActRsnCont(e.target.value)}
            />
          </div>
        </div>

        {/* 조치 액션 버튼 바 (탐지 확인 완료 · 계좌 잠금 · 계좌 잠금 해제) */}
        <div className="modal-footer modal-actions-between">
          <div className="left-actions">
            {acntNo && (
              isAccountLocked ? (
                <button
                  type="button"
                  className="btn-action unlock"
                  disabled={isChangingAcnt}
                  onClick={() => handleAccountStatusChange('ACTIVE')}
                >
                  <Unlock size={14} /> 계좌 잠금 해제 (ACTIVE)
                </button>
              ) : (
                <button
                  type="button"
                  className="btn-action lock"
                  disabled={isChangingAcnt}
                  onClick={() => handleAccountStatusChange('LOCKED')}
                >
                  <Lock size={14} /> 계좌 잠금 (LOCKED)
                </button>
              )
            )}
          </div>

          <div className="right-actions">
            {chkYn !== 'Y' ? (
              <button
                type="button"
                className="btn-action approve"
                disabled={isChecking}
                onClick={handleCheckComplete}
              >
                <CheckCircle size={14} />
                <span>{isChecking ? '확인 저장 중...' : '탐지 확인 완료 (CHECK)'}</span>
              </button>
            ) : (
              <span className="status-pill executed" style={{ marginRight: '8px' }}>
                <CheckCircle size={13} /> 확인 완료됨 (chkYn=Y)
              </span>
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
