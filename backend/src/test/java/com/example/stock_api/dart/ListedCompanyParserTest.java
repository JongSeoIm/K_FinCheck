package com.example.stock_api.dart;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;

class ListedCompanyParserTest {

    private static byte[] zip(String xml) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("CORPCODE.xml"));
            zip.write(xml.getBytes(StandardCharsets.UTF_8));
        }
        return out.toByteArray();
    }

    @Test
    void readsOnlyCompaniesWithSixDigitCode() throws Exception {
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><result>"
                + "<list><corp_code>00000001</corp_code><corp_name>상장사</corp_name><stock_code>123456</stock_code></list>"
                + "<list><corp_code>00000002</corp_code><corp_name>비상장사</corp_name><stock_code> </stock_code></list>"
                + "<list><corp_code>00000003</corp_code><corp_name>새코드</corp_name><stock_code>0088M0</stock_code></list>"
                + "</result>";
        assertEquals(List.of(new ListedCompanyParser.Entry("123456", "상장사", "00000001")),
                ListedCompanyParser.parse(new ByteArrayInputStream(zip(xml))));
    }

    @Test
    void errorResponseIsNotZip() {
        byte[] error = "<result><status>013</status></result>".getBytes(StandardCharsets.UTF_8);
        assertThrows(IOException.class, () -> ListedCompanyParser.parse(new ByteArrayInputStream(error)));
    }
}
