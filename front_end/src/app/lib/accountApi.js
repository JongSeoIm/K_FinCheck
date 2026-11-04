// 회원·관심 종목 API. next.config 의 rewrites 로 같은 주소(3000)처럼 호출해서 세션 쿠키가 그대로 오갑니다.
async function request(url, options = {}) {
  let response
  try {
    response = await fetch(url, { cache: 'no-store', ...options })
  } catch {
    throw Object.assign(new Error('서버에 연결할 수 없습니다.'), { code: 'OFFLINE' })
  }
  // 회원가입(201)·로그아웃(204)은 본문이 없습니다
  const body = await response.json().catch(() => null)
  if (response.ok) return body

  // Next 가 백엔드(8080)에 닿지 못하면 JSON 이 아닌 500 을 돌려줍니다
  if (!body?.code) throw Object.assign(new Error('서버에 연결할 수 없습니다.'), { code: 'OFFLINE' })
  throw Object.assign(new Error(body.error), { code: body.code })
}

// 변경 요청에는 CSRF 토큰을 함께 보냅니다
async function send(url, options = {}) {
  const csrf = await request('/api/auth/csrf')
  return request(url, { ...options, headers: { ...options.headers, [csrf.headerName]: csrf.token } })
}

const postJson = (url, data) => send(url, {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify(data),
})

export const fetchMe = async () => (await request('/api/auth/me')).user
export const signup = (form) => postJson('/api/auth/signup', form)
export const login = (form) => postJson('/api/auth/login', form)
export const logout = () => send('/api/auth/logout', { method: 'POST' })

export const fetchWatchlist = () => request('/api/watchlist')
export const addWatchlist = (code) => send(`/api/watchlist/${code}`, { method: 'POST' })
export const removeWatchlist = (code) => send(`/api/watchlist/${code}`, { method: 'DELETE' })