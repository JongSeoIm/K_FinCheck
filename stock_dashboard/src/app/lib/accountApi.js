const BACKEND_URL = process.env.NEXT_PUBLIC_BACKEND_URL || 'http://localhost:8080'

export function returnPath(value) {
  return typeof value === 'string' && /^\/(?:\?symbol=\d{6})?$/.test(value) ? value : '/'
}

export async function accountApi(path, options = {}) {
  try {
    const headers = { ...options.headers }
    if (options.method && options.method !== 'GET') {
      const csrfResponse = await fetch(`${BACKEND_URL}/api/auth/csrf`, { credentials: 'include', cache: 'no-store' })
      if (!csrfResponse.ok) throw new Error('인증 정보를 가져오지 못했습니다.')
      const csrf = await csrfResponse.json()
      headers[csrf.headerName] = csrf.token
    }
    if (options.body) headers['Content-Type'] = 'application/json'
    const response = await fetch(`${BACKEND_URL}${path}`, { ...options, headers, credentials: 'include', cache: 'no-store' })
    const data = response.status === 204 ? null : await response.json()
    if (!response.ok) {
      const error = new Error(data?.error || '요청을 처리하지 못했습니다.')
      error.status = response.status
      throw error
    }
    return data
  } catch (error) {
    if (error instanceof TypeError) throw new Error('계정 서버에 연결하지 못했습니다. 잠시 후 다시 시도해 주세요.')
    throw error
  }
}
