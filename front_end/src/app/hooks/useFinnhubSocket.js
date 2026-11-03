'use client'
// UI preview only: no upstream connections or live price simulation.
export default function useFinnhubSocket() {
 return { status: 'UI 미리보기', trades: {}, history: {} }
}
