'use client'

import { CartesianGrid, Line, LineChart, ReferenceLine, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts'
import { directionClass, formatClock, formatDay, formatHourMinute, formatNumber, formatPrice, formatRate } from '@/app/lib/format'

// 목업 StockChart 와 같은 모양 (점선 격자, 오른쪽 가격 축, 기간 시작가 점선). 색만 국내 관례
const COLORS = { up: '#c43b3b', down: '#1769aa', flat: '#666' }

function ChartTooltip({ active, payload, label, firstPrice, formatLabel }) {
  if (!active || !payload?.length) return null
  const value = Number(payload[0].value)
  const rate = firstPrice ? ((value - firstPrice) / firstPrice) * 100 : 0
  return (
    <div className='chart-tooltip'>
      <span>{formatLabel(label)}</span>
      <strong>{formatPrice(value)}</strong>
      <span className={directionClass(rate)}>{formatRate(rate)}</span>
    </div>
  )
}

// 선 차트 (실시간 누적·분봉·일봉 종가)
export default function PriceLineChart({ points, interval }) {
  const data = points.map((p) => ({
    time: p.time,
    price: Number(p.close),
  }))
  const first = data[0]?.price
  const last = data[data.length - 1]?.price
  const color = last > first ? COLORS.up : last < first ? COLORS.down : COLORS.flat
  // 값이 거의 같으면 축이 1원 단위로 벌어져 의미 없는 눈금이 생기므로 위아래 여유를 둔다
  const prices = data.map((d) => d.price)
  const min = Math.min(...prices)
  const max = Math.max(...prices)
  const pad = Math.max((max - min) * 0.1, max * 0.002)
  const domain = [Math.floor(min - pad), Math.ceil(max + pad)]

  const formatX = (value) => {
    if (interval === '1d') return formatDay(value)
    if (interval === 'live') return formatClock(value)
    return formatHourMinute(value)
  }
  const formatLabel = (value) => {
    if (interval === '1d') return value
    return formatClock(value)
  }

  return (
    <ResponsiveContainer width='100%' height='100%'>
      <LineChart data={data} margin={{ top: 8, right: 6, bottom: 0, left: 0 }}>
        <CartesianGrid stroke='#e1e1e1' strokeDasharray='3 3' />
        <XAxis dataKey='time' tickFormatter={formatX} minTickGap={36} tick={{ fill: '#666', fontSize: 11 }} />
        <YAxis
          orientation='right'
          domain={domain}
          tickFormatter={formatNumber}
          width={72}
          tick={{ fill: '#666', fontSize: 11 }}
        />
        <Tooltip content={<ChartTooltip firstPrice={first} formatLabel={formatLabel} />} />
        {first != null && <ReferenceLine y={first} stroke='#888' strokeDasharray='4 4' />}
        <Line
          type='monotone'
          dataKey='price'
          stroke={color}
          strokeWidth={2}
          dot={false}
          activeDot={{ r: 4 }}
          isAnimationActive={false}
        />
      </LineChart>
    </ResponsiveContainer>
  )
}
