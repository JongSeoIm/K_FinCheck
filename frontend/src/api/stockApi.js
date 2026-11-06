// 우리 Spring 서버(stock-api)만 호출한다. 증권사 API 키는 서버에만 있고 브라우저에는 없다.
export const API_BASE_URL = process.env.NEXT_PUBLIC_API_BASE_URL || 'http://localhost:8085'
export const WS_URL = process.env.NEXT_PUBLIC_WS_URL || 'ws://localhost:8085/ws/quotes'

// 값을 바꾸는 요청에 보낼 CSRF 토큰 (서버 세션에 묶여 있어 로그아웃하면 다시 받는다)
let csrf = null

const loadCsrf = async () => {
  const response = await fetch(`${API_BASE_URL}/api/auth/csrf`, { credentials: 'include' })
  csrf = await response.json()
  return csrf
}

// fetch 에러 처리와 JSON 파싱을 도와주는 함수 (실습 boardApi.js 방식)
// 로그인 세션 쿠키(JSESSIONID)를 함께 보내도록 credentials: 'include'
export const fetchWrapper = async (path, options = {}, retried = false) => {
  const changing = options.method && options.method !== 'GET'
  let response
  try {
    const token = changing ? (csrf ?? await loadCsrf()) : null
    response = await fetch(`${API_BASE_URL}${path}`, {
      ...options,
      credentials: 'include',
      headers: {
        'Content-Type': 'application/json',
        ...(token ? { [token.headerName]: token.token } : {}),
        ...options.headers,
      },
    })
  } catch (error) {
    if (error.name === 'AbortError') throw error
    throw new Error('서버에 연결할 수 없습니다. stock-api 서버가 실행 중인지 확인하세요.')
  }

  // 세션이 바뀌어 토큰이 만료되면 새로 받아 한 번만 다시 보낸다
  if (changing && response.status === 403 && !retried) {
    csrf = null
    return fetchWrapper(path, options, true)
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

  // 봉 데이터 (interval: 1s·5s·30s·1m·3m·5m·10m·15m·30m·60m·1d·1w·1mo·1y)
  getCandles: (symbol, interval, options) =>
    fetchWrapper(`/api/stocks/${symbol}/candles?interval=${interval}${interval === '1d' ? '&range=1Y' : ''}`, options).then((d) => d.candles),

  // 종목 관련 뉴스 (서버가 네이버 검색 API로 조회, 키는 서버에만 있음)
  getNews: (symbol, options) =>
    fetchWrapper(`/api/stocks/${symbol}/news`, options).then((d) => d.news ?? []),

  // 전문가 투자의견 (서버가 KIS 종목투자의견을 증권사별로 집계)
  getOpinion: (symbol, options) =>
    fetchWrapper(`/api/stocks/${symbol}/opinions`, options).then((d) => d.opinion),

  // 최근 실적 (분기 매출액·영업이익률·EPS, 단위 억원) → { source, unit, quarters }
  getEarnings: (symbol, options) =>
    fetchWrapper(`/api/stocks/${symbol}/earnings`, options),

  // 연간 공시 실적·전년 대비·재무 지표 (서버가 OpenDART 조회 후 DB 저장)
  getAnnualEarnings: (symbol, options) =>
    fetchWrapper(`/api/companies/${symbol}/annual-earnings`, options).then((d) => d.earnings),

  // 내 관심 종목 (로그인 필요, 모두 변경 후 전체 목록 [{ symbol, name, market, createdAt }])
  getWatchlist: (options) =>
    fetchWrapper('/api/watchlist', options).then((d) => d.list ?? []),

  addWatchlist: (symbol) =>
    fetchWrapper('/api/watchlist', { method: 'POST', body: JSON.stringify({ symbol }) }).then((d) => d.list ?? []),

  removeWatchlist: (symbol) =>
    fetchWrapper(`/api/watchlist/${symbol}`, { method: 'DELETE' }).then((d) => d.list ?? []),

  // AI 챗봇 (로그인 필요) → { conversationId, status, answer, sources: [{ provider, label, basisAt, url }], visuals }
  askChat: (symbol, question, conversationId) =>
    fetchWrapper('/api/chat', { method: 'POST', body: JSON.stringify({ symbol, question, conversationId: conversationId ?? undefined }) }),

  // 새 대화방 → { conversationId, stockCode, stockName, title, createdAt, updatedAt }
  createConversation: (symbol) =>
    fetchWrapper('/api/chat/conversations', { method: 'POST', body: JSON.stringify({ symbol }) }).then((d) => d.conversation),

  // 종목의 대화방 목록 (최근에 이어간 순서) → [{ conversationId, stockCode, stockName, title, createdAt, updatedAt }]
  getConversations: (symbol, options) =>
    fetchWrapper(`/api/chat/conversations?symbol=${symbol}`, options).then((d) => d.conversations ?? []),

  // 대화방의 저장된 메시지 → [{ role, content, status, sources, visuals, createdAt }]
  getConversationMessages: (conversationId, options) =>
    fetchWrapper(`/api/chat/conversations/${conversationId}/messages`, options).then((d) => d.messages ?? []),

  deleteConversation: (conversationId) =>
    fetchWrapper(`/api/chat/conversations/${conversationId}`, { method: 'DELETE' }),

  // 시세 수신 상태
  getStatus: (options) =>
    fetchWrapper('/api/market/status', options).then((d) => d.status),
}
