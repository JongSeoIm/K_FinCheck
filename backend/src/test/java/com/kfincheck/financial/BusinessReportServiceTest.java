package com.kfincheck.financial;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;

class BusinessReportServiceTest {
    @Test
    void parsesListedCompaniesFromCorpCodeZip() throws Exception {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <result>
                  <list><corp_code>00126380</corp_code><corp_name>삼성전자</corp_name>
                    <corp_eng_name>SAMSUNG ELECTRONICS CO,.LTD</corp_eng_name><stock_code>005930</stock_code><modify_date>20240101</modify_date></list>
                  <list><corp_code>00999999</corp_code><corp_name>비상장사</corp_name><stock_code> </stock_code></list>
                </result>
                """;
        var bytes = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("CORPCODE.xml"));
            zip.write(xml.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        var codes = BusinessReportService.parseCorpCodes(bytes.toByteArray());
        assertThat(codes).containsEntry("005930", "00126380").hasSize(1);
    }
}
