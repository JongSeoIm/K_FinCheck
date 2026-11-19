import Image from 'next/image'

const supportedSymbols = new Set(['005930', '000660', '035420', '035720', '005380', '000270', '051910', '006400'])

export default function StockIcon({ symbol }) {
  if (!supportedSymbols.has(symbol)) return null
  return <Image className='stock-icon' src={`/stock-icons/${symbol}.png`}
    width={36} height={36} alt='' aria-hidden='true' unoptimized />
}
