import './globals.css'
import './chat-drawer.css'

export const metadata = {
  title: '실시간 주식 대시보드',
  description: 'Finnhub와 WebSocket으로 확인하는 미국 주식 대시보드',
}

export default function RootLayout({ children }) {
  return (
    <html lang='ko'>
      <body>{children}</body>
    </html>
  )
}
