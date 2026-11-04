import express from "express";
import cors from "cors";
import dotenv from "dotenv";
import { registerRoutes } from "./routes.mjs";

dotenv.config();

const app = express();
const PORT = 4000;
const BASE_URL = process.env.KIS_BASE_URL;

if (
  !process.env.KIS_APP_KEY ||
  !process.env.KIS_APP_SECRET ||
  !BASE_URL
) {
  throw new Error("KIS 환경변수를 확인하세요.");
}

app.use(cors({ origin: "http://localhost:5173" }));
app.use(express.json());

// 접근토큰 캐싱
let token = null;
let expiresAt = 0;

async function getToken() {
  if (token && Date.now() < expiresAt) {
    return token;
  }

  const response = await fetch(
    `${BASE_URL}/oauth2/tokenP`,
    {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
      },
      body: JSON.stringify({
        grant_type: "client_credentials",
        appkey: process.env.KIS_APP_KEY,
        appsecret: process.env.KIS_APP_SECRET,
      }),
    }
  );

  const data = await response.json();

  if (!response.ok || !data.access_token) {
    throw new Error(
      `토큰 발급 실패: ${data.msg1 || response.status}`
    );
  }

  token = data.access_token;

  const lifetime = Number(data.expires_in) || 86400;
  expiresAt =
    Date.now() + Math.max(60, lifetime - 300) * 1000;

  return token;
}

// 현재가 응답을 20초 동안 캐싱
const priceCache = new Map();

// 종목 기본정보(종목명, 시장) 사전 및 실시간 메타 조회 헬퍼
const stockMetaMap = new Map([
  ["036570", { name: "엔씨소프트", market: "KOSPI" }],
  ["005930", { name: "삼성전자", market: "KOSPI" }],
  ["000660", { name: "SK하이닉스", market: "KOSPI" }],
  ["373220", { name: "LG에너지솔루션", market: "KOSPI" }],
  ["207940", { name: "삼성바이오로직스", market: "KOSPI" }],
  ["005380", { name: "현대차", market: "KOSPI" }],
  ["000270", { name: "기아", market: "KOSPI" }],
  ["068270", { name: "셀트리온", market: "KOSPI" }],
  ["105560", { name: "KB금융", market: "KOSPI" }],
  ["055550", { name: "신한지주", market: "KOSPI" }],
  ["005490", { name: "POSCO홀딩스", market: "KOSPI" }],
  ["035420", { name: "NAVER", market: "KOSPI" }],
  ["035720", { name: "카카오", market: "KOSPI" }],
  ["051910", { name: "LG화학", market: "KOSPI" }],
  ["006400", { name: "삼성SDI", market: "KOSPI" }],
  ["012330", { name: "현대모비스", market: "KOSPI" }],
  ["066570", { name: "LG전자", market: "KOSPI" }],
  ["009150", { name: "삼성전기", market: "KOSPI" }],
  ["018260", { name: "삼성에스디에스", market: "KOSPI" }],
  ["086790", { name: "하나금융지주", market: "KOSPI" }],
  ["004020", { name: "현대제철", market: "KOSPI" }],
  ["010140", { name: "삼성중공업", market: "KOSPI" }],
  ["000810", { name: "삼성화재", market: "KOSPI" }],
  ["267260", { name: "HD현대일렉트릭", market: "KOSPI" }],
  ["009540", { name: "HD한국조선해양", market: "KOSPI" }],
  ["196170", { name: "알테오젠", market: "KOSDAQ" }],
  ["247540", { name: "에코프로비엠", market: "KOSDAQ" }],
  ["086520", { name: "에코프로", market: "KOSDAQ" }],
  ["028300", { name: "HLB", market: "KOSDAQ" }],
  ["348370", { name: "엔켐", market: "KOSDAQ" }],
  ["277810", { name: "레인보우로보틱스", market: "KOSDAQ" }],
  ["003230", { name: "삼양식품", market: "KOSPI" }],
  ["259960", { name: "크래프톤", market: "KOSPI" }],
  ["352820", { name: "하이브", market: "KOSPI" }],
  ["011200", { name: "HMM", market: "KOSPI" }],
  ["012450", { name: "한화에어로스페이스", market: "KOSPI" }],
  ["034020", { name: "두산에너빌리티", market: "KOSPI" }],
  ["042660", { name: "한화오션", market: "KOSPI" }],
]);

async function resolveStockMeta(code) {
  if (stockMetaMap.has(code)) {
    return stockMetaMap.get(code);
  }

  try {
    const res = await fetch(`https://m.stock.naver.com/api/stock/${code}/basic`, {
      headers: { "User-Agent": "Mozilla/5.0" },
    });
    if (res.ok) {
      const data = await res.json();
      let name = data.stockName || `종목(${code})`;
      if (code === "036570" || name === "NC") {
        name = "엔씨소프트";
      }
      const market = data.stockExchangeName || "KRX";
      const meta = { name, market };
      stockMetaMap.set(code, meta);
      return meta;
    }
  } catch (err) {
    console.warn(`종목명 자동 조회 실패 (${code}):`, err.message);
  }

  return { name: `종목(${code})`, market: "KRX" };
}

app.get("/api/stocks/:code/price", async (req, res) => {
  const { code } = req.params;

  if (!/^\d{6}$/.test(code)) {
    return res.status(400).json({
      error: "종목코드는 6자리 숫자여야 합니다.",
    });
  }

  try {
    const cached = priceCache.get(code);

    if (cached && Date.now() - cached.time < 500) {
      return res.json(cached.data);
    }

    const [accessToken, meta] = await Promise.all([
      getToken(),
      resolveStockMeta(code),
    ]);

    const url = new URL(
      `${BASE_URL}/uapi/domestic-stock/v1/quotations/inquire-price`
    );

    url.searchParams.set("FID_COND_MRKT_DIV_CODE", "J");
    url.searchParams.set("FID_INPUT_ISCD", code);

    const response = await fetch(url, {
      headers: {
        "Content-Type": "application/json",
        authorization: `Bearer ${accessToken}`,
        appkey: process.env.KIS_APP_KEY,
        appsecret: process.env.KIS_APP_SECRET,
        tr_id: "FHKST01010100",
        custtype: "P",
      },
    });

    const result = await response.json();

    if (!response.ok || result.rt_cd !== "0") {
      throw new Error(
        `KIS 조회 실패: ${result.msg1 || response.status}`
      );
    }

    const output = result.output;

    if (!output?.stck_prpr) {
      throw new Error("현재가 데이터가 없습니다.");
    }

    const data = {
      code,
      name: meta.name,
      market: meta.market,
      sector: output.bstp_kor_isnm || "국내주식",
      price: Number(output.stck_prpr),
      change: Number(output.prdy_ctrt),
      volume: Number(output.acml_vol),
      source: "KIS",
    };

    priceCache.set(code, {
      data,
      time: Date.now(),
    });

    res.json(data);
  } catch (error) {
    console.error(`[KIS Proxy] ${code} 조회:`, error.message);

    const cached = priceCache.get(code);
    if (cached?.data) {
      return res.json(cached.data);
    }

    res.status(502).json({
      error: "주식 시세를 가져오지 못했습니다.",
    });
  }
});

// ==========================================================================
// 한국경제 공식 RSS 10분 자동 수집 스케줄러 및 뉴스 API
// ==========================================================================
const newsStore = new Map();
let lastNewsCollectedAt = null;

function parseHankyungRss(xml) {
  const items = [];
  const itemRegex = /<item>([\s\S]*?)<\/item>/g;
  let match;
  while ((match = itemRegex.exec(xml)) !== null) {
    const content = match[1];
    const titleMatch = content.match(/<title>(?:<!\[CDATA\[)?([\s\S]*?)(?:\]\]>)?<\/title>/);
    const linkMatch = content.match(/<link>(?:<!\[CDATA\[)?([\s\S]*?)(?:\]\]>)?<\/link>/);
    const authorMatch = content.match(/<author>(?:<!\[CDATA\[)?([\s\S]*?)(?:\]\]>)?<\/author>/);
    const pubDateMatch = content.match(/<pubDate>([\s\S]*?)<\/pubDate>/);

    const title = titleMatch ? titleMatch[1].trim() : "";
    const url = linkMatch ? linkMatch[1].trim() : "";
    const author = authorMatch ? authorMatch[1].trim() : "";
    const publishedAt = pubDateMatch ? pubDateMatch[1].trim() : "";

    if (title && url) {
      items.push({
        id: url,
        title,
        url,
        author: author || "한국경제",
        publishedAt,
        source: "한국경제 증권",
      });
    }
  }
  return items;
}

async function collectHankyungNews() {
  try {
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), 12000);

    const response = await fetch("https://www.hankyung.com/feed/finance", {
      signal: controller.signal,
      headers: {
        "User-Agent": "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) StockShield/1.0",
      },
    });
    clearTimeout(timeout);

    if (!response.ok) {
      throw new Error(`RSS 응답 오류: ${response.status}`);
    }

    const xml = await response.text();
    const items = parseHankyungRss(xml);

    let newCount = 0;
    for (const item of items) {
      if (!newsStore.has(item.id)) {
        newsStore.set(item.id, item);
        newCount++;
      }
    }

    lastNewsCollectedAt = new Date().toISOString();
    console.log(
      `[한국경제 RSS] 뉴스 수집 완료: +${newCount}건 신규 등록 (총 ${newsStore.size}건 보관 중 · ${new Date().toLocaleTimeString("ko-KR")})`
    );
  } catch (err) {
    console.error("[한국경제 RSS] 뉴스 수집 실패:", err.message);
  }
}

// 10분마다 자동 수집 (10분 = 600,000ms)
const NEWS_INTERVAL_MS = 10 * 60 * 1000;
setInterval(collectHankyungNews, NEWS_INTERVAL_MS);

// 서버 기동 시 최초 즉시 1회 수집
collectHankyungNews();

// 뉴스 조회 REST API
app.get("/api/news", (req, res) => {
  const { stock, query, limit = 40 } = req.query;
  const allNews = Array.from(newsStore.values());

  // 최신 발행일 기준 내림차순 정렬
  allNews.sort((a, b) => new Date(b.publishedAt) - new Date(a.publishedAt));

  const searchTerm = (stock || query || "").trim().toLowerCase();
  let filtered = allNews;
  if (searchTerm) {
    filtered = allNews.filter((item) =>
      item.title.toLowerCase().includes(searchTerm)
    );
  }

  res.json({
    success: true,
    totalCount: allNews.length,
    filteredCount: filtered.length,
    lastUpdated: lastNewsCollectedAt,
    news: filtered.slice(0, Number(limit) || 40),
  });
});

// ==========================================================================
// PostgreSQL DB 연동 REST API 라우트 등록
// ==========================================================================
registerRoutes(app);

app.listen(PORT, "127.0.0.1", () => {
  console.log(`KIS, 한국경제 뉴스 및 PostgreSQL API 서버: http://localhost:${PORT}`);
});
