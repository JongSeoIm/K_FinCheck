// 패널 공통: 불러오는 중 / 오류 / 빈 상태. 가짜 데이터로 채우지 않습니다.
export function PanelLoading({ rows = 3 }) {
  return (
    <div className='skeleton-list' aria-label='불러오는 중'>
      {Array.from({ length: rows }, (_, index) => <span key={index} className='skeleton-row compact' />)}
    </div>
  )
}

export function PanelError({ error, onRetry }) {
  return (
    <div className='panel-state' role='alert'>
      <strong>데이터를 불러오지 못했습니다.</strong>
      <span>{error?.message}</span>
      {onRetry && <button type='button' onClick={onRetry}>다시 시도</button>}
    </div>
  )
}

export function PanelEmpty({ title, children }) {
  return (
    <div className='panel-state'>
      <strong>{title}</strong>
      {children && <span>{children}</span>}
    </div>
  )
}
