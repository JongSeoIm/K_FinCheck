// Closed REST minutes take precedence over overlapping observed stream minutes.
export function mergeAndAggregate(history, observed, seconds) {
  const minutes = new Map()
  for (const row of [...observed, ...history]) {
    const timestamp = Date.parse(row.date)
    if (!Number.isFinite(timestamp) || ![row.open, row.high, row.low, row.close, row.volume].every(Number.isFinite)) continue
    minutes.set(timestamp, row)
  }
  const buckets = new Map()
  for (const [timestamp, row] of [...minutes].sort((a, b) => a[0] - b[0])) {
    const bucket = Math.floor(timestamp / (seconds * 1000)) * seconds * 1000
    const previous = buckets.get(bucket)
    buckets.set(bucket, previous
      ? { ...previous, high: Math.max(previous.high, row.high), low: Math.min(previous.low, row.low), close: row.close, volume: previous.volume + row.volume }
      : { ...row, date: new Date(bucket).toISOString() })
  }
  return [...buckets.values()].slice(-3000)
}
