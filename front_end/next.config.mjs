/** @type {import('next').NextConfig} */
const nextConfig = {
  async rewrites() {
    return [
      // 브라우저는 3000번만 보고, /api/kis/* 요청은 Spring(8080)으로 전달
      { source: '/api/kis/:path*', destination: 'http://localhost:8080/api/kis/:path*' },
    ]
  },
}

export default nextConfig