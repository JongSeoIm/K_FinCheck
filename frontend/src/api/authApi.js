import { fetchWrapper } from './stockApi'

// 회원가입·로그인·로그아웃 (서버 세션 방식, 쿠키는 브라우저가 자동으로 보관)
export const authApi = {
  // { loginId, password, nickname } → 가입 후 로그인된 사용자
  signup: (body) =>
    fetchWrapper('/api/auth/signup', { method: 'POST', body: JSON.stringify(body) }).then((d) => d.user),

  // { loginId, password } → 로그인한 사용자
  login: (body) =>
    fetchWrapper('/api/auth/login', { method: 'POST', body: JSON.stringify(body) }).then((d) => d.user),

  logout: () => fetchWrapper('/api/auth/logout', { method: 'POST' }),

  // 현재 로그인 사용자 (로그인 안 했으면 null)
  me: (options) =>
    fetchWrapper('/api/auth/me', options)
      .then((d) => d.user)
      .catch((error) => {
        if (error.status === 401) return null
        throw error
      }),
}
