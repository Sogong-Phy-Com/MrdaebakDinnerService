package com.mrdabak.dinnerservice.util;

import com.mrdabak.dinnerservice.model.User;

/**
 * 개인정보 마스킹 유틸리티 클래스
 * 고객의 개인정보 공유 동의 여부에 따라 관리자에게 표시되는 정보를 마스킹 처리합니다.
 */
public class PrivacyMaskingUtil {
    
    public static final String MASKED_VALUE = "***";
    
    /**
     * 이름을 마스킹 처리합니다.
     * consentName이 false인 경우 마스킹 처리됩니다.
     */
    public static String maskName(User user) {
        if (user == null) {
            return MASKED_VALUE;
        }
        if (Boolean.TRUE.equals(user.getConsentName())) {
            return user.getName();
        }
        return MASKED_VALUE;
    }
    
    /**
     * 주소를 마스킹 처리합니다.
     * consentAddress가 false인 경우 마스킹 처리됩니다.
     */
    public static String maskAddress(User user) {
        if (user == null) {
            return MASKED_VALUE;
        }
        if (Boolean.TRUE.equals(user.getConsentAddress())) {
            return user.getAddress();
        }
        return MASKED_VALUE;
    }
    
    /**
     * 전화번호를 마스킹 처리합니다.
     * consentPhone이 false인 경우 마스킹 처리됩니다.
     */
    public static String maskPhone(User user) {
        if (user == null) {
            return MASKED_VALUE;
        }
        if (Boolean.TRUE.equals(user.getConsentPhone())) {
            return user.getPhone();
        }
        return MASKED_VALUE;
    }
    
    /**
     * 이메일은 동의 필드가 없으므로 그대로 반환합니다.
     */
    public static String maskEmail(User user) {
        if (user == null) {
            return "";
        }
        return user.getEmail();
    }
}

