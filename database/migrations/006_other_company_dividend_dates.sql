USE k_fincheck;

INSERT INTO company_event
    (stock_code, event_type, title, event_date, source, source_url)
SELECT company.stock_code, 'DIVIDEND_RECORD_DATE', dividend.title,
       dividend.event_date, 'COMPANY_IR', dividend.source_url
FROM company
JOIN (
    SELECT '005380' AS stock_code, '2026년 1분기 배당기준일' AS title,
           '2026-05-31' AS event_date,
           'https://www.hyundai.com/worldwide/ko/company/ir/public-disclosure-and-notices/notices/view-0000000368-ko-10-1-' AS source_url
    UNION ALL
    SELECT '005380', '2026년 2분기 배당기준일', '2026-08-31',
           'https://www.hyundai.com/worldwide/ko/company/ir/public-disclosure-and-notices/notices/view-0000000375-ko-10-1-'
    UNION ALL
    SELECT '035420', '2025년 결산배당 기준일 (주당 2,630원)', '2026-02-27',
           'https://www.navercorp.com/api/article/download/fb35768c-b7e8-42de-b316-47997bf32590'
) dividend ON dividend.stock_code = company.stock_code
WHERE NOT EXISTS (
    SELECT 1 FROM company_event existing
    WHERE existing.stock_code = company.stock_code
      AND existing.event_type = 'DIVIDEND_RECORD_DATE'
      AND existing.event_date = dividend.event_date
);
