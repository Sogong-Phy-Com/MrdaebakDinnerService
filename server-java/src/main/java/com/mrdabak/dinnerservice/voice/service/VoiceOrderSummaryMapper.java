package com.mrdabak.dinnerservice.voice.service;

import com.mrdabak.dinnerservice.voice.dto.VoiceOrderSummaryDto;
import com.mrdabak.dinnerservice.voice.model.VoiceOrderItem;
import com.mrdabak.dinnerservice.voice.model.VoiceOrderSession;
import com.mrdabak.dinnerservice.voice.model.VoiceOrderState;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class VoiceOrderSummaryMapper {

    private final VoiceMenuCatalogService menuCatalogService;

    public VoiceOrderSummaryMapper(VoiceMenuCatalogService menuCatalogService) {
        this.menuCatalogService = menuCatalogService;
    }

    public VoiceOrderSummaryDto toSummary(VoiceOrderSession session) {
        VoiceOrderState state = session.getCurrentState();
        VoiceOrderSummaryDto summary = new VoiceOrderSummaryDto();
        summary.setDinnerName(menuCatalogService.dinnerLabel(state.getDinnerType()));
        summary.setServingStyle(menuCatalogService.servingStyleLabel(state.getServingStyle()));
        summary.setItems(buildItems(state));
        summary.setDeliverySlot(buildDeliverySlot(state));
        summary.setDeliveryAddress(state.getDeliveryAddress());
        summary.setContactPhone(
                state.getContactPhone() != null ? state.getContactPhone() : session.getCustomerPhone());
        summary.setSpecialRequests(state.getSpecialRequests());
        summary.setReadyForConfirmation(state.isReadyForCheckout());
        summary.setFinalConfirmation(state.getFinalConfirmation());  // 주문 확정 의사 반영
        summary.setMissingFields(missingFields(state));
        
        // 주문 완료 후 주문 정보 반영
        if (session.isOrderPlaced() && session.getCreatedOrderId() != null) {
            summary.setOrderId(session.getCreatedOrderId());
        }
        
        return summary;
    }
    
    /**
     * 주문 완료 후 주문 정보를 포함한 요약 생성
     */
    public VoiceOrderSummaryDto toSummaryWithOrder(VoiceOrderSession session, Long orderId, Integer totalPrice) {
        VoiceOrderSummaryDto summary = toSummary(session);
        summary.setOrderId(orderId);
        summary.setTotalPrice(totalPrice);
        summary.setReadyForConfirmation(false); // 주문 완료 후에는 확정 불가
        return summary;
    }

    private List<VoiceOrderSummaryDto.SummaryItem> buildItems(VoiceOrderState state) {
        Map<String, Integer> quantities = new LinkedHashMap<>();
        Map<String, String> labels = new LinkedHashMap<>();

        if (state.getDinnerType() != null) {
            VoiceMenuCatalogService.DinnerDescriptor dinner = menuCatalogService.requireDinner(state.getDinnerType());
            menuCatalogService.getDefaultItems(dinner.id()).forEach(portion -> {
                quantities.put(portion.key(), portion.quantity());
                labels.put(portion.key(), portion.name());
            });
        }

        // 인분(portion) 배수 추출 및 적용
        int portionMultiplier = extractPortionMultiplier(state);
        if (portionMultiplier > 1) {
            // 모든 기본 수량에 인분 배수 적용
            quantities.replaceAll((k, v) -> v * portionMultiplier);
        }

        if (state.getMenuAdjustments() != null) {
            for (VoiceOrderItem item : state.getMenuAdjustments()) {
                if (item.getKey() == null) {
                    continue;
                }
                
                String itemKey = item.getKey();
                Integer itemQuantity = item.getQuantity();
                
                // 인분 정보가 포함된 경우 건너뛰기
                if (item.getName() != null && (item.getName().contains("인분") || item.getName().contains("명분"))) {
                    continue;
                }
                
                if (itemQuantity == null || itemQuantity <= 0) {
                    quantities.remove(itemKey);
                    continue;
                }
                
                // 기존 수량 가져오기 (기본 수량 또는 이미 설정된 수량)
                Integer currentQuantity = quantities.getOrDefault(itemKey, 0);
                
                // action이 "add", "increase", "추가", "증가"인 경우 기존 수량에 추가
                if (item.getAction() != null && 
                    (item.getAction().toLowerCase().contains("add") || 
                     item.getAction().toLowerCase().contains("increase") ||
                     item.getAction().toLowerCase().contains("추가") ||
                     item.getAction().toLowerCase().contains("증가"))) {
                    quantities.put(itemKey, currentQuantity + itemQuantity);
                } else {
                    // action이 없거나 "set", "change"인 경우 수량을 직접 설정
                    quantities.put(itemKey, itemQuantity);
                }
                
                if (item.getName() != null) {
                    labels.put(itemKey, item.getName());
                }
            }
        }

        return quantities.entrySet().stream()
                .filter(entry -> entry.getValue() != null && entry.getValue() > 0)
                .map(entry -> new VoiceOrderSummaryDto.SummaryItem(
                        labels.getOrDefault(entry.getKey(), entry.getKey()),
                        entry.getValue()))
                .toList();
    }
    
    /**
     * 인분(portion) 배수 추출 (예: "2인분", "2명분" → 2)
     */
    private int extractPortionMultiplier(VoiceOrderState state) {
        int multiplier = 1;
        
        // specialRequests에서 인분 정보 추출
        if (state.getSpecialRequests() != null && !state.getSpecialRequests().isBlank()) {
            String requests = state.getSpecialRequests().toLowerCase();
            java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("(\\d+)\\s*(?:인분|명분)");
            java.util.regex.Matcher matcher = pattern.matcher(requests);
            if (matcher.find()) {
                try {
                    multiplier = Integer.parseInt(matcher.group(1));
                } catch (Exception e) {
                    // 파싱 실패 시 1 유지
                }
            }
        }
        
        // menuAdjustments에서 인분 정보 추출
        if (state.getMenuAdjustments() != null) {
            for (VoiceOrderItem item : state.getMenuAdjustments()) {
                if (item.getName() != null) {
                    String name = item.getName().toLowerCase();
                    java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("(\\d+)\\s*(?:인분|명분)");
                    java.util.regex.Matcher matcher = pattern.matcher(name);
                    if (matcher.find()) {
                        try {
                            int found = Integer.parseInt(matcher.group(1));
                            if (found > multiplier) {
                                multiplier = found;
                            }
                        } catch (Exception e) {
                            // 파싱 실패 시 무시
                        }
                    }
                }
            }
        }
        
        return multiplier > 0 ? multiplier : 1;
    }

    private String buildDeliverySlot(VoiceOrderState state) {
        if (state.getDeliveryDate() != null && state.getDeliveryTime() != null) {
            return "%s %s".formatted(state.getDeliveryDate(), state.getDeliveryTime());
        }
        return state.getDeliveryDateTime();
    }

    private List<String> missingFields(VoiceOrderState state) {
        List<String> missing = new ArrayList<>();
        if (!state.hasDinnerSelection()) {
            missing.add("디너 선택");
        }
        if (!state.hasServingStyle()) {
            missing.add("서빙 스타일");
        }
        if (!state.hasDeliverySlot()) {
            missing.add("배달 날짜/시간");
        }
        if (!state.hasAddress()) {
            missing.add("배달 주소");
        }
        if (!state.hasContactPhone()) {
            missing.add("연락처(전화번호)");
        }
        return missing;
    }
}


