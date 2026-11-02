package com.kfincheck.client.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public class KisPriceResponse {

    @JsonProperty("rt_cd")
    private String rtCd;

    @JsonProperty("msg_cd")
    private String msgCd;

    @JsonProperty("msg1")
    private String msg1;

    @JsonProperty("output")
    private PriceOutput output;

    public boolean isSuccess() {
        return "0".equals(rtCd);
    }

    public String getRtCd() {
        return rtCd;
    }

    public void setRtCd(String rtCd) {
        this.rtCd = rtCd;
    }

    public String getMsgCd() {
        return msgCd;
    }

    public void setMsgCd(String msgCd) {
        this.msgCd = msgCd;
    }

    public String getMsg1() {
        return msg1;
    }

    public void setMsg1(String msg1) {
        this.msg1 = msg1;
    }

    public PriceOutput getOutput() {
        return output;
    }

    public void setOutput(PriceOutput output) {
        this.output = output;
    }

    public static class PriceOutput {

        @JsonProperty("stck_prpr")
        private String stckPrpr;

        @JsonProperty("prdy_vrss")
        private String prdyVrss;

        @JsonProperty("prdy_vrss_sign")
        private String prdyVrssSign;

        @JsonProperty("prdy_ctrt")
        private String prdyCtrt;

        @JsonProperty("acml_vol")
        private String acmlVol;

        @JsonProperty("stck_oprc")
        private String stckOprc;

        @JsonProperty("stck_hgpr")
        private String stckHgpr;

        @JsonProperty("stck_lwpr")
        private String stckLwpr;

        @JsonProperty("rprs_mrkt_kor_name")
        private String rprsMrktKorName;

        public String getStckPrpr() {
            return stckPrpr;
        }

        public void setStckPrpr(String stckPrpr) {
            this.stckPrpr = stckPrpr;
        }

        public String getPrdyVrss() {
            return prdyVrss;
        }

        public void setPrdyVrss(String prdyVrss) {
            this.prdyVrss = prdyVrss;
        }

        public String getPrdyVrssSign() {
            return prdyVrssSign;
        }

        public void setPrdyVrssSign(String prdyVrssSign) {
            this.prdyVrssSign = prdyVrssSign;
        }

        public String getPrdyCtrt() {
            return prdyCtrt;
        }

        public void setPrdyCtrt(String prdyCtrt) {
            this.prdyCtrt = prdyCtrt;
        }

        public String getAcmlVol() {
            return acmlVol;
        }

        public void setAcmlVol(String acmlVol) {
            this.acmlVol = acmlVol;
        }

        public String getStckOprc() {
            return stckOprc;
        }

        public void setStckOprc(String stckOprc) {
            this.stckOprc = stckOprc;
        }

        public String getStckHgpr() {
            return stckHgpr;
        }

        public void setStckHgpr(String stckHgpr) {
            this.stckHgpr = stckHgpr;
        }

        public String getStckLwpr() {
            return stckLwpr;
        }

        public void setStckLwpr(String stckLwpr) {
            this.stckLwpr = stckLwpr;
        }

        public String getRprsMrktKorName() {
            return rprsMrktKorName;
        }

        public void setRprsMrktKorName(String rprsMrktKorName) {
            this.rprsMrktKorName = rprsMrktKorName;
        }
    }
}

