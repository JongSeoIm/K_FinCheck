import './globals.css'
import './chat-drawer.css'

export const metadata = {
  title: 'K-FinCheck · 국내 주식 대시보드',
  description: '한국투자증권 Open API 실시간 시세 · 차트 · 공시 기반 국내 주식 대시보드',
}

export default function RootLayout({ children }) {
  return (
    <html lang='ko'>
      <body>{children}</body>
    </html>
  )
}
