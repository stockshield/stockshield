import React, { useState, useEffect, useMemo } from 'react';
import { 
  Newspaper, 
  ExternalLink, 
  RefreshCw, 
  Rss, 
  Clock, 
  Search, 
  Sparkles, 
  ChevronDown, 
  ChevronUp 
} from 'lucide-react';

import { API_BASE, fetchStockNews } from '../utils/storage';
function formatTimeAgo(dateStr) {
  if (!dateStr) return '최신';
  try {
    const pub = new Date(dateStr);
    if (isNaN(pub.getTime())) return dateStr;
    const now = new Date();
    const diffSec = Math.floor((now - pub) / 1000);
    if (diffSec < 60) return '방금 전';
    if (diffSec < 3600) return `${Math.floor(diffSec / 60)}분 전`;
    if (diffSec < 86400) return `${Math.floor(diffSec / 3600)}시간 전`;
    return pub.toLocaleDateString('ko-KR', { month: 'short', day: 'numeric' });
  } catch {
    return dateStr;
  }
}

// 종목명 단축 키워드 (예: 삼성전자 -> ["삼성", "삼전"], SK하이닉스 -> ["SK하이닉스", "하이닉스", "닉스"])
function getStockKeywords(stock) {
  if (!stock?.name) return [];
  const name = stock.name.trim();
  const keywords = [name];
  if (name.includes('삼성')) keywords.push('삼성', '삼전');
  if (name.includes('하이닉스')) keywords.push('하이닉스', 'SK하이닉스', '닉스');
  if (name.includes('현대차') || name.includes('현대자동차')) keywords.push('현대차');
  if (name.includes('카카오')) keywords.push('카카오');
  if (name.includes('네이버') || name.toUpperCase().includes('NAVER')) keywords.push('네이버', 'NAVER');
  if (name.includes('에코프로')) keywords.push('에코프로');
  if (name.includes('엔씨소프트')) keywords.push('엔씨', 'NC');
  return Array.from(new Set(keywords));
}

const MARKET_NEWS_STOCKS = [
  { code: '005930', name: '삼성전자' },
  { code: '000660', name: 'SK하이닉스' },
  { code: '035420', name: 'NAVER' },
  { code: '005380', name: '현대차' },
  { code: '373220', name: 'LG에너지솔루션' },
  { code: '068270', name: '셀트리온' },
  { code: '000270', name: '기아' },
  { code: '207940', name: '삼성바이오로직스' },
];

const NEWS_DOMAIN_MAP = [
  ['hankyung.com', '한국경제'],
  ['mk.co.kr', '매일경제'],
  ['sedaily.com', '서울경제'],
  ['biz.chosun.com', '조선비즈'],
  ['newspim.com', '뉴스핌'],
  ['yna.co.kr', '연합뉴스'],
  ['einfomax.co.kr', '연합인포맥스'],
];

function getMediaNameFromUrl(url) {
  if (!url || url === '#') return '';
  try {
    const host = new URL(url).hostname.toLowerCase();
    const match = NEWS_DOMAIN_MAP.find(([domain]) => host === domain || host.endsWith(`.${domain}`));
    return match ? match[1] : host.replace(/^www\./, '');
  } catch {
    return '';
  }
}

function adaptNewsItem(item, fallbackId, fallbackAuthor) {
  const url = item.newsUrl || item.url || '#';
  return {
    id: item.newsId || item.id || fallbackId,
    title: item.newsTitl || item.title || '',
    summary: item.aiSmryCont || item.summary || '',
    sentiment: item.aiSntmScr,
    publishedAt: item.pubTs || item.publishedAt || '',
    url,
    mediaName: getMediaNameFromUrl(url),
    author: item.author || fallbackAuthor || '증권 뉴스',
  };
}

export default function NewsPanel({ currentStock }) {
  const [stockNews, setStockNews] = useState([]);
  const [allNews, setAllNews] = useState([]);
  const [activeTab, setActiveTab] = useState('STOCK'); // 'STOCK' | 'ALL'
  const [enabled, setEnabled] = useState(true);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [lastUpdated, setLastUpdated] = useState('');
  const [searchQuery, setSearchQuery] = useState('');
  const [isExpanded, setIsExpanded] = useState(false);
  const [isNarrowScreen, setIsNarrowScreen] = useState(() => typeof window !== 'undefined' && window.innerWidth <= 1024);

  useEffect(() => {
    const handleResize = () => setIsNarrowScreen(window.innerWidth <= 1024);
    window.addEventListener('resize', handleResize);
    return () => window.removeEventListener('resize', handleResize);
  }, []);

  const loadAllNews = async (signal) => {
    try {
      const res = await fetch(`${API_BASE}/news`, { signal });
      if (res.ok) {
        const data = await res.json();
        const rawList = Array.isArray(data)
          ? data
          : Array.isArray(data?.content)
          ? data.content
          : Array.isArray(data?.news)
          ? data.news
          : [];
        const adapted = rawList.map((item, idx) => adaptNewsItem(item, `all-${idx}`, '전체 증시'));
        setAllNews(adapted);
        return;
      }
    } catch (err) {
      if (err.name === 'AbortError') return;
    }

    // /api/v1/news 미구현 시 주요 대표 종목 뉴스를 병합해 전체 증권 뉴스 구성
    try {
      const stockMap = new Map();
      if (currentStock?.code) {
        stockMap.set(currentStock.code, currentStock.name || currentStock.code);
      }
      MARKET_NEWS_STOCKS.forEach((s) => {
        if (!stockMap.has(s.code)) stockMap.set(s.code, s.name);
      });
      const entries = Array.from(stockMap.entries());
      const results = await Promise.all(entries.map(([code]) => fetchStockNews(code, signal)));
      const seen = new Set();
      const merged = [];
      results.forEach((r, idx) => {
        if (!r.success) return;
        const stockLabel = entries[idx][1];
        r.items.forEach((item, itemIdx) => {
          const adapted = adaptNewsItem(item, `mkt-${entries[idx][0]}-${itemIdx}`, stockLabel);
          const key = adapted.id || adapted.url || adapted.title;
          if (adapted.title && !seen.has(key)) {
            seen.add(key);
            merged.push(adapted);
          }
        });
      });
      merged.sort((a, b) => new Date(b.publishedAt || 0) - new Date(a.publishedAt || 0));
      setAllNews(merged);
    } catch {
      // ignore
    }
  };

  const loadStockNews = async (signal) => {
    const tckCd = currentStock?.code;
    if (!tckCd) {
      setStockNews([]);
      return;
    }
    setLoading(true);
    try {
      const res = await fetchStockNews(tckCd, signal);
      if (!res.success) {
        throw new Error(res.message || `HTTP ${res.status}`);
      }
      const adapted = res.items.map((item, idx) =>
        adaptNewsItem(item, `${tckCd}-${idx}`, currentStock?.name || '뉴스')
      );
      setStockNews(adapted);
      setEnabled(true);
      setLastUpdated(adapted[0]?.publishedAt || new Date().toISOString());
      setError('');
    } catch (err) {
      if (err.name !== 'AbortError') {
        setError('종목 뉴스를 불러오지 못했습니다.');
      }
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    const controller = new AbortController();
    loadStockNews(controller.signal);
    loadAllNews(controller.signal);
    const timer = setInterval(() => {
      loadStockNews();
      loadAllNews();
    }, 60000);
    return () => {
      controller.abort();
      clearInterval(timer);
    };
  }, [currentStock?.code]);

  // 탭 및 검색어 필터링 결과
  const displayedNews = useMemo(() => {
    const baseList = activeTab === 'ALL' ? allNews : stockNews;
    if (!searchQuery.trim()) return baseList;
    const q = searchQuery.trim().toLowerCase();
    return baseList.filter(
      (item) =>
        item.title.toLowerCase().includes(q) ||
        (item.summary && item.summary.toLowerCase().includes(q))
    );
  }, [activeTab, stockNews, allNews, searchQuery]);

  // 큰 화면: 한 줄에 3개씩 총 6개 (2줄)
  // 좁은 화면: 한 줄에 2개씩 총 4개 (2줄)
  const initialCount = isNarrowScreen ? 4 : 6;
  const itemsToShow = isExpanded ? displayedNews : displayedNews.slice(0, initialCount);
  const remainingCount = Math.max(0, displayedNews.length - initialCount);

  return (
    <section className="news-panel panel" aria-label="증권 뉴스">
      {/* 헤더 영역 */}
      <div className="news-panel-head">
        <div className="news-title-group">
          <div className="news-icon-badge">
            <Newspaper size={18} />
          </div>
          <div>
            <div className="news-title-row">
              <h3>증권 뉴스</h3>
            </div>
            <p className="news-subtitle">
              Finlight API로 국내 경제 매체 기사를 10분마다 확인합니다.
            </p>
          </div>
        </div>

        {/* 자동 수집 상태 뱃지 */}
        <div className="news-head-actions">
          <span className="news-live-tag">
            <span className="news-live-dot" /> {enabled ? '뉴스 API 연동 중' : '뉴스 API 미설정'}
          </span>
          <button
            type="button"
            className={`news-refresh-btn ${loading ? 'loading' : ''}`}
            onClick={() => {
              loadStockNews();
              loadAllNews();
            }}
            title="뉴스 즉시 새로고침"
            aria-label="뉴스 즉시 새로고침"
          >
            <RefreshCw size={14} />
          </button>
        </div>
      </div>

      {/* 기사 목록 (초기 2줄 노출) */}
      <div className="news-list-container">
        {itemsToShow.length > 0 ? (
          <>
            <div className="news-grid">
              {itemsToShow.map((item) => (
                <a
                  key={item.id || item.url}
                  href={item.url}
                  target="_blank"
                  rel="noopener noreferrer"
                  className="news-card-item highlight-stock"
                  title={`${item.title} (새 탭에서 원문 보기)`}
                >
                  <div className="news-item-top">
                    <span className="news-source-tag">
                      {activeTab === 'ALL' ? (item.author || '전체 증시') : (currentStock?.name || '종목')} 뉴스
                    </span>
                    {item.sentiment != null && (
                      <span
                        className="news-related-badge"
                        title="AI가 기사 논조를 -1.0(부정)~+1.0(긍정)으로 매긴 값, 매매 권유 아님"
                      >
                        감성 {Number(item.sentiment).toFixed(2)}
                      </span>
                    )}
                    <span className="news-item-time">{formatTimeAgo(item.publishedAt)}</span>
                  </div>

                  <strong className="news-item-title">
                    {item.title}
                  </strong>
                  {item.summary && (
                    <p style={{ fontSize: '11.5px', color: '#8fa3bc', margin: '4px 0 6px', lineHeight: 1.4, display: '-webkit-box', WebkitLineClamp: 2, WebkitBoxOrient: 'vertical', overflow: 'hidden' }}>
                      {item.summary}
                    </p>
                  )}

                  <div className="news-item-foot">
                    <span className="news-author">
                      {item.mediaName || item.author || '국내 경제 매체'}
                    </span>
                    <span className="news-link-btn">
                      원문 기사 <ExternalLink size={12} />
                    </span>
                  </div>
                </a>
              ))}
            </div>

            {/* 아래로 더보기 / 접기 버튼 */}
            {displayedNews.length > initialCount && (
              <div className="news-more-wrap">
                <button
                  type="button"
                  className="news-more-btn"
                  onClick={() => setIsExpanded(!isExpanded)}
                >
                  {isExpanded ? (
                    <>
                      기사 목록 접기 <ChevronUp size={15} />
                    </>
                  ) : (
                    <>
                      더보기 ({remainingCount}건 더보기) <ChevronDown size={15} />
                    </>
                  )}
                </button>
              </div>
            )}
          </>
        ) : (
          <div className="news-empty-state">
            <Rss size={28} />
            <h4>
              {searchQuery
                ? `'${searchQuery}' 검색 결과가 없습니다.`
                : `'${currentStock?.name || '선택 종목'}' 관련 최근 뉴스가 없습니다.`}
            </h4>
            <p>Finlight API로 국내 경제 매체 기사를 10분마다 확인하며, 수집된 기사는 AI로 요약됩니다.</p>
          </div>
        )}
      </div>

      {/* 하단 투자 참고용·교육 목적 고지 및 Finlight 출처 안내 */}
      <div className="news-panel-footer" style={{ display: 'flex', flexDirection: 'column', gap: '4px' }}>
        <span>
          ※ <b>AI 감성 점수</b>: AI가 기사 논조를 <b>-1.0(부정) ~ +1.0(긍정)</b>으로 산출한 지표이며, 매매 권유가 아닙니다.
        </span>
        <span>
          출처: <b>Finlight API</b> 수집 국내 매체(한국경제·매일경제·서울경제·조선비즈·뉴스핌·연합뉴스 등) 한국어 기사 및 AI 요약 · 본 뉴스 정보는 <b>투자 참고용·교육 목적</b>으로만 제공됩니다.
        </span>
      </div>
    </section>
  );
}
