'use client'

import { useState } from 'react'
import Image from 'next/image'

// public/logos에 내려받아 둔 회사 로고(출처는 public/logos/SOURCES.md). 없거나 불러오지 못하면 이름 첫 글자 칸으로 대신한다.
const LOGO_SYMBOLS = new Set(['005930', '000660', '035420', '035720', '005380', '000270', '051910'])

// 로고와 첫 글자 칸 모두 장식이다(종목명은 옆 글자로 읽힌다).
export default function StockAvatar({ symbol, name, size = 'md' }) {
  const [failed, setFailed] = useState(false)
  const code = String(symbol)
  const pixels = size === 'lg' ? 48 : 40

  if (LOGO_SYMBOLS.has(code) && !failed) {
    return <span className={`stock-avatar stock-avatar-${size} stock-logo`} data-logo={code} aria-hidden='true'>
      <Image src={`/logos/${code}.svg`} alt='' width={pixels} height={pixels} unoptimized onError={() => setFailed(true)} />
    </span>
  }

  const tone = Number.parseInt(code.replace(/\D/g, '') || '0', 10) % 6
  return <span className={`stock-avatar stock-avatar-${size}`} data-tone={tone} aria-hidden='true'>
    {String(name || symbol).trim().charAt(0)}
  </span>
}
