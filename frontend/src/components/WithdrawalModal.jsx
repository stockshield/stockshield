import React, { useState, useEffect } from 'react';
import {
  X,
  Wallet,
  KeyRound,
  AlertCircle,
  AlertTriangle,
  CheckCircle2,
  XCircle,
  Lock,
  RefreshCw,
  Clock,
  ChevronLeft,
  ChevronRight,
  ShieldCheck,
} from 'lucide-react';
import { executeWithdrawal, fetchWithdrawalHistory } from '../utils/storage';

const BANKS = [
  '국민은행',
  '신한은행',
  '우리은행',
  '하나은행',
  'NH농협은행',
  '기업은행',
  '카카오뱅크',
  '토스뱅크',
  '케이뱅크',
];

const won = (x) => Math.round(Number(x || 0)).toLocaleString('ko-KR');

export default function WithdrawalModal({ isOpen, onClose, currentUser, onWithdrawSuccess }) {
  const [activeTab, setActiveTab] = useState('REQUEST'); // 'REQUEST' | 'HISTORY'

  // 출금 입력 상태
  const [wdrAmt, setWdrAmt] = useState('');
  const [rcvBankNm, setRcvBankNm] = useState(BANKS[0]);
  const [rcvAcntNo, setRcvAcntNo] = useState('');

  // 428 Precondition Required 상태 (고액 출금 시 비밀번호 추가 요구)
  const [pwdRequiredInfo, setPwdRequiredInfo] = useState(null); // { reason, dailySum, threshold, message }
  const [wdrPwd, setWdrPwd] = useState('');
  const [remainCnt, setRemainCnt] = useState(null);

  const [isSubmitting, setIsSubmitting] = useState(false);
  const [errorMsg, setErrorMsg] = useState('');
  const [errorCode, setErrorCode] = useState('');
  const [outcome, setOutcome] = useState(null); // { status: 'DONE'|'REJECTED', code, message, withdrawal, remainCnt }

  // 출금 내역 상태
  const [historyList, setHistoryList] = useState([]);
  const [page, setPage] = useState(0);
  const [totalPages, setTotalPages] = useState(0);
  const [totalElements, setTotalElements] = useState(0);
  const [loadingHistory, setLoadingHistory] = useState(false);

  const loadHistory = async (targetPage = 0) => {
    if (!currentUser || currentUser.role === 'ADMIN') return;
    setLoadingHistory(true);
    try {
      const res = await fetchWithdrawalHistory({ page: targetPage, size: 15 });
      if (res.success) {
        setHistoryList(res.content || []);
        setPage(Number(res.page ?? targetPage));
        setTotalPages(Number(res.totalPages ?? 1));
        setTotalElements(Number(res.totalElements ?? 0));
      }
    } finally {
      setLoadingHistory(false);
    }
  };

  useEffect(() => {
    if (!isOpen) return;
    setErrorMsg('');
    setErrorCode('');
    setOutcome(null);
    setPwdRequiredInfo(null);
    setWdrPwd('');
    setRemainCnt(null);
    loadHistory(0);
  }, [isOpen, currentUser?.id]);

  if (!isOpen) return null;

  const balance = Number(currentUser?.balance ?? 0);
  const isAccountLocked = Boolean(currentUser?.isLocked);

  const handleQuickAmount = (amt) => {
    if (pwdRequiredInfo) return; // 428 대기 중에는 금액 고정 (같은 idmpKey 유지)
    setErrorMsg('');
    setErrorCode('');
    setWdrAmt(String(Math.min(balance, amt)));
  };

  const handleSubmit = async (e) => {
    e?.preventDefault();
    setErrorMsg('');
    setErrorCode('');

    const parsedAmt = Number(wdrAmt);
    if (!Number.isInteger(parsedAmt) || parsedAmt < 1) {
      setErrorMsg('출금 금액을 1원 이상의 정수로 입력해 주세요.');
      return;
    }
    const cleanAcnt = rcvAcntNo.replace(/-/g, '').trim();
    if (!/^\d{10,16}$/.test(cleanAcnt)) {
      setErrorMsg('받는 계좌번호는 숫자 10~16자리(하이픈 가능)여야 합니다.');
      return;
    }
    if (pwdRequiredInfo && !/^\d{6}$/.test(wdrPwd.trim())) {
      setErrorMsg('출금 비밀번호 숫자 6자리를 입력해 주세요.');
      return;
    }

    setIsSubmitting(true);
    try {
      const res = await executeWithdrawal({
        wdrAmt: parsedAmt,
        rcvBankNm,
        rcvAcntNo,
        wdrPwd: pwdRequiredInfo ? wdrPwd.trim() : null,
      });

      // 428 Precondition Required: 고액/누적 출금으로 출금 비밀번호 요구
      if (res.passwordRequired) {
        setPwdRequiredInfo({
          reason: res.detail?.reason || 'SINGLE_LIMIT',
          dailySum: Number(res.detail?.dailySum ?? 0),
          threshold: Number(res.detail?.threshold ?? 5000000),
          message: res.message,
        });
        setWdrPwd('');
        return;
      }

      if (res.success) {
        setPwdRequiredInfo(null);
        setWdrPwd('');
        setOutcome({
          status: 'DONE',
          code: null,
          message: res.message,
          withdrawal: res.withdrawal,
        });
        loadHistory(0);
        if (onWithdrawSuccess && res.user) {
          onWithdrawSuccess(res.user, res.message);
        }
        return;
      }

      // 422 WDR_PWD_MISMATCH 등 저장된 거절 또는 423 계좌 잠금(WDR_PWD_LOCKED / ACCOUNT_LOCKED)
      if (res.isRejected) {
        setOutcome({
          status: 'REJECTED',
          code: res.code,
          message: res.message,
          remainCnt: res.remainCnt,
          wdrNo: res.detail?.wdrNo,
        });
        setPwdRequiredInfo(null);
        setWdrPwd('');
        loadHistory(0);
        if (onWithdrawSuccess && res.user) {
          onWithdrawSuccess(res.user, `[출금 거절] ${res.message}`);
        }
      } else {
        setErrorCode(res.code || '');
        setErrorMsg(res.message || '출금 처리에 실패했습니다.');
      }
    } finally {
      setIsSubmitting(false);
    }
  };

  const resetForm = () => {
    setOutcome(null);
    setPwdRequiredInfo(null);
    setWdrPwd('');
    setRemainCnt(null);
    setErrorMsg('');
    setErrorCode('');
  };

  return (
    <div className="modal-overlay" onClick={onClose} role="dialog" aria-modal="true">
      <div className="modal-card order-history-modal" style={{ maxWidth: '680px' }} onClick={(e) => e.stopPropagation()}>
        <div className="modal-header">
          <div className="modal-title-wrap">
            <div className="icon-badge">
              <Wallet size={20} className="primary-icon" />
            </div>
            <div>
              <h3>모의 출금 및 출금 내역</h3>
              <p className="modal-subtitle">
                {currentUser?.name} 님 ({currentUser?.accountNo || '-'}) · 출금 가능 예수금 ₩{won(balance)}
              </p>
            </div>
          </div>
          <button type="button" className="close-btn" onClick={onClose} aria-label="창 닫기">
            <X size={20} strokeWidth={2.3} />
          </button>
        </div>

        {/* 상단 탭 전환: 모의 출금 신청 / 출금 내역 */}
        <div className="order-filter-bar">
          <div className="filter-tabs">
            <button
              type="button"
              className={`filter-tab ${activeTab === 'REQUEST' ? 'active' : ''}`}
              onClick={() => setActiveTab('REQUEST')}
            >
              <Wallet size={13} /> 모의 출금 신청
            </button>
            <button
              type="button"
              className={`filter-tab ${activeTab === 'HISTORY' ? 'active' : ''}`}
              onClick={() => {
                setActiveTab('HISTORY');
                loadHistory(0);
              }}
            >
              <Clock size={13} /> 출금 내역 ({totalElements})
            </button>
          </div>
        </div>

        {activeTab === 'REQUEST' ? (
          <div style={{ padding: '18px 22px' }}>
            {isAccountLocked && (
              <div style={{ background: '#3b161c', border: '1px solid #ff4d64', borderRadius: '8px', padding: '12px 14px', marginBottom: '16px', color: '#ff8596', fontSize: '12.5px', display: 'flex', alignItems: 'center', gap: '8px' }}>
                <Lock size={16} style={{ flexShrink: 0 }} />
                <div>
                  <strong>계좌가 잠겨 있습니다 ({currentUser?.acntStatCd})</strong>
                  <div style={{ fontSize: '11.5px', marginTop: '2px' }}>
                    사유: {currentUser?.lockReason || '관리자에게 문의하세요.'}
                  </div>
                </div>
              </div>
            )}

            {outcome ? (
              <div className={`order-result-box ${outcome.status === 'DONE' ? 'executed' : 'rejected'}`}>
                {outcome.status === 'DONE' ? (
                  <>
                    <div className="order-result-icon executed">
                      <CheckCircle2 size={42} />
                    </div>
                    <h3 className="order-result-title text-green">모의 출금 완료 (DONE)</h3>
                    <p className="order-result-msg">{outcome.message}</p>
                    {outcome.withdrawal && (
                      <div className="order-summary-table">
                        <div>
                          <span>출금 번호</span>
                          <b>{outcome.withdrawal.wdrNo}</b>
                        </div>
                        <div>
                          <span>출금 금액</span>
                          <b className="accent-price">₩{won(outcome.withdrawal.wdrAmt)}</b>
                        </div>
                        <div>
                          <span>입금 계좌</span>
                          <b>{outcome.withdrawal.rcvBankNm} {outcome.withdrawal.mskRcvAcntNo}</b>
                        </div>
                        <div>
                          <span>출금 후 예수금</span>
                          <b>₩{won(outcome.withdrawal.postDpsBal)}</b>
                        </div>
                        <div>
                          <span>추가 인증 여부</span>
                          <span className="fds-badge-pill">
                            {outcome.withdrawal.addAuthYn === 'Y' ? '출금 비밀번호 인증 완료' : '일반 출금'}
                          </span>
                        </div>
                      </div>
                    )}
                  </>
                ) : (
                  <>
                    <div className="order-result-icon rejected">
                      {outcome.code === 'WDR_PWD_LOCKED' || outcome.code === 'ACCOUNT_LOCKED' ? (
                        <Lock size={42} />
                      ) : (
                        <XCircle size={42} />
                      )}
                    </div>
                    <h3 className="order-result-title text-red">
                      {outcome.code === 'WDR_PWD_LOCKED'
                        ? '출금 비밀번호 5회 오류 — 계좌 잠금 (TEMP_LOCKED)'
                        : outcome.code === 'ACCOUNT_LOCKED'
                        ? '계좌 잠금 상태 출금 거절 (ACCOUNT_LOCKED)'
                        : outcome.code === 'WDR_PWD_MISMATCH'
                        ? '출금 비밀번호 불일치 거절 (WDR_PWD_MISMATCH)'
                        : outcome.code === 'INSUFFICIENT_BALANCE'
                        ? '예수금 부족 출금 거절 (INSUFFICIENT_BALANCE)'
                        : `출금 거절 (${outcome.code || 'REJECTED'})`}
                    </h3>
                    <p className="order-result-msg">{outcome.message}</p>
                    <div className="order-status-notice rejected">
                      <strong>거절 코드: {outcome.code}</strong>
                      {outcome.remainCnt != null && (
                        <p>남은 비밀번호 시도 횟수: <strong>{outcome.remainCnt}회</strong> (5회 오류 시 계좌 임시 잠금)</p>
                      )}
                      {outcome.wdrNo && <p>출금 기록 번호: {outcome.wdrNo}</p>}
                    </div>
                  </>
                )}

                <div className="modal-actions" style={{ marginTop: '16px' }}>
                  <button type="button" className="secondary-btn" onClick={() => setActiveTab('HISTORY')}>
                    출금 내역 보기
                  </button>
                  <button type="button" className="primary-btn" onClick={resetForm}>
                    <RefreshCw size={14} /> 다시 출금하기
                  </button>
                </div>
              </div>
            ) : (
              <form onSubmit={handleSubmit} className="signup-step-body">
                {errorMsg && (
                  <div className="form-alert error">
                    <AlertCircle size={16} />
                    <span>{errorMsg}</span>
                  </div>
                )}

                <div className="form-group">
                  <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                    <label htmlFor="wdr-amt">출금 금액 (원)</label>
                    <span style={{ fontSize: '11.5px', color: '#8fa0b5' }}>
                      출금 가능: ₩{won(balance)}
                    </span>
                  </div>
                  <div className="form-control">
                    <input
                      id="wdr-amt"
                      type="number"
                      min="1"
                      value={wdrAmt}
                      disabled={Boolean(pwdRequiredInfo)}
                      onChange={(e) => setWdrAmt(e.target.value)}
                      placeholder="출금할 금액을 입력하세요 (예: 5000000)"
                      required
                    />
                  </div>
                  {!pwdRequiredInfo && (
                    <div className="quick-ticks" style={{ marginTop: '6px' }}>
                      {[
                        { label: '+100만', val: 1000000 },
                        { label: '+300만', val: 3000000 },
                        { label: '+500만(고액)', val: 5000000 },
                        { label: '전액', val: balance },
                      ].map((btn) => (
                        <button
                          key={btn.label}
                          type="button"
                          onClick={() => handleQuickAmount(btn.label === '전액' ? balance : Number(wdrAmt || 0) + btn.val)}
                        >
                          {btn.label}
                        </button>
                      ))}
                    </div>
                  )}
                </div>

                <div style={{ display: 'grid', gridTemplateColumns: '180px 1fr', gap: '10px' }}>
                  <div className="form-group">
                    <label htmlFor="wdr-bank">입금 은행</label>
                    <div className="form-control">
                      <select
                        id="wdr-bank"
                        value={rcvBankNm}
                        disabled={Boolean(pwdRequiredInfo)}
                        onChange={(e) => setRcvBankNm(e.target.value)}
                        style={{ background: 'transparent', border: 0, color: '#e5ecf4', width: '100%', fontSize: '13px', outline: 'none' }}
                      >
                        {BANKS.map((b) => (
                          <option key={b} value={b} style={{ background: '#162030', color: '#e5ecf4' }}>
                            {b}
                          </option>
                        ))}
                      </select>
                    </div>
                  </div>

                  <div className="form-group">
                    <label htmlFor="wdr-acnt">입금 계좌번호 (숫자 10~16자리)</label>
                    <div className="form-control">
                      <input
                        id="wdr-acnt"
                        type="text"
                        value={rcvAcntNo}
                        disabled={Boolean(pwdRequiredInfo)}
                        onChange={(e) => setRcvAcntNo(e.target.value.replace(/[^0-9-]/g, ''))}
                        placeholder="예: 110-123-456789 (하이픈 허용)"
                        required
                      />
                    </div>
                  </div>
                </div>

                {/* 428 WDR_PWD_REQUIRED 응답 시 표시되는 출금 비밀번호 추가 인증 입력창 */}
                {pwdRequiredInfo && (
                  <div style={{ background: '#261e14', border: '1px solid #f1cc62', borderRadius: '8px', padding: '14px', margin: '8px 0' }}>
                    <div style={{ display: 'flex', alignItems: 'center', gap: '6px', color: '#f1cc62', fontWeight: 700, fontSize: '13px', marginBottom: '6px' }}>
                      <AlertTriangle size={16} />
                      <span>FDS 고액 출금 추가 인증 필요 (HTTP 428 WDR_PWD_REQUIRED)</span>
                    </div>
                    <p style={{ fontSize: '12px', color: '#d8c9a3', marginBottom: '10px', lineHeight: 1.5 }}>
                      {pwdRequiredInfo.message}
                      <br />
                      <small style={{ color: '#b5a47b' }}>
                        • 기준 사유: {pwdRequiredInfo.reason} · 24시간 누적 출금액: ₩{won(pwdRequiredInfo.dailySum)} (기준: ₩{won(pwdRequiredInfo.threshold)})
                      </small>
                    </p>
                    <label htmlFor="wdr-pwd-input" style={{ display: 'block', fontSize: '12px', fontWeight: 600, color: '#f1cc62', marginBottom: '6px' }}>
                      출금 비밀번호 (숫자 6자리) 입력
                    </label>
                    <div className="form-control">
                      <KeyRound size={16} className="input-icon" />
                      <input
                        id="wdr-pwd-input"
                        type="password"
                        inputMode="numeric"
                        maxLength={6}
                        value={wdrPwd}
                        onChange={(e) => setWdrPwd(e.target.value.replace(/[^0-9]/g, '').slice(0, 6))}
                        placeholder="가입 시 설정한 6자리 출금 비밀번호"
                        autoFocus
                        required
                      />
                    </div>
                  </div>
                )}

                <div className="modal-actions" style={{ marginTop: '14px' }}>
                  {pwdRequiredInfo ? (
                    <button
                      type="button"
                      className="secondary-btn"
                      onClick={() => {
                        setPwdRequiredInfo(null);
                        setWdrPwd('');
                      }}
                    >
                      금액/계좌 수정
                    </button>
                  ) : (
                    <button type="button" className="secondary-btn" onClick={onClose}>
                      취소
                    </button>
                  )}
                  <button
                    type="submit"
                    className="primary-btn"
                    disabled={isSubmitting}
                  >
                    {isSubmitting
                      ? '출금 처리 중...'
                      : pwdRequiredInfo
                      ? '출금 비밀번호 확인 및 출금 실행'
                      : '모의 출금 신청'}
                  </button>
                </div>
              </form>
            )}
          </div>
        ) : (
          /* 출금 내역 탭 (GET /api/v1/accounts/me/withdrawals) */
          <>
            <div className="order-table-wrap">
              {loadingHistory ? (
                <div className="empty-order-state">
                  <RefreshCw size={32} className="empty-icon rotating" />
                  <p>출금 내역을 불러오는 중입니다...</p>
                </div>
              ) : historyList.length === 0 ? (
                <div className="empty-order-state">
                  <Clock size={36} className="empty-icon" />
                  <p>모의 출금 내역이 없습니다.</p>
                </div>
              ) : (
                <table className="order-table">
                  <thead>
                    <tr>
                      <th>출금번호 / 시각</th>
                      <th>입금 은행 / 계좌(마스킹)</th>
                      <th>출금 금액</th>
                      <th>추가 인증</th>
                      <th>상태</th>
                      <th>출금 후 잔액 / 거절 사유</th>
                    </tr>
                  </thead>
                  <tbody>
                    {historyList.map((w) => {
                      const isDone = w.wdrStatCd === 'DONE';
                      return (
                        <tr key={w.wdrNo} className={!isDone ? 'row-rejected' : ''}>
                          <td className="time-col">
                            <div style={{ fontWeight: 600, color: '#cbd8ea', fontSize: '11px' }}>{w.wdrNo}</div>
                            <div style={{ fontSize: '11px', color: '#7e92ab' }}>{w.regTs}</div>
                          </td>
                          <td>
                            <strong>{w.rcvBankNm}</strong>
                            <span className="code-sub" style={{ display: 'block' }}>{w.mskRcvAcntNo}</span>
                          </td>
                          <td className="total-col">
                            <strong>₩{won(w.wdrAmt)}</strong>
                          </td>
                          <td>
                            <span className="fds-badge-pill">
                              {w.addAuthYn === 'Y' ? '비밀번호 인증(Y)' : '일반(N)'}
                            </span>
                          </td>
                          <td>
                            {isDone ? (
                              <span className="status-pill executed">
                                <ShieldCheck size={12} /> 완료 (DONE)
                              </span>
                            ) : (
                              <span className="status-pill rejected">
                                <XCircle size={12} /> 거절 (REJECTED)
                              </span>
                            )}
                          </td>
                          <td className="reason-col">
                            {isDone ? (
                              <span style={{ color: '#9cb3d0', fontSize: '12px' }}>
                                잔액 ₩{won(w.postDpsBal)}
                              </span>
                            ) : (
                              <div>
                                <strong style={{ color: '#ff7b89', fontSize: '11px', display: 'block' }}>
                                  {w.rjctRsnCd}
                                </strong>
                                <span className="reason-text text-amber">{w.rjctRsnMsg}</span>
                              </div>
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
                  disabled={page <= 0 || loadingHistory}
                  onClick={() => loadHistory(page - 1)}
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
                  disabled={page + 1 >= totalPages || loadingHistory}
                  onClick={() => loadHistory(page + 1)}
                  style={{ padding: '5px 10px', fontSize: '12px' }}
                >
                  다음 <ChevronRight size={14} />
                </button>
              </div>
              <button type="button" className="btn-secondary" onClick={onClose}>
                <X size={15} strokeWidth={2.3} />
                <span>닫기</span>
              </button>
            </div>
          </>
        )}
      </div>
    </div>
  );
}
