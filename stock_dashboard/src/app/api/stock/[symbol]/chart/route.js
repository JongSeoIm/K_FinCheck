import { getStockCandles, getStockQuote } from '@/app/lib/finnhub'

const rangeConfig = {
  '1D': { seconds: 60 * 60 * 24, resolution: '5' },
  '1W': { seconds: 60 * 60 * 24 * 7, resolution: '60' },
  '1M': { seconds: 60 * 60 * 24 * 35, resolution: 'D' },
  '1Y': { seconds: 60 * 60 * 24 * 365, resolution: 'W' },
}

function createFallback(price, range) {
  const pointCount = range === '1D' ? 12 : range === '1W' ? 7 : range === '1Y' ? 52 : 22
  const interval = range === '1D' ? 30 * 60 * 1000 : 24 * 60 * 60 * 1000
  const end = Date.now()

  return Array.from({ length: pointCount }, (_, index) => {
    const timestamp = end - (pointCount - index - 1) * interval
    const wave = Math.sin(index * 1.35) * 0.75
    const trend = (index - pointCount / 2) * (range === '1Y' ? 0.08 : range === '1M' ? 0.11 : 0.16)

    return {
      time: new Date(timestamp).toLocaleString('ko-KR', range === '1D'
        ? { hour: '2-digit', minute: '2-digit' }
        : { month: 'numeric', day: 'numeric' }),
      price: Number((price + wave + trend).toFixed(2)),
      fallback: true,
    }
  })
}

export async function GET(request, { params }) {
  const { symbol: rawSymbol } = await params
  const symbol = rawSymbol.toUpperCase()
  const range = new URL(request.url).searchParams.get('range') || '1D'
  const config = rangeConfig[range] || rangeConfig['1D']
  const to = Math.floor(Date.now() / 1000)
  const from = to - config.seconds

  try {
    const candles = await getStockCandles(symbol, config.resolution, from, to)
    return Response.json(candles.slice(range === '1Y' ? -52 : -30))
  } catch {
    try {
      const quote = await getStockQuote(symbol)
      return Response.json(createFallback(quote.price, range))
    } catch (error) {
      return Response.json({ error: error.message }, { status: 500 })
    }
  }
}
