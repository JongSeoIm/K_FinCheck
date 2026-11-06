'use client'

import { useCallback, useEffect, useRef, useState } from 'react'

const MIN_BARS = 10
const ZOOM_STEP = 0.15

// 마우스 휠 확대·축소: 커서 위치를 중심으로 보이는 구간을 줄이거나 늘린다
// offset 은 오른쪽 끝에서 떨어진 봉 수 (0이면 새 봉이 들어올 때 계속 오른쪽 끝을 보여준다)
export default function useChartZoom(length, defaultSize) {
  const ref = useRef(null)
  const [view, setView] = useState({ size: null, offset: 0 })
  const lengthRef = useRef(length)
  const defaultRef = useRef(defaultSize)

  useEffect(() => {
    lengthRef.current = length
    defaultRef.current = defaultSize
  }, [length, defaultSize])

  useEffect(() => {
    const element = ref.current
    if (!element) return undefined
    const onWheel = (event) => {
      const total = lengthRef.current
      if (total <= MIN_BARS) return
      event.preventDefault()
      const rect = element.getBoundingClientRect()
      const ratio = Math.min(1, Math.max(0, (event.clientX - rect.left) / rect.width))
      setView((current) => {
        const size = Math.min(current.size ?? defaultRef.current ?? total, total)
        const nextSize = Math.round(Math.min(total, Math.max(MIN_BARS, size * (event.deltaY > 0 ? 1 + ZOOM_STEP : 1 - ZOOM_STEP))))
        const end = total - current.offset
        const anchor = end - size + ratio * size
        const nextEnd = Math.round(anchor + (1 - ratio) * nextSize)
        const offset = Math.min(total - nextSize, Math.max(0, total - nextEnd))
        return { size: nextSize, offset }
      })
    }
    element.addEventListener('wheel', onWheel, { passive: false })
    return () => element.removeEventListener('wheel', onWheel)
  })

  const reset = useCallback(() => setView({ size: null, offset: 0 }), [])
  const size = Math.min(view.size ?? defaultSize ?? length, length)
  const offset = Math.min(view.offset, Math.max(0, length - size))
  const end = length - offset

  return { ref, start: Math.max(0, end - size), end, zoomed: view.size !== null, reset }
}
