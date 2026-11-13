USE k_fincheck;

ALTER TABLE company_event
    ADD COLUMN IF NOT EXISTS venue VARCHAR(255) NULL,
    ADD COLUMN IF NOT EXISTS agenda VARCHAR(500) NULL;

INSERT INTO company_event
    (stock_code, event_type, title, event_date, event_time, source, source_url, venue, agenda)
SELECT company.stock_code, meeting.event_type, meeting.title, meeting.event_date,
       meeting.event_time, 'COMPANY_IR', meeting.source_url, meeting.venue, meeting.agenda
FROM company
CROSS JOIN (
    SELECT 'ANNUAL_SHAREHOLDER_MEETING' AS event_type, '제57기 정기 주주총회' AS title,
           '2026-03-18' AS event_date, '09:00:00' AS event_time,
           'https://www.samsung.com/sec/ir/reports-disclosures/public-disclosure/3755/' AS source_url,
           '경기도 수원시 수원컨벤션센터' AS venue,
           '정관 변경, 재무제표 승인, 이사 및 감사위원 선임, 자기주식 보유·처분 계획 승인' AS agenda
    UNION ALL
    SELECT 'SPECIAL_SHAREHOLDER_MEETING', '제54기 임시 주주총회',
           '2022-11-03', '10:00:00',
           'https://images.samsung.com/kdp/ir/general-meeting/2022_02_SEC_Reference_Material_kor_01.pdf',
           '경기도 용인시 삼성전자 인재개발원 서천연수원 콘서트홀',
           '사외이사 2명 선임'
) meeting
WHERE company.stock_code = '005930'
  AND NOT EXISTS (
      SELECT 1 FROM company_event existing
      WHERE existing.stock_code = company.stock_code
        AND existing.event_type = meeting.event_type
        AND existing.event_date = meeting.event_date
  );
