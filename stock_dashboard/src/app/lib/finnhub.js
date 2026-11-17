import 'server-only'

// Finnhub 외부 호출 완전 비활성화 (국내 시세 및 OpenDART 연동 전환용 더미 데이터 반환)

export async function searchStocks(query) {
  return []
}

export async function getStockQuote(symbol) {
  return {
    symbol,
    price: 0,
    priceChange: 0,
    change: 0,
    high: 0,
    low: 0,
    open: 0,
    previousClose: 0,
  }
}

export async function getCompanyProfile(symbol) {
  return {
    symbol,
    name: symbol,
    country: 'KR',
    currency: 'KRW',
    exchange: 'KRX',
    industry: '국내 주식',
    ipo: '',
    logo: '',
    marketCapitalization: 0,
    weburl: '',
  }
}

export async function getRecommendationTrends(symbol) {
  return []
}

export async function getEarnings(symbol) {
  return []
}

export async function getCompanyNews(symbol, from, to) {
  return []
}

export async function getStockCandles(symbol, resolution, from, to) {
  return { s: 'ok', c: [], h: [], l: [], o: [], v: [], t: [] }
}
