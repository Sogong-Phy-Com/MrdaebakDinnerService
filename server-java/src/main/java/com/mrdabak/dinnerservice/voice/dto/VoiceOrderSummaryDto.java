package com.mrdabak.dinnerservice.voice.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class VoiceOrderSummaryDto {
    private String dinnerName;
    private String servingStyle;
    private List<SummaryItem> items = new ArrayList<>();
    private String deliverySlot;
    private String deliveryAddress;
    private String contactPhone;
    private String specialRequests;
    private boolean readyForConfirmation;
    private Boolean finalConfirmation;  // 고객이 주문 확정 의사를 표현했는지
    private List<String> missingFields = new ArrayList<>();
    private Long orderId;  // 주문 완료 후 주문 ID
    private Integer totalPrice;  // 주문 완료 후 총 금액

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SummaryItem {
        private String name;
        private Integer quantity;
    }
}


