'use client'

export default function NewsFeed({ symbol }) {
  return (
    <div className='empty-state' style={{ padding: '24px 16px' }}>
      <strong>국내 주요 뉴스 연동 준비 중</strong>
      <p style={{ marginTop: '8px', color: 'var(--muted)', fontSize: '12px' }}>
        국내 증시 및 기업 관련 실시간 뉴스 피드는 차기 단계에서 제공될 예정입니다.
      </p>
    </div>
  )
}
