// 국내 주요 KOSPI & KOSDAQ 대표 종목 데이터베이스
export const DEFAULT_STOCKS = [
  // --- KOSPI 대형주 ---
  {
    code: '005930',
    name: '삼성전자',
    market: 'KOSPI',
    sector: '반도체',
    price: 57400,
    change: 1.23,
    volume: '15.4M',
    color: '#6c85f7'
  },
  {
    code: '000660',
    name: 'SK하이닉스',
    market: 'KOSPI',
    sector: '반도체',
    price: 184300,
    change: 2.46,
    volume: '3.8M',
    color: '#e8896a'
  },
  {
    code: '373220',
    name: 'LG에너지솔루션',
    market: 'KOSPI',
    sector: '2차전지',
    price: 340750,
    change: -4.28,
    volume: '256K',
    color: '#eb6281'
  },
  {
    code: '207940',
    name: '삼성바이오로직스',
    market: 'KOSPI',
    sector: '바이오',
    price: 1040000,
    change: 0.87,
    volume: '68K',
    color: '#52a5f5'
  },
  {
    code: '005380',
    name: '현대차',
    market: 'KOSPI',
    sector: '자동차',
    price: 241000,
    change: 0.67,
    volume: '924K',
    color: '#7b9ccf'
  },
  {
    code: '000270',
    name: '기아',
    market: 'KOSPI',
    sector: '자동차',
    price: 93200,
    change: 1.42,
    volume: '1.2M',
    color: '#e06b6b'
  },
  {
    code: '068270',
    name: '셀트리온',
    market: 'KOSPI',
    sector: '바이오',
    price: 183500,
    change: -0.81,
    volume: '480K',
    color: '#4db5b0'
  },
  {
    code: '105560',
    name: 'KB금융',
    market: 'KOSPI',
    sector: '금융',
    price: 89400,
    change: 2.17,
    volume: '1.5M',
    color: '#f1b742'
  },
  {
    code: '055550',
    name: '신한지주',
    market: 'KOSPI',
    sector: '금융',
    price: 52100,
    change: 1.16,
    volume: '980K',
    color: '#4986e7'
  },
  {
    code: '005490',
    name: 'POSCO홀딩스',
    market: 'KOSPI',
    sector: '철강/소재',
    price: 334500,
    change: -1.04,
    volume: '340K',
    color: '#658cb0'
  },
  {
    code: '035420',
    name: 'NAVER',
    market: 'KOSPI',
    sector: '인터넷',
    price: 213500,
    change: -0.93,
    volume: '1.1M',
    color: '#53bd91'
  },
  {
    code: '035720',
    name: '카카오',
    market: 'KOSPI',
    sector: '인터넷',
    price: 43850,
    change: -1.24,
    volume: '2.7M',
    color: '#f1cc62'
  },
  {
    code: '051910',
    name: 'LG화학',
    market: 'KOSPI',
    sector: '화학/배터리',
    price: 310500,
    change: -2.36,
    volume: '185K',
    color: '#db6079'
  },
  {
    code: '006400',
    name: '삼성SDI',
    market: 'KOSPI',
    sector: '2차전지',
    price: 312000,
    change: -1.73,
    volume: '220K',
    color: '#4484d8'
  },
  {
    code: '012330',
    name: '현대모비스',
    market: 'KOSPI',
    sector: '자동차부품',
    price: 247000,
    change: 1.65,
    volume: '310K',
    color: '#5f7ea6'
  },
  {
    code: '012450',
    name: '한화에어로스페이스',
    market: 'KOSPI',
    sector: '방산/우주',
    price: 368500,
    change: 3.80,
    volume: '540K',
    color: '#ea8142'
  },
  {
    code: '034020',
    name: '두산에너빌리티',
    market: 'KOSPI',
    sector: '원전/에너지',
    price: 21450,
    change: 2.14,
    volume: '7.8M',
    color: '#389bd8'
  },
  {
    code: '042660',
    name: '한화오션',
    market: 'KOSPI',
    sector: '조선/방산',
    price: 35600,
    change: 4.09,
    volume: '2.6M',
    color: '#3488aa'
  },
  {
    code: '003670',
    name: '포스코퓨처엠',
    market: 'KOSPI',
    sector: '2차전지소재',
    price: 218500,
    change: -3.32,
    volume: '290K',
    color: '#46a19f'
  },
  {
    code: '323410',
    name: '카카오뱅크',
    market: 'KOSPI',
    sector: '인터넷금융',
    price: 23150,
    change: 0.43,
    volume: '1.2M',
    color: '#f5c842'
  },
  {
    code: '259960',
    name: '크래프톤',
    market: 'KOSPI',
    sector: '게임',
    price: 326000,
    change: 1.88,
    volume: '145K',
    color: '#5873d6'
  },
  {
    code: '352820',
    name: '하이브',
    market: 'KOSPI',
    sector: '엔터',
    price: 198000,
    change: 2.59,
    volume: '360K',
    color: '#f05b7a'
  },
  {
    code: '003230',
    name: '삼양식품',
    market: 'KOSPI',
    sector: '음식료',
    price: 546000,
    change: 3.41,
    volume: '180K',
    color: '#e6653a'
  },
  {
    code: '011200',
    name: 'HMM',
    market: 'KOSPI',
    sector: '해운',
    price: 17820,
    change: -0.67,
    volume: '2.1M',
    color: '#3b7ea1'
  },
  {
    code: '036570',
    name: '엔씨소프트',
    market: 'KOSPI',
    sector: '게임/IT',
    price: 196800,
    change: -0.86,
    volume: '22K',
    color: '#658cf4'
  },
  {
    code: '066570',
    name: 'LG전자',
    market: 'KOSPI',
    sector: '가전/전장',
    price: 144300,
    change: -6.12,
    volume: '330K',
    color: '#cb516b'
  },
  {
    code: '009150',
    name: '삼성전기',
    market: 'KOSPI',
    sector: 'IT부품',
    price: 142100,
    change: 1.25,
    volume: '240K',
    color: '#4982d6'
  },
  {
    code: '000810',
    name: '삼성화재',
    market: 'KOSPI',
    sector: '보험/금융',
    price: 378000,
    change: 0.80,
    volume: '88K',
    color: '#3b78aa'
  },
  {
    code: '010140',
    name: '삼성중공업',
    market: 'KOSPI',
    sector: '조선/해양',
    price: 10450,
    change: 2.15,
    volume: '4.8M',
    color: '#348eab'
  },
  {
    code: '004020',
    name: '현대제철',
    market: 'KOSPI',
    sector: '철강',
    price: 28450,
    change: -0.70,
    volume: '410K',
    color: '#5b7692'
  },
  {
    code: '267260',
    name: 'HD현대일렉트릭',
    market: 'KOSPI',
    sector: '전력기기',
    price: 341000,
    change: 4.60,
    volume: '190K',
    color: '#e2803c'
  },
  {
    code: '009540',
    name: 'HD한국조선해양',
    market: 'KOSPI',
    sector: '조선지주',
    price: 189500,
    change: 3.10,
    volume: '220K',
    color: '#399696'
  },
  {
    code: '010130',
    name: '고려아연',
    market: 'KOSPI',
    sector: '비철금속',
    price: 1024000,
    change: 5.24,
    volume: '95K',
    color: '#cca044'
  },

  // --- KOSDAQ 대표주 ---
  {
    code: '196170',
    name: '알테오젠',
    market: 'KOSDAQ',
    sector: '바이오/플랫폼',
    price: 375500,
    change: 3.16,
    volume: '780K',
    color: '#956bf5'
  },
  {
    code: '247540',
    name: '에코프로비엠',
    market: 'KOSDAQ',
    sector: '2차전지양극재',
    price: 87600,
    change: -4.89,
    volume: '276K',
    color: '#52b0cf'
  },
  {
    code: '086520',
    name: '에코프로',
    market: 'KOSDAQ',
    sector: '2차전지/지주',
    price: 78900,
    change: -3.78,
    volume: '840K',
    color: '#4dbd9d'
  },
  {
    code: '028300',
    name: 'HLB',
    market: 'KOSDAQ',
    sector: '바이오',
    price: 66300,
    change: 1.07,
    volume: '620K',
    color: '#5874c7'
  },
  {
    code: '348370',
    name: '엔켐',
    market: 'KOSDAQ',
    sector: '전해액',
    price: 168200,
    change: -2.21,
    volume: '310K',
    color: '#e07455'
  },
  {
    code: '277810',
    name: '레인보우로보틱스',
    market: 'KOSDAQ',
    sector: '로봇/AI',
    price: 139800,
    change: 4.17,
    volume: '490K',
    color: '#4994f0'
  },
  {
    code: '058470',
    name: '리노공업',
    market: 'KOSDAQ',
    sector: '반도체검사',
    price: 198400,
    change: 0.97,
    volume: '150K',
    color: '#3db08a'
  },
  {
    code: '214150',
    name: '클래시스',
    market: 'KOSDAQ',
    sector: '의료기기',
    price: 53200,
    change: 1.92,
    volume: '420K',
    color: '#d46995'
  },
  {
    code: '145020',
    name: '휴젤',
    market: 'KOSDAQ',
    sector: '바이오/보톡스',
    price: 278500,
    change: 2.39,
    volume: '110K',
    color: '#8b69db'
  },
  {
    code: '000250',
    name: '삼천당제약',
    market: 'KOSDAQ',
    sector: '바이오',
    price: 136200,
    change: -1.16,
    volume: '340K',
    color: '#489fd1'
  },
  {
    code: '263750',
    name: '펄어비스',
    market: 'KOSDAQ',
    sector: '게임',
    price: 36800,
    change: -0.54,
    volume: '230K',
    color: '#6c77d6'
  },
  {
    code: '035900',
    name: 'JYP Ent.',
    market: 'KOSDAQ',
    sector: '엔터',
    price: 57900,
    change: 2.12,
    volume: '410K',
    color: '#34b293'
  },
  {
    code: '403870',
    name: 'HPSP',
    market: 'KOSDAQ',
    sector: '반도체장비',
    price: 34150,
    change: 1.64,
    volume: '620K',
    color: '#5584d4'
  }
];
