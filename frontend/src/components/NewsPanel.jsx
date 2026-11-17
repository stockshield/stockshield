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

export default function NewsPanel({ currentStock }) {
  const [stockNews, setStockNews] = useState([]);
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
      const adapted = res.items.map((item, idx) => ({
        id: item.newsId || item.id || `${tckCd}-${idx}`,
        title: item.newsTitl || item.title || '',
        summary: item.aiSmryCont || item.summary || '',
        sentiment: item.aiSntmScr,
        publishedAt: item.pubTs || item.publishedAt || '',
        url: item.newsUrl || item.url || '#',
        author: item.author || currentStock?.name || '뉴스',
      }));
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
    const timer = setInterval(() => loadStockNews(), 60000);
    return () => {
      controller.abort();
      clearInterval(timer);
    };
  }, [currentStock?.code]);

  // 검색어 필터링 결과
  const displayedNews = useMemo(() => {
    if (!searchQuery.trim()) return stockNews;
    const q = searchQuery.trim().toLowerCase();
    return stockNews.filter(
      (item) =>
        item.title.toLowerCase().includes(q) ||
        (item.summary && item.summary.toLowerCase().includes(q))
    );
  }, [stockNews, searchQuery]);

  // 큰 화면: 한 줄에 3개씩 총 6개 (2줄)
  // 좁은 화면: 한 줄에 2개씩 총 4개 (2줄)
  const initialCount = isNarrowScreen ? 4 : 6;
  const itemsToShow = isExpanded ? displayedNews : displayedNews.slice(0, initialCount);
  const remainingCount = Math.max(0, displayedNews.length - initialCount);

  return (
    <section className="news-panel panel" aria-label="실시간 증권 뉴스">
      {/* 헤더 영역 */}
      <div className="news-panel-head">
        <div className="news-title-group">
          <div className="news-icon-badge">
            <Newspaper size={18} />
          </div>
          <div>
            <div className="news-title-row">
              <h3>실시간 증권 뉴스</h3>
            </div>
            <p className="news-subtitle">
              Finlight API를 통해 수집되는 실시간 주요 증시·종목 속보입니다.
            </p>
          </div>
        </div>

        {/* 자동 수집 상태 뱃지 */}
        <div className="news-head-actions">
          <span className="news-live-tag">
            <span className="news-live-dot" /> {enabled ? '실시간 뉴스 연동 중' : '뉴스 API 미설정'}
          </span>
          {lastUpdated && (
            <span className="news-last-time">
              <Clock size={12} /> {formatTimeAgo(lastUpdated)} 갱신
            </span>
          )}
          <button
            type="button"
            className={`news-refresh-btn ${loading ? 'loading' : ''}`}
            onClick={() => loadStockNews()}
            title="뉴스 즉시 새로고침"
            aria-label="뉴스 즉시 새로고침"
          >
            <RefreshCw size={14} />
          </button>
        </div>
      </div>

      {/* 필터 탭 & 검색창 */}
      <div className="news-filter-bar">
        <div className="news-tabs">
          <button
            type="button"
            className="news-tab active"
          >
            <Sparkles size={12} />
            {currentStock?.name || '종목'} 최신 뉴스 <span className="tab-count">{stockNews.length}</span>
          </button>
        </div>

        <div className="news-search-wrap">
          <Search size={14} />
          <input
            type="text"
            placeholder="기사 제목, 키워드 검색"
            value={searchQuery}
            onChange={(e) => {
              setSearchQuery(e.target.value);
              setIsExpanded(false);
            }}
            aria-label="기사 검색"
          />
          {searchQuery && (
            <button
              type="button"
              className="news-search-clear"
              onClick={() => setSearchQuery('')}
              aria-label="검색어 지우기"
            >
              ✕
            </button>
          )}
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
                    <span className="news-source-tag">{currentStock?.name || '종목'} 뉴스</span>
                    {item.sentiment != null && (
                      <span className="news-related-badge">
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
                      {item.author || 'Finlight'}
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
            <p>Finlight API를 통해 10분마다 새로운 종목 뉴스가 자동 수집 및 AI 요약됩니다.</p>
          </div>
        )}
      </div>

      {/* 하단 출처 및 RSS 안내 */}
      <div className="news-panel-footer">
        <span>
          출처: <b>한국경제(hankyung.com) 공식 금융 RSS</b> · 기사 저작권은 한국경제신문사에 있으며 클릭 시 원문으로 연결됩니다.
        </span>
      </div>
    </section>
  );
}
