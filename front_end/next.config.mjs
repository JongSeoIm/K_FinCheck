/** @type {import('next').NextConfig} */
const nextConfig = {
  async rewrites() {
    return [
      // 브라우저는 3000번만 보고, 아래 요청은 Spring(8080)으로 전달
      { source: '/api/kis/:path*', destination: 'http://localhost:8080/api/kis/:path*' },
      { source: '/api/auth/:path*', destination: 'http://localhost:8080/api/auth/:path*' },
      { source: '/api/chart/:path*', destination: 'http://localhost:8080/api/chart/:path*' },
      { source: '/api/quote/:path*', destination: 'http://localhost:8080/api/quote/:path*' },
      { source: '/api/chat/:path*', destination: 'http://localhost:8080/api/chat/:path*' },
      { source: '/api/watchlist/:path*', destination: 'http://localhost:8080/api/watchlist/:path*' },
    ]
  },
}

export default nextConfig
