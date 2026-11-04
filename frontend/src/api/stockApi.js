// 우리 Spring 서버(stock-api)만 호출한다. 증권사 API 키는 서버에만 있고 브라우저에는 없다.
export const API_BASE_URL = process.env.NEXT_PUBLIC_API_BASE_URL || 'http://localhost:8085'
export const WS_URL = process.env.NEXT_PUBLIC_WS_URL || 'ws://localhost:8085/ws/quotes'

// fetch 에러 처리와 JSON 파싱을 도와주는 함수 (실습 boardApi.js 방식)
// 로그인 세션 쿠키(JSESSIONID)를 함께 보내도록 credentials: 'include'
export const fetchWrapper = async (path, options = {}) => {
  let response
  try {
    response = await fetch(`${API_BASE_URL}${path}`, {
      ...options,
      credentials: 'include',
      headers: { 'Content-Type': 'application/json', ...options.headers },
    })
  } catch (error) {
    if (error.name === 'AbortError') throw error
    throw new Error('서버에 연결할 수 없습니다. stock-api 서버가 실행 중인지 확인하세요.')
  }

  const data = await response.json().catch(() => ({}))
  if (!response.ok) {
    const error = new Error(data.error || `요청에 실패했습니다. (${response.status})`)
    error.status = response.status
    throw error
  }
  return data
}

// 종목·시세·차트 통신을 한 곳에서 관리
export const stockApi = {
  // 종목 검색 (빈 값이면 지원 종목 전체)
  search: (query = '', options) =>
    fetchWrapper(`/api/stocks?query=${encodeURIComponent(query)}`, options).then((d) => d.list ?? []),

  // 최신 시세 1종목
  getQuote: (symbol, options) =>
    fetchWrapper(`/api/stocks/${symbol}/quote`, options).then((d) => d.quote),

  // 최신 시세 여러 종목 (Polling용)
  getQuotes: (symbols, options) =>
    fetchWrapper(`/api/quotes?symbols=${symbols.join(',')}`, options).then((d) => d.list ?? []),

  // 선 차트용 이력 (range: 1D 분봉 | 1W·1M·1Y 일봉)
  getHistory: (symbol, range, options) =>
    fetchWrapper(`/api/stocks/${symbol}/history?range=${range}`, options).then((d) => d.history),

  // 캔들 OHLC (일봉, range: 1W | 1M | 1Y)
  getCandles: (symbol, range, options) =>
    fetchWrapper(`/api/stocks/${symbol}/candles?interval=1d&range=${range}`, options).then((d) => d.candles),

  // 종목 관련 뉴스 (서버가 네이버 검색 API로 조회, 키는 서버에만 있음)
  getNews: (symbol, options) =>
    fetchWrapper(`/api/stocks/${symbol}/news`, options).then((d) => d.news ?? []),

  // 전문가 투자의견 (서버가 KIS 종목투자의견을 증권사별로 집계)
  getOpinion: (symbol, options) =>
    fetchWrapper(`/api/stocks/${symbol}/opinions`, options).then((d) => d.opinion),

  // 최근 실적 (분기 매출액·영업이익률·EPS, 단위 억원) → { source, unit, quarters }
  getEarnings: (symbol, options) =>
    fetchWrapper(`/api/stocks/${symbol}/earnings`, options),

  // 내 관심 종목 (로그인 필요, 모두 변경 후 전체 목록 [{ symbol, name, market, regdate }])
  getWatchlist: (options) =>
    fetchWrapper('/api/watchlist', options).then((d) => d.list ?? []),

  addWatchlist: (symbol) =>
    fetchWrapper('/api/watchlist', { method: 'POST', body: JSON.stringify({ symbol }) }).then((d) => d.list ?? []),

  removeWatchlist: (symbol) =>
    fetchWrapper(`/api/watchlist/${symbol}`, { method: 'DELETE' }).then((d) => d.list ?? []),

  // AI 챗봇 (로그인 필요) → { status, answer, sources: [{ provider, label, basisAt, url }] }
  askChat: (symbol, question) =>
    fetchWrapper('/api/chat/messages', { method: 'POST', body: JSON.stringify({ symbol, question }) }),

  // 서버가 기억 중인 최근 대화 (최대 10개) → [{ role, content, status, sources, createdAt }]
  getConversation: (symbol, options) =>
    fetchWrapper(`/api/chat/conversations/${symbol}`, options).then((d) => d.messages ?? []),

  // 새 대화 (서버의 대화 문맥 삭제)
  clearConversation: (symbol) =>
    fetchWrapper(`/api/chat/conversations/${symbol}`, { method: 'DELETE' }),

  // 시세 수신 상태
  getStatus: (options) =>
    fetchWrapper('/api/market/status', options).then((d) => d.status),
}
