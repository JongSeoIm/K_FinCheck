package com.kfincheck.client.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public class KisTokenRequest {

    @JsonProperty("grant_type")
    private final String grantType = "client_credentials";

    @JsonProperty("appkey")
    private final String appKey;

    @JsonProperty("appsecret")
    private final String appSecret;

    public KisTokenRequest(String appKey, String appSecret) {
        this.appKey = appKey;
        this.appSecret = appSecret;
    }

    public String getGrantType() {
        return grantType;
    }

    public String getAppKey() {
        return appKey;
    }

    public String getAppSecret() {
        return appSecret;
    }
}

