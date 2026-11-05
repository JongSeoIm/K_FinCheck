package com.example.stock_api.dart;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipInputStream;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/** OpenDART 고유번호 파일(zip 안의 CORPCODE.xml)에서 숫자 6자리 종목 코드가 있는 상장사만 읽습니다. */
final class ListedCompanyParser {

    record Entry(String stockCode, String name, String corpCode) {}

    private ListedCompanyParser() {}

    static List<Entry> parse(InputStream zipFile) throws IOException, XMLStreamException {
        try (ZipInputStream zip = new ZipInputStream(zipFile)) {
            if (zip.getNextEntry() == null) {
                throw new IOException("OpenDART 고유번호 파일이 zip 형식이 아닙니다.");
            }
            return read(zip);
        }
    }

    private static List<Entry> read(InputStream xml) throws XMLStreamException {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        XMLStreamReader reader = factory.createXMLStreamReader(xml);

        List<Entry> result = new ArrayList<>();
        String corpCode = "";
        String name = "";
        String stockCode = "";
        while (reader.hasNext()) {
            int event = reader.next();
            if (event == XMLStreamConstants.START_ELEMENT) {
                switch (reader.getLocalName()) {
                    case "list" -> { corpCode = ""; name = ""; stockCode = ""; }
                    case "corp_code" -> corpCode = reader.getElementText().trim();
                    case "corp_name" -> name = reader.getElementText().trim();
                    case "stock_code" -> stockCode = reader.getElementText().trim();
                    default -> { }
                }
            } else if (event == XMLStreamConstants.END_ELEMENT && "list".equals(reader.getLocalName())
                    && stockCode.matches("\\d{6}") && !name.isEmpty()) {
                result.add(new Entry(stockCode, name, corpCode));
            }
        }
        return result;
    }
}
