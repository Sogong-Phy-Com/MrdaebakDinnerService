package com.mrdabak.dinnerservice.voice.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class VoiceOrderConfirmResponse {
    private String sessionId;
    private Long orderId;
    private Integer totalPrice;
    private VoiceOrderSummaryDto summary;
    private String confirmationMessage;
    private Boolean loyalty_discount_applied;
    private Integer original_price;
    private Integer discount_amount;
    private Integer discount_percentage;
}


