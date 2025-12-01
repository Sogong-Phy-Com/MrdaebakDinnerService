package com.mrdabak.dinnerservice.voice.service;

import com.mrdabak.dinnerservice.voice.model.VoiceOrderItem;
import com.mrdabak.dinnerservice.voice.model.VoiceOrderState;
import com.mrdabak.dinnerservice.voice.util.DomainVocabularyNormalizer;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@Service
public class VoiceOrderStateMerger {

    private static final List<DateTimeFormatter> SUPPORTED_DATETIME_FORMATS = List.of(
            DateTimeFormatter.ISO_LOCAL_DATE_TIME,
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"),
            DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm"));

    private final DomainVocabularyNormalizer normalizer;

    public VoiceOrderStateMerger(DomainVocabularyNormalizer normalizer) {
        this.normalizer = normalizer;
    }

    public void merge(VoiceOrderState current, VoiceOrderState incoming) {
        if (incoming == null) {
            return;
        }

        Optional.ofNullable(incoming.getDinnerType())
                .map(normalizer::normalizeDinnerType)
                .orElseGet(() -> Optional.ofNullable(incoming.getDinnerType()))
                .ifPresent(token -> current.setDinnerType(token));

        Optional.ofNullable(incoming.getServingStyle())
                .map(normalizer::normalizeServingStyle)
                .orElseGet(() -> Optional.ofNullable(incoming.getServingStyle()))
                .ifPresent(style -> current.setServingStyle(style.toLowerCase(Locale.ROOT)));

        if (incoming.getMenuAdjustments() != null && !incoming.getMenuAdjustments().isEmpty()) {
            // 기존 메뉴 조정사항을 가져오거나 새로 생성
            List<VoiceOrderItem> existingAdjustments = current.getMenuAdjustments() != null 
                    ? new ArrayList<>(current.getMenuAdjustments()) 
                    : new ArrayList<>();
            
            // 기존 항목들을 맵으로 변환 (key 또는 name으로 인덱싱)
            Map<String, VoiceOrderItem> adjustmentMap = new LinkedHashMap<>();
            for (VoiceOrderItem existing : existingAdjustments) {
                String key = existing.getKey() != null ? existing.getKey() : existing.getName();
                if (key != null) {
                    adjustmentMap.put(key, existing);
                }
            }
            
            // 새로운 조정사항을 병합
            for (VoiceOrderItem item : incoming.getMenuAdjustments()) {
                if (item == null) continue;
                
                String itemKey = normalizer.normalizeMenuItemKey(
                        Optional.ofNullable(item.getKey()).orElse(item.getName()))
                        .orElse(item.getKey() != null ? item.getKey() : item.getName());
                
                if (itemKey == null) continue;
                
                VoiceOrderItem existing = adjustmentMap.get(itemKey);
                
                // action이 "add" 또는 "increase"인 경우 기존 수량에 추가
                if (item.getAction() != null && 
                    (item.getAction().toLowerCase().contains("add") || 
                     item.getAction().toLowerCase().contains("increase") ||
                     item.getAction().toLowerCase().contains("추가") ||
                     item.getAction().toLowerCase().contains("증가"))) {
                    int currentQuantity = existing != null && existing.getQuantity() != null 
                            ? existing.getQuantity() 
                            : 0;
                    int addQuantity = item.getQuantity() != null ? item.getQuantity() : 1;
                    VoiceOrderItem newItem = new VoiceOrderItem();
                    newItem.setKey(itemKey);
                    newItem.setName(item.getName());
                    newItem.setQuantity(currentQuantity + addQuantity);
                    newItem.setAction(item.getAction());
                    adjustmentMap.put(itemKey, newItem);
                } else {
                    // action이 없거나 "set", "change"인 경우 수량을 직접 설정
                    VoiceOrderItem clone = new VoiceOrderItem();
                    clone.setQuantity(item.getQuantity());
                    clone.setAction(item.getAction());
                    clone.setName(item.getName());
                    clone.setKey(itemKey);
                    adjustmentMap.put(itemKey, clone);
                }
            }
            
            current.setMenuAdjustments(new ArrayList<>(adjustmentMap.values()));
        }

        if (incoming.getDeliveryDateTime() != null && !incoming.getDeliveryDateTime().isBlank()) {
            parseDateTime(incoming.getDeliveryDateTime()).ifPresent(dt -> {
                current.setDeliveryDate(dt.toLocalDate().toString());
                current.setDeliveryTime(dt.toLocalTime().withSecond(0).withNano(0).toString());
                current.setDeliveryDateTime(dt.toString());
            });
        } else {
            if (incoming.getDeliveryDate() != null) {
                current.setDeliveryDate(incoming.getDeliveryDate());
            }
            if (incoming.getDeliveryTime() != null) {
                current.setDeliveryTime(incoming.getDeliveryTime());
            }
        }

        if (incoming.getDeliveryAddress() != null && !incoming.getDeliveryAddress().isBlank()) {
            current.setDeliveryAddress(incoming.getDeliveryAddress().trim());
        }

        if (incoming.getContactPhone() != null) {
            current.setContactPhone(incoming.getContactPhone());
        }
        if (incoming.getContactName() != null) {
            current.setContactName(incoming.getContactName());
        }
        if (incoming.getSpecialRequests() != null) {
            current.setSpecialRequests(incoming.getSpecialRequests());
        }
        if (incoming.getReadyForConfirmation() != null) {
            current.setReadyForConfirmation(incoming.getReadyForConfirmation());
        }
        if (incoming.getFinalConfirmation() != null) {
            current.setFinalConfirmation(incoming.getFinalConfirmation());
        }
        if (incoming.getNeedsMoreInfo() != null && !incoming.getNeedsMoreInfo().isEmpty()) {
            current.setNeedsMoreInfo(incoming.getNeedsMoreInfo());
        }
    }

    private Optional<LocalDateTime> parseDateTime(String raw) {
        for (DateTimeFormatter formatter : SUPPORTED_DATETIME_FORMATS) {
            try {
                return Optional.of(LocalDateTime.parse(raw.trim(), formatter));
            } catch (Exception ignored) { }
        }
        return Optional.empty();
    }
}


