USE k_fincheck;

INSERT INTO company_event
    (stock_code, event_type, title, event_date, source, source_url)
SELECT company.stock_code, dividend.event_type, dividend.title,
       dividend.event_date, 'COMPANY_IR', dividend.source_url
FROM company
CROSS JOIN (
    SELECT 'DIVIDEND_RECORD_DATE' AS event_type, '2026년 1분기 배당기준일' AS title,
           '2026-03-31' AS event_date,
           'https://www.samsung.com/sec/ir/reports-disclosures/public-disclosure/3959/' AS source_url
    UNION ALL
    SELECT 'DIVIDEND_PAYMENT', '2026년 1분기 배당금 지급 예정일 (주당 372원)',
           '2026-05-29', 'https://www.samsung.com/sec/ir/reports-disclosures/public-disclosure/3959/'
    UNION ALL
    SELECT 'DIVIDEND_RECORD_DATE', '2026년 2분기 배당기준일',
           '2026-06-30', 'https://www.samsung.com/sec/ir/reports-disclosures/public-disclosure/4017/'
    UNION ALL
    SELECT 'DIVIDEND_PAYMENT', '2026년 2분기 배당금 지급 예정일 (주당 374원)',
           '2026-08-28', 'https://www.samsung.com/sec/ir/reports-disclosures/public-disclosure/4017/'
    UNION ALL
    SELECT 'DIVIDEND_RECORD_DATE', '2026년 3분기 배당기준일',
           '2026-09-30', 'https://www.samsung.com/sec/ir/stock-information/shareholder-return/'
) dividend
WHERE company.stock_code = '005930'
  AND NOT EXISTS (
      SELECT 1 FROM company_event existing
      WHERE existing.stock_code = company.stock_code
        AND existing.event_type = dividend.event_type
        AND existing.event_date = dividend.event_date
  );
