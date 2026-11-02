'use client'

import { CartesianGrid, Line, LineChart, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts'
import { formatClock, formatDay, formatHourMinute, formatNumber, formatPrice } from '@/app/lib/format'

const COLORS = { up: '#d1343c', down: '#2f6fd6', flat: '#555' }

// 선 차트 (실시간 누적·분봉·일봉 종가)
export default function PriceLineChart({ points, interval }) {
  const data = points.map((p) => ({
    time: p.time,
    price: Number(p.close),
  }))
  const first = data[0]?.price
  const last = data[data.length - 1]?.price
  const color = last > first ? COLORS.up : last < first ? COLORS.down : COLORS.flat

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
      <LineChart data={data} margin={{ top: 8, right: 12, bottom: 0, left: 4 }}>
        <CartesianGrid stroke='#ececec' vertical={false} />
        <XAxis
          dataKey='time'
          tickFormatter={formatX}
          minTickGap={48}
          tick={{ fontSize: 11, fill: '#777' }}
          axisLine={{ stroke: '#d6d6d6' }}
          tickLine={false}
        />
        <YAxis
          domain={['auto', 'auto']}
          tickFormatter={formatNumber}
          width={72}
          tick={{ fontSize: 11, fill: '#777' }}
          axisLine={false}
          tickLine={false}
        />
        <Tooltip
          formatter={(value) => [formatPrice(value), interval === '1d' ? '종가' : '가격']}
          labelFormatter={formatLabel}
          contentStyle={{ borderRadius: 0, border: '1px solid #bbb', fontSize: 12 }}
        />
        <Line
          type='linear'
          dataKey='price'
          stroke={color}
          strokeWidth={1.6}
          dot={false}
          isAnimationActive={false}
        />
      </LineChart>
    </ResponsiveContainer>
  )
}
