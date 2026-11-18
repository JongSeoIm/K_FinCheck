import './globals.css'
import './chat-drawer.css'

export const metadata = {
  title: 'K-FinCheck | 국내 주식 리서치',
  description: '국내 주식의 시세·차트·실적·배당·정책·뉴스를 확인하고 출처가 연결된 AI 답변을 받는 대시보드',
}

export const viewport = {
  themeColor: '#f2f4f6',
}

export default function RootLayout({ children }) {
  return (
    <html lang='ko'>
      <body>{children}</body>
    </html>
  )
}
