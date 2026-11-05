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
import AdminFdsPanel from './components/AdminFdsPanel';
import { 
  getActiveSession, 
  authenticateUser, 
  logoutUser,
  clearActiveSession, 
  toggleUserWatchlist,
  getStoredAlerts,
  switchSessionUser,
  syncFromPostgres,
  INITIAL_DEMO_USER 
} from './utils/storage';
import { DEFAULT_STOCKS } from './stocksData';

const won = (x) => Math.round(x).toLocaleString('ko-KR');
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
  const response = await fetch(
    `http://localhost:4000/api/stocks/${code}/price`,
    { signal }
  );

  if (!response.ok) {
    throw new Error("주식 시세 조회 실패");
  }

  return response.json();
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
  if (interval === '1개월') return '최근 1개월 (일봉)';
  if (interval === '3개월') return '최근 3개월 (일봉)';
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

function buildCandles(seed, interval, currentPrice) {
  const config = INTERVAL_CONFIGS[interval] || INTERVAL_CONFIGS['1일'];
  const { count, startRatio, waveAmp, wickAmp } = config;
  const base = currentPrice || 70000;
  const hash = typeof seed === 'number'
    ? seed * 37
    : String(seed || '005930').split('').reduce((acc, c) => acc + c.charCodeAt(0), 0);
  let p = base * startRatio;
  const timestamps = generateCandleTimestamps(interval, count);

  return Array.from({ length: count }, (_, i) => {
    const wave = (Math.sin(i * 1.73 + hash * 0.3) * 0.6 + Math.cos(i * 0.58 + hash) * 0.4) * waveAmp;
    const drift = ((base - p) / Math.max(1, count - i)) * 0.65;
    const open = p;
    const close = i === count - 1 ? base : p + drift + base * wave;
    const high = Math.max(open, close) + base * (wickAmp + (Math.sin(i * 3.2) + 1) * (wickAmp * 0.6));
    const low = Math.min(open, close) - base * (wickAmp * 0.8 + (Math.cos(i * 2.1) + 1) * (wickAmp * 0.5));
    p = close;
    const ts = timestamps[i] || { dateStr: '', label: '' };
    return {
      open,
      close,
      high,
      low,
      volume: 30 + Math.abs(Math.sin(i * 2.7)) * 75,
      dateStr: ts.dateStr,
      label: ts.label,
    };
  });
}

function CandleChart({ stock, chartInterval, setChartInterval, candles, hover, setHover }) {
  const currentPrice = stock?.price || 70000;

  // 1. 줌 & 팬 상태 관리 (기본 45개 표시, 드래그 및 마우스 휠로 조절)
  const [zoomCount, setZoomCount] = useState(45);
  const [panOffset, setPanOffset] = useState(0); // 0: 최신 데이터, >0: 과거 데이터
  const [isDragging, setIsDragging] = useState(false);
  const [isFullscreen, setIsFullscreen] = useState(false);

  const chartWrapRef = useRef(null);
  const dragStartRef = useRef({ x: 0, panOffset: 0, hasMoved: false });

  // 주기(interval) 변경 시 기본 뷰(최신 시세, 45개)로 리셋
  useEffect(() => {
    setZoomCount(45);
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
        const maxCount = Math.min(candles.length, 90);
        return Math.max(minCount, Math.min(maxCount, prev + delta));
      });
    };

    el.addEventListener('wheel', onWheel, { passive: false });
    return () => el.removeEventListener('wheel', onWheel);
  }, [candles.length]);

  // 슬라이스된 화면 표시용 캔들 계산
  const totalCandles = candles.length;
  const clampedZoom = Math.max(16, Math.min(totalCandles, zoomCount));
  const maxOffset = Math.max(0, totalCandles - clampedZoom);
  const clampedOffset = Math.max(0, Math.min(maxOffset, panOffset));

  const startIndex = totalCandles - clampedZoom - clampedOffset;
  const endIndex = totalCandles - clampedOffset;
  const visibleCandles = candles.slice(startIndex, endIndex);

  // 차트 뷰포트 좌표 (일반 vs 전체화면)
  const W = isFullscreen ? 1240 : 890;
  const H = isFullscreen ? 500 : 310;
  const left = isFullscreen ? 20 : 16;
  const right = isFullscreen ? 84 : 76;
  const top = isFullscreen ? 20 : 14;
  const chartBottom = isFullscreen ? 350 : 208;
  const volumeTop = isFullscreen ? 385 : 228;
  const volumeBottom = isFullscreen ? 450 : 266;
  const xAxisDividerY = isFullscreen ? 456 : 270;
  const xAxisTextY = isFullscreen ? 472 : 283;
  const hoverBadgeY = isFullscreen ? 478 : 291;
  const hoverTextY = isFullscreen ? 491 : 303;

  const high = Math.max(...visibleCandles.map((c) => c.high)) * 1.003;
  const low = Math.min(...visibleCandles.map((c) => c.low)) * 0.997;
  const y = (p) => top + ((high - p) / (high - low)) * (chartBottom - top);
  const step = (W - left - right) / visibleCandles.length;
  const lineY = y(currentPrice);

  const isViewCustomized = clampedOffset > 0 || clampedZoom !== 45;

  const handleResetView = () => {
    setPanOffset(0);
    setZoomCount(45);
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
          <span className="current-interval-badge">{getIntervalBadgeLabel(chartInterval)}</span>
          <span
            className="chart-active-date-badge"
            style={{
              fontSize: '11px',
              color: '#9cb3d0',
              background: 'rgba(34, 48, 68, 0.75)',
              border: '1px solid #2d3d52',
              padding: '2px 9px',
              borderRadius: '5px',
              fontWeight: 600,
              display: 'inline-flex',
              alignItems: 'center',
              gap: '5px',
            }}
            title="현재 선택 또는 호버된 캔들의 기준 일자 및 시간"
          >
            <Clock size={11} />
            {isHoverVisible && visibleCandles[visibleHoverIndex]
              ? visibleCandles[visibleHoverIndex].dateStr
              : (visibleCandles.at(-1)?.dateStr || '2026.11.05')}
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
            {['1분', '5분', '10분', '30분'].map((item) => (
              <button
                key={item}
                type="button"
                className={chartInterval === item ? 'selected' : ''}
                onClick={() => { setHover(null); setChartInterval(item); }}
              >
                {item}
              </button>
            ))}
          </div>
          <span className="interval-divider" />
          <div className="interval-group">
            {['1시간', '3시간'].map((item) => (
              <button
                key={item}
                type="button"
                className={chartInterval === item ? 'selected' : ''}
                onClick={() => { setHover(null); setChartInterval(item); }}
              >
                {item}
              </button>
            ))}
          </div>
          <span className="interval-divider" />
          <div className="interval-group">
            {['1일', '1주', '1개월', '3개월'].map((item) => (
              <button
                key={item}
                type="button"
                className={chartInterval === item ? 'selected' : ''}
                onClick={() => { setHover(null); setChartInterval(item); }}
              >
                {item}
              </button>
            ))}
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
          {visibleCandles.map((c, i) => {
            const x = left + i * step + step / 2;
            const up = c.close >= c.open;
            const cColor = up ? '#f06770' : '#4d9cf2';
            const bodyTop = Math.min(y(c.open), y(c.close));
            const bodyH = Math.max(1.7, Math.abs(y(c.open) - y(c.close)));
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
                <rect x={x - step * 0.27} y={bodyTop} width={step * 0.54} height={bodyH} rx=".7" fill={cColor} />
                <rect
                  x={x - step * 0.26}
                  y={volumeBottom - (c.volume / 110) * (volumeBottom - volumeTop)}
                  width={step * 0.52}
                  height={(c.volume / 110) * (volumeBottom - volumeTop)}
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
          {getIntervalLabels(chartInterval, visibleCandles).map((t, i) => (
            <text
              key={t + i}
              x={left + 35 + ((W - left - right - 80) * i) / 4}
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
                x1={left + visibleHoverIndex * step + step / 2}
                x2={left + visibleHoverIndex * step + step / 2}
                y1={top}
                y2={hoverBadgeY}
                stroke="#63a7ff"
                strokeWidth="1"
                strokeDasharray="2 3"
                opacity="0.85"
              />
              <rect
                x={Math.max(left, Math.min(W - right - 78, left + visibleHoverIndex * step + step / 2 - 39))}
                y={hoverBadgeY}
                width="78"
                height="16"
                rx="4"
                fill="#2563eb"
                stroke="#3b82f6"
                strokeWidth="1"
              />
              <text
                x={Math.max(left + 39, Math.min(W - right - 39, left + visibleHoverIndex * step + step / 2))}
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
      </div>

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

function OrderBook({ stock, onSelectPrice }) {
  const tick = stock.price >= 100000 ? 100 : stock.price >= 50000 ? 100 : 50;
  const asks = Array.from({ length: 5 }, (_, i) => ({
    price: stock.price + (5 - i) * tick,
    volume: Math.round(600 + 540 * (1 + Math.sin(i * 2.4 + stock.price)))
  }));
  const bids = Array.from({ length: 5 }, (_, i) => ({
    price: stock.price - i * tick,
    volume: Math.round(700 + 570 * (1 + Math.cos(i * 2.1 + stock.price)))
  }));

  return (
    <section className="orderbook panel">
      <div className="section-heading book-title">
        <Activity size={18} />
        <strong>호가창</strong>
        <span
          className="mini-label"
          style={{ cursor: 'pointer' }}
          onClick={() => onSelectPrice && onSelectPrice(stock.price, 'BUY')}
          title="클릭하여 모의 주문창 열기"
        >
          호가 클릭 주문
        </span>
      </div>
      <div className="book-columns">
        <span>매도잔량</span>
        <span>호가 (KRW)</span>
        <span>매수잔량</span>
      </div>
      <div className="book-rows">
        {asks.map((item, i) => (
          <div
            className="book-row ask"
            key={i}
            style={{ cursor: 'pointer' }}
            title={`클릭하여 ₩${won(item.price)} 매수 주문`}
            onClick={() => onSelectPrice && onSelectPrice(item.price, 'BUY')}
          >
            <div className="depth askdepth" style={{ width: `${Math.min(92, item.volume / 22)}%` }} />
            <span className="book-volume">{won(item.volume)}</span>
            <b>{won(item.price)}</b>
            <span>—</span>
          </div>
        ))}
        <div
          className="book-current"
          style={{ cursor: 'pointer' }}
          title={`클릭하여 현재가 ₩${won(stock.price)} 매수 주문`}
          onClick={() => onSelectPrice && onSelectPrice(stock.price, 'BUY')}
        >
          <span>현재가</span>
          <b>{won(stock.price)}</b>
          <span className={stock.change >= 0 ? 'rise' : 'fall'}>
            {stock.change >= 0 ? '+' : ''}{stock.change.toFixed(2)}%
          </span>
        </div>
        {bids.map((item, i) => (
          <div
            className="book-row bid"
            key={i}
            style={{ cursor: 'pointer' }}
            title={`클릭하여 ₩${won(item.price)} 매도 주문`}
            onClick={() => onSelectPrice && onSelectPrice(item.price, 'SELL')}
          >
            <div className="depth biddepth" style={{ width: `${Math.min(92, item.volume / 22)}%` }} />
            <span>—</span>
            <b>{won(item.price)}</b>
            <span className="book-volume">{won(item.volume)}</span>
          </div>
        ))}
      </div>
      <div className="book-summary">
        <span>매도 총잔량 <b>{won(asks.reduce((s, x) => s + x.volume, 0))}</b></span>
        <span>매수 총잔량 <b>{won(bids.reduce((s, x) => s + x.volume, 0))}</b></span>
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
      ? `KRX 정규장 운영 중 (09:00~15:30 KST) · 실시간 지수 수신`
      : `KRX 정규장 마감 (${reason} · 정규장 평일 09:00~15:30 KST)`,
  };
}

function App() {
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
  const [orderType, setOrderType] = useState('BUY');
  const [orderPrice, setOrderPrice] = useState(null);
  const [showHoldings, setShowHoldings] = useState(true);
  const [toast, setToast] = useState('');
  const [sideOpen, setSideOpen] = useState(false);
  const [isOrderHighlighted, setIsOrderHighlighted] = useState(false);
  const orderSidebarRef = useRef(null);
  const splitOrderRef = useRef(null);

  // 뷰 전환 및 추가 모달 상태 (트레이딩 vs 관리자 관제, 주문 내역)
  const [activeView, setActiveView] = useState('trading'); // 'trading' | 'admin'
  const [isOrderHistoryOpen, setIsOrderHistoryOpen] = useState(false);
  const [alertsList, setAlertsList] = useState(getStoredAlerts);

  useEffect(() => {
    const timer = setInterval(() => {
      setAlertsList(getStoredAlerts());
    }, 4000);
    return () => clearInterval(timer);
  }, []);

  const unresolvedAlertCount = alertsList.filter((a) => a.status === 'HOLD').length;
  
  // 인증 및 세션 상태
  const [currentUser, setCurrentUser] = useState(() => getActiveSession());
  const [loginId, setLoginId] = useState('');
  const [loginPassword, setLoginPassword] = useState('');
  const [loginError, setLoginError] = useState('');

  // 최초 진입 및 사용자 변경 시 PostgreSQL 원장 데이터 동기화
  useEffect(() => {
    syncFromPostgres(currentUser?.id || 'demo');
  }, [currentUser?.id]);

  // 30초마다 KRX 시장 운영 상태 갱신
  useEffect(() => {
    const timer = setInterval(() => {
      setKrxStatus(getKrxMarketStatus());
    }, 30000);
    return () => clearInterval(timer);
  }, []);

  const [quotes, setQuotes] = useState({});
  const [lastQuoteTime, setLastQuoteTime] = useState(() => Date.now());
  const [quoteError, setQuoteError] = useState("");

  const currentStock = stocks[stockIndex] || stocks[0] || DEFAULT_STOCKS[0];
  const selectedCode = currentStock.code;

  const stock = {
    ...currentStock,
    ...(quotes[selectedCode] || {}),
  };
  const currentPrice = stock?.price || 70000;
  const candles = useMemo(() => buildCandles(stock?.code, chartInterval, currentPrice), [stock?.code, chartInterval, currentPrice]);
  const activeCandle = chartHover === null ? candles.at(-1) : candles[chartHover];
  const activePercent = chartHover === null && stock?.change != null
    ? stock.change
    : (((activeCandle?.close || currentPrice) / (activeCandle?.open || currentPrice)) - 1) * 100;

  useEffect(() => {
    let active = true;
    let isFetching = false;

    const updatePrice = async () => {
      if (isFetching) return;
      isFetching = true;

      try {
        const data = await fetchStockPrice(selectedCode);

        if (!active) return;

        setQuotes((prev) => ({
          ...prev,
          [selectedCode]: data,
        }));
        setLastQuoteTime(Date.now());

        setQuoteError("");
      } catch (error) {
        if (!active) return;
        if (error.name !== "AbortError") {
          setQuotes((prev) => {
            if (!prev[selectedCode]) {
              setQuoteError("실제 시세를 불러오지 못했습니다.");
            }
            return prev;
          });
        }
      } finally {
        isFetching = false;
      }
    };

    updatePrice();

    // 0.5초(500ms)마다 선택된 종목 현재가 조회
    const timer = setInterval(updatePrice, 500);

    return () => {
      active = false;
      clearInterval(timer);
    };
  }, [selectedCode]);

  const alert = (msg) => {
    setToast(msg);
    window.setTimeout(() => setToast(''), 4500);
  };

  // 모의 주문 실행 / 주문창 열기 (타입 및 가격 유연 인자 수신)
  const handleOpenOrder = (arg1 = 'BUY', arg2 = null) => {
    if (!currentUser) {
      alert('모의 주문은 로그인 또는 회원가입 후 이용하실 수 있습니다.');
      setIsSignUpOpen(true);
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

  // 로그인 처리 (BCrypt 검증 & JWT 발급 & 5회 오류 시 30분 잠금)
  const handleLogin = async (e) => {
    e?.preventDefault();
    setLoginError('');
    if (!loginId.trim() || !loginPassword) {
      setLoginError('아이디와 비밀번호를 모두 입력해주세요.');
      return;
    }
    const res = await authenticateUser(loginId, loginPassword);
    if (res.success) {
      setCurrentUser(res.user);
      setLoginId('');
      setLoginPassword('');
      alert(`환영합니다, ${res.user.name}님! FDS 보안 모니터링이 활성화되었습니다.`);
    } else {
      setLoginError(res.message);
    }
  };

  // 데모 계정 원클릭 간편 로그인
  const handleDemoLogin = async () => {
    setLoginError('');
    const res = await authenticateUser('demo', 'Demo1234!');
    if (res.success) {
      setCurrentUser(res.user);
      alert('체험용 일반회원 데모 계정으로 로그인되었습니다.');
    } else {
      setLoginError(res.message);
    }
  };

  // 관리자 계정 원클릭 간편 로그인
  const handleAdminLogin = async () => {
    setLoginError('');
    const res = await authenticateUser('admin', 'Admin1234!');
    if (res.success) {
      setCurrentUser(res.user);
      setActiveView('admin');
      alert('보안관제팀 관리자(admin) 계정으로 로그인되었습니다.');
    } else {
      setLoginError(res.message);
    }
  };

  // 로그아웃 (Refresh 토큰 폐기)
  const handleLogout = async () => {
    await logoutUser();
    setCurrentUser(null);
    setIsOrderOpen(false);
    setActiveView('trading');
    alert('안전하게 로그아웃되었습니다.');
  };

  // 회원가입 성공 시 콜백 (자동 로그인 처리)
  const handleRegisterSuccess = (newUser) => {
    setCurrentUser(newUser);
    alert(`🎉 ${newUser.name}님 환영합니다! 모의투자 지원금 ${won(newUser.balance)}원이 지급되었습니다.`);
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

  // 탭 목록: '인기 종목', '코스피', '코스닥', '관심 종목'
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

    // 인기 종목: 검색 중이 아닐 때는 상위 16개 대표주 노출
    if (!query) {
      return list.slice(0, 16);
    }
    return list;
  }, [stocks, trimmedSearch, tab, currentUser?.watchlist]);

  const isCurrentStockStarred = currentUser?.watchlist?.includes(stock.code) || false;

  // 보유 자산 및 포트폴리오 평가액/손익/수익률 계산 (기능 ID 64)
  const portfolioEntries = Object.entries(currentUser?.portfolio || {});
  let totalStockCost = 0;
  let portfolioStockValue = 0;
  portfolioEntries.forEach(([code, item]) => {
    const s = stocks.find((st) => st.code === code);
    const currPrice = s ? (quotes[code]?.price ?? s.price) : item.avgPrice;
    totalStockCost += item.avgPrice * item.quantity;
    portfolioStockValue += currPrice * item.quantity;
  });
  const totalAssets = (currentUser?.balance || 0) + portfolioStockValue;
  const totalProfitLoss = portfolioStockValue - totalStockCost;
  const totalReturnRate = totalStockCost > 0 ? (totalProfitLoss / totalStockCost) * 100 : 0;

  return (
    <div className="app-shell">
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
          <span>KRX 시장 지수</span>
          <span className={`ticker-status-pill ${krxStatus.isOpen ? 'open' : 'closed'}`}>
            {krxStatus.statusText}
          </span>
        </div>
        <div className="ticker-track-wrap">
          <div className="ticker-track">
            {TICKER_ITEMS.map((item, idx) => (
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
                StockShield FDS 관제 센터는 이상거래 탐지 및 주문 승인 권한을 가진 관리자(ADMIN) 전용 시스템입니다. 일반 회원은 접근할 수 없습니다 (HTTP 403).
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

              {/* 모의투자 자산 현황 (예수금, 평가금액, 총손익, 수익률, 기능 ID 64) */}
              <div className="profile-balance-card">
                <div className="balance-label-row">
                  <span>모의투자 총 보유자산</span>
                  <span style={{ color: currentUser.isLocked ? '#ff6666' : '#4cd49e' }}>
                    {currentUser.isLocked ? '⚠️ 거래 잠김 (주문 정지)' : 'FDS 정상'}
                  </span>
                </div>
                <div className="balance-total">₩{won(totalAssets)}</div>
                <div className="balance-sub-row">
                  <span>주문 가능 예수금</span>
                  <b>₩{won(currentUser.balance)}</b>
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
                        const currPrice = targetStock ? (quotes[code]?.price ?? targetStock.price) : item.avgPrice;
                        const profitRate = ((currPrice / item.avgPrice) - 1) * 100;
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
                              <small>{item.quantity}주</small>
                              <div style={{ fontSize: '10px', color: profitRate >= 0 ? '#ed707b' : '#62a4fc' }}>
                                {profitRate >= 0 ? '+' : ''}{profitRate.toFixed(2)}% (평단 ₩{won(item.avgPrice)})
                              </div>
                            </div>
                            <div style={{ display: 'flex', alignItems: 'center', textAlign: 'right' }}>
                              <b style={{ fontSize: '11.5px', color: '#e2edf8' }}>
                                ₩{won(currPrice * item.quantity)}
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

              {/* 계좌 액션 버튼 (주문 내역 / FDS 관제) */}
              <div className="user-action-row">
                <button
                  type="button"
                  className="user-history-btn"
                  onClick={() => setIsOrderHistoryOpen(true)}
                  title="주문 및 체결 내역 확인"
                >
                  <Clock3 size={13} />
                  <span>주문 내역 ({currentUser.orderHistory?.length || 0})</span>
                </button>
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
              {['인기 종목', '코스피', '코스닥', '관심 종목'].map((t) => (
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
                      <b>{s.name}</b>
                      <small>
                        <span className={`market-tag ${s.market === 'KOSDAQ' ? 'kosdaq' : ''}`}>
                          {s.market || 'KOSPI'}
                        </span>
                        {s.code}
                      </small>
                    </span>
                    <span className="stock-price">
                      <b>{won(qPrice)}</b>
                      <small className={qChange >= 0 ? 'rise' : 'fall'}>
                        {qChange >= 0 ? '+' : ''}{Number(qChange).toFixed(2)}%
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

        {/* 중앙 컨텐츠 영역: 시세, 캔들 차트, 호가창 */}
        <div className="content">
          <div className="trading-grid" id="market">
            <div className="chart-column">
              <section className="stock-overview panel">
                <div className="stock-overview-main">
                  {/* 종목 기본 정보 (코드, 로고, 종목명) */}
                  <div className="stock-overview-meta">
                    <div className="ticker-label">
                      {stock.market || 'KOSPI'} <span className="ticker-separator">/</span> {stock.code}
                    </div>
                    <div className="stock-header">
                      <span
                        className="company-logo"
                        style={{ background: (stock.color || '#6c85f7') + '25', color: stock.color || '#6c85f7' }}
                      >
                        {stock.name.slice(0, 1)}
                      </span>
                      <h2>{stock.name}</h2>
                      <span className="kospi-label">{stock.market || '국내주식'}</span>
                      <button
                        type="button"
                        className={`star-btn ${isCurrentStockStarred ? 'starred' : ''}`}
                        onClick={() => handleToggleWatchlist(stock.code)}
                        title={isCurrentStockStarred ? '관심 종목에서 제거' : '관심 종목으로 추가'}
                      >
                        <Star size={18} fill={isCurrentStockStarred ? '#f1cc62' : 'none'} />
                      </button>
                    </div>
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
                    <div className={`price-change ${stock.change >= 0 ? 'rise' : 'fall'}`}>
                      <span className="price-change-val">
                        {stock.change >= 0 ? <ArrowUpRight size={15} /> : <ArrowDownLeft size={15} />}
                        {' '}
                        {stock.change >= 0 ? '+' : ''}
                        {won((stock.price * stock.change) / (100 + stock.change))} ({stock.change >= 0 ? '+' : ''}{stock.change.toFixed(2)}%)
                      </span>
                      <span className="price-source-tag">
                        {stock.source === "KIS"
                          ? "전일 대비 · KIS 현재가"
                          : "전일 대비 · 가상 데이터"}
                      </span>
                    </div>
                  </div>
                </div>
                {/* 당일 시세 OHLC (시/고/저/종) - 종목 개요 우측(옆)에 배치 */}
                <div className="stock-overview-ohlc">
                  <div className="ohlc-meta-top">
                    <span style={{ fontSize: '11px', color: '#8aa1be', display: 'inline-flex', alignItems: 'center', gap: '4px', overflow: 'hidden', whiteSpace: 'nowrap' }}>
                      <Calendar size={11} style={{ flexShrink: 0 }} /> 기준: <strong style={{ color: '#d8e7fa', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap', maxWidth: '165px' }}>{activeCandle?.dateStr || '2026.11.05 (목)'}</strong>
                    </span>
                    <span style={{ fontSize: '10px', color: '#687e99', fontWeight: 600, flexShrink: 0 }}>{getIntervalBadgeLabel(chartInterval)}</span>
                  </div>
                  <div className="ohlc-grid">
                    <div className="ohlc-card">
                      <span className="ohlc-title">시가</span>
                      <strong className="ohlc-num">{won(activeCandle?.open || stock.price)}</strong>
                    </div>
                    <div className="ohlc-card">
                      <span className="ohlc-title">고가</span>
                      <strong className="ohlc-num rise">{won(activeCandle?.high || stock.price)}</strong>
                    </div>
                    <div className="ohlc-card">
                      <span className="ohlc-title">저가</span>
                      <strong className="ohlc-num fall">{won(activeCandle?.low || stock.price)}</strong>
                    </div>
                    <div className="ohlc-card">
                      <span className="ohlc-title">종가</span>
                      <strong className={`ohlc-num ${activePercent >= 0 ? 'rise' : 'fall'}`}>
                        {won(activeCandle?.close || stock.price)}
                      </strong>
                    </div>
                  </div>
                  <div className="stock-overview-meta-row">
                    <div><span>거래량</span> <b>{typeof stock.volume === 'number' ? won(stock.volume) : stock.volume}</b></div>
                    <div><span>거래소</span> <b>KRX</b></div>
                  </div>
                </div>
              </section>

              <CandleChart
                stock={stock}
                chartInterval={chartInterval}
                setChartInterval={setChartInterval}
                candles={candles}
                hover={chartHover}
                setHover={setChartHover}
              />
            </div>

            {/* 호가창 & 축소 화면 분할 주문창 컨테이너 (화면 축소 시: 좌측 호가창, 우측 주문창) */}
            <div className="orderbook-order-split" ref={splitOrderRef}>
              <OrderBook stock={stock} onSelectPrice={handleOpenOrder} />
              <div className="split-trade-panel">
                {currentUser ? (
                  <TradingOrderPanel
                    stock={stock}
                    currentUser={currentUser}
                    orderType={orderType}
                    setOrderType={setOrderType}
                    orderPrice={orderPrice}
                    setOrderPrice={setOrderPrice}
                    onOrderSuccess={(updatedUser, msg) => {
                      setCurrentUser(updatedUser);
                      alert(msg);
                    }}
                    isHighlighted={isOrderHighlighted}
                  />
                ) : (
                  <div className="panel auth-card split-login-prompt">
                    <div className="auth-mark" style={{ marginBottom: '12px' }}>
                      <span className="brand-icon">
                        <ShieldCheck size={22} />
                      </span>
                    </div>
                    <h3 style={{ fontSize: '15px', margin: '0 0 6px', fontWeight: 700 }}>모의투자 주문</h3>
                    <p style={{ fontSize: '12px', color: '#889bb0', margin: '0 0 14px' }}>
                      주문하려면 먼저 로그인해야 합니다.
                    </p>
                    <button
                      type="button"
                      className="primary-btn"
                      style={{ width: '100%', marginBottom: '8px' }}
                      onClick={handleDemoLogin}
                    >
                      <Sparkles size={14} /> 데모 계정으로 바로 시작
                    </button>
                    <button
                      type="button"
                      className="guest-demo-btn"
                      onClick={() => setIsSignUpOpen(true)}
                    >
                      회원가입
                    </button>
                  </div>
                )}
              </div>
            </div>
          </div>

          {/* 한국경제 공식 RSS 실시간 증권 뉴스 패널 (10분 자동 수집) */}
          <NewsPanel currentStock={stock} />
        </div>

        {/* 우측 사이드바: 데스크톱 주식 거래 주문창 또는 로그인 폼 */}
        <aside className={`auth-sidebar ${currentUser ? 'user-logged-in' : ''}`} ref={orderSidebarRef}>
          {currentUser ? (
            /* 로그인 완료 상태: 주식 거래 주문 패널 */
            <TradingOrderPanel
              stock={stock}
              currentUser={currentUser}
              orderType={orderType}
              setOrderType={setOrderType}
              orderPrice={orderPrice}
              setOrderPrice={setOrderPrice}
              onOrderSuccess={(updatedUser, msg) => {
                setCurrentUser(updatedUser);
                alert(msg);
              }}
              isHighlighted={isOrderHighlighted}
            />
          ) : (
            /* 비로그인 상태: 로그인 폼 */
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
                <label htmlFor="login-id">아이디</label>
                <div className="form-control">
                  <input
                    id="login-id"
                    name="id"
                    value={loginId}
                    onChange={(e) => setLoginId(e.target.value)}
                    autoComplete="username"
                    required
                    placeholder="아이디를 입력하세요"
                  />
                </div>

                <div className="label-row">
                  <label htmlFor="login-password">비밀번호</label>
                  <button
                    type="button"
                    onClick={() => alert('비밀번호를 분실하신 경우 데모 계정(demo / password123)을 이용하시거나 신규 회원가입을 진행해주세요.')}
                  >
                    비밀번호 찾기
                  </button>
                </div>

                <div className="form-control password">
                  <input
                    id="login-password"
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

              {/* 체험용 원클릭 데모 및 관리자 로그인 */}
              <div style={{ display: 'flex', flexDirection: 'column', gap: '8px', margin: '14px 0 6px 0' }}>
                <button
                  type="button"
                  className="demo-login-shortcut"
                  onClick={handleDemoLogin}
                >
                  <Sparkles size={14} /> 체험용 일반회원(demo) 1초 로그인
                </button>
                <button
                  type="button"
                  className="demo-login-shortcut"
                  style={{ background: '#222838', borderColor: '#3b5277', color: '#9bb8e5' }}
                  onClick={handleAdminLogin}
                >
                  <ShieldAlert size={14} /> FDS 보안관제팀 관리자(admin) 로그인
                </button>
              </div>

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
          )}

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
            FRONTEND PROTOTYPE <span>v0.3</span>
          </div>
        </aside>
      </main>
      )}

      {/* 토스트 알림 */}
      {toast && (
        <div className="toast">
          <Shield size={18} />
          <span>{toast}</span>
          <button onClick={() => setToast('')} aria-label="닫기">
            <X size={16} />
          </button>
        </div>
      )}

      {/* 주문 및 체결 내역 모달 */}
      <OrderHistoryModal
        isOpen={isOrderHistoryOpen}
        onClose={() => setIsOrderHistoryOpen(false)}
        currentUser={currentUser}
        onSelectStock={(code) => {
          const idx = stocks.findIndex((s) => s.code === code);
          if (idx !== -1) setStockIndex(idx);
        }}
      />

      {/* 회원가입 모달 */}
      <SignUpModal
        isOpen={isSignUpOpen}
        onClose={() => setIsSignUpOpen(false)}
        onRegisterSuccess={handleRegisterSuccess}
      />
    </div>
  );
}

createRoot(document.getElementById('root')).render(<App />);
