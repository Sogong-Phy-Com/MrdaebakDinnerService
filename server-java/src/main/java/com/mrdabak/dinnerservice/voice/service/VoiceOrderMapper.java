package com.mrdabak.dinnerservice.voice.service;

import com.mrdabak.dinnerservice.dto.OrderItemDto;
import com.mrdabak.dinnerservice.dto.OrderRequest;
import com.mrdabak.dinnerservice.voice.VoiceOrderException;
import com.mrdabak.dinnerservice.voice.model.VoiceOrderItem;
import com.mrdabak.dinnerservice.voice.model.VoiceOrderSession;
import com.mrdabak.dinnerservice.voice.model.VoiceOrderState;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class VoiceOrderMapper {

    private final VoiceMenuCatalogService menuCatalogService;

    public VoiceOrderMapper(VoiceMenuCatalogService menuCatalogService) {
        this.menuCatalogService = menuCatalogService;
    }

    public OrderRequest toOrderRequest(VoiceOrderSession session) {
        VoiceOrderState state = session.getCurrentState();
        if (!state.isReadyForCheckout()) {
            List<String> missing = new ArrayList<>();
            if (!state.hasDinnerSelection()) missing.add("디너 선택");
            if (!state.hasServingStyle()) missing.add("서빙 스타일");
            if (!state.hasDeliverySlot()) missing.add("배달 날짜/시간");
            if (!state.hasAddress()) missing.add("배달 주소");
            if (!state.hasContactPhone()) missing.add("연락처(전화번호)");
            throw new VoiceOrderException("주문 확정을 위해 필요한 정보가 부족합니다: " + String.join(", ", missing));
        }

        VoiceMenuCatalogService.DinnerDescriptor dinner = menuCatalogService.requireDinner(state.getDinnerType());
        validateServingStyle(state, dinner);

        String deliveryTimestamp = resolveDeliveryTimestamp(state);
        String address = state.getDeliveryAddress() != null
                ? state.getDeliveryAddress()
                : session.getCustomerDefaultAddress();
        if (address == null || address.isBlank()) {
            throw new VoiceOrderException("배달 주소를 확인할 수 없습니다.");
        }
        
        // 전화번호 검증
        String phone = state.getContactPhone() != null 
                ? state.getContactPhone() 
                : session.getCustomerPhone();
        if (phone == null || phone.isBlank()) {
            throw new VoiceOrderException("연락처(전화번호)를 확인할 수 없습니다.");
        }

        List<OrderItemDto> items = buildItems(state, dinner);
        if (items.isEmpty()) {
            throw new VoiceOrderException("주문 항목을 확인하지 못했습니다.");
        }

        OrderRequest request = new OrderRequest();
        request.setDinnerTypeId(dinner.id());
        request.setServingStyle(state.getServingStyle());
        request.setDeliveryTime(deliveryTimestamp);
        request.setDeliveryAddress(address);
        request.setItems(items);
        request.setPaymentMethod("card"); // 일반 주문과 동일하게 "card"로 설정
        return request;
    }

    private void validateServingStyle(VoiceOrderState state, VoiceMenuCatalogService.DinnerDescriptor dinner) {
        if (state.getServingStyle() == null) {
            throw new VoiceOrderException("서빙 스타일을 먼저 정해 주세요.");
        }
        if (!dinner.allowedServingStyles().contains(state.getServingStyle())) {
            throw new VoiceOrderException("선택한 디너와 서빙 스타일 조합이 허용되지 않습니다.");
        }
    }

    private List<OrderItemDto> buildItems(VoiceOrderState state, VoiceMenuCatalogService.DinnerDescriptor dinner) {
        Map<Long, Integer> quantities = new LinkedHashMap<>();

        // 기본 디너 메뉴 수량 설정
        menuCatalogService.getDefaultItems(dinner.id()).forEach(portion ->
                quantities.put(portion.menuItemId(), portion.quantity()));

        // 인분(portion) 배수 추출 및 적용
        int portionMultiplier = extractPortionMultiplier(state);
        if (portionMultiplier > 1) {
            // 모든 기본 수량에 인분 배수 적용
            quantities.replaceAll((k, v) -> v * portionMultiplier);
        }

        if (state.getMenuAdjustments() != null) {
            for (VoiceOrderItem adjustment : state.getMenuAdjustments()) {
                if (adjustment.getQuantity() == null) {
                    continue;
                }
                try {
                    VoiceMenuCatalogService.MenuItemPortion target = menuCatalogService.describeMenuItem(
                            adjustment.getKey() != null ? adjustment.getKey() : adjustment.getName());
                    
                    Long menuItemId = target.menuItemId();
                    Integer currentQuantity = quantities.getOrDefault(menuItemId, 0);
                    
                    // action이 "add", "increase", "추가", "증가"인 경우 기존 수량에 추가
                    if (adjustment.getAction() != null && 
                        (adjustment.getAction().toLowerCase().contains("add") || 
                         adjustment.getAction().toLowerCase().contains("increase") ||
                         adjustment.getAction().toLowerCase().contains("추가") ||
                         adjustment.getAction().toLowerCase().contains("증가"))) {
                        int addQuantity = adjustment.getQuantity() != null ? adjustment.getQuantity() : 1;
                        quantities.put(menuItemId, currentQuantity + addQuantity);
                    } else if (adjustment.getQuantity() <= 0) {
                        // 수량이 0 이하이면 제거
                        quantities.remove(menuItemId);
                    } else {
                        // action이 없거나 "set", "change"인 경우 수량을 직접 설정
                        // 인분 배수가 적용된 상태에서 추가 조정하므로, 인분 배수를 고려하여 설정
                        if (portionMultiplier > 1) {
                            quantities.put(menuItemId, adjustment.getQuantity());
                        } else {
                            quantities.put(menuItemId, adjustment.getQuantity());
                        }
                    }
                } catch (Exception e) {
                    // 메뉴 항목을 찾을 수 없는 경우 무시 (시스템 프롬프트에서 처리하도록)
                }
            }
        }

        return quantities.entrySet().stream()
                .map(entry -> new OrderItemDto(entry.getKey(), entry.getValue()))
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

    private String resolveDeliveryTimestamp(VoiceOrderState state) {
        if (state.getDeliveryDateTime() != null && !state.getDeliveryDateTime().isBlank()) {
            try {
                // 이미 ISO 형식인 경우 파싱하여 검증
                LocalDateTime deliveryDateTime = LocalDateTime.parse(state.getDeliveryDateTime(), DateTimeFormatter.ISO_LOCAL_DATE_TIME);
                
                // 과거 날짜/시간 검증 - 현재 시간 이후만 허용
                LocalDateTime now = LocalDateTime.now();
                if (deliveryDateTime.isBefore(now) || deliveryDateTime.isEqual(now)) {
                    throw new VoiceOrderException("과거 날짜/시간은 주문하실 수 없습니다. 현재 시간 이후의 날짜/시간을 선택해주세요.");
                }
                
                return state.getDeliveryDateTime();
            } catch (VoiceOrderException e) {
                throw e;
            } catch (Exception e) {
                // 파싱 실패 시 아래 로직으로 처리
            }
        }
        
        try {
            LocalDate date;
            String deliveryDate = state.getDeliveryDate();
            
            // 자연어 날짜 처리: "오늘", "내일", "모레" 등
            if (deliveryDate != null && !deliveryDate.isBlank()) {
                String dateLower = deliveryDate.toLowerCase().trim();
                LocalDate today = LocalDate.now();
                
                if (dateLower.contains("오늘") || dateLower.equals("today")) {
                    date = today;
                } else if (dateLower.contains("내일") || dateLower.contains("다음날") || dateLower.equals("tomorrow")) {
                    date = today.plusDays(1);
                } else if (dateLower.contains("모레") || dateLower.contains("이틀") || dateLower.equals("day after tomorrow")) {
                    date = today.plusDays(2);
                } else {
                    // 일반 날짜 파싱 시도
                    try {
                        date = LocalDate.parse(deliveryDate);
                    } catch (Exception e) {
                        throw new VoiceOrderException("배달 날짜를 확인할 수 없습니다. '오늘', '내일' 또는 날짜(YYYY-MM-DD)를 알려주세요.");
                    }
                }
            } else {
                throw new VoiceOrderException("배달 날짜를 확인할 수 없습니다. '오늘', '내일' 또는 날짜를 알려주세요.");
            }
            
            LocalTime time;
            String deliveryTime = state.getDeliveryTime();
            if (deliveryTime != null && !deliveryTime.isBlank()) {
                try {
                    time = LocalTime.parse(deliveryTime);
                } catch (Exception e) {
                    throw new VoiceOrderException("배달 시간을 확인할 수 없습니다. 시간(HH:mm 형식)을 알려주세요.");
                }
            } else {
                throw new VoiceOrderException("배달 시간을 확인할 수 없습니다. 시간을 알려주세요.");
            }
            
            LocalDateTime deliveryDateTime = LocalDateTime.of(date, time.withSecond(0).withNano(0));
            
            // 과거 날짜/시간 검증 - 현재 시간 이후만 허용
            LocalDateTime now = LocalDateTime.now();
            if (deliveryDateTime.isBefore(now) || deliveryDateTime.isEqual(now)) {
                throw new VoiceOrderException("과거 날짜/시간은 주문하실 수 없습니다. 현재 시간 이후의 날짜/시간을 선택해주세요.");
            }
            
            return deliveryDateTime.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        } catch (VoiceOrderException e) {
            throw e;
        } catch (Exception e) {
            throw new VoiceOrderException("배달 시간을 해석할 수 없습니다. 다시 알려주세요.");
        }
    }
}


