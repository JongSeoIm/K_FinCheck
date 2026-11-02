// 차트 데이터 가공

const KST_OFFSET_MS = 9 * 60 * 60 * 1000

// epoch ms → 한국 날짜 'YYYY-MM-DD'
const kstDate = (ms) => new Date(ms + KST_OFFSET_MS).toISOString().slice(0, 10)
// epoch ms → 그 분의 시작 시각(ms)
const minuteStart = (ms) => Math.floor(ms / 60000) * 60000

/**
 * 받아 둔 차트 데이터의 마지막 봉을 최신 시세로 갱신한다. (정규장 운영 중일 때만 호출)
 * - interval '1d': 오늘 일봉의 고가·저가·종가 갱신, 오늘 봉이 없으면 추가
 * - interval '1m': 같은 분이면 마지막 분봉 갱신, 다음 분이면 새 분봉 추가
 */
export function mergeLiveQuote(points, interval, quote) {
  if (!points?.length || !quote?.price || !quote.eventTime) return points
  const price = Number(quote.price)
  const tradeMs = new Date(quote.eventTime).getTime()
  if (!Number.isFinite(tradeMs)) return points

  const last = points[points.length - 1]

  if (interval === '1d') {
    const today = kstDate(tradeMs)
    if (last.time === today) {
      return [...points.slice(0, -1), {
        ...last,
        high: Math.max(Number(last.high), Number(quote.high ?? price), price),
        low: Math.min(Number(last.low), Number(quote.low ?? price), price),
        close: price,
        volume: quote.cumulativeVolume ?? last.volume,
      }]
    }
    if (today > last.time) {
      return [...points, {
        time: today,
        open: Number(quote.open ?? price),
        high: Number(quote.high ?? price),
        low: Number(quote.low ?? price),
        close: price,
        volume: quote.cumulativeVolume,
      }]
    }
    return points
  }

  if (interval === '1m') {
    const lastMs = new Date(last.time).getTime()
    if (kstDate(lastMs) !== kstDate(tradeMs)) return points
    const tradeMinute = minuteStart(tradeMs)
    if (tradeMinute === minuteStart(lastMs)) {
      return [...points.slice(0, -1), {
        ...last,
        high: Math.max(Number(last.high), price),
        low: Math.min(Number(last.low), price),
        close: price,
      }]
    }
    if (tradeMinute > lastMs) {
      return [...points, {
        time: new Date(tradeMinute).toISOString(),
        open: price, high: price, low: price, close: price, volume: null,
      }]
    }
  }
  return points
}

// 기간 시작가·고가·저가·등락률
// base가 있으면 그 값 대비 등락률 (1일 차트는 전일 종가 대비로 현재가 등락률과 맞춘다)
export function periodStats(points, base) {
  if (!points?.length) return null
  const first = Number(points[0].open ?? points[0].close)
  const last = Number(points[points.length - 1].close)
  const high = Math.max(...points.map((p) => Number(p.high ?? p.close)))
  const low = Math.min(...points.map((p) => Number(p.low ?? p.close)))
  const from = Number(base) || first
  const changeRate = from ? ((last - from) / from) * 100 : 0
  return { first, last, high, low, changeRate }
}
