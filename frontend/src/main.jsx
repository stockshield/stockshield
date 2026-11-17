import React, { useMemo, useState, useEffect, useRef } from 'react';
import { createRoot } from 'react-dom/client';
import {
  Activity,
  ArrowDownLeft,
  ArrowRight,
  ArrowUpRight,
  Bell,
  CandlestickChart,
  ChartNoAxesCombined,
  Check,
  ChevronDown,
  CircleHelp,
  Clock,
  Clock3,
  Calendar,
  Eye,
  EyeOff,
  LockKeyhole,
  Menu,
  Search,
  Shield,
  ShieldCheck,
  ShieldAlert,
  TrendingUp,
  Wallet,
  X,
  LogOut,
  Star,
  User,
  Sparkles,
  AlertCircle,
  AlertTriangle,
  Coins,
  Maximize2,
  Minimize2,
  ZoomIn,
  ZoomOut,
  RotateCcw,
  MoveHorizontal
} from 'lucide-react';
import './styles.css';
import SignUpModal from './components/SignUpModal';
import OrderModal from './components/OrderModal';
import TradingOrderPanel from './components/TradingOrderPanel';
import NewsPanel from './components/NewsPanel';
import OrderHistoryModal from './components/OrderHistoryModal';
import WithdrawalModal from './components/WithdrawalModal';
import AdminFdsPanel from './components/AdminFdsPanel';
import {
  getActiveSession,
  authenticateUser,
  verifyNewDeviceLogin,
  resendNewDeviceEmailCode,
  logoutUser,
  clearActiveSession,
  toggleUserWatchlist,
  getStoredAlerts,
  switchSessionUser,
  syncFromPostgres,
  fetchMyAccount,
  fetchMarketSummary,
  fetchStockDetail,
  fetchStockPeriodCandles,
  fetchStockOrderbook,
  fetchRiskStocks,
  searchMarketStocks,
  apiRequest,
  INITIAL_DEMO_USER
} from './utils/storage';
import { createStompManager } from './utils/stompClient';
import { DEFAULT_STOCKS } from './stocksData';

const won = (x) => Math.round(Number(x || 0)).toLocaleString('ko-KR');
const fmtPrice = (x) => `₩${won(x)}`;

const MARKET_INDICES = [
  { name: '코스피', en: 'KOSPI', value: '2,583.42', diff: '▲ 18.71', change: '+0.73%', isUp: true },
  { name: '코스닥', en: 'KOSDAQ', value: '748.16', diff: '▼ 3.22', change: '-0.43%', isUp: false },
  { name: '코스피 200', en: 'KOSPI 200', value: '346.20', diff: '▲ 2.85', change: '+0.83%', isUp: true },
  { name: '원 / 달러', en: 'USD/KRW', value: '1,368.50', diff: '▲ 2.80', change: '+0.21%', isUp: true },
];

const TICKER_ITEMS = [
  ...MARKET_INDICES,
  ...MARKET_INDICES,
  ...MARKET_INDICES,
  ...MARKET_INDICES,
];

async function fetchStockPrice(code, signal) {
  const res = await apiRequest(`/market/stocks/${code}`, { signal, skipAuth: true });
  if (!res.ok || !res.data) {
    throw new Error(res.message || '주식 시세 조회 실패');
  }
  const d = res.data;
  return {
    code: d.tckCd,
    name: d.tckNm,
    market: d.mktCd,
    price: d.currentPrice != null ? Number(d.currentPrice) : undefined,
    change: d.chgRt != null ? Number(d.chgRt) : undefined,
    chgPrc: d.chgPrc != null ? Number(d.chgPrc) : undefined,
    volume: d.totalTrdVol != null ? Number(d.totalTrdVol) : undefined,
    prcTs: d.prcTs,
    priceDelayed: Boolean(d.priceDelayed),
    chart: Array.isArray(d.chart) ? d.chart : [],
    risk: d.risk || { riskYn: 'N', dir: null, reason: null },
    source: 'KIS',
  };
}

const INTERVAL_CONFIGS = {
  '1분': { count: 80, startRatio: 0.995, waveAmp: 0.003, wickAmp: 0.0012 },
  '5분': { count: 80, startRatio: 0.991, waveAmp: 0.0045, wickAmp: 0.0016 },
  '10분': { count: 80, startRatio: 0.987, waveAmp: 0.0055, wickAmp: 0.0018 },
  '30분': { count: 70, startRatio: 0.982, waveAmp: 0.0065, wickAmp: 0.0020 },
  '1시간': { count: 75, startRatio: 0.975, waveAmp: 0.008, wickAmp: 0.0022 },
  '3시간': { count: 70, startRatio: 0.965, waveAmp: 0.0095, wickAmp: 0.0025 },
  '1일': { count: 120, startRatio: 0.978, waveAmp: 0.007, wickAmp: 0.0025 },
  '1주': { count: 70, startRatio: 0.930, waveAmp: 0.012, wickAmp: 0.0035 },
  '1개월': { count: 50, startRatio: 0.970, waveAmp: 0.008, wickAmp: 0.0025 },
  '3개월': { count: 100, startRatio: 0.940, waveAmp: 0.012, wickAmp: 0.0035 },
};

// KRX 영업일(주말 제외) 및 일자/시간 포맷터
function formatKRXDate(d, fmt = 'MM/DD') {
  const yyyy = d.getFullYear();
  const mm = String(d.getMonth() + 1).padStart(2, '0');
  const dd = String(d.getDate()).padStart(2, '0');
  const yy = String(yyyy).slice(-2);
  const days = ['일', '월', '화', '수', '목', '금', '토'];
  const dayName = days[d.getDay()];

  if (fmt === 'YYYY.MM.DD') return `${yyyy}.${mm}.${dd}`;
  if (fmt === 'YYYY.MM.DD (DAY)') return `${yyyy}.${mm}.${dd} (${dayName})`;
  if (fmt === 'YY.MM.DD') return `${yy}.${mm}.${dd}`;
  if (fmt === 'YY.MM') return `${yy}.${mm}`;
  if (fmt === 'YYYY.MM') return `${yyyy}.${mm}`;
  return `${mm}/${dd}`;
}

function getIntervalBadgeLabel(interval) {
  if (interval === '1일') return '일봉';
  if (interval === '1주') return '주봉';
  if (interval === '1개월') return '월봉';
  if (interval === '3개월') return '3개월봉';
  if (interval === '6개월') return '6개월봉';
  if (interval === '1년') return '년봉';
  return `${interval} 봉`;
}

// 직전 N개의 KRX 영업일(주말 제외) 배열 생성 (과거 -> 현재 순서)
function getRecentTradingDays(count, endDate = new Date()) {
  const days = [];
  const curr = new Date(endDate);
  while (days.length < count) {
    const dayOfWeek = curr.getDay();
    if (dayOfWeek !== 0 && dayOfWeek !== 6) {
      days.unshift(new Date(curr));
    }
    curr.setDate(curr.getDate() - 1);
  }
  return days;
}

// 각 주기(일봉, 주봉, 월봉, 분봉)별 캔들 타임스탬프 및 X축 라벨 생성
function generateCandleTimestamps(interval, count) {
  const now = new Date();

  if (interval === '1일') {
    // 1일 (일봉): 최근 약 2개월(46영업일) KRX 실제 영업일
    const tradingDays = getRecentTradingDays(count, now);
    return tradingDays.map((d) => ({
      dateStr: formatKRXDate(d, 'YYYY.MM.DD (DAY)'),
      label: formatKRXDate(d, 'MM/DD'),
    }));
  }

  if (interval === '1개월') {
    // 1개월: 최근 1개월간(약 22영업일)의 실제 KRX 거래일자 (MM/DD)
    const tradingDays = getRecentTradingDays(count, now);
    return tradingDays.map((d) => ({
      dateStr: formatKRXDate(d, 'YYYY.MM.DD (DAY)'),
      label: formatKRXDate(d, 'MM/DD'),
    }));
  }

  if (interval === '3개월') {
    // 3개월: 최근 3개월간(약 60영업일)의 실제 KRX 거래일자 (MM/DD: 08/11, 09/01, 09/22, 10/15, 11/05 등)
    const tradingDays = getRecentTradingDays(count, now);
    return tradingDays.map((d) => ({
      dateStr: formatKRXDate(d, 'YYYY.MM.DD (DAY)'),
      label: formatKRXDate(d, 'MM/DD'),
    }));
  }

  if (interval === '1주') {
    // 1주 (주봉): 최근 N개 주의 시작일(월요일)
    const weeks = [];
    const curr = new Date(now);
    const day = curr.getDay();
    const diff = curr.getDate() - day + (day === 0 ? -6 : 1);
    curr.setDate(diff);
    for (let i = 0; i < count; i++) {
      weeks.unshift(new Date(curr));
      curr.setDate(curr.getDate() - 7);
    }
    return weeks.map((d) => {
      const endOfWeek = new Date(d);
      endOfWeek.setDate(d.getDate() + 4);
      return {
        dateStr: `${formatKRXDate(d, 'YYYY.MM.DD')} ~ ${formatKRXDate(endOfWeek, 'MM.DD')} 주차`,
        label: formatKRXDate(d, 'YY.MM.DD'),
      };
    });
  }

  if (interval === '3시간') {
    // 3시간봉: 최근 영업일의 오전(09:00~12:00)과 오후(12:00~15:30)
    const dayCount = Math.ceil(count / 2);
    const tradingDays = getRecentTradingDays(dayCount, now);
    const result = [];
    tradingDays.forEach((d) => {
      const dStr = formatKRXDate(d, 'YYYY.MM.DD');
      const mmdd = formatKRXDate(d, 'MM/DD');
      result.push({ dateStr: `${dStr} 09:00~12:00`, label: `${mmdd} 09시` });
      result.push({ dateStr: `${dStr} 12:00~15:30`, label: `${mmdd} 12시` });
    });
    return result.slice(-count);
  }

  // 분봉/시간봉 (1분, 5분, 10분, 30분, 1시간)
  let stepMinutes = 1;
  if (interval === '5분') stepMinutes = 5;
  else if (interval === '10분') stepMinutes = 10;
  else if (interval === '30분') stepMinutes = 30;
  else if (interval === '1시간') stepMinutes = 60;

  // 한국거래소(KRX) 정규장(09:00 ~ 15:30) 내의 캔들 시각 슬롯 목록 생성 (오름차순)
  const daySlots = [];
  let currMin = 9 * 60 + stepMinutes;
  while (currMin <= 15 * 60 + 30) {
    const hh = String(Math.floor(currMin / 60)).padStart(2, '0');
    const mm = String(currMin % 60).padStart(2, '0');
    daySlots.push({ hh, mm, totalMin: currMin });
    currMin += stepMinutes;
  }
  // 장 마감(15:30) 종가봉 보장
  if (daySlots.length === 0 || daySlots[daySlots.length - 1].totalMin !== 15 * 60 + 30) {
    daySlots.push({ hh: '15', mm: '30', totalMin: 15 * 60 + 30 });
  }

  const slotsPerDay = daySlots.length;
  const daysNeeded = Math.ceil(count / slotsPerDay);
  const tradingDays = getRecentTradingDays(daysNeeded, now);

  const allSlots = [];
  tradingDays.forEach((d) => {
    const dStr = formatKRXDate(d, 'YYYY.MM.DD');
    const mmdd = formatKRXDate(d, 'MM/DD');
    daySlots.forEach((slot) => {
      allSlots.push({
        dateStr: `${dStr} ${slot.hh}:${slot.mm}`,
        label: daysNeeded > 1 ? `${mmdd} ${slot.hh}:${slot.mm}` : `${slot.hh}:${slot.mm}`,
      });
    });
  });

  return allSlots.slice(-count);
}

function getIntervalLabels(interval, candles = []) {
  if (!candles || candles.length === 0) {
    return ['09:00', '10:30', '12:00', '13:30', '15:30'];
  }
  const n = candles.length;
  const indices = [0, Math.floor(n * 0.25), Math.floor(n * 0.5), Math.floor(n * 0.75), n - 1];
  return indices.map((idx) => candles[idx]?.label || '');
}

// 백엔드 1분봉 배열(chart: [{ bsMi: 'YYYYMMDDHHmm', openPrc, highPrc, lowPrc, closePrc, trdVol }])을 차트 캔들로 변환
function formatBsMi(bsMi) {
  const s = String(bsMi || '');
  if (s.length >= 12) {
    const yyyy = s.slice(0, 4);
    const mm = s.slice(4, 6);
    const dd = s.slice(6, 8);
    const hh = s.slice(8, 10);
    const mi = s.slice(10, 12);
    return {
      dateStr: `${yyyy}.${mm}.${dd} ${hh}:${mi}`,
      label: `${hh}:${mi}`,
    };
  }
  return { dateStr: s, label: s };
}

function adaptChartBars(rawChart = []) {
  if (!Array.isArray(rawChart) || rawChart.length === 0) return [];
  return rawChart.map((bar) => {
    const ts = formatBsMi(bar.bsMi);
    return {
      bsMi: bar.bsMi,
      open: Number(bar.openPrc ?? bar.open ?? 0),
      high: Number(bar.highPrc ?? bar.high ?? 0),
      low: Number(bar.lowPrc ?? bar.low ?? 0),
      close: Number(bar.closePrc ?? bar.close ?? 0),
      volume: Number(bar.trdVol ?? bar.volume ?? 0),
      dateStr: ts.dateStr,
      label: ts.label,
    };
  });
}

function aggregateMinuteBars(oneMinCandles = [], interval = '1분') {
  if (!Array.isArray(oneMinCandles) || oneMinCandles.length === 0) return [];
  let groupSize = 1;
  if (interval === '3분') groupSize = 3;
  else if (interval === '5분') groupSize = 5;
  else if (interval === '10분') groupSize = 10;
  else if (interval === '15분') groupSize = 15;
  else if (interval === '30분') groupSize = 30;
  else if (interval === '1시간') groupSize = 60;
  else if (interval === '3시간') groupSize = 180;
  else if (interval === '6시간') groupSize = 360;
  else if (['1일', '1주', '1개월', '3개월', '6개월', '1년'].includes(interval)) {
    groupSize = oneMinCandles.length;
  }

  if (groupSize <= 1) return oneMinCandles;

  // 09:00(540분) 기준으로 N분 단위 시간 구간 버킷에 묶음 (예: 5분봉 = 09:00~09:04, 09:05~09:09 ...)
  const buckets = new Map();
  for (const bar of oneMinCandles) {
    const s = String(bar.bsMi || '');
    if (s.length < 12) continue;
    const ymd = s.slice(0, 8);
    const totalMin = Number(s.slice(8, 10)) * 60 + Number(s.slice(10, 12));
    const bucketIdx = Math.floor((totalMin - 540) / groupSize);
    const key = `${ymd}:${bucketIdx}`;
    if (!buckets.has(key)) {
      const startMin = 540 + bucketIdx * groupSize;
      const bHH = String(Math.floor(startMin / 60)).padStart(2, '0');
      const bMI = String(startMin % 60).padStart(2, '0');
      buckets.set(key, {
        ymd,
        bHH,
        bMI,
        bars: [],
      });
    }
    buckets.get(key).bars.push(bar);
  }

  const aggregated = [];
  for (const bucket of buckets.values()) {
    const { ymd, bHH, bMI, bars } = bucket;
    const first = bars[0];
    const last = bars[bars.length - 1];
    aggregated.push({
      bsMi: last.bsMi,
      open: first.open,
      close: last.close,
      high: Math.max(...bars.map((c) => c.high)),
      low: Math.min(...bars.map((c) => c.low)),
      volume: bars.reduce((acc, c) => acc + (Number(c.volume) || 0), 0),
      dateStr: `${ymd.slice(0, 4)}.${ymd.slice(4, 6)}.${ymd.slice(6, 8)} ${bHH}:${bMI}`,
      label: `${bHH}:${bMI}`,
    });
  }
  return aggregated;
}

function aggregateMonthlyBars(monthlyCandles = [], monthsPerBar = 3) {
  if (!Array.isArray(monthlyCandles) || monthlyCandles.length === 0) return [];
  if (monthsPerBar <= 1) return monthlyCandles;

  const result = [];
  const n = monthlyCandles.length;
  const remainder = n % monthsPerBar;
  let idx = 0;

  const mergeSlice = (slice) => {
    const first = slice[0];
    const last = slice[slice.length - 1];
    const s1 = String(first.bsMi || '');
    const s2 = String(last.bsMi || '');
    const ym1 = s1.length >= 6 ? `${s1.slice(0, 4)}.${s1.slice(4, 6)}` : s1;
    const ym2 = s2.length >= 6 ? `${s2.slice(0, 4)}.${s2.slice(4, 6)}` : s2;
    return {
      bsMi: last.bsMi,
      open: Number(first.open ?? first.openPrc ?? 0),
      close: Number(last.close ?? last.closePrc ?? 0),
      high: Math.max(...slice.map((c) => Number(c.high ?? c.highPrc ?? 0))),
      low: Math.min(...slice.map((c) => Number(c.low ?? c.lowPrc ?? 0))),
      volume: slice.reduce((acc, c) => acc + (Number(c.volume ?? c.trdVol) || 0), 0),
      dateStr: ym1 === ym2 ? `${ym2} (${monthsPerBar}개월봉)` : `${ym1} ~ ${ym2}`,
      label: s2.length >= 6 ? `${s2.slice(2, 4)}.${s2.slice(4, 6)}` : last.label,
    };
  };

  if (remainder > 0) {
    result.push(mergeSlice(monthlyCandles.slice(0, remainder)));
    idx = remainder;
  }
  while (idx < n) {
    const end = Math.min(idx + monthsPerBar, n);
    result.push(mergeSlice(monthlyCandles.slice(idx, end)));
    idx = end;
  }
  return result;
}

function parseKstTick(prcTs) {
  const parsed = prcTs ? new Date(prcTs) : new Date();
  const baseMs = Number.isNaN(parsed.getTime()) ? Date.now() : parsed.getTime();
  const kst = new Date(baseMs + 9 * 3600_000);
  const yyyy = kst.getUTCFullYear();
  const mmNum = kst.getUTCMonth() + 1;
  const ddNum = kst.getUTCDate();
  const hhNum = kst.getUTCHours();
  const miNum = kst.getUTCMinutes();
  const mm = String(mmNum).padStart(2, '0');
  const dd = String(ddNum).padStart(2, '0');
  const hh = String(hhNum).padStart(2, '0');
  const mi = String(miNum).padStart(2, '0');
  const dayNames = ['일', '월', '화', '수', '목', '금', '토'];
  const dayName = dayNames[kst.getUTCDay()];
  return {
    yyyy,
    mmNum,
    ddNum,
    ymd: `${yyyy}${mm}${dd}`,
    bsMi: `${yyyy}${mm}${dd}${hh}${mi}`,
    mm,
    dd,
    hh,
    mi,
    dayName,
  };
}

function getWeekMondayYmd(ymd) {
  const s = String(ymd || '');
  if (s.length < 8) return s;
  const d = new Date(Date.UTC(Number(s.slice(0, 4)), Number(s.slice(4, 6)) - 1, Number(s.slice(6, 8))));
  const day = d.getUTCDay();
  const diff = day === 0 ? -6 : 1 - day;
  d.setUTCDate(d.getUTCDate() + diff);
  const y = d.getUTCFullYear();
  const m = String(d.getUTCMonth() + 1).padStart(2, '0');
  const dd = String(d.getUTCDate()).padStart(2, '0');
  return `${y}${m}${dd}`;
}

function applyTickToMinuteCandles(prev, tickPrice, prcTs) {
  if (!tickPrice || tickPrice <= 0) return prev;
  const t = parseKstTick(prcTs);
  const dateStr = `${t.yyyy}.${t.mm}.${t.dd} ${t.hh}:${t.mi}`;
  const label = `${t.hh}:${t.mi}`;

  if (!prev || prev.length === 0) {
    return [{
      bsMi: t.bsMi,
      open: tickPrice,
      high: tickPrice,
      low: tickPrice,
      close: tickPrice,
      volume: 0,
      dateStr,
      label,
    }];
  }

  const last = prev[prev.length - 1];
  const lastBsMi = String(last.bsMi || '');
  if (t.bsMi === lastBsMi) {
    return [
      ...prev.slice(0, -1),
      {
        ...last,
        close: tickPrice,
        high: Math.max(last.high, tickPrice),
        low: Math.min(last.low, tickPrice),
      },
    ];
  }
  if (t.bsMi > lastBsMi) {
    return [
      ...prev,
      {
        bsMi: t.bsMi,
        open: tickPrice,
        high: tickPrice,
        low: tickPrice,
        close: tickPrice,
        volume: 0,
        dateStr,
        label,
      },
    ].slice(-390);
  }
  return prev;
}

function applyTickToPeriodCandles(prev, tickPrice, tickVol, prcTs, interval) {
  if (!tickPrice || tickPrice <= 0 || !prev || prev.length === 0) return prev;
  const t = parseKstTick(prcTs);
  const last = prev[prev.length - 1];
  const lastYmd = String(last.bsMi || '');
  if (lastYmd.length < 8) return prev;

  let isSameBucket = false;
  let isNextBucket = false;
  let newDateStr = `${t.yyyy}.${t.mm}.${t.dd} (${t.dayName})`;
  let newLabel = `${t.mm}/${t.dd}`;

  if (interval === '1일') {
    isSameBucket = lastYmd === t.ymd;
    isNextBucket = t.ymd > lastYmd;
  } else if (interval === '1주') {
    const lastW = getWeekMondayYmd(lastYmd);
    const tickW = getWeekMondayYmd(t.ymd);
    isSameBucket = lastW === tickW;
    isNextBucket = tickW > lastW;
    newDateStr = `${t.yyyy}.${t.mm}.${t.dd} (주봉)`;
    newLabel = `${String(t.yyyy).slice(-2)}.${t.mm}.${t.dd}`;
  } else if (interval === '1개월') {
    const lastYm = lastYmd.slice(0, 6);
    const tickYm = t.ymd.slice(0, 6);
    isSameBucket = lastYm === tickYm;
    isNextBucket = tickYm > lastYm;
    newDateStr = `${t.yyyy}년 ${t.mm}월 (월봉)`;
    newLabel = `${String(t.yyyy).slice(-2)}.${t.mm}`;
  } else if (['3개월', '6개월', '1년'].includes(interval)) {
    const bucketMonths = interval === '3개월' ? 3 : interval === '6개월' ? 6 : 12;
    const lastY = Number(lastYmd.slice(0, 4));
    const lastM = Number(lastYmd.slice(4, 6));
    const monthDiff = (t.yyyy * 12 + t.mmNum) - (lastY * 12 + lastM);
    isSameBucket = monthDiff >= 0 && monthDiff < bucketMonths;
    isNextBucket = monthDiff >= bucketMonths;
    newDateStr = `${t.yyyy}.${t.mm} (${interval}봉)`;
    newLabel = `${String(t.yyyy).slice(-2)}.${t.mm}`;
  }

  if (isSameBucket) {
    return [
      ...prev.slice(0, -1),
      {
        ...last,
        close: tickPrice,
        high: Math.max(last.high, tickPrice),
        low: Math.min(last.low, tickPrice),
        volume: interval === '1일' && tickVol > 0 ? Math.max(Number(last.volume || 0), tickVol) : last.volume,
      },
    ];
  }
  if (isNextBucket) {
    return [
      ...prev,
      {
        bsMi: t.ymd,
        open: tickPrice,
        high: tickPrice,
        low: tickPrice,
        close: tickPrice,
        volume: tickVol || 0,
        dateStr: newDateStr,
        label: newLabel,
      },
    ];
  }
  return prev;
}

const CHART_INTERVAL_GROUPS = [
  { group: '분', items: ['1분', '3분', '5분', '10분', '15분', '30분'] },
  { group: '시간', items: ['1시간', '3시간', '6시간'] },
  { group: '기간', items: ['1일', '1주', '1개월', '3개월', '6개월', '1년'] },
];
const ALL_CHART_INTERVALS = CHART_INTERVAL_GROUPS.flatMap((g) => g.items);
const FAVORITE_INTERVALS_KEY = 'stockshield_chart_favorite_intervals';
const DEFAULT_FAVORITE_INTERVALS = ['1분', '15분', '1시간', '1일', '1주', '1개월'];

function getDefaultZoomCount(interval) {
  if (interval === '1일') return 125;   // 약 6개월 (KRX 영업일 기준)
  if (interval === '1주') return 52;    // 약 1년 (52주)
  if (['1개월', '3개월', '6개월', '1년'].includes(interval)) return 9999; // 전체
  return 120; // 분/시간봉: 최근 약 120개
}

function CandleChart({ stock, chartInterval, setChartInterval, candles, hover, setHover }) {
  const currentPrice = stock?.price || 70000;
  const defaultZoom = getDefaultZoomCount(chartInterval);

  // 1. 줌 & 팬 상태 관리 (주기별 기본 표시 범위, 드래그 및 마우스 휠로 조절)
  const [zoomCount, setZoomCount] = useState(() => getDefaultZoomCount(chartInterval));
  const [panOffset, setPanOffset] = useState(0); // 0: 최신 데이터, >0: 과거 데이터
  const [isDragging, setIsDragging] = useState(false);
  const [isFullscreen, setIsFullscreen] = useState(false);
  const [isIntervalMenuOpen, setIsIntervalMenuOpen] = useState(false);
  const [favoriteIntervals, setFavoriteIntervals] = useState(() => {
    try {
      const saved = JSON.parse(localStorage.getItem(FAVORITE_INTERVALS_KEY) || 'null');
      if (Array.isArray(saved) && saved.length > 0) {
        return saved.filter((iv) => ALL_CHART_INTERVALS.includes(iv));
      }
    } catch (e) {
      // ignore
    }
    return DEFAULT_FAVORITE_INTERVALS;
  });

  const chartWrapRef = useRef(null);
  const intervalMenuRef = useRef(null);
  const dragStartRef = useRef({ x: 0, panOffset: 0, hasMoved: false });

  useEffect(() => {
    if (!isIntervalMenuOpen) return;
    const handleOutside = (e) => {
      if (intervalMenuRef.current && !intervalMenuRef.current.contains(e.target)) {
        setIsIntervalMenuOpen(false);
      }
    };
    document.addEventListener('mousedown', handleOutside);
    return () => document.removeEventListener('mousedown', handleOutside);
  }, [isIntervalMenuOpen]);

  const toggleFavoriteInterval = (item, e) => {
    e.stopPropagation();
    setFavoriteIntervals((prev) => {
      const exists = prev.includes(item);
      const next = exists
        ? prev.filter((x) => x !== item)
        : ALL_CHART_INTERVALS.filter((x) => x === item || prev.includes(x));
      const safeNext = next.length > 0 ? next : [item];
      try {
        localStorage.setItem(FAVORITE_INTERVALS_KEY, JSON.stringify(safeNext));
      } catch (err) {
        // ignore
      }
      return safeNext;
    });
  };

  // 주기(interval) 변경 시 주기별 기본 뷰로 리셋
  useEffect(() => {
    setZoomCount(getDefaultZoomCount(chartInterval));
    setPanOffset(0);
    setHover(null);
  }, [chartInterval]);

  // 전체화면 시 ESC 키로 닫기 및 배경 스크롤 방지
  useEffect(() => {
    if (!isFullscreen) return;
    const handleKeyDown = (e) => {
      if (e.key === 'Escape') setIsFullscreen(false);
    };
    document.body.style.overflow = 'hidden';
    window.addEventListener('keydown', handleKeyDown);
    return () => {
      document.body.style.overflow = '';
      window.removeEventListener('keydown', handleKeyDown);
    };
  }, [isFullscreen]);

  // 마우스 휠 확대/축소 (패시브 이벤트 충돌 방지 및 부드러운 줌)
  useEffect(() => {
    const el = chartWrapRef.current;
    if (!el) return;

    const onWheel = (e) => {
      e.preventDefault();
      // 위로 스크롤: 확대 (캔들 수 감소), 아래로 스크롤: 축소 (캔들 수 증가)
      const delta = e.deltaY < 0 ? -4 : 4;
      setZoomCount((prev) => {
        const minCount = 16;
        const maxCount = Math.max(minCount, candles.length);
        return Math.max(minCount, Math.min(maxCount, prev + delta));
      });
    };

    el.addEventListener('wheel', onWheel, { passive: false });
    return () => el.removeEventListener('wheel', onWheel);
  }, [candles.length]);

  // 슬라이스된 화면 표시용 캔들 계산
  const totalCandles = candles.length;
  const minZoom = Math.min(16, totalCandles);
  const clampedZoom = Math.max(minZoom, Math.min(totalCandles, zoomCount));
  const maxOffset = Math.max(0, totalCandles - clampedZoom);
  const clampedOffset = Math.max(0, Math.min(maxOffset, panOffset));

  const startIndex = Math.max(0, totalCandles - clampedZoom - clampedOffset);
  const endIndex = totalCandles - clampedOffset;
  const visibleCandles = candles.slice(startIndex, endIndex);

  // 차트 뷰포트 좌표 (일반 vs 전체화면)
  const W = isFullscreen ? 1240 : 890;
  const H = isFullscreen ? 500 : 360;
  const left = isFullscreen ? 20 : 16;
  const right = isFullscreen ? 84 : 76;
  const top = isFullscreen ? 20 : 14;
  const chartBottom = isFullscreen ? 350 : 255;
  const volumeTop = isFullscreen ? 385 : 274;
  const volumeBottom = isFullscreen ? 450 : 322;
  const xAxisDividerY = isFullscreen ? 456 : 328;
  const xAxisTextY = isFullscreen ? 472 : 342;
  const hoverBadgeY = isFullscreen ? 478 : 346;
  const hoverTextY = isFullscreen ? 491 : 357;

  const high = visibleCandles.length > 0
    ? Math.max(...visibleCandles.map((c) => c.high || 1)) * 1.003
    : (currentPrice || 70000) * 1.01;
  const low = visibleCandles.length > 0
    ? Math.min(...visibleCandles.map((c) => c.low || 1)) * 0.997
    : (currentPrice || 70000) * 0.99;
  const maxVolume = visibleCandles.length > 0
    ? Math.max(...visibleCandles.map((c) => Number(c.volume) || 0), 1)
    : 1;
  const y = (p) => top + ((high - p) / Math.max(1, high - low)) * (chartBottom - top);

  const step = (W - left - right) / Math.max(1, visibleCandles.length);
  const chartContentWidth = W - left - right;
  const xOffset = left;
  const lineY = y(currentPrice);

  const expectedDefaultZoom = Math.max(minZoom, Math.min(totalCandles, defaultZoom));
  const isViewCustomized = clampedOffset > 0 || (totalCandles > 0 && clampedZoom !== expectedDefaultZoom);

  const handleResetView = () => {
    setPanOffset(0);
    setZoomCount(defaultZoom);
    setHover(null);
  };

  // 마우스 좌우 드래그 이동 (과거 / 현재 데이터 탐색)
  const handleMouseDown = (e) => {
    if (e.button !== 0) return;
    setIsDragging(true);
    dragStartRef.current = {
      x: e.clientX,
      panOffset: clampedOffset,
      hasMoved: false,
    };
  };

  useEffect(() => {
    if (!isDragging) return;

    const onMouseMove = (e) => {
      const dx = e.clientX - dragStartRef.current.x;
      if (Math.abs(dx) > 3) {
        dragStartRef.current.hasMoved = true;
        setHover(null);
      }
      const candlePxWidth = (W - left - right) / visibleCandles.length;
      const candleShift = Math.round(dx / Math.max(4, candlePxWidth));
      const maxOff = Math.max(0, candles.length - clampedZoom);
      const newOffset = Math.max(0, Math.min(maxOff, dragStartRef.current.panOffset + candleShift));
      setPanOffset(newOffset);
    };

    const onMouseUp = () => {
      setIsDragging(false);
    };

    window.addEventListener('mousemove', onMouseMove);
    window.addEventListener('mouseup', onMouseUp);
    return () => {
      window.removeEventListener('mousemove', onMouseMove);
      window.removeEventListener('mouseup', onMouseUp);
    };
  }, [isDragging, clampedZoom, candles.length, visibleCandles.length, W, left, right]);

  // 터치 스와이프 드래그 지원
  const handleTouchStart = (e) => {
    if (e.touches.length !== 1) return;
    setIsDragging(true);
    dragStartRef.current = {
      x: e.touches[0].clientX,
      panOffset: clampedOffset,
      hasMoved: false,
    };
  };

  const handleTouchMove = (e) => {
    if (!isDragging || e.touches.length !== 1) return;
    const dx = e.touches[0].clientX - dragStartRef.current.x;
    const candlePxWidth = (W - left - right) / visibleCandles.length;
    const candleShift = Math.round(dx / Math.max(4, candlePxWidth));
    const maxOff = Math.max(0, candles.length - clampedZoom);
    const newOffset = Math.max(0, Math.min(maxOff, dragStartRef.current.panOffset + candleShift));
    setPanOffset(newOffset);
  };

  const handleTouchEnd = () => {
    setIsDragging(false);
  };

  const isHoverVisible = hover !== null && hover >= startIndex && hover < endIndex;
  const visibleHoverIndex = isHoverVisible ? hover - startIndex : null;

  return (
    <div className={`chart-card panel ${isFullscreen ? 'fullscreen-chart-active' : ''}`}>
      <div className="chart-head">
        <div className="section-heading">
          <CandlestickChart size={18} />
          <strong>주가 차트</strong>
          <div className="chart-help-wrap">
            <button type="button" className="chart-help-btn" aria-label="시세 기준 안내" title="시세 기준 안내">
              <CircleHelp size={15} />
            </button>
            <div className="chart-help-tooltip">
              {stock?.source === 'KIS'
                ? '실시간 KIS 현재가 기준으로 스케일된 캔들 차트입니다.'
                : '실제 투자 데이터가 아닌 디자인용 시세입니다.'}
            </div>
          </div>
          <span
            className="chart-active-date-badge"
            style={{
              fontSize: '11px',
              color: '#9cb3d0',
              background: 'rgba(34, 48, 68, 0.75)',
              border: '1px solid #2d3d52',
              padding: '2px 8px',
              borderRadius: '5px',
              fontWeight: 600,
              display: 'inline-flex',
              alignItems: 'center',
              justifyContent: 'center',
              gap: '5px',
              width: '148px',
              minWidth: '148px',
              maxWidth: '148px',
              whiteSpace: 'nowrap',
              flexShrink: 0,
              boxSizing: 'border-box',
            }}
            title="현재 선택 또는 호버된 캔들의 기준 일자 및 시간"
          >
            <Clock size={11} style={{ flexShrink: 0 }} />
            <span style={{ overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap', textAlign: 'center', width: '100%' }}>
              {isHoverVisible && visibleCandles[visibleHoverIndex]
                ? visibleCandles[visibleHoverIndex].dateStr
                : (visibleCandles.at(-1)?.dateStr || '2026.11.06')}
            </span>
          </span>
          {clampedOffset > 0 && (
            <span
              style={{
                fontSize: '11px',
                color: '#ffbe55',
                background: 'rgba(255, 178, 36, 0.12)',
                border: '1px solid rgba(255, 178, 36, 0.35)',
                padding: '2px 8px',
                borderRadius: '5px',
                fontWeight: 600,
              }}
              title="현재 최신 시세가 아닌 과거 특정 구간을 조회 중입니다."
            >
              과거 시세 조회 중 ({clampedOffset}봉 전)
            </span>
          )}
        </div>

        <div className="intervals" role="tablist" aria-label="차트 주기 선택">
          <div className="interval-group">
            {favoriteIntervals.map((item) => (
              <button
                key={item}
                type="button"
                className={chartInterval === item ? 'selected' : ''}
                onClick={() => { setHover(null); setChartInterval(item); }}
              >
                {item}
              </button>
            ))}
            {!favoriteIntervals.includes(chartInterval) && (
              <button
                type="button"
                className="selected"
                onClick={() => { setHover(null); setChartInterval(chartInterval); }}
              >
                {chartInterval}
              </button>
            )}
          </div>

          <div className="interval-dropdown-wrap" ref={intervalMenuRef}>
            <button
              type="button"
              className={`interval-dropdown-trigger ${isIntervalMenuOpen ? 'open' : ''}`}
              onClick={() => setIsIntervalMenuOpen((prev) => !prev)}
              aria-expanded={isIntervalMenuOpen}
              title="전체 시간대 봉 선택 및 즐겨찾기 관리"
            >
              <span>전체 주기</span>
              <ChevronDown size={13} />
            </button>

            {isIntervalMenuOpen && (
              <div className="interval-dropdown-menu panel">
                <div className="interval-dropdown-hint">
                  <Star size={11} fill="#f1cc62" color="#f1cc62" /> 별표로 차트 상단 즐겨찾기 추가/해제
                </div>
                {CHART_INTERVAL_GROUPS.map((grp) => (
                  <div className="interval-dropdown-section" key={grp.group}>
                    <div className="interval-dropdown-group-label">{grp.group}</div>
                    <div className="interval-dropdown-grid">
                      {grp.items.map((item) => {
                        const isFav = favoriteIntervals.includes(item);
                        const isActive = chartInterval === item;
                        return (
                          <div
                            key={item}
                            role="button"
                            tabIndex={0}
                            className={`interval-dropdown-item ${isActive ? 'active' : ''}`}
                            onClick={() => {
                              setHover(null);
                              setChartInterval(item);
                              setIsIntervalMenuOpen(false);
                            }}
                            onKeyDown={(e) => {
                              if (e.key === 'Enter' || e.key === ' ') {
                                setHover(null);
                                setChartInterval(item);
                                setIsIntervalMenuOpen(false);
                              }
                            }}
                          >
                            <span>{item}</span>
                            <button
                              type="button"
                              className={`interval-fav-btn ${isFav ? 'starred' : ''}`}
                              onClick={(e) => toggleFavoriteInterval(item, e)}
                              aria-label={isFav ? `${item} 즐겨찾기 해제` : `${item} 즐겨찾기 추가`}
                              title={isFav ? '즐겨찾기 해제' : '즐겨찾기 추가'}
                            >
                              <Star size={12} fill={isFav ? '#f1cc62' : 'none'} />
                            </button>
                          </div>
                        );
                      })}
                    </div>
                  </div>
                ))}
              </div>
            )}
          </div>
        </div>
      </div>

      <div
        className="chart-wrap"
        ref={chartWrapRef}
        onMouseDown={handleMouseDown}
        onTouchStart={handleTouchStart}
        onTouchMove={handleTouchMove}
        onTouchEnd={handleTouchEnd}
      >
        {visibleCandles.length === 0 ? (
          <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center', minHeight: isFullscreen ? '460px' : '220px', color: '#7e92ab', gap: '8px', fontSize: '13px' }}>
            <CandlestickChart size={32} style={{ color: '#4d6484' }} />
            <strong>수집된 실제 분봉 데이터가 없습니다.</strong>
            <span style={{ fontSize: '11.5px', color: '#5f7490' }}>
              장 운영 중 실시간 체결 시세(price_minute)가 수집되면 실제 봉이 자동으로 표시됩니다.
            </span>
          </div>
        ) : (
          <svg
            className={`candle-svg draggable ${isDragging ? 'is-dragging' : ''}`}
            viewBox={`0 0 ${W} ${H}`}
            role="img"
            aria-label="인터랙티브 캔들 차트"
          >
            {[0, 1, 2, 3, 4].map((i) => {
              const yy = top + (i * (chartBottom - top)) / 4;
              return (
                <g key={i}>
                  <line x1={left} x2={W - right + 6} y1={yy} y2={yy} stroke="#293341" strokeDasharray="3 5" />
                  <text x={W - right + 13} y={yy + 4} fill="#8190a4" fontSize="11">
                    {won(high - ((high - low) * i) / 4)}
                  </text>
                </g>
              );
            })}
            <line x1={left} x2={W - right + 6} y1={volumeTop - 8} y2={volumeTop - 8} stroke="#293341" />
            <text x={W - right + 8} y={volumeTop - 2} fontSize="9" fill="#586e88">
              {maxVolume >= 10000000 ? `${(maxVolume / 10000000).toFixed(1)}천만주` : maxVolume >= 10000 ? `${Math.round(maxVolume / 10000)}만주` : `${won(maxVolume)}주`}
            </text>
            {visibleCandles.map((c, i) => {
              const x = xOffset + i * step + step / 2;
              const up = c.close >= c.open;
              const cColor = up ? '#f06770' : '#4d9cf2';
              const bodyTop = Math.min(y(c.open), y(c.close));
              const bodyH = Math.max(1.7, Math.abs(y(c.open) - y(c.close)));
              const bodyW = Math.max(3, Math.min(20, step * 0.62));
              const volW = Math.max(2, Math.min(18, step * 0.58));
              const vHeight = Math.max(1, Math.min(volumeBottom - volumeTop, ((Number(c.volume) || 0) / maxVolume) * (volumeBottom - volumeTop)));
              return (
                <g
                  key={startIndex + i}
                  onMouseEnter={() => {
                    if (!isDragging) setHover(startIndex + i);
                  }}
                  onMouseLeave={() => {
                    if (!isDragging) setHover(null);
                  }}
                  style={{ cursor: 'crosshair' }}
                >
                  <line x1={x} x2={x} y1={y(c.high)} y2={y(c.low)} stroke={cColor} strokeWidth="1.5" />
                  <rect x={x - bodyW / 2} y={bodyTop} width={bodyW} height={bodyH} rx=".7" fill={cColor} />
                  <rect
                    x={x - volW / 2}
                    y={volumeBottom - vHeight}
                    width={volW}
                    height={vHeight}
                    fill={cColor}
                    opacity=".45"
                  />
                  <rect x={x - step / 2} y={top} width={step} height={volumeBottom - top} fill="transparent" />
                </g>
              );
            })}

            <line x1={left} x2={W - right + 6} y1={lineY} y2={lineY} stroke="#a1b6cf" strokeDasharray="4 4" opacity=".7" />
            <rect x={W - right + 3} y={lineY - 10} width={right - 2} height="20" rx="4" fill="#426782" />
            <text x={W - right + 8} y={lineY + 4} fontSize="11" fill="#fff">
              {won(currentPrice)}
            </text>

            {/* 하단 X축 구분 보조선 */}
            <line x1={left} x2={W - right} y1={xAxisDividerY} y2={xAxisDividerY} stroke="#1b2533" strokeWidth="1" />

            {/* 상단 행: 차트 기준 시간 라벨 (X축) */}
            {visibleCandles.length <= 6
              ? visibleCandles.map((c, i) => (
                <text
                  key={c.label + i}
                  x={xOffset + i * step + step / 2}
                  y={xAxisTextY}
                  fontSize="11"
                  textAnchor="middle"
                  fill="#74859a"
                >
                  {c.label}
                </text>
              ))
              : getIntervalLabels(chartInterval, visibleCandles).map((t, i) => (
                <text
                  key={t + i}
                  x={xOffset + 20 + ((chartContentWidth - 40) * i) / 4}
                  y={xAxisTextY}
                  fontSize="11"
                  textAnchor="middle"
                  fill="#74859a"
                >
                  {t}
                </text>
              ))}

            {/* 하단 행: 커서 호버 시 시간 배지 (위의 기준 시간과 겹치지 않도록 살짝 아래로 내려서 배치) */}
            {isHoverVisible && visibleCandles[visibleHoverIndex] && (
              <g pointerEvents="none">
                <line
                  x1={xOffset + visibleHoverIndex * step + step / 2}
                  x2={xOffset + visibleHoverIndex * step + step / 2}
                  y1={top}
                  y2={hoverBadgeY}
                  stroke="#63a7ff"
                  strokeWidth="1"
                  strokeDasharray="2 3"
                  opacity="0.85"
                />
                <rect
                  x={Math.max(left, Math.min(W - right - 78, xOffset + visibleHoverIndex * step + step / 2 - 39))}
                  y={hoverBadgeY}
                  width="78"
                  height="16"
                  rx="4"
                  fill="#2563eb"
                  stroke="#3b82f6"
                  strokeWidth="1"
                />
                <text
                  x={Math.max(left + 39, Math.min(W - right - 39, xOffset + visibleHoverIndex * step + step / 2))}
                  y={hoverTextY}
                  fontSize="10"
                  fontWeight="700"
                  fill="#ffffff"
                  textAnchor="middle"
                >
                  {visibleCandles[visibleHoverIndex].label}
                </text>
              </g>
            )}
          </svg>
        )}
      </div>

      {/* 차트 박스 내부 OHLC(시가·고가·저가·종가·거래량) 한 줄 요약 (범례/확대·축소 위) */}
      {(() => {
        const displayCandle = hover === null ? candles.at(-1) : candles[hover];
        const displayPercent = displayCandle?.open
          ? (((displayCandle?.close || currentPrice) / displayCandle.open) - 1) * 100
          : Number(stock?.change || 0);
        return (
          <div className="chart-ohlc-inline">
            <span className="chart-ohlc-date">
              <Calendar size={11} />
              <strong>{displayCandle?.dateStr || '-'}</strong>
            </span>
            <div className="chart-ohlc-items">
              <span className="chart-ohlc-item">
                <span>시가</span>
                <b>{won(displayCandle?.open || stock.price)}</b>
              </span>
              <span className="chart-ohlc-item">
                <span>고가</span>
                <b className="rise">{won(displayCandle?.high || stock.price)}</b>
              </span>
              <span className="chart-ohlc-item">
                <span>저가</span>
                <b className="fall">{won(displayCandle?.low || stock.price)}</b>
              </span>
              <span className="chart-ohlc-item">
                <span>종가</span>
                <b className={displayPercent >= 0 ? 'rise' : 'fall'}>
                  {won(displayCandle?.close || stock.price)}
                </b>
              </span>
              <span className="chart-ohlc-item">
                <span>거래량</span>
                <b>
                  {displayCandle?.volume != null
                    ? `${won(displayCandle.volume)}주`
                    : typeof stock.volume === 'number'
                      ? `${won(stock.volume)}주`
                      : (stock.volume || '0주')}
                </b>
              </span>
            </div>
          </div>
        );
      })()}

      <div className="chart-footer">
        <div className="chart-footer-left">
          <span className="legend-item"><span className="dot red" /> 상승</span>
          <span className="legend-item"><span className="dot blue" /> 하락</span>
          <span className="chart-guide-pill">
            <MoveHorizontal size={11} /> 드래그: 과거/현재 이동 · 마우스 휠: 확대/축소
          </span>
        </div>

        {/* 사용자 지정 위치: 기존 "실시간 KIS..." 자리에 차트 조절 버튼들(+ - 최신으로 전체화면) 배치 */}
        <div className="chart-tools-group">
          <button
            type="button"
            className="chart-tool-btn"
            onClick={() => setZoomCount((prev) => Math.max(16, prev - 6))}
            title="차트 확대 (캔들 크게 보기)"
          >
            <ZoomIn size={13} /> 확대
          </button>
          <button
            type="button"
            className="chart-tool-btn"
            onClick={() => setZoomCount((prev) => Math.min(candles.length, prev + 6))}
            title="차트 축소 (캔들 작게 전체 보기)"
          >
            <ZoomOut size={13} /> 축소
          </button>
          {isViewCustomized && (
            <button
              type="button"
              className="chart-tool-btn reset-btn"
              onClick={handleResetView}
              title="최신 시세 및 기본 배율(45개)로 리셋"
            >
              <RotateCcw size={12} /> 최신으로
            </button>
          )}
          <button
            type="button"
            className={`chart-tool-btn fullscreen-btn ${isFullscreen ? 'active' : ''}`}
            onClick={() => setIsFullscreen(!isFullscreen)}
            title={isFullscreen ? '전체화면 닫기 (ESC)' : '전체화면 확대 보기'}
          >
            {isFullscreen ? <Minimize2 size={13} /> : <Maximize2 size={13} />}
            <span>{isFullscreen ? '닫기 (ESC)' : '전체화면'}</span>
          </button>
        </div>
      </div>
    </div>
  );
}

function OrderBook({ stock, orderbook, orderbookUnavailable = false, onSelectPrice }) {
  const basePrice = Number(stock.price || 0);
  const changeVal = Number(stock.change || 0);
  // 백엔드 asks·bids는 모두 1단계(현재가에 가까운 쪽)부터 오므로 매도(asks)는 뒤집어서 5단계->1단계 순으로 위에 표시
  const rawAsks = Array.isArray(orderbook?.asks) ? orderbook.asks.slice(0, 5) : [];
  const asks = [...rawAsks].reverse().map((item) => ({
    price: Number(item.prc ?? item.price ?? 0),
    volume: Number(item.rsqn ?? item.volume ?? 0),
  }));
  const bids = (Array.isArray(orderbook?.bids) ? orderbook.bids.slice(0, 5) : []).map((item) => ({
    price: Number(item.prc ?? item.price ?? 0),
    volume: Number(item.rsqn ?? item.volume ?? 0),
  }));
  const maxBookVol = Math.max(
    1,
    ...asks.map((x) => x.volume),
    ...bids.map((x) => x.volume)
  );
  const totalAskVol = orderbook?.totAskRsqn != null
    ? Number(orderbook.totAskRsqn)
    : orderbook?.totalAskVol != null
    ? Number(orderbook.totalAskVol)
    : asks.reduce((s, x) => s + x.volume, 0);
  const totalBidVol = orderbook?.totBidRsqn != null
    ? Number(orderbook.totBidRsqn)
    : orderbook?.totalBidVol != null
    ? Number(orderbook.totalBidVol)
    : bids.reduce((s, x) => s + x.volume, 0);

  return (
    <section className="orderbook panel">
      <div className="section-heading book-title">
        <Activity size={18} />
        <strong>호가창</strong>
        <span
          className="mini-label"
          style={{ cursor: 'pointer' }}
          onClick={() => onSelectPrice && onSelectPrice(basePrice, 'BUY')}
          title="클릭하여 모의 주문창 열기"
        >
          {orderbookUnavailable
            ? '호가 일시 중단'
            : orderbook?.mktOpenYn === 'N'
            ? '장 마감 호가'
            : 'KIS 실시간 호가'}
        </span>
      </div>
      <div className="book-columns">
        <span>매도잔량</span>
        <span>호가 (KRW)</span>
        <span>매수잔량</span>
      </div>
      <div className="book-rows">
        {orderbookUnavailable ? (
          <div style={{ gridRow: 'span 5', display: 'flex', alignItems: 'center', justifyContent: 'center', fontSize: '12px', color: '#f1cc62' }}>
            호가 일시 중단 (503)
          </div>
        ) : asks.length > 0 ? asks.map((item, i) => (
          <div
            className="book-row ask"
            key={`ask-${i}`}
            style={{ cursor: 'pointer' }}
            title={`클릭하여 ₩${won(item.price)} 매수 주문`}
            onClick={() => onSelectPrice && onSelectPrice(item.price, 'BUY')}
          >
            <div className="depth askdepth" style={{ width: `${Math.min(92, (item.volume / maxBookVol) * 92)}%` }} />
            <span className="book-volume">{won(item.volume)}</span>
            <b>{won(item.price)}</b>
            <span>—</span>
          </div>
        )) : (
          <div style={{ gridRow: 'span 5', display: 'flex', alignItems: 'center', justifyContent: 'center', fontSize: '11.5px', color: '#6b7e96' }}>
            매도 호가 대기 중
          </div>
        )}
        <div
          className="book-current"
          style={{ cursor: 'pointer' }}
          title={`클릭하여 현재가 ₩${won(basePrice)} 매수 주문`}
          onClick={() => onSelectPrice && onSelectPrice(basePrice, 'BUY')}
        >
          <span>현재가</span>
          <b>{basePrice > 0 ? won(basePrice) : '시세 대기'}</b>
          <span className={changeVal >= 0 ? 'rise' : 'fall'}>
            {changeVal >= 0 ? '+' : ''}{changeVal.toFixed(2)}%
          </span>
        </div>
        {orderbookUnavailable ? (
          <div style={{ gridRow: 'span 5', display: 'flex', alignItems: 'center', justifyContent: 'center', fontSize: '12px', color: '#f1cc62' }}>
            잠시 후 자동으로 다시 조회합니다
          </div>
        ) : bids.length > 0 ? bids.map((item, i) => (
          <div
            className="book-row bid"
            key={`bid-${i}`}
            style={{ cursor: 'pointer' }}
            title={`클릭하여 ₩${won(item.price)} 매도 주문`}
            onClick={() => onSelectPrice && onSelectPrice(item.price, 'SELL')}
          >
            <div className="depth biddepth" style={{ width: `${Math.min(92, (item.volume / maxBookVol) * 92)}%` }} />
            <span>—</span>
            <b>{won(item.price)}</b>
            <span className="book-volume">{won(item.volume)}</span>
          </div>
        )) : (
          <div style={{ gridRow: 'span 5', display: 'flex', alignItems: 'center', justifyContent: 'center', fontSize: '11.5px', color: '#6b7e96' }}>
            매수 호가 대기 중
          </div>
        )}
      </div>
      <div className="book-summary">
        <span>매도 총잔량 <b>{won(totalAskVol)}</b></span>
        <span>매수 총잔량 <b>{won(totalBidVol)}</b></span>
      </div>
    </section>
  );
}

// KRX 정규장 개장/마감 상태 계산 (평일 09:00 ~ 15:30 KST)
function getKrxMarketStatus() {
  const now = new Date();
  // 한국 표준시 (KST = UTC+9) 기준 계산
  const utc = now.getTime() + (now.getTimezoneOffset() * 60000);
  const kst = new Date(utc + (9 * 60 * 60000));

  const day = kst.getDay(); // 0: 일요일, 1: 월요일, ..., 5: 금요일, 6: 토요일
  const hours = kst.getHours();
  const minutes = kst.getMinutes();
  const totalMinutes = hours * 60 + minutes;

  const isWeekday = day >= 1 && day <= 5;
  // 정규장: 09:00(540분) ~ 15:30(930분)
  const isOpen = isWeekday && totalMinutes >= 540 && totalMinutes <= 930;

  let reason = '';
  if (!isWeekday) {
    reason = day === 0 ? '일요일 휴장' : '토요일 휴장';
  } else if (totalMinutes < 540) {
    reason = '정규장 개장 전 (09:00 시작)';
  } else {
    reason = '정규장 마감 (15:30 마감)';
  }

  return {
    isOpen,
    statusText: isOpen ? 'LIVE' : '장마감',
    tooltip: isOpen
      ? `KRX 정규장 운영 중 (09:00~15:30 KST) · 지수 표시값은 예시`
      : `KRX 정규장 마감 (${reason} · 정규장 평일 09:00~15:30 KST)`,
  };
}

function App() {
  const [serverMode, setServerMode] = useState('확인 중');
  const [stocks, setStocks] = useState(DEFAULT_STOCKS);
  const [stockIndex, setStockIndex] = useState(0);
  const [krxStatus, setKrxStatus] = useState(getKrxMarketStatus);
  const [chartInterval, setChartInterval] = useState('1일');
  const [chartHover, setChartHover] = useState(null);
  const [tab, setTab] = useState('인기 종목');
  const [search, setSearch] = useState('');
  const [isSearchingKis, setIsSearchingKis] = useState(false);
  const [showPassword, setShowPassword] = useState(false);
  const [isSignUpOpen, setIsSignUpOpen] = useState(false);
  const [isOrderOpen, setIsOrderOpen] = useState(false);
  const [isWithdrawOpen, setIsWithdrawOpen] = useState(false);
  const [orderType, setOrderType] = useState('BUY');
  const [orderPrice, setOrderPrice] = useState(null);
  const [showHoldings, setShowHoldings] = useState(true);
  const [toast, setToast] = useState('');
  const [sideOpen, setSideOpen] = useState(false);
  const [isOrderHighlighted, setIsOrderHighlighted] = useState(false);
  const orderSidebarRef = useRef(null);
  const splitOrderRef = useRef(null);

  // 인증 및 세션 상태
  const [currentUser, setCurrentUser] = useState(() => getActiveSession());
  // 뷰 전환 및 추가 모달 상태 (관리자는 기본 관제 화면으로 이동, 주문·출금 미제공)
  const [activeView, setActiveView] = useState(() =>
    getActiveSession()?.role === 'ADMIN' ? 'admin' : 'trading'
  ); // 'trading' | 'admin'
  const [isOrderHistoryOpen, setIsOrderHistoryOpen] = useState(false);
  const [alertsList, setAlertsList] = useState(getStoredAlerts);
  const unresolvedAlertCount = alertsList.filter((a) => a.chkYn === 'N').length;

  const [loginId, setLoginId] = useState('');
  const [loginPassword, setLoginPassword] = useState('');
  const [loginError, setLoginError] = useState('');
  // 새 기기 로그인 추가 인증(202 Accepted) 상태
  const [loginEmailAuth, setLoginEmailAuth] = useState(null); // { authId, mskEmlAddr, ttlSec, resendCooldown }
  const [loginAuthCode, setLoginAuthCode] = useState('');
  const [loginAuthError, setLoginAuthError] = useState('');
  const [isVerifyingLoginAuth, setIsVerifyingLoginAuth] = useState(false);

  // 실시간 시장 요약, 종목별 시세, 위험 종목, STOMP 상태
  const [marketSummary, setMarketSummary] = useState(null);
  const [riskStocksMap, setRiskStocksMap] = useState({}); // { [tckCd]: { tckCd, tckNm, dir, chgRt, reason, dtcTs } }
  const [quotes, setQuotes] = useState({});
  const [lastQuoteTime, setLastQuoteTime] = useState(() => Date.now());
  const [quoteError, setQuoteError] = useState('');
  const [realCandles, setRealCandles] = useState([]);
  const [periodCandles, setPeriodCandles] = useState([]);
  const [orderbook, setOrderbook] = useState(null);
  const [orderbookUnavailable, setOrderbookUnavailable] = useState(false);
  const stompRef = useRef(null);
  const priceSubCleanupRef = useRef(null);
  const selectedCodeRef = useRef('005930');
  const chartIntervalRef = useRef(chartInterval);
  chartIntervalRef.current = chartInterval;

  const currentStock = stocks[stockIndex] || stocks[0] || DEFAULT_STOCKS[0];
  const selectedCode = currentStock.code;
  selectedCodeRef.current = selectedCode;

  // 새 기기 로그인 인증 타이머 (5분 만료 & 60초 재발송 제한)
  useEffect(() => {
    if (!loginEmailAuth) return;
    const timer = setInterval(() => {
      setLoginEmailAuth((prev) => {
        if (!prev) return null;
        return {
          ...prev,
          ttlSec: Math.max(0, (prev.ttlSec || 0) - 1),
          resendCooldown: Math.max(0, (prev.resendCooldown || 0) - 1),
        };
      });
    }, 1000);
    return () => clearInterval(timer);
  }, [Boolean(loginEmailAuth)]);

  // 최초 진입 및 사용자 변경 시 원장 데이터 동기화 (GET /members/me -> GET /accounts/me/summary, holdings, orders)
  useEffect(() => {
    if (!currentUser?.id) return;
    let active = true;
    const sync = async () => {
      const result = await fetchMyAccount();
      if (active && result.success && result.user) {
        setCurrentUser(result.user);
        if (result.user.role === 'ADMIN') {
          setActiveView('admin');
        }
      } else if (active && result.status === 401) {
        setCurrentUser(null);
      }
    };
    sync();
    const timer = setInterval(sync, 15000);
    return () => { active = false; clearInterval(timer); };
  }, [currentUser?.id]);

  // 30초마다 KRX 시장 운영 상태 갱신
  useEffect(() => {
    const timer = setInterval(() => {
      setKrxStatus(getKrxMarketStatus());
    }, 30000);
    return () => clearInterval(timer);
  }, []);

  // 시장 요약 데이터(GET /market/summary 또는 /topic/ranking 메시지)를 상태에 반영
  const applyMarketSummary = (summaryData) => {
    if (!summaryData) return;
    setMarketSummary(summaryData);
    setServerMode(summaryData.priceDelayed ? '시세 지연' : '실시간 시세');
    setLastQuoteTime(Date.now());

    // 위험 종목 맵 갱신
    if (Array.isArray(summaryData.riskStocks)) {
      const nextRisk = {};
      summaryData.riskStocks.forEach((r) => {
        if (r?.tckCd) nextRisk[r.tckCd] = r;
      });
      setRiskStocksMap(nextRisk);
    }

    // topRanks (rise, fall, volume)의 시세 정보를 quotes에 반영
    const rankItems = [
      ...(summaryData.topRanks?.rise || []),
      ...(summaryData.topRanks?.fall || []),
      ...(summaryData.topRanks?.volume || []),
    ];
    if (rankItems.length > 0) {
      setQuotes((prev) => {
        const next = { ...prev };
        rankItems.forEach((item) => {
          if (!item?.tckCd) return;
          next[item.tckCd] = {
            ...(next[item.tckCd] || {}),
            code: item.tckCd,
            name: item.tckNm || next[item.tckCd]?.name,
            price: item.currentPrice != null ? Number(item.currentPrice) : next[item.tckCd]?.price,
            change: item.chgRt != null ? Number(item.chgRt) : next[item.tckCd]?.change,
            chgPrc: item.chgPrc != null ? Number(item.chgPrc) : next[item.tckCd]?.chgPrc,
            volume: item.totalTrdVol != null ? Number(item.totalTrdVol) : next[item.tckCd]?.volume,
            riskYn: item.riskYn || 'N',
            source: 'KIS',
          };
        });
        return next;
      });
    }
  };

  // 선택된 종목 상세 및 실제 1분봉(chart) REST 조회: GET /api/v1/market/stocks/{tckCd}
  const loadStockDetailRest = async (code, signal) => {
    try {
      const data = await fetchStockPrice(code, signal);
      setQuotes((prev) => ({
        ...prev,
        [code]: {
          ...(prev[code] || {}),
          ...data,
        },
      }));
      if (data.risk?.riskYn === 'Y') {
        setRiskStocksMap((prev) => ({
          ...prev,
          [code]: {
            tckCd: code,
            tckNm: data.name,
            dir: data.risk.dir,
            reason: data.risk.reason,
          },
        }));
      }
      setRealCandles(adaptChartBars(data.chart));
      setLastQuoteTime(Date.now());
      setQuoteError('');
    } catch (error) {
      if (error.name !== 'AbortError') {
        setQuoteError('실시간 종목 상세 시세를 불러오지 못했습니다.');
      }
    }
  };

  // 종목 변경 시 REST로 종목 상세와 실제 분봉 조회
  useEffect(() => {
    const controller = new AbortController();
    setRealCandles([]);
    loadStockDetailRest(selectedCode, controller.signal);
    return () => controller.abort();
  }, [selectedCode]);

  // 선택 종목 실시간 5단계 호가/잔량 KIS REST 조회 (3초 주기 갱신)
  useEffect(() => {
    let active = true;
    const controller = new AbortController();
    setOrderbook(null);
    setOrderbookUnavailable(false);

    const loadOrderbook = async () => {
      const res = await fetchStockOrderbook(selectedCode, controller.signal);
      if (!active) return;
      if (res.success && res.data) {
        setOrderbook(res.data);
        setOrderbookUnavailable(false);
      } else if (res.unavailable || res.status === 503) {
        setOrderbookUnavailable(true);
      }
    };
    loadOrderbook();
    const timer = setInterval(loadOrderbook, 3000);

    return () => {
      active = false;
      controller.abort();
      clearInterval(timer);
    };
  }, [selectedCode]);

  // 1일·1주·1개월·3개월·6개월·1년봉 선택 시 KIS REST API 기간별 캔들 조회
  useEffect(() => {
    if (!['1일', '1주', '1개월', '3개월', '6개월', '1년'].includes(chartInterval)) {
      return;
    }
    const apiInterval = ['3개월', '6개월', '1년'].includes(chartInterval) ? '1개월' : chartInterval;
    const controller = new AbortController();
    fetchStockPeriodCandles(selectedCode, apiInterval, controller.signal).then((res) => {
      if (res.success && Array.isArray(res.candles)) {
        const dayNames = ['일', '월', '화', '수', '목', '금', '토'];
        const adapted = res.candles.map((bar) => {
          const dt = String(bar.bsDt || bar.bsMi || '');
          let dateStr = bar.dateStr || dt;
          let label = bar.label || dt;
          if (dt.length >= 8) {
            const y = dt.slice(0, 4);
            const m = dt.slice(4, 6);
            const d = dt.slice(6, 8);
            const dayName = dayNames[new Date(Number(y), Number(m) - 1, Number(d)).getDay()];
            if (chartInterval === '1주') {
              dateStr = bar.dateStr || `${y}.${m}.${d} (주봉)`;
              label = bar.label || `${y.slice(-2)}.${m}.${d}`;
            } else if (['1개월', '3개월', '6개월', '1년'].includes(chartInterval)) {
              dateStr = bar.dateStr || `${y}년 ${m}월 (월봉)`;
              label = bar.label || `${y.slice(-2)}.${m}`;
            } else {
              dateStr = bar.dateStr || `${y}.${m}.${d} (${dayName})`;
              label = bar.label || `${m}/${d}`;
            }
          }
          return {
            bsMi: dt,
            open: Number(bar.openPrc ?? bar.open ?? 0),
            high: Number(bar.highPrc ?? bar.high ?? 0),
            low: Number(bar.lowPrc ?? bar.low ?? 0),
            close: Number(bar.closePrc ?? bar.close ?? 0),
            volume: Number(bar.trdVol ?? bar.volume ?? 0),
            dateStr,
            label,
          };
        });
        if (chartInterval === '3개월') {
          setPeriodCandles(aggregateMonthlyBars(adapted, 3));
        } else if (chartInterval === '6개월') {
          setPeriodCandles(aggregateMonthlyBars(adapted, 6));
        } else if (chartInterval === '1년') {
          setPeriodCandles(aggregateMonthlyBars(adapted, 12));
        } else {
          setPeriodCandles(adapted);
        }
      }
    });
    return () => controller.abort();
  }, [selectedCode, chartInterval]);

  // 처음 진입 시 REST로 시장 요약 조회 + /ws STOMP 연결(/topic/ranking, /topic/risk, /topic/price/{tckCd})
  // 연결이 끊기면 자동 재연결 후 최신 REST 데이터를 다시 조회
  useEffect(() => {
    const refreshAllMarketRest = () => {
      fetchMarketSummary().then((res) => {
        if (res.success && res.data) {
          applyMarketSummary(res.data);
        } else {
          setServerMode('서버 연결 실패');
        }
      });
      loadStockDetailRest(selectedCodeRef.current);
    };

    refreshAllMarketRest();

    const stomp = createStompManager({
      withAuth: false,
      onReconnect: () => {
        // 재연결 시 최신 REST 데이터 다시 조회
        refreshAllMarketRest();
      },
    });
    stompRef.current = stomp;

    // /topic/ranking 구독: 2초마다 GET /market/summary 전체 구조 수신
    const unsubRanking = stomp.subscribe('/topic/ranking', (payload) => {
      if (payload && typeof payload === 'object') {
        applyMarketSummary(payload);
      }
    });

    // /topic/risk 구독: 위험 의심 종목 변경 시 전체 목록 수신
    const unsubRisk = stomp.subscribe('/topic/risk', (payload) => {
      if (Array.isArray(payload)) {
        const nextRisk = {};
        payload.forEach((r) => {
          if (r?.tckCd) nextRisk[r.tckCd] = r;
        });
        setRiskStocksMap(nextRisk);
      }
    });

    return () => {
      unsubRanking();
      unsubRisk();
      if (priceSubCleanupRef.current) {
        priceSubCleanupRef.current();
        priceSubCleanupRef.current = null;
      }
      stomp.disconnect();
      stompRef.current = null;
    };
  }, []);

  // 선택 종목 변경 시 /topic/price/{종목코드} STOMP 구독 전환
  useEffect(() => {
    if (!stompRef.current) return;
    if (priceSubCleanupRef.current) {
      priceSubCleanupRef.current();
      priceSubCleanupRef.current = null;
    }

    const code = selectedCode;
    priceSubCleanupRef.current = stompRef.current.subscribe(`/topic/price/${code}`, (tick) => {
      if (!tick || !tick.tckCd) return;
      const tickPrice = Number(tick.currentPrice ?? 0);
      const tickVol = Number(tick.totalTrdVol ?? 0);

      setQuotes((prev) => ({
        ...prev,
        [tick.tckCd]: {
          ...(prev[tick.tckCd] || {}),
          code: tick.tckCd,
          price: tickPrice,
          chgPrc: Number(tick.chgPrc ?? 0),
          change: Number(tick.chgRt ?? 0),
          volume: tickVol,
          prcTs: tick.prcTs,
          priceDelayed: false,
          source: 'KIS',
        },
      }));
      setLastQuoteTime(Date.now());
      setQuoteError('');

      // 현재 보고 있는 종목의 최신 1분봉 및 일·주·월봉 실시간 갱신 (같은 구간이면 종가·고가·저가 갱신, 다음 구간이면 새 봉 생성)
      if (tickPrice > 0) {
        setRealCandles((prev) => applyTickToMinuteCandles(prev, tickPrice, tick.prcTs));
        setPeriodCandles((prev) =>
          applyTickToPeriodCandles(prev, tickPrice, tickVol, tick.prcTs, chartIntervalRef.current)
        );
      }
    });

    return () => {
      if (priceSubCleanupRef.current) {
        priceSubCleanupRef.current();
        priceSubCleanupRef.current = null;
      }
    };
  }, [selectedCode]);

  const stock = {
    ...currentStock,
    ...(quotes[selectedCode] || {}),
    riskInfo: riskStocksMap[selectedCode] || (quotes[selectedCode]?.risk?.riskYn === 'Y' ? quotes[selectedCode].risk : null),
  };
  const currentPrice = stock?.price || 0;

  // 일·주·월·분기·반기·년봉은 KIS 기간별 시세 API 캔들 사용, 분봉·시간봉은 서버 수집 1분봉을 집계하여 표시
  const candles = useMemo(() => {
    if (['1일', '1주', '1개월', '3개월', '6개월', '1년'].includes(chartInterval)) {
      return periodCandles;
    }
    return aggregateMinuteBars(realCandles, chartInterval);
  }, [periodCandles, realCandles, chartInterval]);

  const activeCandle = chartHover === null ? candles.at(-1) : candles[chartHover];
  const activePercent = chartHover === null && stock?.change != null
    ? Number(stock.change)
    : activeCandle?.open
      ? (((activeCandle?.close || currentPrice) / activeCandle.open) - 1) * 100
      : 0;

  const alert = (msg) => {
    setToast(msg);
    window.setTimeout(() => setToast(''), 4500);
  };

  // 모의 주문 실행 / 주문창 열기 (관리자는 주문·출금 제공하지 않음)
  const handleOpenOrder = (arg1 = 'BUY', arg2 = null) => {
    if (!currentUser) {
      alert('모의 주문은 로그인 또는 회원가입 후 이용하실 수 있습니다.');
      setIsSignUpOpen(true);
      return;
    }
    if (currentUser.role === 'ADMIN') {
      alert('관리자 계정은 직무 분리 원칙에 따라 주문·출금 기능을 이용할 수 없습니다.');
      setActiveView('admin');
      return;
    }
    let type = 'BUY';
    let customPrice = null;
    if (typeof arg1 === 'number') {
      customPrice = arg1;
      type = arg2 || 'BUY';
    } else {
      type = arg1 || 'BUY';
      customPrice = arg2;
    }
    setOrderType(type);
    setOrderPrice(customPrice || stock.price);
    setIsOrderOpen(true);

    // 우측/분할 주문 패널 강조 및 부드러운 스크롤 포커스
    setIsOrderHighlighted(true);
    setTimeout(() => setIsOrderHighlighted(false), 1300);
    if (window.innerWidth <= 1150) {
      splitOrderRef.current?.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
    } else {
      orderSidebarRef.current?.scrollIntoView({ behavior: 'smooth' });
    }
  };

  // 로그인 완료 공통 처리 (관리자는 관제 화면으로 이동, 일반회원은 트레이딩 화면)
  const applyLoginSuccess = (user, msg) => {
    setCurrentUser(user);
    setLoginId('');
    setLoginPassword('');
    setLoginEmailAuth(null);
    setLoginAuthCode('');
    setLoginAuthError('');
    if (user.role === 'ADMIN') {
      setActiveView('admin');
      alert(msg || `관리자(${user.name}) 계정으로 로그인되었습니다. FDS 관제 센터로 이동합니다.`);
    } else {
      setActiveView('trading');
      alert(msg || `환영합니다, ${user.name}님! 계좌(${user.accountNo}) 조회가 완료되었습니다.`);
    }
  };

  // 로그인 처리 (200 토큰 발급 or 202 새 기기 추가 이메일 인증)
  const handleLogin = async (e) => {
    e?.preventDefault();
    setLoginError('');
    if (!loginId.trim() || !loginPassword) {
      setLoginError('아이디와 비밀번호를 모두 입력해주세요.');
      return;
    }
    const res = await authenticateUser(loginId, loginPassword);
    if (res.success && res.user) {
      applyLoginSuccess(res.user);
    } else if (res.authRequired) {
      setLoginEmailAuth({
        authId: res.authId,
        mskEmlAddr: res.mskEmlAddr,
        ttlSec: res.ttlSec || 300,
        resendCooldown: 60,
      });
      setLoginAuthCode('');
      setLoginAuthError('');
      alert(`새로운 기기/IP 접속이 감지되었습니다. ${res.mskEmlAddr}로 발송된 6자리 인증 코드를 입력해주세요.`);
    } else {
      setLoginError(res.message);
    }
  };

  // 새 기기 로그인 추가 인증 확인 (POST /api/v1/auth/email-verify)
  const handleVerifyLoginEmail = async (e) => {
    e?.preventDefault();
    if (!loginEmailAuth?.authId) return;
    const cleanCode = loginAuthCode.trim();
    if (!/^\d{6}$/.test(cleanCode)) {
      setLoginAuthError('인증 코드는 숫자 6자리로 입력해주세요.');
      return;
    }
    setIsVerifyingLoginAuth(true);
    setLoginAuthError('');
    try {
      const res = await verifyNewDeviceLogin(loginEmailAuth.authId, cleanCode);
      if (res.success && res.user) {
        applyLoginSuccess(res.user, `신규 기기 인증이 완료되었습니다. 환영합니다, ${res.user.name}님!`);
      } else {
        if (res.code === 'AUTH_CODE_EXPIRED') {
          setLoginEmailAuth(null);
          setLoginError(res.message || '인증 코드가 만료되었습니다. 처음부터 다시 로그인해주세요.');
        } else if (res.remainCnt != null) {
          setLoginAuthError(`${res.message} (남은 시도 횟수: ${res.remainCnt}회)`);
        } else {
          setLoginAuthError(res.message || '인증 코드 확인에 실패했습니다.');
        }
      }
    } finally {
      setIsVerifyingLoginAuth(false);
    }
  };

  // 새 기기 로그인 인증 코드 재발송 (POST /api/v1/auth/email-resend)
  const handleResendLoginEmail = async () => {
    if (!loginEmailAuth?.authId || loginEmailAuth.resendCooldown > 0) return;
    setLoginAuthError('');
    const res = await resendNewDeviceEmailCode(loginEmailAuth.authId);
    if (res.success) {
      setLoginEmailAuth((prev) => prev ? {
        ...prev,
        ttlSec: res.ttlSec ?? prev.ttlSec,
        resendCooldown: 60,
      } : null);
      alert('인증 코드를 이메일로 다시 발송했습니다.');
    } else if (res.code === 'AUTH_CODE_EXPIRED') {
      setLoginEmailAuth(null);
      setLoginError(res.message || '인증 시간이 만료되었습니다. 다시 로그인해주세요.');
    } else {
      setLoginAuthError(res.message || '재발송에 실패했습니다.');
    }
  };

  // 로그아웃 (Refresh 토큰 폐기)
  const handleLogout = async () => {
    await logoutUser();
    setCurrentUser(null);
    setIsOrderOpen(false);
    setLoginEmailAuth(null);
    setActiveView('trading');
    alert('안전하게 로그아웃되었습니다.');
  };

  // 회원가입 성공 시 콜백
  const handleRegisterSuccess = (newUser, registeredInfo) => {
    if (newUser?.id) {
      applyLoginSuccess(newUser, `🎉 ${newUser.name}님 환영합니다! 모의투자 지원금 ₩${won(newUser.balance)}이 지급되었습니다.`);
    } else if (registeredInfo?.lgnId) {
      setLoginId(registeredInfo.lgnId);
      alert(`🎉 회원가입이 완료되었습니다 (계좌번호: ${registeredInfo.acntNo}). 로그인해 주세요!`);
    }
  };

  // 관심 종목 토글
  const handleToggleWatchlist = async (stockCode) => {
    if (!currentUser) {
      alert('관심 종목을 등록하려면 먼저 로그인 또는 회원가입을 해주세요.');
      setIsSignUpOpen(true);
      return;
    }
    const updated = await toggleUserWatchlist(currentUser.id, stockCode);
    if (updated) {
      setCurrentUser(updated);
      const isStarred = updated.watchlist?.includes(stockCode);
      const targetStock = stocks.find((s) => s.code === stockCode);
      alert(isStarred ? `⭐ '${targetStock?.name || stockCode}'이(가) 관심 종목에 추가되었습니다.` : `'${targetStock?.name || stockCode}'이(가) 관심 종목에서 제외되었습니다.`);
    }
  };

  // 모의투자 총 보유자산 카드 렌더링 (매수/매도 주문창 상단 배치)
  const renderProfileBalanceCard = () => {
    if (!currentUser) return null;
    return (
      <div className="profile-balance-card" style={{ marginBottom: '12px' }}>
        <div className="balance-label-row">
          <span>모의투자 총 보유자산</span>
          <span style={{ color: currentUser.isLocked ? '#ff6666' : (krxStatus.isOpen && isAccountPriceDelayed) ? '#f1cc62' : '#4cd49e' }}>
            {currentUser.isLocked
              ? `⚠️ 거래 잠김 (${currentUser.acntStatCd || 'LOCKED'})`
              : (krxStatus.isOpen && isAccountPriceDelayed)
                ? '⏳ 시세 지연'
                : '정상 (ACTIVE)'}
          </span>
        </div>
        <div className="balance-total">₩{won(totalAssets)}</div>
        {currentUser.accountNo && (
          <div className="balance-sub-row">
            <span>계좌번호</span>
            <b style={{ fontFamily: 'monospace', fontSize: '11.5px', color: '#9cb3d0' }}>{currentUser.accountNo}</b>
          </div>
        )}
        <div className="balance-sub-row">
          <span>주문 가능 예수금</span>
          <b>₩{won(currentUser.balance)}</b>
        </div>
        <div className="balance-sub-row">
          <span>총 매입금액</span>
          <b>₩{won(totalStockCost)}</b>
        </div>
        <div className="balance-sub-row">
          <span>주식 평가액</span>
          <b style={{ color: portfolioStockValue > 0 ? '#6da2ff' : '#889bb0' }}>
            ₩{won(portfolioStockValue)}
          </b>
        </div>
        <div className="balance-sub-row">
          <span>총 평가손익</span>
          <b style={{ color: totalProfitLoss >= 0 ? '#ff6b7b' : '#6da2ff' }}>
            {totalProfitLoss >= 0 ? '+' : ''}₩{won(totalProfitLoss)} ({totalReturnRate >= 0 ? '+' : ''}{totalReturnRate.toFixed(2)}%)
          </b>
        </div>
      </div>
    );
  };

  // 로그인 및 새 기기 2차 인증 카드 렌더링 (데스크톱 우측 사이드바 & 축소 화면 분할 패널 공용)
  const renderAuthLoginCard = (idSuffix = '') => {
    if (loginEmailAuth) {
      return (
        <div className="auth-card panel">
          <div className="auth-mark">
            <span className="brand-icon large">
              <ShieldAlert size={28} />
            </span>
          </div>
          <h2>새 기기 로그인<br />추가 인증</h2>
          <p>
            처음 접속하는 IP·기기 환경이 감지되었습니다.<br />
            <strong style={{ color: '#9cb3d0' }}>{loginEmailAuth.mskEmlAddr}</strong>로 발송된 6자리 인증 코드를 입력해주세요.
          </p>

          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', background: '#141e2e', border: '1px solid #2b3c56', borderRadius: '6px', padding: '8px 12px', marginBottom: '12px', fontSize: '12px' }}>
            <span style={{ color: '#8fa0b5' }}>유효 시간 (5분)</span>
            <strong style={{ color: loginEmailAuth.ttlSec <= 60 ? '#ff6b7b' : '#4cd49e', fontFamily: 'monospace', fontSize: '13px' }}>
              {String(Math.floor((loginEmailAuth.ttlSec || 0) / 60)).padStart(2, '0')}:
              {String((loginEmailAuth.ttlSec || 0) % 60).padStart(2, '0')}
            </strong>
          </div>

          {loginAuthError && (
            <div className="form-alert error">
              <AlertCircle size={15} />
              <span>{loginAuthError}</span>
            </div>
          )}

          <form onSubmit={handleVerifyLoginEmail}>
            <label htmlFor={`login-auth-code${idSuffix}`}>이메일 인증 코드 (6자리)</label>
            <div className="form-control">
              <input
                id={`login-auth-code${idSuffix}`}
                type="text"
                inputMode="numeric"
                maxLength={6}
                value={loginAuthCode}
                onChange={(e) => setLoginAuthCode(e.target.value.replace(/[^0-9]/g, '').slice(0, 6))}
                placeholder="숫자 6자리 입력"
                required
              />
            </div>

            <button
              className="primary-btn"
              type="submit"
              disabled={isVerifyingLoginAuth || loginEmailAuth.ttlSec <= 0 || loginAuthCode.length !== 6}
            >
              {isVerifyingLoginAuth ? '인증 확인 중...' : '인증 완료 및 로그인'} <ArrowRight size={18} />
            </button>
          </form>

          <div style={{ display: 'flex', gap: '8px', marginTop: '10px' }}>
            <button
              type="button"
              className="demo-login-shortcut"
              style={{ flex: 1, justifyContent: 'center' }}
              disabled={loginEmailAuth.resendCooldown > 0 || loginEmailAuth.ttlSec <= 0}
              onClick={handleResendLoginEmail}
            >
              {loginEmailAuth.resendCooldown > 0
                ? `재발송 (${loginEmailAuth.resendCooldown}초)`
                : '인증 코드 재발송'}
            </button>
            <button
              type="button"
              className="demo-login-shortcut"
              style={{ background: '#1e2636', borderColor: '#33415c', color: '#9cb3d0', justifyContent: 'center' }}
              onClick={() => {
                setLoginEmailAuth(null);
                setLoginAuthCode('');
                setLoginAuthError('');
              }}
            >
              취소
            </button>
          </div>
        </div>
      );
    }

    return (
      <div className="auth-card panel">
        <div className="auth-mark">
          <span className="brand-icon large">
            <ShieldCheck size={28} />
          </span>
        </div>
        <h2>다시 오신 것을<br />환영합니다!</h2>
        <p>로그인하고 안전한 주식 거래와<br />FDS 모의투자를 시작하세요.</p>

        {loginError && (
          <div className="form-alert error">
            <AlertCircle size={15} />
            <span>{loginError}</span>
          </div>
        )}

        <form onSubmit={handleLogin}>
          <label htmlFor={`login-id${idSuffix}`}>아이디</label>
          <div className="form-control">
            <input
              id={`login-id${idSuffix}`}
              name="id"
              value={loginId}
              onChange={(e) => setLoginId(e.target.value)}
              autoComplete="username"
              required
              placeholder="아이디를 입력하세요"
            />
          </div>

          <div className="label-row">
            <label htmlFor={`login-password${idSuffix}`}>비밀번호</label>
            <button
              type="button"
              onClick={() => alert('비밀번호를 분실하신 경우 관리자에게 문의하시거나 신규 회원가입을 진행해주세요.')}
            >
              비밀번호 찾기
            </button>
          </div>

          <div className="form-control password">
            <input
              id={`login-password${idSuffix}`}
              type={showPassword ? 'text' : 'password'}
              name="password"
              value={loginPassword}
              onChange={(e) => setLoginPassword(e.target.value)}
              autoComplete="current-password"
              required
              placeholder="비밀번호를 입력하세요"
            />
            <button
              aria-label={showPassword ? '비밀번호 숨기기' : '비밀번호 보기'}
              type="button"
              onClick={() => setShowPassword(!showPassword)}
            >
              {showPassword ? <EyeOff size={17} /> : <Eye size={17} />}
            </button>
          </div>

          <button className="primary-btn" type="submit">
            로그인 <ArrowRight size={18} />
          </button>
        </form>

        <div className="signup-row">
          아직 계정이 없으신가요?{' '}
          <button type="button" onClick={() => setIsSignUpOpen(true)}>
            회원가입
          </button>
        </div>

        <div className="auth-foot">
          <LockKeyhole size={16} />
          <span>StockShield는 FDS 이상거래 탐지 시스템을 통해 거래 보안을 강화하도록 설계되었습니다.</span>
        </div>
      </div>
    );
  };

  // 6자리 종목 코드 KIS 실시간 조회 및 추가
  const trimmedSearch = search.trim();
  const isSixDigitCode = /^\d{6}$/.test(trimmedSearch);

  const handleLookupAndAddStock = async (codeToLookup) => {
    const code = codeToLookup || trimmedSearch;
    if (!/^\d{6}$/.test(code)) {
      alert('종목 코드는 6자리 숫자여야 합니다. (예: 005930, 000660)');
      return;
    }

    const existingIndex = stocks.findIndex((s) => s.code === code);
    if (existingIndex !== -1) {
      setStockIndex(existingIndex);
      setSearch('');
      alert(`'${stocks[existingIndex].name}' 종목을 선택했습니다.`);
      return;
    }

    setIsSearchingKis(true);
    try {
      const data = await fetchStockPrice(code);
      const stockName = data.name && data.name !== `종목(${code})`
        ? data.name
        : (code === '036570' ? '엔씨소프트' : `종목(${code})`);
      const stockMarket = data.market || (data.source === 'KIS' ? 'KOSPI' : 'KRX');
      const stockSector = data.sector || '국내주식';

      const newStock = {
        code,
        name: stockName,
        market: stockMarket,
        sector: stockSector,
        price: data.price,
        change: data.change,
        volume: data.volume,
        color: '#658cf4',
        source: 'KIS',
      };
      setStocks((prev) => [newStock, ...prev]);
      setQuotes((prev) => ({ ...prev, [code]: data }));
      setStockIndex(0);
      setSearch('');
      alert(`⚡ '${stockName}(${code})' KIS 실시간 시세를 성공적으로 불러와 종목에 추가했습니다!`);
    } catch (err) {
      alert(`종목코드 ${code}의 KIS 시세를 조회하지 못했습니다. 올바른 종목코드인지 확인해주세요.`);
    } finally {
      setIsSearchingKis(false);
    }
  };

  // 실시간 시장 지수 (GET /market/summary 또는 /topic/ranking의 kospi, kosdaq 반영)
  const tickerItems = useMemo(() => {
    const k = marketSummary?.kospi;
    const q = marketSummary?.kosdaq;
    const baseIndices = [
      k?.currentVal != null
        ? {
          name: '코스피',
          en: 'KOSPI',
          value: Number(k.currentVal).toLocaleString('ko-KR', { minimumFractionDigits: 2, maximumFractionDigits: 2 }),
          diff: `${Number(k.chgVal ?? 0) >= 0 ? '▲' : '▼'} ${Math.abs(Number(k.chgVal ?? 0)).toFixed(2)}`,
          change: `${Number(k.chgRt ?? 0) >= 0 ? '+' : ''}${Number(k.chgRt ?? 0).toFixed(2)}%`,
          isUp: Number(k.chgRt ?? 0) >= 0,
        }
        : MARKET_INDICES[0],
      q?.currentVal != null
        ? {
          name: '코스닥',
          en: 'KOSDAQ',
          value: Number(q.currentVal).toLocaleString('ko-KR', { minimumFractionDigits: 2, maximumFractionDigits: 2 }),
          diff: `${Number(q.chgVal ?? 0) >= 0 ? '▲' : '▼'} ${Math.abs(Number(q.chgVal ?? 0)).toFixed(2)}`,
          change: `${Number(q.chgRt ?? 0) >= 0 ? '+' : ''}${Number(q.chgRt ?? 0).toFixed(2)}%`,
          isUp: Number(q.chgRt ?? 0) >= 0,
        }
        : MARKET_INDICES[1],
      MARKET_INDICES[2],
      MARKET_INDICES[3],
    ];
    return [...baseIndices, ...baseIndices, ...baseIndices, ...baseIndices];
  }, [marketSummary]);

  // 탭 목록: '인기 종목', '상승률', '하락률', '코스피', '코스닥', '관심 종목'
  const displayList = useMemo(() => {
    const query = trimmedSearch.toLowerCase();
    let list = stocks;

    // 검색어가 있으면 전체 목록에서 이름, 코드, 섹터, 시장 검색
    if (query) {
      list = list.filter((s) =>
        s.name.toLowerCase().includes(query) ||
        s.code.includes(query) ||
        (s.sector && s.sector.toLowerCase().includes(query)) ||
        (s.market && s.market.toLowerCase().includes(query))
      );
    }

    if (tab === '코스피') {
      return list.filter((s) => s.market === 'KOSPI');
    }
    if (tab === '코스닥') {
      return list.filter((s) => s.market === 'KOSDAQ');
    }
    if (tab === '관심 종목') {
      return list.filter((s) => currentUser?.watchlist?.includes(s.code));
    }
    if (tab === '상승률' && Array.isArray(marketSummary?.topRanks?.rise) && !query) {
      return marketSummary.topRanks.rise
        .map((r) => stocks.find((s) => s.code === r.tckCd))
        .filter(Boolean);
    }
    if (tab === '하락률' && Array.isArray(marketSummary?.topRanks?.fall) && !query) {
      return marketSummary.topRanks.fall
        .map((r) => stocks.find((s) => s.code === r.tckCd))
        .filter(Boolean);
    }

    // 인기 종목: topRanks.volume이 있으면 거래량 상위 순, 없으면 기본 상위 노출
    if (!query && Array.isArray(marketSummary?.topRanks?.volume) && marketSummary.topRanks.volume.length > 0) {
      const volOrder = marketSummary.topRanks.volume
        .map((r) => stocks.find((s) => s.code === r.tckCd))
        .filter(Boolean);
      const rest = list.filter((s) => !volOrder.includes(s));
      return [...volOrder, ...rest].slice(0, 20);
    }
    if (!query) {
      return list.slice(0, 20);
    }
    return list;
  }, [stocks, trimmedSearch, tab, currentUser?.watchlist, marketSummary]);

  const isCurrentStockStarred = currentUser?.watchlist?.includes(stock.code) || false;

  // 보유 자산 및 포트폴리오 평가액/손익/수익률 계산 (GET /accounts/me/summary 및 /holdings 응답 우선 반영)
  const portfolioEntries = Object.entries(currentUser?.portfolio || {});
  let calcStockCost = 0;
  let calcStockValue = 0;
  portfolioEntries.forEach(([code, item]) => {
    const s = stocks.find((st) => st.code === code);
    const liveQuotePrice = quotes[code]?.price;
    const currPrice = liveQuotePrice ?? item.currentPrice ?? (s ? s.price : item.avgPrice);
    calcStockCost += (item.avgBuyPrc ?? item.avgPrice ?? 0) * (item.hldQty ?? item.quantity ?? 0);
    calcStockValue += currPrice * (item.hldQty ?? item.quantity ?? 0);
  });
  const hasLiveOverrides = portfolioEntries.some(([code]) => quotes[code]?.price != null);
  const totalStockCost = currentUser?.totalBuyAmt != null ? currentUser.totalBuyAmt : Math.round(calcStockCost);
  const portfolioStockValue = (!hasLiveOverrides && currentUser?.totalEvalAmt != null)
    ? currentUser.totalEvalAmt
    : Math.round(calcStockValue);
  const totalAssets = (!hasLiveOverrides && currentUser?.totalAssetAmt != null)
    ? currentUser.totalAssetAmt
    : (currentUser?.balance || 0) + portfolioStockValue;
  const totalProfitLoss = (!hasLiveOverrides && currentUser?.totalPnl != null)
    ? currentUser.totalPnl
    : portfolioStockValue - totalStockCost;
  const totalReturnRate = (!hasLiveOverrides && currentUser?.yieldRt != null)
    ? Number(currentUser.yieldRt)
    : (totalStockCost > 0 ? (totalProfitLoss / totalStockCost) * 100 : 0);
  const isAccountPriceDelayed = Boolean(currentUser?.priceDelayed);

  return (
    <div className="app-shell">
      <div style={{ padding: "8px 18px", background: "#18352d", color: "#b8eed5", fontSize: 12 }}>Spring Boot 별도 프로젝트 · {serverMode} · 모의투자 전용 · 과거 차트·지수·호가 예시</div>
      {/* 상단 네비게이션 */}
      <header className="topbar">
        <div className="topbar-inner">
          <button className="mobile-menu" aria-label="메뉴" onClick={() => setSideOpen(!sideOpen)}>
            <Menu size={20} />
          </button>
          <a className="brand" href="#home" title="StockShield 홈">
            <span className="brand-icon">
              <ShieldCheck size={21} />
            </span>
            <span>
              Stock<span className="brand-accent">Shield</span>
            </span>
          </a>
          <span className="brand-divider" />
          <div className="topbar-tagline">
            실시간 시세 흐름과 FDS 이상거래 탐지 엔진으로 보호되는 차세대 주식 플랫폼
          </div>
          <div className="topbar-right">
            <div className="topbar-status-group">
              <div
                className="topbar-security-badge"
                title="StockShield FDS 거래 보호 시스템 24시간 실시간 감지 가동 중"
                onClick={() => alert('[FDS 거래 보호 시스템]\n• 이상거래 탐지: 24시간 실시간 감지 가동 중\n• 비정상 거래 패턴 즉시 차단 및 사용자 자산 보호\n• 안전한 실시간 모의 투자 환경 제공')}
                role="button"
                tabIndex={0}
              >
                <ShieldCheck size={13} className="security-shield-icon" />
                <span className="security-title">FDS 거래 보호 시스템</span>
                <span className="security-status-tag">24시간 실시간 감지</span>
              </div>
              <div className="topbar-security-desc">
                <span className="live-dot" />
                <span>이상거래를 24시간 실시간 감지하여 안전한 모의 투자 경험 제공</span>
              </div>
            </div>

            {/* FDS 관제 전환 버튼 (관리자 로그인 시에만 출력, 기능 ID 21) */}
            {currentUser?.role === 'ADMIN' && (
              <button
                type="button"
                className={`fds-control-nav-btn ${activeView === 'admin' ? 'active' : ''}`}
                onClick={() => setActiveView(activeView === 'admin' ? 'trading' : 'admin')}
                title={activeView === 'admin' ? '실시간 트레이딩 대시보드로 돌아가기' : 'FDS 이상거래 관제 센터로 이동'}
              >
                <ShieldAlert size={15} />
                <span>{activeView === 'admin' ? '트레이딩 화면' : 'FDS 관제 센터'}</span>
                {unresolvedAlertCount > 0 && (
                  <span className="fds-alert-counter" title="미처리 심사 대기 경보 수">
                    {unresolvedAlertCount}
                  </span>
                )}
              </button>
            )}

            <button
              className="icon-btn"
              aria-label="알림"
              onClick={() => alert(currentUser ? `[FDS 보안 관제] 미처리 이상거래 경보: ${unresolvedAlertCount}건 · 모든 보안 모듈 정상 가동 중` : '알림은 로그인 후 확인할 수 있습니다.')}
            >
              <Bell size={18} />
            </button>

            {!currentUser && (
              <div style={{ display: 'flex', gap: '8px' }}>
                <button
                  className="login-nav"
                  style={{ background: '#25354e', borderColor: '#3b5277' }}
                  onClick={() => setIsSignUpOpen(true)}
                >
                  회원가입
                </button>
                <button
                  className="login-nav"
                  onClick={() => document.getElementById('login-id')?.focus()}
                >
                  로그인 <ArrowRight size={15} />
                </button>
              </div>
            )}
          </div>
        </div>
      </header>

      {/* 상단 고정 바 바로 아래 실시간 마켓 티커 배너 바 */}
      <div className="ticker-banner-bar" role="region" aria-label="실시간 시장 지수 티커">
        <div className="ticker-badge" title={krxStatus.tooltip}>
          <span className={`ticker-live-dot ${krxStatus.isOpen ? 'open' : 'closed'}`} />
          <span>시장 지수 예시</span>
          <span className={`ticker-status-pill ${krxStatus.isOpen ? 'open' : 'closed'}`}>
            {krxStatus.statusText}
          </span>
        </div>
        <div className="ticker-track-wrap">
          <div className="ticker-track">
            {tickerItems.map((item, idx) => (
              <div className="ticker-item" key={idx}>
                <span className="ticker-name">
                  {item.name} <small>{item.en}</small>
                </span>
                <strong className="ticker-val">{item.value}</strong>
                <span className={`ticker-diff ${item.isUp ? 'rise' : 'fall'}`}>
                  {item.diff} ({item.change})
                </span>
                <span className="ticker-divider">/</span>
              </div>
            ))}
          </div>
        </div>
      </div>

      {/* 메인 레이아웃: 트레이딩 화면 vs 관리자 FDS 관제 센터 (RBAC 403 차단, 기능 ID 20) */}
      {activeView === 'admin' ? (
        currentUser?.role === 'ADMIN' ? (
          <AdminFdsPanel
            onBackToTrading={() => setActiveView('trading')}
            currentUser={currentUser}
            onNotify={alert}
            lastQuoteTime={lastQuoteTime}
            onSessionExpired={() => {
              setCurrentUser(null);
              setActiveView('trading');
              alert('세션이 만료되었습니다. 다시 로그인해 주세요.');
            }}
          />
        ) : (
          <main className="layout" style={{ display: 'flex', justifyContent: 'center', alignItems: 'center', minHeight: '620px', padding: '40px 20px' }}>
            <div className="forbidden-403-panel" style={{ background: '#1c1722', border: '1px solid #702e3b', borderRadius: '12px', padding: '40px 32px', maxWidth: '520px', textAlign: 'center', boxShadow: '0 12px 36px rgba(0,0,0,0.5)' }}>
              <div style={{ display: 'inline-flex', padding: '14px', borderRadius: '50%', background: '#3d1620', color: '#ff5468', marginBottom: '16px' }}>
                <ShieldAlert size={42} />
              </div>
              <div style={{ display: 'inline-block', background: '#ff334b22', color: '#ff667a', border: '1px solid #ff334b55', borderRadius: '4px', fontSize: '11px', fontWeight: 800, padding: '3px 8px', marginBottom: '12px', letterSpacing: '0.05em' }}>
                403 FORBIDDEN / 접근 거부
              </div>
              <h2 style={{ fontSize: '20px', color: '#ffffff', marginBottom: '10px' }}>관리자 전용 관제 화면입니다</h2>
              <p style={{ color: '#a0aab8', fontSize: '13px', lineHeight: 1.6, marginBottom: '20px' }}>
                StockShield FDS 관제 센터는 이상거래 탐지 기록 확인·계좌 잠금 및 원장 대사 권한을 가진 관리자(ADMIN) 전용 시스템입니다. 일반 회원은 접근할 수 없습니다 (HTTP 403).
              </p>
              <div style={{ background: '#11131a', borderRadius: '6px', padding: '10px 14px', fontSize: '12px', color: '#7a8ba0', marginBottom: '24px' }}>
                현재 접속 계정: <strong style={{ color: '#e0e8f5' }}>@{currentUser?.id || '비로그인'}</strong> ({currentUser?.role || 'GUEST'})
              </div>
              <button
                type="button"
                className="btn-trading-switch"
                style={{ width: '100%', justifyContent: 'center', padding: '10px 16px', background: '#25354e', borderColor: '#3b5277', color: '#ffffff', borderRadius: '6px', cursor: 'pointer', display: 'flex', alignItems: 'center', gap: '8px' }}
                onClick={() => setActiveView('trading')}
              >
                <ArrowLeft size={16} />
                <span>실시간 트레이딩 대시보드로 돌아가기</span>
              </button>
            </div>
          </main>
        )
      ) : (
        <main className="layout" id="home">
          {/* 좌측 사이드바: 내 계좌 & 종목 감시 */}
          <aside className={`left-sidebar ${sideOpen ? 'open' : ''}`}>
            {/* 사용자 자산 및 계좌 정보 카드 (MARKET WATCH 상단) */}
            {currentUser && (
              <div className="user-profile-card left-user-account-panel">
                <div className="profile-top">
                  <div className="user-avatar">
                    {currentUser.name.slice(0, 1)}
                  </div>
                  <div className="profile-meta">
                    <div className="profile-name-row">
                      <span className="profile-name">{currentUser.name} 님</span>
                    </div>
                    <div className="profile-id">@{currentUser.id} · 회원</div>
                  </div>
                  <button
                    type="button"
                    className="logout-icon-btn"
                    onClick={handleLogout}
                    title="로그아웃"
                    aria-label="로그아웃"
                  >
                    <LogOut size={16} />
                  </button>
                </div>

                {/* 계좌 잠금 안내 배너 (기능 ID 67) */}
                {currentUser.isLocked && (
                  <div className="account-locked-banner" style={{ background: '#3b161c', border: '1px solid #ff4d64', borderRadius: '6px', padding: '10px 12px', margin: '0 0 12px 0', display: 'flex', alignItems: 'center', gap: '8px', color: '#ff667a', fontSize: '12px' }}>
                    <AlertTriangle size={16} style={{ flexShrink: 0 }} />
                    <div>
                      <strong>보안을 위해 거래가 잠겼습니다</strong>
                      <div style={{ fontSize: '11px', color: '#ff9ba7', marginTop: '2px' }}>
                        사유: {currentUser.lockReason || 'FDS 이상거래 탐지 동결'}
                      </div>
                    </div>
                  </div>
                )}

                {/* 보유 주식 현황 (Holdings) */}
                <div className="holdings-section">
                  <div className="holdings-head">
                    <span>내 보유 주식 ({portfolioEntries.length}종목)</span>
                    <button
                      type="button"
                      style={{ background: 'none', border: 0, color: '#6889ff', fontSize: '10px', cursor: 'pointer' }}
                      onClick={() => setShowHoldings(!showHoldings)}
                    >
                      {showHoldings ? '접기' : '상세보기'}
                    </button>
                  </div>
                  {showHoldings && (
                    <div className="holdings-list">
                      {portfolioEntries.length > 0 ? (
                        portfolioEntries.map(([code, item]) => {
                          const targetStock = stocks.find((s) => s.code === code);
                          const livePrice = quotes[code]?.price;
                          const currPrice = livePrice ?? item.currentPrice ?? (targetStock ? targetStock.price : item.avgPrice);
                          const evalAmt = livePrice != null
                            ? livePrice * item.quantity
                            : (item.evalAmt ?? currPrice * item.quantity);
                          const profitRate = livePrice != null || item.pnlRt == null
                            ? (((currPrice / (item.avgPrice || 1)) - 1) * 100)
                            : Number(item.pnlRt);
                          return (
                            <div
                              className="holding-item"
                              key={code}
                              style={{ cursor: 'pointer' }}
                              onClick={() => {
                                const idx = stocks.findIndex((s) => s.code === code);
                                if (idx !== -1) setStockIndex(idx);
                              }}
                              title={`클릭하여 ${item.name} 차트 보기`}
                            >
                              <div>
                                <strong>{item.name}</strong>
                                <small>{item.quantity}주{item.priceDelayed ? ' · 지연' : ''}</small>
                                <div style={{ fontSize: '10px', color: profitRate >= 0 ? '#ed707b' : '#62a4fc' }}>
                                  {profitRate >= 0 ? '+' : ''}{profitRate.toFixed(2)}% (평단 ₩{won(item.avgPrice)})
                                </div>
                              </div>
                              <div style={{ display: 'flex', alignItems: 'center', textAlign: 'right' }}>
                                <b style={{ fontSize: '11.5px', color: '#e2edf8' }}>
                                  ₩{won(evalAmt)}
                                </b>
                              </div>
                            </div>
                          );
                        })
                      ) : (
                        <div style={{ fontSize: '11px', color: '#72859b', padding: '6px 2px', textAlign: 'center' }}>
                          보유 중인 주식이 없습니다.<br />우측 주문창에서 매수를 시작해보세요!
                        </div>
                      )}
                    </div>
                  )}
                </div>

                {/* 계좌 액션 버튼 (주문 내역 / 모의 출금 / FDS 관제) */}
                <div className="user-action-row" style={{ display: 'flex', gap: '6px' }}>
                  {currentUser.role !== 'ADMIN' && (
                    <>
                      <button
                        type="button"
                        className="user-history-btn"
                        onClick={() => setIsOrderHistoryOpen(true)}
                        title="주문 및 체결 내역 확인"
                        style={{ flex: 1 }}
                      >
                        <Clock3 size={13} />
                        <span>주문 내역 ({currentUser.orderHistory?.length || 0})</span>
                      </button>
                      <button
                        type="button"
                        className="user-history-btn"
                        onClick={() => setIsWithdrawOpen(true)}
                        title="모의 출금 신청 및 출금 내역 확인"
                        style={{ flex: 1, background: '#1e324a', borderColor: '#365880', color: '#9cd0ff' }}
                      >
                        <Wallet size={13} />
                        <span>모의 출금</span>
                      </button>
                    </>
                  )}
                  {currentUser.role === 'ADMIN' && (
                    <button
                      type="button"
                      className="user-admin-btn"
                      onClick={() => setActiveView('admin')}
                      title="관리자 전용 FDS 관제 센터 열기"
                    >
                      <ShieldAlert size={13} />
                      <span>FDS 관제</span>
                    </button>
                  )}
                </div>
              </div>
            )}

            {/* MARKET WATCH 상단 고정 헤더 영역 (검색 & 4분할 탭) */}
            <div className="market-watch-sticky-header">
              <div className="sidebar-heading">
                <span>MARKET WATCH</span>
                <TrendingUp size={16} />
              </div>
              <div className="search-wrap">
                <Search size={16} />
                <input
                  value={search}
                  onChange={(e) => setSearch(e.target.value)}
                  placeholder="종목명, 코드(005930), 섹터 검색"
                  aria-label="종목 검색"
                />
                {search && (
                  <button
                    type="button"
                    className="search-clear-btn"
                    onClick={() => setSearch('')}
                    aria-label="검색어 지우기"
                  >
                    <X size={15} />
                  </button>
                )}
              </div>

              <div className="list-tabs">
                {['인기 종목', '상승률', '하락률', '코스피', '코스닥', '관심 종목'].map((t) => (
                  <button
                    type="button"
                    className={tab === t ? 'active' : ''}
                    key={t}
                    onClick={() => setTab(t)}
                  >
                    {t}
                  </button>
                ))}
              </div>
            </div>

            {/* 6자리 종목코드 입력 시 KIS 실시간 조회 및 추가 카드 */}
            {isSixDigitCode && (
              <div
                className="kis-search-card"
                onClick={() => handleLookupAndAddStock(trimmedSearch)}
                title="클릭하여 KIS 실시간 시세 조회 및 종목 등록"
              >
                <div className="kis-search-head">
                  <span>⚡ KIS 실시간 조회</span>
                  <Sparkles size={14} />
                </div>
                <div className="kis-search-desc">
                  {isSearchingKis
                    ? '한국투자증권 API로 실시간 시세를 조회하는 중...'
                    : (() => {
                      const match = stocks.find((s) => s.code === trimmedSearch);
                      const namePreview = match ? match.name : (trimmedSearch === '036570' ? '엔씨소프트' : '');
                      return namePreview
                        ? `[${trimmedSearch}] ${namePreview} 실시간 현재가 조회 및 추가`
                        : `코드 [${trimmedSearch}] 현재가 조회 및 종목 목록에 추가`;
                    })()}
                </div>
              </div>
            )}

            <div className="stock-list">
              {displayList.length > 0 ? (
                displayList.map((s) => {
                  const idx = stocks.indexOf(s);
                  const isStarred = currentUser?.watchlist?.includes(s.code);
                  const qPrice = quotes[s.code]?.price ?? s.price;
                  const qChange = quotes[s.code]?.change ?? s.change;
                  const isRisky = Boolean(riskStocksMap[s.code] || quotes[s.code]?.riskYn === 'Y');
                  return (
                    <div
                      role="button"
                      tabIndex={0}
                      className={`stock-item ${stockIndex === idx ? 'active' : ''}`}
                      key={s.code}
                      onClick={() => {
                        setStockIndex(idx);
                        setSideOpen(false);
                      }}
                      onKeyDown={(e) => {
                        if (e.key === 'Enter' || e.key === ' ') {
                          setStockIndex(idx);
                          setSideOpen(false);
                        }
                      }}
                    >
                      <span
                        className="stock-symbol"
                        style={{ background: (s.color || '#6c85f7') + '22', color: s.color || '#6c85f7' }}
                      >
                        {s.name.slice(0, 1)}
                      </span>
                      <span className="stock-name">
                        <b>
                          {s.name}
                          {isRisky && (
                            <span
                              style={{ marginLeft: '5px', fontSize: '10px', background: '#4a1822', color: '#ff7082', border: '1px solid #ff4d64', borderRadius: '3px', padding: '1px 4px', fontWeight: 700 }}
                              title={riskStocksMap[s.code]?.reason || '위험 의심 종목'}
                            >
                              ⚠️ 위험
                            </span>
                          )}
                        </b>
                        <small>
                          <span className={`market-tag ${s.market === 'KOSDAQ' ? 'kosdaq' : ''}`}>
                            {s.market || 'KOSPI'}
                          </span>
                          {s.code}
                        </small>
                      </span>
                      <span className="stock-price">
                        <b>{qPrice > 0 ? won(qPrice) : '-'}</b>
                        <small className={qChange >= 0 ? 'rise' : 'fall'}>
                          {qPrice > 0 ? `${qChange >= 0 ? '+' : ''}${Number(qChange).toFixed(2)}%` : '대기'}
                        </small>
                      </span>
                      {currentUser && (
                        <button
                          type="button"
                          className={`star-btn ${isStarred ? 'starred' : ''}`}
                          onClick={(e) => {
                            e.stopPropagation();
                            handleToggleWatchlist(s.code);
                          }}
                          aria-label={isStarred ? '관심 종목 해제' : '관심 종목 추가'}
                        >
                          <Star size={14} fill={isStarred ? '#f1cc62' : 'none'} />
                        </button>
                      )}
                    </div>
                  );
                })
              ) : tab === '관심 종목' && !currentUser ? (
                <div className="watch-empty">
                  <Shield size={24} />
                  <p>로그인 후 관심 종목을<br />관리할 수 있습니다.</p>
                  <div style={{ display: 'flex', gap: '6px', justifyContent: 'center' }}>
                    <button onClick={() => document.getElementById('login-id')?.focus()}>로그인</button>
                    <button
                      style={{ background: '#364966', borderColor: '#4a6792' }}
                      onClick={() => setIsSignUpOpen(true)}
                    >
                      회원가입
                    </button>
                  </div>
                </div>
              ) : tab === '관심 종목' ? (
                <div className="watch-empty">
                  <Star size={24} style={{ color: '#f1cc62' }} />
                  <p>등록된 관심 종목이 없습니다.<br />종목의 별표(★)를 눌러 추가해보세요.</p>
                </div>
              ) : (
                <div className="empty">
                  <p>'{search}' 검색 결과가 없습니다.</p>
                  {/^\d{1,5}$/.test(trimmedSearch) ? (
                    <p style={{ fontSize: '11px', color: '#6e85a6', marginTop: '4px' }}>
                      6자리 종목코드를 입력하시면 KIS 실시간 조회가 가능합니다.
                    </p>
                  ) : (
                    <p style={{ fontSize: '11px', color: '#6e85a6', marginTop: '4px' }}>
                      다른 검색어를 입력하시거나 6자리 종목코드를 입력해보세요.
                    </p>
                  )}
                </div>
              )}
            </div>
          </aside>

          {/* 중앙 컨텐츠 영역: 상단 전체 종목 개요(가격·시가종가) + 하단 2단(주가차트 / 호가창) */}
          <div className="content">
            <section className="stock-overview panel">
              <div className="stock-overview-main">
                {/* 종목 기본 정보 (코드, 로고, 종목명) */}
                <div className="stock-overview-meta">
                  <div className="stock-header">
                    <span
                      className="company-logo"
                      style={{ background: (stock.color || '#6c85f7') + '25', color: stock.color || '#6c85f7' }}
                    >
                      {stock.name.slice(0, 1)}
                    </span>
                    <h2>{stock.name}</h2>
                    <span className="kospi-label">{stock.market || 'KOSPI'} · {stock.code}</span>
                    <button
                      type="button"
                      className={`star-btn ${isCurrentStockStarred ? 'starred' : ''}`}
                      onClick={() => handleToggleWatchlist(stock.code)}
                      title={isCurrentStockStarred ? '관심 종목에서 제거' : '관심 종목으로 추가'}
                    >
                      <Star size={18} fill={isCurrentStockStarred ? '#f1cc62' : 'none'} />
                    </button>
                  </div>
                  {(stock.riskInfo || (krxStatus.isOpen && stock.priceDelayed)) && (
                    <div style={{ display: 'flex', flexWrap: 'wrap', alignItems: 'center', gap: '6px' }}>
                      {stock.riskInfo && (
                        <span
                          style={{ background: '#4a1822', color: '#ff7b8b', border: '1px solid #ff4d64', borderRadius: '5px', padding: '2px 8px', fontSize: '11.5px', fontWeight: 700, display: 'inline-flex', alignItems: 'center', gap: '4px' }}
                          title={stock.riskInfo.reason || '급등/급락 위험 의심 종목'}
                        >
                          <AlertTriangle size={13} />
                          위험 의심 ({stock.riskInfo.dir === 'UP' || stock.riskInfo.dir === 'SURGE' ? '급등' : stock.riskInfo.dir === 'DOWN' || stock.riskInfo.dir === 'PLUNGE' ? '급락' : '주의'}): {stock.riskInfo.reason}
                        </span>
                      )}
                      {krxStatus.isOpen && stock.priceDelayed && (
                        <span
                          style={{ background: '#332714', color: '#f1cc62', border: '1px solid #f1cc62', borderRadius: '5px', padding: '2px 8px', fontSize: '11.5px', fontWeight: 600 }}
                        >
                          시세 지연 (60초 초과)
                        </span>
                      )}
                    </div>
                  )}
                </div>

                {/* 현재가 및 등락률 (종목 정보 우측에 배치) */}
                <div className="stock-overview-price">
                  <div className="big-price">
                    {won(stock.price)} <small>KRW</small>
                  </div>
                  {quoteError && (
                    <p style={{ color: "#ed707b", fontSize: "12px", margin: "4px 0" }}>
                      {quoteError} 이전 또는 가상 시세가 표시될 수 있습니다.
                    </p>
                  )}
                  <div className={`price-change ${Number(stock.change || 0) >= 0 ? 'rise' : 'fall'}`}>
                    <span className="price-change-val">
                      {Number(stock.change || 0) >= 0 ? <ArrowUpRight size={15} /> : <ArrowDownLeft size={15} />}
                      {' '}
                      {Number(stock.change || 0) >= 0 ? '+' : ''}
                      {stock.chgPrc != null
                        ? won(stock.chgPrc)
                        : won((Number(stock.price || 0) * Number(stock.change || 0)) / (100 + Number(stock.change || 0)))}{' '}
                      ({Number(stock.change || 0) >= 0 ? '+' : ''}{Number(stock.change || 0).toFixed(2)}%)
                    </span>
                    <span className="price-source-tag">
                      전일 대비
                    </span>
                  </div>
                </div>
              </div>
            </section>

            <div className="trading-grid" id="market">
              <div className="chart-column">
                <CandleChart
                  stock={stock}
                  chartInterval={chartInterval}
                  setChartInterval={setChartInterval}
                  candles={candles}
                  hover={chartHover}
                  setHover={setChartHover}
                />
              </div>

              {/* 호가창 & 반응형 분할 주문창 영역 (창 축소 시 호가창 우측에 주문창 표시) */}
              <div className="orderbook-column orderbook-order-split" ref={splitOrderRef}>
                <OrderBook
                  stock={stock}
                  orderbook={orderbook}
                  orderbookUnavailable={orderbookUnavailable}
                  onSelectPrice={handleOpenOrder}
                />

                <div className="split-trade-panel">
                  {currentUser ? (
                    currentUser.role === 'ADMIN' ? (
                      <div className="split-login-prompt panel">
                        <ShieldAlert size={28} style={{ color: '#f1cc62', marginBottom: '8px' }} />
                        <h3 style={{ fontSize: '15px', color: '#e5ecf4', marginBottom: '6px' }}>관리자 직무 분리 적용</h3>
                        <p style={{ fontSize: '12px', color: '#8fa0b5', marginBottom: '14px', lineHeight: 1.4 }}>
                          관리자 계정은 관제 전용이며 주문·출금 기능을 제공하지 않습니다.
                        </p>
                        <button
                          type="button"
                          className="primary-btn"
                          onClick={() => setActiveView('admin')}
                          style={{ padding: '8px 18px', fontSize: '12px', fontWeight: 700 }}
                        >
                          FDS 관제 센터로 이동
                        </button>
                      </div>
                    ) : (
                      <TradingOrderPanel
                        stock={stock}
                        currentUser={currentUser}
                        orderType={orderType}
                        setOrderType={setOrderType}
                        orderPrice={orderPrice}
                        setOrderPrice={setOrderPrice}
                        balanceCard={renderProfileBalanceCard()}
                        isMarketOpen={krxStatus.isOpen}
                        onOrderSuccess={(updatedUser, msg) => {
                          setCurrentUser(updatedUser);
                          alert(msg);
                        }}
                        isHighlighted={isOrderHighlighted}
                      />
                    )
                  ) : (
                    renderAuthLoginCard('-split')
                  )}
                </div>
              </div>
            </div>

            {/* 한국경제 공식 RSS 실시간 증권 뉴스 패널 (10분 자동 수집) */}
            <NewsPanel currentStock={stock} />
          </div>

          {/* 우측 사이드바: 데스크톱 주식 거래 주문창 (일반회원 로그인 시) 또는 로그인/추가인증 창 (비로그인 시) */}
      <aside className={`auth-sidebar ${currentUser ? 'user-logged-in' : 'user-logged-out'}`} ref={orderSidebarRef}>
        {currentUser ? (
          currentUser.role === 'ADMIN' ? (
            <div className="auth-card panel">
              <div className="auth-mark">
                <span className="brand-icon large">
                  <ShieldAlert size={28} />
                </span>
              </div>
              <h2>FDS 관제 관리자</h2>
              <p>관리자 계정(@{currentUser.id})은 직무 분리 원칙에 따라 주문·출금 기능을 제공하지 않습니다.</p>
              <button
                type="button"
                className="primary-btn"
                onClick={() => setActiveView('admin')}
                style={{ marginTop: '12px' }}
              >
                FDS 관제 센터로 이동 <ArrowRight size={18} />
              </button>
              <button
                type="button"
                className="demo-login-shortcut"
                onClick={handleLogout}
                style={{ marginTop: '10px', justifyContent: 'center' }}
              >
                <LogOut size={14} /> 로그아웃
              </button>
            </div>
          ) : (
            <>
              <TradingOrderPanel
                stock={stock}
                currentUser={currentUser}
                orderType={orderType}
                setOrderType={setOrderType}
                orderPrice={orderPrice}
                setOrderPrice={setOrderPrice}
                balanceCard={renderProfileBalanceCard()}
                isMarketOpen={krxStatus.isOpen}
                onOrderSuccess={(updatedUser, msg) => {
                  setCurrentUser(updatedUser);
                  alert(msg);
                }}
                isHighlighted={isOrderHighlighted}
              />

              <div className="helper-card">
                <div className="helper-icon">
                  <ChartNoAxesCombined size={19} />
                </div>
                <div>
                  <b>더 스마트한 주식 거래</b>
                  <p>시세 확인부터 거래 보안까지,<br />하나의 플랫폼에서.</p>
                </div>
                <ArrowRight size={16} />
              </div>

              <div className="phase-info">
                STOCKSHIELD <span>v1.0</span>
              </div>
            </>
          )
        ) : (
          renderAuthLoginCard('')
        )}
      </aside>
    </main >
      )
}

{/* 토스트 알림 */ }
{
  toast && (
    <div className="toast">
      <Shield size={18} />
      <span>{toast}</span>
      <button onClick={() => setToast('')} aria-label="닫기">
        <X size={16} />
      </button>
    </div>
  )
}

{/* 주문 및 체결 내역 모달 */ }
<OrderHistoryModal
  isOpen={isOrderHistoryOpen}
  onClose={() => setIsOrderHistoryOpen(false)}
  currentUser={currentUser}
  onSelectStock={(code) => {
    const idx = stocks.findIndex((s) => s.code === code);
    if (idx !== -1) setStockIndex(idx);
  }}
/>

{/* 모의 출금 및 출금 내역 모달 */ }
<WithdrawalModal
  isOpen={isWithdrawOpen}
  onClose={() => setIsWithdrawOpen(false)}
  currentUser={currentUser}
  onWithdrawSuccess={(updatedUser, msg) => {
    setCurrentUser(updatedUser);
    if (msg) alert(msg);
  }}
/>

{/* 회원가입 모달 */ }
<SignUpModal
  isOpen={isSignUpOpen}
  onClose={() => setIsSignUpOpen(false)}
  onRegisterSuccess={handleRegisterSuccess}
/>
    </div >
  );
}

class AppErrorBoundary extends React.Component {
  constructor(props) {
    super(props);
    this.state = { error: null };
  }
  static getDerivedStateFromError(error) {
    return { error };
  }
  render() {
    if (this.state.error) {
      return (
        <div style={{ padding: '32px', color: '#ff8a98', background: '#0c111b', minHeight: '100vh', fontFamily: 'monospace' }}>
          <h2 style={{ color: '#fff', marginBottom: '12px' }}>화면 렌더링 오류 발생</h2>
          <pre style={{ whiteSpace: 'pre-wrap', background: '#161f2e', padding: '16px', borderRadius: '8px', border: '1px solid #ff4d64' }}>
            {String(this.state.error?.stack || this.state.error?.message || this.state.error)}
          </pre>
        </div>
      );
    }
    return this.props.children;
  }
}

createRoot(document.getElementById('root')).render(
  <AppErrorBoundary>
    <App />
  </AppErrorBoundary>
);
