/** @type {import('next').NextConfig} */
const nextConfig = {
  async rewrites() {
    return [
      // 브라우저는 3000번만 보고, /api/kis/* 요청은 Spring(8080)으로 전달
      { source: '/api/kis/:path*', destination: 'http://localhost:8080/api/kis/:path*' },
      // 로그인 세션 쿠키가 3000번 주소에 저장되도록 회원·관심 종목 API 도 같은 방식으로 전달
      { source: '/api/auth/:path*', destination: 'http://localhost:8080/api/auth/:path*' },
      { source: '/api/watchlist', destination: 'http://localhost:8080/api/watchlist' },
      { source: '/api/watchlist/:path*', destination: 'http://localhost:8080/api/watchlist/:path*' },
    ]
  },
}

export default nextConfig