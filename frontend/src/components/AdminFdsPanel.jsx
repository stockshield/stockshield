import React, { useState, useEffect, useCallback } from 'react';
import {
  ShieldAlert,
  AlertTriangle,
  CheckCircle,
  Lock,
  Unlock,
  Search,
  RefreshCw,
  ArrowLeft,
  Activity,
  Bot,
  CheckCircle2,
  AlertOctagon,
  TrendingUp,
  ChevronLeft,
  ChevronRight,
  Clock,
} from 'lucide-react';
import {
  fetchAdminDetections,
  fetchRiskStocks,
  runServerReconciliation,
  fetchAdminReconcileHistory,
  fetchAdminReconcileDetail,
  changeAdminAccountStatus,
  formatDateTime,
} from '../utils/storage';
import { createStompManager } from '../utils/stompClient';
import AlertDetailModal from './AlertDetailModal';

const RULE_NAME_MAP = {
  LOGIN_NEW_ACCESS: '새 기기·IP 로그인',
  WITHDRAW_LARGE: '대량 출금',
  TRADE_NEW_IP: '새 IP 직후 주문',
};

// 계좌 잠금(LOCKED) 긴급 경보 Web Audio 알림음
function playEmergencyAlertSound() {
  try {
    const AudioCtx = window.AudioContext || window.webkitAudioContext;
    if (!AudioCtx) return;
    const ctx = new AudioCtx();
    const notes = [880, 1174.66, 880];
    notes.forEach((freq, idx) => {
      const osc = ctx.createOscillator();
      const gain = ctx.createGain();
      osc.type = 'triangle';
      osc.frequency.value = freq;
      const start = ctx.currentTime + idx * 0.14;
      gain.gain.setValueAtTime(0.001, start);
      gain.gain.exponentialRampToValueAtTime(0.18, start + 0.02);
      gain.gain.exponentialRampToValueAtTime(0.001, start + 0.12);
      osc.connect(gain);
      gain.connect(ctx.destination);
      osc.start(start);
      osc.stop(start + 0.13);
    });
    setTimeout(() => {
      ctx.close().catch(() => {});
    }, 600);
  } catch {
    // 브라우저 오디오 정책 차단 시 무시
  }
}

export default function AdminFdsPanel({ onBackToTrading, currentUser, onNotify, onSessionExpired, onUncheckedCountChange }) {
  const [activeSection, setActiveSection] = useState('DETECTIONS'); // 'DETECTIONS' | 'RECONCILE' | 'RISK_STOCKS' | 'ACCOUNT_LOCK'

  // 1) FDS 탐지 목록 상태
  const [detections, setDetections] = useState([]);
  const [ruleFilter, setRuleFilter] = useState('ALL'); // ALL, LOGIN_NEW_ACCESS, WITHDRAW_LARGE, TRADE_NEW_IP
  const [chkFilter, setChkFilter] = useState('ALL'); // ALL, N, Y
  const [searchQuery, setSearchQuery] = useState('');
  const [page, setPage] = useState(0);
  const [totalPages, setTotalPages] = useState(0);
  const [totalElements, setTotalElements] = useState(0);
  const [overallTotalCount, setOverallTotalCount] = useState(0);
  const [overallUncheckedCount, setOverallUncheckedCount] = useState(0);
  const [loadingDetections, setLoadingDetections] = useState(false);
  const [selectedDetection, setSelectedDetection] = useState(null);

  // 실시간 긴급 경보 (rsltCd === 'LOCKED' 등) 및 시세 지연 알림 배너
  const [emergencyAlerts, setEmergencyAlerts] = useState([]);
  const [delayedStocksMap, setDelayedStocksMap] = useState({}); // { [tckCd]: { tckCd, tckNm, priceDelayed, prcTs } }
  const [wsConnected, setWsConnected] = useState(false);

  // 2) 위험 의심 종목 목록 (GET /api/v1/market/risk-stocks + /topic/risk)
  const [riskStocks, setRiskStocks] = useState([]);
  const [loadingRiskStocks, setLoadingRiskStocks] = useState(false);

  // 3) 원장 대사 실행·이력·상세 상태 (POST/GET /api/v1/admin/reconcile, GET /{rcclRunNo})
  const [isAuditing, setIsAuditing] = useState(false);
  const [reconcileRuns, setReconcileRuns] = useState([]);
  const [rcclPage, setRcclPage] = useState(0);
  const [rcclTotalPages, setRcclTotalPages] = useState(0);
  const [selectedRunDetail, setSelectedRunDetail] = useState(null);
  const [diffOnly, setDiffOnly] = useState(true);
  const [loadingRunDetail, setLoadingRunDetail] = useState(false);
  const [auditBannerMsg, setAuditBannerMsg] = useState('원장 대사 즉시 실행 또는 실행 이력을 확인하세요.');

  // 4) 계좌 번호 직접 잠금/해제 도구 상태 (PUT /api/v1/admin/accounts/{acntNo}/status)
  const [targetAcntNo, setTargetAcntNo] = useState('');
  const [targetStatCd, setTargetStatCd] = useState('LOCKED');
  const [targetReason, setTargetReason] = useState('');
  const [acntActionMsg, setAcntActionMsg] = useState('');
  const [isChangingAcnt, setIsChangingAcnt] = useState(false);

  const loadDetections = useCallback(async (targetPage = page, targetRule = ruleFilter, targetChk = chkFilter) => {
    setLoadingDetections(true);
    try {
      const isUnfiltered = targetRule === 'ALL' && targetChk === 'ALL';
      const [res, totalRes, uncheckedRes] = await Promise.all([
        fetchAdminDetections({
          ruleCd: targetRule === 'ALL' ? null : targetRule,
          chkYn: targetChk === 'ALL' ? null : targetChk,
          page: targetPage,
          size: 20,
        }),
        isUnfiltered ? Promise.resolve(null) : fetchAdminDetections({ ruleCd: null, chkYn: null, page: 0, size: 1 }),
        targetRule === 'ALL' && targetChk === 'N'
          ? Promise.resolve(null)
          : fetchAdminDetections({ ruleCd: null, chkYn: 'N', page: 0, size: 1 }),
      ]);
      if (res.success) {
        setDetections(res.content || []);
        const filteredTotal = Number(res.totalElements ?? 0);
        setTotalElements(filteredTotal);
        setTotalPages(Number(res.totalPages ?? 1));
        if (isUnfiltered) {
          setOverallTotalCount(filteredTotal);
        } else if (totalRes?.success) {
          setOverallTotalCount(Number(totalRes.totalElements ?? 0));
        }
        const nextUnchecked =
          targetRule === 'ALL' && targetChk === 'N'
            ? filteredTotal
            : uncheckedRes?.success
            ? Number(uncheckedRes.totalElements ?? 0)
            : null;
        if (nextUnchecked !== null) {
          setOverallUncheckedCount(nextUnchecked);
          onUncheckedCountChange?.(nextUnchecked);
        }
      }
    } finally {
      setLoadingDetections(false);
    }
  }, [page, ruleFilter, chkFilter, onUncheckedCountChange]);

  const loadRiskStocks = useCallback(async () => {
    setLoadingRiskStocks(true);
    try {
      const res = await fetchRiskStocks();
      if (res.success) {
        setRiskStocks(res.items || []);
      }
    } finally {
      setLoadingRiskStocks(false);
    }
  }, []);

  const loadReconcileHistory = useCallback(async (targetPage = rcclPage) => {
    const res = await fetchAdminReconcileHistory({ page: targetPage, size: 15 });
    if (res.success) {
      const list = res.content || [];
      setReconcileRuns(list);
      setRcclTotalPages(Number(res.totalPages ?? 1));
      if (list.length > 0) {
        const latest = list[0];
        setAuditBannerMsg(
          `최근 대사 #${latest.rcclRunNo} (${latest.rcclDt}, ${latest.trgDvCd}): 상태 ${latest.runStatCd} · 총 ${latest.totAcntCnt ?? 0}계좌 중 불일치 ${latest.diffAcntCnt ?? 0}건`
        );
      }
    }
  }, [rcclPage]);

  const loadReconcileDetail = async (rcclRunNo, onlyDiff = diffOnly) => {
    if (!rcclRunNo) return;
    setLoadingRunDetail(true);
    try {
      const res = await fetchAdminReconcileDetail(rcclRunNo, onlyDiff);
      if (res.success && res.detail) {
        setSelectedRunDetail(res.detail);
      }
    } finally {
      setLoadingRunDetail(false);
    }
  };

  // 초기 조회
  useEffect(() => {
    loadDetections(0, ruleFilter, chkFilter);
  }, [ruleFilter, chkFilter]);

  useEffect(() => {
    loadRiskStocks();
    loadReconcileHistory(0);
  }, []);

  // STOMP 관리자 채널 및 위험 종목 채널 실시간 구독 (/ws)
  useEffect(() => {
    const stomp = createStompManager({
      withAuth: true,
      onConnect: () => setWsConnected(true),
      onReconnect: () => {
        setWsConnected(true);
        // 재연결 시 놓친 탐지·위험종목·대사이력 다시 조회
        loadDetections(page, ruleFilter, chkFilter);
        loadRiskStocks();
        loadReconcileHistory(rcclPage);
      },
      onDisconnect: () => setWsConnected(false),
      onAuthFailed: () => {
        setWsConnected(false);
        if (onNotify) {
          onNotify('⚠️ 인증이 만료되어 실시간 관제 연결이 종료되었습니다. 다시 로그인해 주세요.');
        }
        if (onSessionExpired) {
          onSessionExpired();
        }
      },
    });

    // 1. /topic/admin/detections: 신규 탐지 또는 AI 설명 상태 변경
    const unsubDetections = stomp.subscribe('/topic/admin/detections', (payload) => {
      if (!payload || !payload.dtcSeq) return;
      if (payload.rsltCd === 'LOCKED') {
        playEmergencyAlertSound();
        setEmergencyAlerts((prev) => {
          const filtered = prev.filter((item) => item.dtcSeq !== payload.dtcSeq);
          return [payload, ...filtered].slice(0, 5);
        });
        if (onNotify) {
          onNotify(`🚨 [긴급 경보] 탐지 #${payload.dtcSeq} (${payload.ruleCd}) — 계좌 ${payload.acntNo || ''} 긴급 잠금(LOCKED) 발생!`);
        }
      }
      // 선택된 모달의 aiStatCd 등 실시간 반영 및 목록 새로고침
      setSelectedDetection((prev) =>
        prev && (prev.dtcSeq === payload.dtcSeq || prev.id === payload.dtcSeq)
          ? { ...prev, ...payload }
          : prev
      );
      loadDetections(page, ruleFilter, chkFilter);
    });

    // 2. /topic/admin/market: 종목별 시세 지연 시작·해제 ({ tckCd, tckNm, priceDelayed, prcTs })
    const unsubMarket = stomp.subscribe('/topic/admin/market', (payload) => {
      if (!payload || !payload.tckCd) return;
      setDelayedStocksMap((prev) => {
        const next = { ...prev };
        if (payload.priceDelayed) {
          next[payload.tckCd] = payload;
        } else {
          delete next[payload.tckCd];
        }
        return next;
      });
    });

    // 3. /topic/admin/reconcile: 원장 대사 불일치/실패 경보
    const unsubReconcile = stomp.subscribe('/topic/admin/reconcile', (payload) => {
      if (!payload) return;
      setAuditBannerMsg(
        `[대사 경보] 실행 #${payload.rcclRunNo} (${payload.rcclDt}): 상태 ${payload.runStatCd} · 총 ${payload.totAcntCnt ?? 0}계좌 중 불일치 ${payload.diffAcntCnt ?? 0}건 ${payload.failRsnCont || ''}`
      );
      if (onNotify) {
        onNotify(`⚠️ [원장 대사 경보] 실행 #${payload.rcclRunNo} 결과 확인이 필요합니다 (${payload.runStatCd}).`);
      }
      loadReconcileHistory(0);
    });

    // 4. /topic/risk: 위험 의심 종목 목록 실시간 갱신
    const unsubRisk = stomp.subscribe('/topic/risk', (payload) => {
      if (Array.isArray(payload)) {
        setRiskStocks(payload);
      }
    });

    return () => {
      unsubDetections();
      unsubMarket();
      unsubReconcile();
      unsubRisk();
      stomp.disconnect();
    };
  }, [page, ruleFilter, chkFilter, rcclPage]);

  // 수동 원장 대사 실행: POST /api/v1/admin/reconcile
  const handleManualReconcile = async () => {
    setIsAuditing(true);
    try {
      const res = await runServerReconciliation();
      if (res.success) {
        const diffCnt = Number(res.diffAcntCnt ?? 0);
        setAuditBannerMsg(
          `수동 대사 실행 완료 (#${res.rcclRunNo}, ${res.rcclDt}): 상태 ${res.runStatCd} · 전체 ${res.totAcntCnt ?? 0}계좌 중 불일치 ${diffCnt}건`
        );
        if (onNotify) {
          onNotify(
            diffCnt === 0
              ? `[원장 대사] #${res.rcclRunNo} 완료 — 전체 ${res.totAcntCnt}계좌 100% 일치`
              : `⚠️ [원장 대사] #${res.rcclRunNo} 완료 — 불일치 ${diffCnt}계좌 발견`
          );
        }
        await loadReconcileHistory(0);
        await loadReconcileDetail(res.rcclRunNo, diffOnly);
        setActiveSection('RECONCILE');
      } else {
        setAuditBannerMsg(`원장 대사 실행 실패: ${res.message || '오류'}`);
        if (onNotify) onNotify(`[대사 실패] ${res.message}`);
      }
    } finally {
      setIsAuditing(false);
    }
  };

  // 계좌 직접 잠금/해제 실행
  const handleDirectAccountStatus = async (e) => {
    e?.preventDefault();
    setAcntActionMsg('');
    const cleanAcnt = targetAcntNo.trim();
    const cleanReason = targetReason.trim();
    if (!cleanAcnt) {
      setAcntActionMsg('계좌번호를 입력해 주세요.');
      return;
    }
    if (!cleanReason) {
      setAcntActionMsg('조치 사유(메모)를 반드시 입력해 주세요.');
      return;
    }
    setIsChangingAcnt(true);
    try {
      const res = await changeAdminAccountStatus(cleanAcnt, targetStatCd, cleanReason);
      if (res.success) {
        const msg = `계좌 ${res.data.acntNo} 상태가 ${res.data.acntStatCd}(으)로 변경되었습니다.`;
        setAcntActionMsg(msg);
        setTargetReason('');
        if (onNotify) onNotify(`[계좌 조치] ${msg}`);
      } else {
        setAcntActionMsg(`실패: ${res.message}`);
      }
    } finally {
      setIsChangingAcnt(false);
    }
  };

  const delayedStocksList = Object.values(delayedStocksMap);

  const filteredDetections = detections.filter((d) => {
    if (!searchQuery.trim()) return true;
    const q = searchQuery.toLowerCase();
    return (
      String(d.dtcSeq || '').includes(q) ||
      (d.ruleCd || '').toLowerCase().includes(q) ||
      (d.mskLgnId || '').toLowerCase().includes(q) ||
      (d.acntNo || '').toLowerCase().includes(q)
    );
  });

  const uncheckedCount = detections.filter((d) => d.chkYn === 'N').length;
  const lockedResultCount = detections.filter((d) => d.rsltCd === 'LOCKED').length;

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
                <h2>StockShield FDS 보안 관제 센터</h2>
                <span className="live-pulse-badge">
                  <span className={`pulse-dot ${wsConnected ? 'green' : 'amber'}`} />
                  {wsConnected ? 'STOMP 실시간 연결됨 (/ws)' : 'STOMP 재연결 대기 중'}
                </span>
                {delayedStocksList.length > 0 ? (
                  <span
                    className="feed-status-badge text-warning"
                    style={{ background: '#451a1a', color: '#ff7070', borderColor: '#ff4444' }}
                  >
                    <AlertTriangle size={13} style={{ display: 'inline', marginRight: 4 }} />
                    시세 지연 종목: <strong>{delayedStocksList.length}개 (60초 초과)</strong>
                  </span>
                ) : (
                  <span className="feed-status-badge">
                    시세 피드 상태: 정상
                  </span>
                )}
              </div>
              <p className="admin-subtext">
                FDS 규칙 탐지 확인(CHECK) · AI 사유 설명 · 계좌 잠금/해제 · 위험 의심 종목 · 원장 정합성 대사
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
            <span>시세 대시보드 보기</span>
          </button>
        </div>
      </header>

      {/* 긴급 경보 배너 (rsltCd === 'LOCKED' 실시간 수신 시 상단 고정 노출) */}
      {emergencyAlerts.length > 0 && (
        <div style={{ background: '#3b1219', border: '1px solid #ff4d64', borderRadius: '8px', padding: '12px 16px', marginBottom: '14px', display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: '10px', color: '#ff8596', fontSize: '13px' }}>
            <AlertOctagon size={18} />
            <div>
              <strong>🚨 실시간 긴급 경보 ({emergencyAlerts.length}건):</strong>{' '}
              최근 탐지 #{emergencyAlerts[0].dtcSeq} ({RULE_NAME_MAP[emergencyAlerts[0].ruleCd] || emergencyAlerts[0].ruleCd}) — 계좌 {emergencyAlerts[0].acntNo || '-'} 자동 잠금(LOCKED)
            </div>
          </div>
          <div style={{ display: 'flex', gap: '8px' }}>
            <button
              type="button"
              className="btn-table-action btn-urgent"
              onClick={() => setSelectedDetection(emergencyAlerts[0])}
            >
              상세 확인
            </button>
            <button
              type="button"
              className="btn-secondary"
              style={{ padding: '4px 10px', fontSize: '11px' }}
              onClick={() => setEmergencyAlerts([])}
            >
              배너 닫기
            </button>
          </div>
        </div>
      )}

      {/* 관제 지표 요약 카드 그리드 */}
      <div className="admin-kpi-grid">
        <div
          className="admin-kpi-card"
          onClick={() => {
            setActiveSection('DETECTIONS');
            setRuleFilter('ALL');
            setChkFilter('ALL');
            setSearchQuery('');
            setPage(0);
            if (ruleFilter === 'ALL' && chkFilter === 'ALL') {
              loadDetections(0, 'ALL', 'ALL');
            }
          }}
          style={{ cursor: 'pointer' }}
        >
          <div className="kpi-icon-wrap bg-blue">
            <Activity size={20} />
          </div>
          <div className="kpi-info">
            <span className="kpi-title">총 FDS 탐지 기록</span>
            <strong className="kpi-num">{overallTotalCount.toLocaleString()}건</strong>
            <span className="kpi-trend text-muted">새 기기·IP 로그인 · 대량 출금 · 새 IP 직후 주문</span>
          </div>
        </div>

        <div
          className="admin-kpi-card highlight-card"
          onClick={() => {
            setActiveSection('DETECTIONS');
            setRuleFilter('ALL');
            setChkFilter('N');
            setSearchQuery('');
            setPage(0);
            if (ruleFilter === 'ALL' && chkFilter === 'N') {
              loadDetections(0, 'ALL', 'N');
            }
          }}
          style={{ cursor: 'pointer' }}
        >
          <div className="kpi-icon-wrap bg-amber">
            <AlertTriangle size={20} />
          </div>
          <div className="kpi-info">
            <span className="kpi-title">미확인 FDS 탐지</span>
            <strong className="kpi-num text-amber">{overallUncheckedCount}건</strong>
            <span className="kpi-trend text-amber">
              {overallUncheckedCount > 0 ? '⚠️ 관리자 확인 필요' : '모두 확인 완료'}
            </span>
          </div>
        </div>

        <div className="admin-kpi-card" onClick={() => setActiveSection('ACCOUNT_LOCK')} style={{ cursor: 'pointer' }}>
          <div className="kpi-icon-wrap bg-purple">
            <Lock size={20} />
          </div>
          <div className="kpi-info">
            <span className="kpi-title">긴급 잠금(LOCKED) 탐지</span>
            <strong className="kpi-num text-purple">{lockedResultCount}건</strong>
            <span className="kpi-trend text-muted">클릭하여 계좌 잠금·해제 관리</span>
          </div>
        </div>

        <div className="admin-kpi-card" onClick={() => setActiveSection('RISK_STOCKS')} style={{ cursor: 'pointer' }}>
          <div className="kpi-icon-wrap bg-red">
            <TrendingUp size={20} />
          </div>
          <div className="kpi-info">
            <span className="kpi-title">실시간 위험 의심 종목</span>
            <strong className="kpi-num text-red">{riskStocks.length}종목</strong>
            <span className="kpi-trend text-red">급등(UP) · 급락(DOWN) 감지</span>
          </div>
        </div>
      </div>

      {/* 원장 정합성 점검 배너 */}
      <div className="integrity-banner">
        <div className="integrity-left">
          <div className="audit-icon-wrap">
            <CheckCircle2 size={18} className="text-green" />
          </div>
          <div>
            <span className="audit-label">원장 무결성 및 정합성 대사:</span>
            <span className="audit-desc">{auditBannerMsg}</span>
          </div>
        </div>
        <div style={{ display: 'flex', gap: '8px' }}>
          <button
            type="button"
            className="btn-secondary"
            style={{ padding: '7px 12px', fontSize: '12px' }}
            onClick={() => {
              setActiveSection('RECONCILE');
              loadReconcileHistory(0);
            }}
          >
            대사 이력/상세 보기
          </button>
          <button
            type="button"
            className="btn-manual-audit"
            onClick={handleManualReconcile}
            disabled={isAuditing}
          >
            <RefreshCw size={13} className={isAuditing ? 'rotating' : ''} />
            <span>{isAuditing ? '원장 대사 실행 중...' : '원장 전수 대사 즉시 실행'}</span>
          </button>
        </div>
      </div>

      {/* 상단 섹션 전환 탭 */}
      <div style={{ display: 'flex', gap: '8px', marginBottom: '14px' }}>
        {[
          { key: 'DETECTIONS', label: `FDS 탐지 목록 (${overallTotalCount})` },
          { key: 'RISK_STOCKS', label: `위험 의심 종목 (${riskStocks.length})` },
          { key: 'RECONCILE', label: `원장 대사 이력·상세 (${reconcileRuns.length})` },
          { key: 'ACCOUNT_LOCK', label: '계좌 잠금·해제 관리' },
        ].map((tab) => (
          <button
            key={tab.key}
            type="button"
            className={`pill-btn ${activeSection === tab.key ? 'active' : ''}`}
            style={{ padding: '8px 16px', fontSize: '12.5px', fontWeight: 700 }}
            onClick={() => setActiveSection(tab.key)}
          >
            {tab.label}
          </button>
        ))}
      </div>

      {/* SECTION 1: FDS 탐지 목록 */}
      {activeSection === 'DETECTIONS' && (
        <div className="admin-main-section">
          <div className="table-controls-bar">
            <div className="control-group" style={{ flexWrap: 'wrap', gap: '10px' }}>
              <div className="search-box">
                <Search size={15} />
                <input
                  type="text"
                  placeholder="탐지번호, 계좌번호, 마스킹ID 검색..."
                  value={searchQuery}
                  onChange={(e) => setSearchQuery(e.target.value)}
                />
              </div>

              <div className="filter-pill-group">
                <span className="pill-group-label">탐지 규칙:</span>
                {[
                  { code: 'ALL', label: '전체' },
                  { code: 'LOGIN_NEW_ACCESS', label: '새 기기·IP 로그인' },
                  { code: 'WITHDRAW_LARGE', label: '대량 출금' },
                  { code: 'TRADE_NEW_IP', label: '새 IP 직후 주문' },
                ].map((r) => (
                  <button
                    key={r.code}
                    type="button"
                    className={`pill-btn ${ruleFilter === r.code ? 'active' : ''}`}
                    onClick={() => {
                      setPage(0);
                      setRuleFilter(r.code);
                    }}
                  >
                    {r.label}
                  </button>
                ))}
              </div>

              <div className="filter-pill-group">
                <span className="pill-group-label">확인 상태:</span>
                {[
                  { code: 'ALL', label: '전체' },
                  { code: 'N', label: '미확인 (N)' },
                  { code: 'Y', label: '확인 완료 (Y)' },
                ].map((st) => (
                  <button
                    key={st.code}
                    type="button"
                    className={`pill-btn ${chkFilter === st.code ? 'active' : ''}`}
                    onClick={() => {
                      setPage(0);
                      setChkFilter(st.code);
                    }}
                  >
                    {st.label}
                  </button>
                ))}
              </div>

              <button
                type="button"
                className="pill-btn"
                onClick={() => loadDetections(page, ruleFilter, chkFilter)}
                disabled={loadingDetections}
              >
                <RefreshCw size={13} className={loadingDetections ? 'rotating' : ''} /> 새로고침
              </button>
            </div>
          </div>

          <div className="admin-table-wrap">
            <table className="admin-table">
              <thead>
                <tr>
                  <th>탐지 번호</th>
                  <th>탐지 시각</th>
                  <th>규칙</th>
                  <th>회원 ID (마스킹)</th>
                  <th>계좌번호</th>
                  <th>판정 결과</th>
                  <th>확인 여부</th>
                  <th>상세 및 조치</th>
                </tr>
              </thead>
              <tbody>
                {loadingDetections ? (
                  <tr>
                    <td colSpan="8" className="empty-table-cell">
                      탐지 기록을 불러오는 중입니다...
                    </td>
                  </tr>
                ) : filteredDetections.length === 0 ? (
                  <tr>
                    <td colSpan="8" className="empty-table-cell">
                      조건에 해당하는 FDS 탐지 기록이 없습니다.
                    </td>
                  </tr>
                ) : (
                  filteredDetections.map((d) => {
                    const isUnchecked = d.chkYn === 'N';
                    const isLockedResult = d.rsltCd === 'LOCKED';

                    return (
                      <tr key={d.dtcSeq} className={isUnchecked ? 'row-highlight-hold' : ''}>
                        <td>
                          <span className="alert-code">#{d.dtcSeq}</span>
                        </td>
                        <td className="time-col">{formatDateTime(d.regTs)}</td>
                        <td>
                          <strong>{RULE_NAME_MAP[d.ruleCd] || d.ruleCd}</strong>
                          <span className="code-sub" style={{ display: 'block' }}>{d.ruleCd}</span>
                        </td>
                        <td>
                          <strong>{d.mskLgnId || '-'}</strong>
                        </td>
                        <td>
                          <span className="acc-sub">{d.acntNo || '-'}</span>
                        </td>
                        <td>
                          <span
                            className={`status-pill ${
                              isLockedResult
                                ? 'rejected'
                                : d.rsltCd === 'PENDING'
                                ? 'hold'
                                : 'executed'
                            }`}
                          >
                            {d.rsltCd}
                          </span>
                        </td>
                        <td>
                          <span className={`status-pill ${d.chkYn === 'Y' ? 'executed' : 'hold'}`}>
                            {d.chkYn === 'Y' ? '확인 완료 (Y)' : '미확인 (N)'}
                          </span>
                        </td>
                        <td>
                          <button
                            type="button"
                            className={`btn-table-action ${isUnchecked ? 'btn-urgent' : ''}`}
                            onClick={() => setSelectedDetection(d)}
                          >
                            <Bot size={13} />
                            <span>{isUnchecked ? '상세 확인·조치' : '상세 보기'}</span>
                          </button>
                        </td>
                      </tr>
                    );
                  })
                )}
              </tbody>
            </table>
          </div>

          {/* 페이징 */}
          <div style={{ display: 'flex', justifyContent: 'center', alignItems: 'center', gap: '10px', padding: '14px' }}>
            <button
              type="button"
              className="pill-btn"
              disabled={page <= 0 || loadingDetections}
              onClick={() => {
                const next = page - 1;
                setPage(next);
                loadDetections(next, ruleFilter, chkFilter);
              }}
            >
              <ChevronLeft size={14} /> 이전
            </button>
            <span style={{ fontSize: '12px', color: '#9cb3d0' }}>
              {totalPages > 0 ? `${page + 1} / ${totalPages} 페이지` : '1 / 1 페이지'}
            </span>
            <button
              type="button"
              className="pill-btn"
              disabled={page + 1 >= totalPages || loadingDetections}
              onClick={() => {
                const next = page + 1;
                setPage(next);
                loadDetections(next, ruleFilter, chkFilter);
              }}
            >
              다음 <ChevronRight size={14} />
            </button>
          </div>
        </div>
      )}

      {/* SECTION 2: 위험 의심 종목 목록 (/api/v1/market/risk-stocks & /topic/risk) */}
      {activeSection === 'RISK_STOCKS' && (
        <div className="admin-main-section">
          <div className="table-controls-bar" style={{ justifyContent: 'space-between' }}>
            <strong style={{ color: '#e5ecf4', fontSize: '14px' }}>
              실시간 위험 의심 종목 목록
            </strong>
            <button type="button" className="pill-btn" onClick={loadRiskStocks} disabled={loadingRiskStocks}>
              <RefreshCw size={13} className={loadingRiskStocks ? 'rotating' : ''} /> 새로고침
            </button>
          </div>
          <div className="admin-table-wrap">
            <table className="admin-table">
              <thead>
                <tr>
                  <th>종목코드</th>
                  <th>종목명</th>
                  <th>방향</th>
                  <th>변동률</th>
                  <th>위험 판단 사유</th>
                  <th>탐지 시각</th>
                </tr>
              </thead>
              <tbody>
                {riskStocks.length === 0 ? (
                  <tr>
                    <td colSpan="6" className="empty-table-cell">
                      현재 감지된 위험 의심 종목이 없습니다 (또는 시세 60초 이상 지연 시 자동 비움).
                    </td>
                  </tr>
                ) : (
                  riskStocks.map((r) => (
                    <tr key={r.tckCd}>
                      <td><strong>{r.tckCd}</strong></td>
                      <td><strong>{r.tckNm}</strong></td>
                      <td>
                        <span className={`type-tag ${r.dir === 'UP' || r.dir === 'SURGE' ? 'buy' : 'sell'}`}>
                          {r.dir === 'UP' || r.dir === 'SURGE' ? '급등 (UP)' : '급락 (DOWN)'}
                        </span>
                      </td>
                      <td>
                        <strong className={Number(r.chgRt) >= 0 ? 'rise' : 'fall'}>
                          {Number(r.chgRt) >= 0 ? '+' : ''}{Number(r.chgRt).toFixed(2)}%
                        </strong>
                      </td>
                      <td>{r.reason}</td>
                      <td className="time-col">{formatDateTime(r.dtcTs)}</td>
                    </tr>
                  ))
                )}
              </tbody>
            </table>
          </div>
        </div>
      )}

      {/* SECTION 3: 원장 대사 실행 이력 및 상세 (/api/v1/admin/reconcile) */}
      {activeSection === 'RECONCILE' && (
        <div className="admin-main-section" style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '16px', padding: '16px' }}>
          <div>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '10px' }}>
              <strong style={{ color: '#e5ecf4', fontSize: '14px' }}>원장 대사 실행 이력</strong>
              <button type="button" className="pill-btn" onClick={() => loadReconcileHistory(rcclPage)}>
                <RefreshCw size={13} /> 새로고침
              </button>
            </div>
            <div className="admin-table-wrap">
              <table className="admin-table">
                <thead>
                  <tr>
                    <th>실행번호</th>
                    <th>일자 / 구분</th>
                    <th>상태</th>
                    <th>전체 / 불일치</th>
                    <th>실행 시각</th>
                    <th>상세</th>
                  </tr>
                </thead>
                <tbody>
                  {reconcileRuns.length === 0 ? (
                    <tr>
                      <td colSpan="6" className="empty-table-cell">대사 실행 이력이 없습니다.</td>
                    </tr>
                  ) : (
                    reconcileRuns.map((run) => (
                      <tr key={run.rcclRunNo} className={run.diffAcntCnt > 0 || run.runStatCd === 'FAILED' ? 'row-highlight-hold' : ''}>
                        <td><strong>#{run.rcclRunNo}</strong></td>
                        <td>
                          <div>{run.rcclDt}</div>
                          <small style={{ color: '#8fa0b5' }}>
                            {run.trgDvCd === 'SCHEDULED' ? '정기' : run.trgDvCd === 'MANUAL' ? '수동' : run.trgDvCd}
                          </small>
                        </td>
                        <td>
                          <span className={`status-pill ${run.runStatCd === 'DONE' ? 'executed' : run.runStatCd === 'FAILED' ? 'rejected' : 'hold'}`}>
                            {run.runStatCd === 'DONE' ? '완료' : run.runStatCd === 'FAILED' ? '실패' : run.runStatCd}
                          </span>
                        </td>
                        <td>
                          {run.totAcntCnt ?? 0} / <strong style={{ color: run.diffAcntCnt > 0 ? '#ff6b7b' : '#4cd49e' }}>{run.diffAcntCnt ?? 0}</strong>
                        </td>
                        <td className="time-col">{formatDateTime(run.regTs)}</td>
                        <td>
                          <button
                            type="button"
                            className="btn-table-action"
                            onClick={() => loadReconcileDetail(run.rcclRunNo, diffOnly)}
                          >
                            상세 조회
                          </button>
                        </td>
                      </tr>
                    ))
                  )}
                </tbody>
              </table>
            </div>
          </div>

          <div>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '10px' }}>
              <strong style={{ color: '#e5ecf4', fontSize: '14px' }}>
                {selectedRunDetail
                  ? `대사 #${selectedRunDetail.rcclRunNo} 상세 결과 (${selectedRunDetail.rcclDt} · ${selectedRunDetail.runStatCd === 'DONE' ? '완료' : selectedRunDetail.runStatCd === 'FAILED' ? '실패' : selectedRunDetail.runStatCd})`
                  : '대사 상세 결과 (좌측에서 실행 이력을 선택하세요)'}
              </strong>
              {selectedRunDetail && (
                <div style={{ display: 'flex', gap: '6px' }}>
                  <button
                    type="button"
                    className={`pill-btn ${diffOnly ? 'active' : ''}`}
                    onClick={() => {
                      setDiffOnly(true);
                      loadReconcileDetail(selectedRunDetail.rcclRunNo, true);
                    }}
                  >
                    불일치만
                  </button>
                  <button
                    type="button"
                    className={`pill-btn ${!diffOnly ? 'active' : ''}`}
                    onClick={() => {
                      setDiffOnly(false);
                      loadReconcileDetail(selectedRunDetail.rcclRunNo, false);
                    }}
                  >
                    전체 항목
                  </button>
                </div>
              )}
            </div>

            <div className="admin-table-wrap">
              <table className="admin-table">
                <thead>
                  <tr>
                    <th>계좌번호</th>
                    <th>점검 항목</th>
                    <th>종목코드</th>
                    <th>기대값</th>
                    <th>실제값</th>
                    <th>불일치</th>
                  </tr>
                </thead>
                <tbody>
                  {loadingRunDetail ? (
                    <tr>
                      <td colSpan="6" className="empty-table-cell">상세 결과를 불러오는 중입니다...</td>
                    </tr>
                  ) : !selectedRunDetail ? (
                    <tr>
                      <td colSpan="6" className="empty-table-cell">좌측 목록에서 [상세 조회]를 클릭하세요.</td>
                    </tr>
                  ) : (selectedRunDetail.results || []).length === 0 ? (
                    <tr>
                      <td colSpan="6" className="empty-table-cell">
                        {diffOnly ? '불일치 항목이 없습니다 (원장 100% 일치).' : '점검 결과 항목이 없습니다.'}
                      </td>
                    </tr>
                  ) : (
                    selectedRunDetail.results.map((item, idx) => (
                      <tr key={`${item.acntNo}-${item.chkItemCd}-${item.tckCd || idx}`}>
                        <td><code>{item.acntNo}</code></td>
                        <td>{item.chkItemCd}</td>
                        <td>{item.tckCd || '-'}</td>
                        <td>{Number(item.expVal ?? 0).toLocaleString('ko-KR')}</td>
                        <td>{Number(item.actlVal ?? 0).toLocaleString('ko-KR')}</td>
                        <td>
                          <span className={`status-pill ${item.diffYn === 'Y' ? 'rejected' : 'executed'}`}>
                            {item.diffYn}
                          </span>
                        </td>
                      </tr>
                    ))
                  )}
                </tbody>
              </table>
            </div>
          </div>
        </div>
      )}

      {/* SECTION 4: 계좌 직접 잠금·해제 관리 (PUT /api/v1/admin/accounts/{acntNo}/status) */}
      {activeSection === 'ACCOUNT_LOCK' && (
        <div className="admin-main-section" style={{ padding: '22px', maxWidth: '580px' }}>
          <h3 style={{ color: '#e5ecf4', fontSize: '16px', marginBottom: '8px' }}>
            계좌 상태 직접 변경 (잠금 LOCKED / 해제 ACTIVE)
          </h3>
          <p style={{ color: '#8fa0b5', fontSize: '12.5px', marginBottom: '16px' }}>
            탐지 상세 모달뿐 아니라 계좌번호를 직접 지정하여 잠금(LOCKED) 또는 잠금 해제(ACTIVE) 조치를 수행할 수 있습니다.
          </p>
          {acntActionMsg && (
            <div style={{ background: '#182b3e', border: '1px solid #355880', borderRadius: '6px', padding: '10px 14px', marginBottom: '14px', color: '#b8d9ff', fontSize: '12.5px' }}>
              {acntActionMsg}
            </div>
          )}
          <form onSubmit={handleDirectAccountStatus} style={{ display: 'flex', flexDirection: 'column', gap: '12px' }}>
            <div className="form-group">
              <label htmlFor="admin-acnt-no">대상 계좌번호</label>
              <div className="form-control">
                <input
                  id="admin-acnt-no"
                  type="text"
                  value={targetAcntNo}
                  onChange={(e) => setTargetAcntNo(e.target.value)}
                  placeholder="예: 100000000018"
                  required
                />
              </div>
            </div>
            <div className="form-group">
              <label>변경할 상태</label>
              <div style={{ display: 'flex', gap: '8px' }}>
                <button
                  type="button"
                  className={`pill-btn ${targetStatCd === 'LOCKED' ? 'active' : ''}`}
                  onClick={() => setTargetStatCd('LOCKED')}
                >
                  <Lock size={13} /> 계좌 잠금 (LOCKED)
                </button>
                <button
                  type="button"
                  className={`pill-btn ${targetStatCd === 'ACTIVE' ? 'active' : ''}`}
                  onClick={() => setTargetStatCd('ACTIVE')}
                >
                  <Unlock size={13} /> 잠금 해제 (ACTIVE)
                </button>
              </div>
            </div>
            <div className="form-group">
              <label htmlFor="admin-acnt-reason">조치 사유 메모 (필수)</label>
              <div className="form-control">
                <input
                  id="admin-acnt-reason"
                  type="text"
                  value={targetReason}
                  onChange={(e) => setTargetReason(e.target.value)}
                  placeholder="예: 고객 본인 확인 완료 후 잠금 해제"
                  required
                />
              </div>
            </div>
            <button type="submit" className="primary-btn" disabled={isChangingAcnt}>
              {isChangingAcnt ? '처리 중...' : `계좌 ${targetStatCd} 조치 실행`}
            </button>
          </form>
        </div>
      )}

      {/* 탐지 상세 및 AI 설명 / 확인 완료 / 계좌 잠금·해제 모달 */}
      <AlertDetailModal
        alert={selectedDetection}
        isOpen={Boolean(selectedDetection)}
        onClose={() => setSelectedDetection(null)}
        onUpdated={() => loadDetections(page, ruleFilter, chkFilter)}
        onNotify={onNotify}
      />
    </div>
  );
}
