// 로그인·관심종목 API 호출 도구.
// 변경 요청(POST·DELETE)에는 서버가 내려 준 XSRF-TOKEN 쿠키 값을 X-XSRF-TOKEN 헤더로 함께 보냅니다.

function readCookie(name) {
  if (typeof document === 'undefined') return ''
  const found = document.cookie
    .split('; ')
    .find((item) => item.startsWith(`${name}=`))
  return found ? decodeURIComponent(found.slice(name.length + 1)) : ''
}

async function request(path, method, body) {
  const headers = { Accept: 'application/json' }
  if (body !== undefined) headers['Content-Type'] = 'application/json'
  if (method !== 'GET') {
    const token = readCookie('XSRF-TOKEN')
    if (token) headers['X-XSRF-TOKEN'] = token
  }
  const response = await fetch(path, {
    method,
    headers,
    body: body !== undefined ? JSON.stringify(body) : undefined,
    credentials: 'same-origin',
    cache: 'no-store',
  })
  const data = await response.json().catch(() => null)
  return { ok: response.ok, status: response.status, data }
}

export async function apiFetch(path, { method = 'GET', body } = {}) {
  try {
    let result = await request(path, method, body)
    // 토큰 쿠키가 아직 없어서 거절당한 첫 요청이면, 토큰을 받아 온 뒤 한 번만 다시 보냅니다.
    if (result.status === 403 && method !== 'GET') {
      await request('/api/auth/me', 'GET')
      result = await request(path, method, body)
    }
    return result
  } catch {
    return { ok: false, status: 0, data: { error: '서버에 연결하지 못했습니다.' } }
  }
}

// 지금 보던 화면 주소를 담아 로그인 화면으로 보냅니다. (로그인 후 같은 종목 화면으로 돌아옵니다.)
export function goToLogin() {
  const next = window.location.pathname + window.location.search
  window.location.assign(`/login?next=${encodeURIComponent(next)}`)
}

// 외부 주소로 보내지 못하도록, 같은 사이트 안의 경로만 허용합니다.
export function safeNextPath(value) {
  return typeof value === 'string' && value.startsWith('/') && !value.startsWith('//') ? value : '/'
}
