'use client'

import { useState } from 'react'

export default function useFinnhubSocket(symbols) {
  const [status] = useState('KIS REST 연동 모드')
  const [trades] = useState({})
  const [history] = useState({})

  return { status, trades, history }
}
