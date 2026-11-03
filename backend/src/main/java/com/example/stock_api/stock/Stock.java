package com.example.stock_api.stock;

/** 지원 종목 (DB stock 테이블) */
public class Stock {
    private String code;
    private String name;
    private String market;
    private String industry;
    private String dartCorpCode;

    public Stock() {
    }

    public Stock(String code, String name, String market, String industry, String dartCorpCode) {
        this.code = code;
        this.name = name;
        this.market = market;
        this.industry = industry;
        this.dartCorpCode = dartCorpCode;
    }

    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getMarket() { return market; }
    public void setMarket(String market) { this.market = market; }

    public String getIndustry() { return industry; }
    public void setIndustry(String industry) { this.industry = industry; }

    public String getDartCorpCode() { return dartCorpCode; }
    public void setDartCorpCode(String dartCorpCode) { this.dartCorpCode = dartCorpCode; }
}