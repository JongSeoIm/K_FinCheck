package com.kfincheck.dto;

public class StockPriceDto {

    private String stockCode;
    private String stockName;
    private Long price;
    private Long priceChange;
    private Double changeRate;
    private Long volume;
    private Long open;
    private Long high;
    private Long low;
    private String marketName;
    private String source;

    public StockPriceDto() {
    }

    public StockPriceDto(String stockCode, String stockName, Long price, Long priceChange,
                         Double changeRate, Long volume, Long open, Long high, Long low,
                         String marketName, String source) {
        this.stockCode = stockCode;
        this.stockName = stockName;
        this.price = price;
        this.priceChange = priceChange;
        this.changeRate = changeRate;
        this.volume = volume;
        this.open = open;
        this.high = high;
        this.low = low;
        this.marketName = marketName;
        this.source = source;
    }

    public String getStockCode() {
        return stockCode;
    }

    public void setStockCode(String stockCode) {
        this.stockCode = stockCode;
    }

    public String getStockName() {
        return stockName;
    }

    public void setStockName(String stockName) {
        this.stockName = stockName;
    }

    public Long getPrice() {
        return price;
    }

    public void setPrice(Long price) {
        this.price = price;
    }

    public Long getPriceChange() {
        return priceChange;
    }

    public void setPriceChange(Long priceChange) {
        this.priceChange = priceChange;
    }

    public Double getChangeRate() {
        return changeRate;
    }

    public void setChangeRate(Double changeRate) {
        this.changeRate = changeRate;
    }

    public Long getVolume() {
        return volume;
    }

    public void setVolume(Long volume) {
        this.volume = volume;
    }

    public Long getOpen() {
        return open;
    }

    public void setOpen(Long open) {
        this.open = open;
    }

    public Long getHigh() {
        return high;
    }

    public void setHigh(Long high) {
        this.high = high;
    }

    public Long getLow() {
        return low;
    }

    public void setLow(Long low) {
        this.low = low;
    }

    public String getMarketName() {
        return marketName;
    }

    public void setMarketName(String marketName) {
        this.marketName = marketName;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }
}

