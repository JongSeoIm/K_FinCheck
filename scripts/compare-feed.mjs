// 시세 전달 방식 비교: 같은 시간·같은 종목으로 Push(0.5초)와 Polling(응답 후 2초)을 차례로 측정한다.
// 사용법 (stock-api·Kafka 실행 후, Node 22 이상):
//   node scripts/compare-feed.mjs --seconds 60 --symbols 005930,000660
// 지연은 서버가 시세를 받은 시각(receivedAt)과 이 스크립트가 받은 시각의 차이다.
// 서버와 같은 PC에서 실행해 시계 차이를 없앤다. 다른 PC에서 실행하면 시계 차이만큼 값이 달라진다.

const args = Object.fromEntries(process.argv.slice(2).join(' ').split('--').filter(Boolean).map((part) => part.trim().split(/\s+/)))
const seconds = Number(args.seconds ?? 60)
const symbols = (args.symbols ?? '005930,000660').split(',')
const baseUrl = args.url ?? 'http://localhost:8085'
const wsUrl = baseUrl.replace(/^http/, 'ws') + '/ws/quotes'
const POLL_DELAY_MS = 2000

const percentile = (values, p) => {
  if (!values.length) return null
  const sorted = [...values].sort((a, b) => a - b)
  return sorted[Math.min(sorted.length - 1, Math.floor((p / 100) * sorted.length))]
}

const newResult = () => ({ requests: 0, messages: 0, updates: 0, bytes: 0, latency: [], seen: new Set(), duplicates: 0, outOfOrder: 0, failures: 0, last: {} })

// 받은 시세 1건 기록: 같은 이벤트는 중복, 앞선 시각보다 이전이면 역순으로 센다
function record(result, quote) {
  if (result.seen.has(quote.eventId)) {
    result.duplicates += 1
    return
  }
  result.seen.add(quote.eventId)
  result.updates += 1
  const received = Date.parse(quote.receivedAt)
  if (received) result.latency.push(Date.now() - received)
  const last = result.last[quote.symbol]
  if (last && received < last) result.outOfOrder += 1
  result.last[quote.symbol] = received
}

function measurePush() {
  return new Promise((resolve, reject) => {
    const result = newResult()
    const socket = new WebSocket(wsUrl)
    socket.onopen = () => {
      socket.send(JSON.stringify({ type: 'subscribe', symbols }))
      setTimeout(() => {
        socket.close()
        resolve(result)
      }, seconds * 1000)
    }
    socket.onerror = () => reject(new Error(`WebSocket 연결 실패: ${wsUrl}`))
    socket.onmessage = (event) => {
      result.messages += 1
      result.bytes += Buffer.byteLength(event.data)
      const message = JSON.parse(event.data)
      if (message.type === 'quote') record(result, message.data)
    }
  })
}

async function measurePolling() {
  const result = newResult()
  const end = Date.now() + seconds * 1000
  while (Date.now() < end) {
    result.requests += 1
    try {
      const response = await fetch(`${baseUrl}/api/quotes?symbols=${symbols.join(',')}`)
      const text = await response.text()
      result.messages += 1
      result.bytes += Buffer.byteLength(text)
      if (!response.ok) result.failures += 1
      else JSON.parse(text).list.forEach((quote) => record(result, quote))
    } catch {
      result.failures += 1
    }
    await new Promise((done) => setTimeout(done, POLL_DELAY_MS))
  }
  return result
}

const avg = (values) => (values.length ? Math.round(values.reduce((a, b) => a + b, 0) / values.length) : null)

function row(name, push, poll) {
  console.log(`| ${name} | ${push ?? '—'} | ${poll ?? '—'} |`)
}

console.log(`측정 조건: ${seconds}초씩, 종목 ${symbols.join(', ')}, 접속 1개, 서버 ${baseUrl}`)
console.log(`측정 시각: ${new Date().toLocaleString('ko-KR', { timeZone: 'Asia/Seoul' })}\n`)

const status = await fetch(`${baseUrl}/api/market/status`).then((r) => r.json()).then((d) => d.status)
console.log(`데이터: ${status.dataMode}, 수집 상태: ${status.feedStatus}, 정규장: ${status.marketOpen}\n`)

const push = await measurePush()
const poll = await measurePolling()

console.log('| 지표 | Push (0.5초) | Polling (응답 후 2초) |')
console.log('|---|---|---|')
row('HTTP 요청 수', '0 (접속 후 WebSocket 1개)', poll.requests)
row('받은 메시지 수', push.messages, poll.messages)
row('전달량 (바이트)', push.bytes, poll.bytes)
row('새 시세 수신 건수', push.updates, poll.updates)
row('지연 평균 (ms)', avg(push.latency), avg(poll.latency))
row('지연 중앙값 (ms)', percentile(push.latency, 50), percentile(poll.latency, 50))
row('지연 95% (ms)', percentile(push.latency, 95), percentile(poll.latency, 95))
row('중복', push.duplicates, poll.duplicates)
row('역순', push.outOfOrder, poll.outOfOrder)
row('실패', push.failures, poll.failures)
console.log('\n참고: Polling 의 중복은 시세가 바뀌지 않은 동안 같은 값을 다시 받은 건수다.')
