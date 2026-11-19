import './globals.css'
import './chat-drawer.css'

export const metadata = {
  title: 'K-FinCheck | 국내 주식',
  description: '국내 주식 시세, 기업 일정, 공시를 확인하는 K-FinCheck',
}

export default function RootLayout({ children }) {
  return (
    <html lang='ko'>
      <body>{children}</body>
    </html>
  )
}
