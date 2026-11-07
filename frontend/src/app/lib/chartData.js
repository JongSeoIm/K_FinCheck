// 차트 데이터 가공

const KST_OFFSET_MS = 9 * 60 * 60 * 1000

// epoch ms → 한국 날짜 'YYYY-MM-DD'
const kstDate = (ms) => new Date(ms + KST_OFFSET_MS).toISOString().slice(0, 10)

/**
 * 받아 둔 일봉의 마지막 봉을 최신 시세로 갱신한다. (정규장 운영 중일 때만 호출)
 * 오늘 일봉의 고가·저가·종가를 갱신하고, 오늘 봉이 없으면 추가한다.
 */
export function mergeDailyQuote(points, quote) {
  if (!points?.length || !quote?.price || !quote.eventTime) return points
  const price = Number(quote.price)
  const tradeMs = new Date(quote.eventTime).getTime()
  if (!Number.isFinite(tradeMs)) return points

  const last = points[points.length - 1]

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

// Push 로 받은 봉을 합친다. 같은 구간이면 교체하고, 새 구간이면 앞 봉을 완성으로 표시하고 뒤에 붙인다
export function mergeCandle(points, candle) {
  if (!candle) return points
  const time = new Date(candle.time).getTime()
  const last = points[points.length - 1]
  const lastTime = last ? new Date(last.time).getTime() : null
  if (lastTime === null) return [candle]
  if (time === lastTime) {
    return [...points.slice(0, -1), { ...candle, open: last.open, high: Math.max(Number(last.high), Number(candle.high)), low: Math.min(Number(last.low), Number(candle.low)) }]
  }
  if (time > lastTime) return [...points.slice(0, -1), { ...last, complete: true }, candle]
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
