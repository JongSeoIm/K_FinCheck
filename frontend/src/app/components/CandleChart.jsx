'use client'

import { Bar, CartesianGrid, ComposedChart, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts'
import { directionClass, formatChartTime, formatClock, formatNumber, formatPrice, formatRate, formatVolume } from '@/app/lib/format'

const UP = '#d1343c'
const DOWN = '#2f6fd6'

// 봉 한 개: 고가~저가 막대 영역 안에 꼬리와 몸통을 그린다. 진행 중인 봉은 흐리게 표시
function CandleShape({ x, y, width, height, payload }) {
  const { open, close, high, low, complete } = payload
  const color = close >= open ? UP : DOWN
  const center = x + width / 2
  const perPrice = high > low ? height / (high - low) : 0
  const bodyTop = y + (high - Math.max(open, close)) * perPrice
  const bodyHeight = Math.max(1, Math.abs(close - open) * perPrice)
  const bodyWidth = Math.max(1, width * 0.7)
  return (
    <g opacity={complete === false ? 0.5 : 1}>
      <line x1={center} x2={center} y1={y} y2={y + Math.max(height, 1)} stroke={color} />
      <rect x={center - bodyWidth / 2} y={bodyTop} width={bodyWidth} height={bodyHeight} fill={color} />
    </g>
  )
}

function CandleTooltip({ active, payload, interval }) {
  if (!active || !payload?.length) return null
  const p = payload[0].payload
  const rate = p.open ? ((p.close - p.open) / p.open) * 100 : 0
  const label = interval?.endsWith('s') || interval?.endsWith('m') ? formatClock(p.time) : formatChartTime(p.time, interval)
  return (
    <div className='chart-tooltip'>
      <span>{label}{p.complete === false ? ' · 진행 중' : ''}</span>
      <strong>{formatPrice(p.close)}</strong>
      <span className={directionClass(rate)}>{formatRate(rate)}</span>
      <span>시 {formatNumber(p.open)} · 고 {formatNumber(p.high)}</span>
      <span>저 {formatNumber(p.low)} · 종 {formatNumber(p.close)}</span>
      {p.volume != null && <span>거래량 {formatVolume(p.volume)}</span>}
    </div>
  )
}

// 캔들 차트 (Recharts). 상승 빨강 / 하락 파랑
export default function CandleChart({ points, interval, multiDay }) {
  const data = points.map((p) => {
    const high = Number(p.high)
    const low = Number(p.low)
    return {
      time: p.time,
      open: Number(p.open),
      high,
      low,
      close: Number(p.close),
      volume: p.volume,
      complete: p.complete,
      range: [low, high],
    }
  })
  // 표시 중인 구간에 맞춰 가격 축을 조정한다
  const max = Math.max(...data.map((d) => d.high))
  const min = Math.min(...data.map((d) => d.low))
  const pad = Math.max((max - min) * 0.08, max * 0.001)

  return (
    <ResponsiveContainer width='100%' height='100%'>
      <ComposedChart data={data} margin={{ top: 8, right: 6, bottom: 0, left: 0 }} barCategoryGap='15%'>
        <CartesianGrid stroke='#e1e1e1' strokeDasharray='3 3' />
        <XAxis
          dataKey='time'
          tickFormatter={(value) => formatChartTime(value, interval, multiDay)}
          minTickGap={40}
          tick={{ fill: '#666', fontSize: 11 }}
        />
        <YAxis
          orientation='right'
          domain={[Math.floor(min - pad), Math.ceil(max + pad)]}
          allowDataOverflow
          tickFormatter={formatNumber}
          width={72}
          tick={{ fill: '#666', fontSize: 11 }}
        />
        <Tooltip content={<CandleTooltip interval={interval} />} cursor={{ fill: 'rgba(0,0,0,0.04)' }} />
        <Bar dataKey='range' shape={<CandleShape />} minPointSize={1} isAnimationActive={false} />
      </ComposedChart>
    </ResponsiveContainer>
  )
}
