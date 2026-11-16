// 서비스 대상 30종목 (백엔드 V3__stock_master_seed.sql 기준)
// 코스피 시가총액 상위 20개 + 코스닥 시가총액 상위 10개
export const DEFAULT_STOCKS = [
  // --- KOSPI 20종목 ---
  { code: '005930', name: '삼성전자', market: 'KOSPI', sector: '반도체', price: 0, change: 0, volume: 0, color: '#6c85f7' },
  { code: '000660', name: 'SK하이닉스', market: 'KOSPI', sector: '반도체', price: 0, change: 0, volume: 0, color: '#e8896a' },
  { code: '402340', name: 'SK스퀘어', market: 'KOSPI', sector: '투자/지주', price: 0, change: 0, volume: 0, color: '#d46995' },
  { code: '373220', name: 'LG에너지솔루션', market: 'KOSPI', sector: '2차전지', price: 0, change: 0, volume: 0, color: '#eb6281' },
  { code: '207940', name: '삼성바이오로직스', market: 'KOSPI', sector: '바이오', price: 0, change: 0, volume: 0, color: '#52a5f5' },
  { code: '009150', name: '삼성전기', market: 'KOSPI', sector: 'IT부품', price: 0, change: 0, volume: 0, color: '#4982d6' },
  { code: '005380', name: '현대차', market: 'KOSPI', sector: '자동차', price: 0, change: 0, volume: 0, color: '#7b9ccf' },
  { code: '105560', name: 'KB금융', market: 'KOSPI', sector: '금융', price: 0, change: 0, volume: 0, color: '#f1b742' },
  { code: '032830', name: '삼성생명', market: 'KOSPI', sector: '보험/금융', price: 0, change: 0, volume: 0, color: '#3b78aa' },
  { code: '068270', name: '셀트리온', market: 'KOSPI', sector: '바이오', price: 0, change: 0, volume: 0, color: '#4db5b0' },
  { code: '028260', name: '삼성물산', market: 'KOSPI', sector: '복합기업', price: 0, change: 0, volume: 0, color: '#5f7ea6' },
  { code: '055550', name: '신한지주', market: 'KOSPI', sector: '금융', price: 0, change: 0, volume: 0, color: '#4986e7' },
  { code: '000270', name: '기아', market: 'KOSPI', sector: '자동차', price: 0, change: 0, volume: 0, color: '#e06b6b' },
  { code: '006400', name: '삼성SDI', market: 'KOSPI', sector: '2차전지', price: 0, change: 0, volume: 0, color: '#4484d8' },
  { code: '034730', name: 'SK', market: 'KOSPI', sector: '지주', price: 0, change: 0, volume: 0, color: '#e2803c' },
  { code: '012450', name: '한화에어로스페이스', market: 'KOSPI', sector: '방산/우주', price: 0, change: 0, volume: 0, color: '#ea8142' },
  { code: '086790', name: '하나금융지주', market: 'KOSPI', sector: '금융', price: 0, change: 0, volume: 0, color: '#3db08a' },
  { code: '012330', name: '현대모비스', market: 'KOSPI', sector: '자동차부품', price: 0, change: 0, volume: 0, color: '#5f7ea6' },
  { code: '034020', name: '두산에너빌리티', market: 'KOSPI', sector: '원전/에너지', price: 0, change: 0, volume: 0, color: '#389bd8' },
  { code: '035420', name: 'NAVER', market: 'KOSPI', sector: '인터넷', price: 0, change: 0, volume: 0, color: '#53bd91' },

  // --- KOSDAQ 10종목 ---
  { code: '196170', name: '알테오젠', market: 'KOSDAQ', sector: '바이오/플랫폼', price: 0, change: 0, volume: 0, color: '#956bf5' },
  { code: '086520', name: '에코프로', market: 'KOSDAQ', sector: '2차전지/지주', price: 0, change: 0, volume: 0, color: '#4dbd9d' },
  { code: '247540', name: '에코프로비엠', market: 'KOSDAQ', sector: '2차전지양극재', price: 0, change: 0, volume: 0, color: '#52b0cf' },
  { code: '036930', name: '주성엔지니어링', market: 'KOSDAQ', sector: '반도체장비', price: 0, change: 0, volume: 0, color: '#5584d4' },
  { code: '277810', name: '레인보우로보틱스', market: 'KOSDAQ', sector: '로봇/AI', price: 0, change: 0, volume: 0, color: '#4994f0' },
  { code: '039030', name: '이오테크닉스', market: 'KOSDAQ', sector: '반도체장비', price: 0, change: 0, volume: 0, color: '#658cb0' },
  { code: '000250', name: '삼천당제약', market: 'KOSDAQ', sector: '바이오', price: 0, change: 0, volume: 0, color: '#489fd1' },
  { code: '222800', name: '심텍', market: 'KOSDAQ', sector: '반도체기판', price: 0, change: 0, volume: 0, color: '#8b69db' },
  { code: '028300', name: 'HLB', market: 'KOSDAQ', sector: '바이오', price: 0, change: 0, volume: 0, color: '#5874c7' },
  { code: '058470', name: '리노공업', market: 'KOSDAQ', sector: '반도체검사', price: 0, change: 0, volume: 0, color: '#3db08a' },
];
