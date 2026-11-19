package com.kfincheck.client.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public class KisDailyChartResponse {

    @JsonProperty("rt_cd")
    private String rtCd;

    @JsonProperty("msg_cd")
    private String msgCd;

    @JsonProperty("msg1")
    private String msg1;

    @JsonProperty("output1")
    private Output1 output1;

    @JsonProperty("output2")
    private List<DailyCandleOutput> output2;

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

    public Output1 getOutput1() {
        return output1;
    }

    public void setOutput1(Output1 output1) {
        this.output1 = output1;
    }

    public List<DailyCandleOutput> getOutput2() {
        return output2;
    }

    public void setOutput2(List<DailyCandleOutput> output2) {
        this.output2 = output2;
    }

    public static class Output1 {
        @JsonProperty("hts_kor_isnm")
        private String htsKorIsnm;

        public String getHtsKorIsnm() {
            return htsKorIsnm;
        }

        public void setHtsKorIsnm(String htsKorIsnm) {
            this.htsKorIsnm = htsKorIsnm;
        }
    }

    public static class DailyCandleOutput {
        @JsonProperty("stck_bsop_date")
        private String stckBsopDate;

        @JsonProperty("stck_clpr")
        private String stckClpr;

        @JsonProperty("stck_oprc")
        private String stckOprc;

        @JsonProperty("stck_hgpr")
        private String stckHgpr;

        @JsonProperty("stck_lwpr")
        private String stckLwpr;

        @JsonProperty("acml_vol")
        private String acmlVol;

        public String getStckBsopDate() {
            return stckBsopDate;
        }

        public void setStckBsopDate(String stckBsopDate) {
            this.stckBsopDate = stckBsopDate;
        }

        public String getStckClpr() {
            return stckClpr;
        }

        public void setStckClpr(String stckClpr) {
            this.stckClpr = stckClpr;
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

        public String getAcmlVol() {
            return acmlVol;
        }

        public void setAcmlVol(String acmlVol) {
            this.acmlVol = acmlVol;
        }
    }
}

