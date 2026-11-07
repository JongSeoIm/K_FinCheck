import './globals.css'

export const metadata = {
  title: '국내 주식 실시간 시세',
  description: '국내 종목의 실시간 현재가와 분봉·일봉 차트를 확인하는 대시보드 (1차)',
}

export default function RootLayout({ children }) {
  return (
    <html lang='ko'>
      <body>{children}</body>
    </html>
  )
}
