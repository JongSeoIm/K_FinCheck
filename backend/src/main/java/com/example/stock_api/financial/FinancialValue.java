package com.example.stock_api.financial;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** financial_value 한 행. 금액이 없으면 0 이 아니라 null */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class FinancialValue {
    private String accountCode;
    private String accountName;
    private Long currentAmount;
    private Long previousAmount;
    private String currency;
    private String sourceAccountId;
    private String sourceAccountName;
    private String valueStatus;
}