package com.mrdabak.dinnerservice.controller;

import com.mrdabak.dinnerservice.dto.AuthRequest;
import com.mrdabak.dinnerservice.dto.AuthResponse;
import com.mrdabak.dinnerservice.model.User;
import com.mrdabak.dinnerservice.repository.UserRepository;
import com.mrdabak.dinnerservice.service.AuthService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public AuthController(AuthService authService, UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.authService = authService;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @PostMapping("/register")
    public ResponseEntity<?> register(@Valid @RequestBody AuthRequest request) {
        try {
            AuthResponse response = authService.register(request);
            return ResponseEntity.status(HttpStatus.CREATED).body(response);
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody AuthRequest request) {
        try {
            AuthResponse response = authService.login(request);
            return ResponseEntity.ok(response);
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/change-password")
    public ResponseEntity<?> changePassword(@RequestBody Map<String, String> request, Authentication authentication) {
        try {
            if (authentication == null || authentication.getName() == null) {
                return ResponseEntity.status(401).body(Map.of("error", "Authentication required"));
            }

            Long userId = Long.parseLong(authentication.getName());
            User user = userRepository.findById(userId)
                    .orElseThrow(() -> new RuntimeException("User not found"));

            String currentPassword = request.get("currentPassword");
            String newPassword = request.get("newPassword");

            if (currentPassword == null || newPassword == null) {
                return ResponseEntity.badRequest().body(Map.of("error", "Current password and new password are required"));
            }

            if (!passwordEncoder.matches(currentPassword, user.getPassword())) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Current password is incorrect"));
            }

            user.setPassword(passwordEncoder.encode(newPassword));
            userRepository.save(user);

            return ResponseEntity.ok(Map.of("message", "Password changed successfully"));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PutMapping("/update-address")
    public ResponseEntity<?> updateAddress(@RequestBody Map<String, String> request, Authentication authentication) {
        try {
            if (authentication == null || authentication.getName() == null) {
                return ResponseEntity.status(401).body(Map.of("error", "Authentication required"));
            }

            Long userId = Long.parseLong(authentication.getName());
            User user = userRepository.findById(userId)
                    .orElseThrow(() -> new RuntimeException("User not found"));

            String address = request.get("address");
            if (address == null) {
                return ResponseEntity.badRequest().body(Map.of("error", "Address is required"));
            }

            user.setAddress(address);
            userRepository.save(user);

            return ResponseEntity.ok(Map.of("message", "Address updated successfully", "address", address));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PutMapping("/update-profile")
    public ResponseEntity<?> updateProfile(@RequestBody Map<String, String> request, Authentication authentication) {
        try {
            if (authentication == null || authentication.getName() == null) {
                return ResponseEntity.status(401).body(Map.of("error", "Authentication required"));
            }

            Long userId = Long.parseLong(authentication.getName());
            User user = userRepository.findById(userId)
                    .orElseThrow(() -> new RuntimeException("User not found"));

            String name = request.get("name");
            String phone = request.get("phone");
            
            if (name != null) {
                user.setName(name);
            }
            if (phone != null) {
                user.setPhone(phone);
            }
            
            userRepository.save(user);

            return ResponseEntity.ok(Map.of("message", "Profile updated successfully"));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PutMapping("/update-card")
    public ResponseEntity<?> updateCard(@RequestBody Map<String, String> request, Authentication authentication) {
        try {
            if (authentication == null || authentication.getName() == null) {
                return ResponseEntity.status(401).body(Map.of("error", "Authentication required"));
            }

            Long userId = Long.parseLong(authentication.getName());
            User user = userRepository.findById(userId)
                    .orElseThrow(() -> new RuntimeException("User not found"));

            String cardNumber = request.get("cardNumber");
            String cardExpiry = request.get("cardExpiry");
            String cardCvv = request.get("cardCvv");
            String cardHolderName = request.get("cardHolderName");
            
            if (cardNumber != null) {
                user.setCardNumber(cardNumber);
            }
            if (cardExpiry != null) {
                user.setCardExpiry(cardExpiry);
            }
            if (cardCvv != null) {
                user.setCardCvv(cardCvv);
            }
            if (cardHolderName != null) {
                user.setCardHolderName(cardHolderName);
            }
            
            userRepository.save(user);

            return ResponseEntity.ok(Map.of("message", "Card information updated successfully"));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/me")
    public ResponseEntity<?> getCurrentUser(Authentication authentication) {
        try {
            if (authentication == null || authentication.getName() == null) {
                return ResponseEntity.status(401).body(Map.of("error", "Authentication required"));
            }

            Long userId = Long.parseLong(authentication.getName());
            User user = userRepository.findById(userId)
                    .orElseThrow(() -> new RuntimeException("User not found"));

            Map<String, Object> userMap = new java.util.HashMap<>();
            userMap.put("id", user.getId());
            userMap.put("email", user.getEmail());
            userMap.put("name", user.getName());
            userMap.put("address", user.getAddress());
            userMap.put("phone", user.getPhone());
            userMap.put("role", user.getRole());
            userMap.put("approvalStatus", user.getApprovalStatus());
            userMap.put("consentName", Boolean.TRUE.equals(user.getConsentName()));
            userMap.put("consentAddress", Boolean.TRUE.equals(user.getConsentAddress()));
            userMap.put("consentPhone", Boolean.TRUE.equals(user.getConsentPhone()));
            userMap.put("loyaltyConsent", Boolean.TRUE.equals(user.getLoyaltyConsent()));
            // 카드 정보는 마지막 4자리만 반환 (보안)
            if (user.getCardNumber() != null && user.getCardNumber().length() > 4) {
                userMap.put("cardNumber", "****-****-****-" + user.getCardNumber().substring(user.getCardNumber().length() - 4));
            } else {
                userMap.put("cardNumber", user.getCardNumber());
            }
            userMap.put("hasCard", user.getCardNumber() != null && !user.getCardNumber().isEmpty());

            return ResponseEntity.ok(userMap);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/verify-password")
    public ResponseEntity<?> verifyPassword(@RequestBody Map<String, String> request, Authentication authentication) {
        try {
            if (authentication == null || authentication.getName() == null) {
                return ResponseEntity.status(401).body(Map.of("error", "Authentication required"));
            }

            Long userId = Long.parseLong(authentication.getName());
            User user = userRepository.findById(userId)
                    .orElseThrow(() -> new RuntimeException("User not found"));

            String password = request.get("password");
            if (password == null) {
                return ResponseEntity.badRequest().body(Map.of("error", "Password is required"));
            }

            if (!passwordEncoder.matches(password, user.getPassword())) {
                return ResponseEntity.status(401).body(Map.of("error", "비밀번호가 올바르지 않습니다"));
            }

            return ResponseEntity.ok(Map.of("message", "Password verified"));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/user/{email}/security-question")
    public ResponseEntity<?> getSecurityQuestion(@PathVariable String email) {
        try {
            User user = userRepository.findByEmail(email)
                    .orElseThrow(() -> new RuntimeException("존재하지 않는 계정입니다"));

            return ResponseEntity.ok(Map.of("securityQuestion", user.getSecurityQuestion() != null ? user.getSecurityQuestion() : ""));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", "오류가 발생했습니다"));
        }
    }

    @PostMapping("/forgot-password")
    public ResponseEntity<?> forgotPassword(@RequestBody Map<String, String> request) {
        try {
            String email = request.get("email");
            String securityQuestion = request.get("securityQuestion");
            String securityAnswer = request.get("securityAnswer");
            String newPassword = request.get("newPassword");

            if (email == null || securityQuestion == null || securityAnswer == null || newPassword == null) {
                return ResponseEntity.badRequest().body(Map.of("error", "모든 필드를 입력해주세요"));
            }
            if (newPassword.length() < 6) {
                return ResponseEntity.badRequest().body(Map.of("error", "새 비밀번호는 6자 이상이어야 합니다."));
            }

            authService.forgotPassword(email, securityQuestion, securityAnswer, newPassword);
            return ResponseEntity.ok(Map.of("message", "Password reset successfully"));
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", "비밀번호 재설정 중 오류가 발생했습니다."));
        }
    }

    @PostMapping("/reset-password")
    public ResponseEntity<?> resetPassword(@RequestBody Map<String, String> request) {
        try {
            String email = request.get("email");
            String securityAnswer = request.get("securityAnswer");
            String newPassword = request.get("newPassword");

            if (email == null || securityAnswer == null || newPassword == null) {
                return ResponseEntity.badRequest().body(Map.of("error", "모든 필드를 입력해주세요"));
            }

            User user = userRepository.findByEmail(email)
                    .orElseThrow(() -> new RuntimeException("존재하지 않는 계정입니다"));

            if (user.getSecurityAnswer() == null || !user.getSecurityAnswer().equals(securityAnswer)) {
                return ResponseEntity.status(401).body(Map.of("error", "보안 질문 답변이 올바르지 않습니다"));
            }

            user.setPassword(passwordEncoder.encode(newPassword));
            userRepository.save(user);

            return ResponseEntity.ok(Map.of("message", "비밀번호가 변경되었습니다"));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", "오류가 발생했습니다"));
        }
    }

    @PatchMapping("/me/consent")
    public ResponseEntity<?> updateConsent(@RequestBody Map<String, Object> request, Authentication authentication) {
        try {
            if (authentication == null || authentication.getName() == null) {
                return ResponseEntity.status(401).body(Map.of("error", "Authentication required"));
            }

            Long userId = Long.parseLong(authentication.getName());
            User user = userRepository.findById(userId)
                    .orElseThrow(() -> new RuntimeException("User not found"));

            // 개인정보 동의 현황 업데이트
            if (request.containsKey("consentName")) {
                user.setConsentName(Boolean.TRUE.equals(request.get("consentName")));
            }
            if (request.containsKey("consentAddress")) {
                user.setConsentAddress(Boolean.TRUE.equals(request.get("consentAddress")));
            }
            if (request.containsKey("consentPhone")) {
                user.setConsentPhone(Boolean.TRUE.equals(request.get("consentPhone")));
            }
            if (request.containsKey("loyaltyConsent")) {
                user.setLoyaltyConsent(Boolean.TRUE.equals(request.get("loyaltyConsent")));
            }

            userRepository.save(user);

            Map<String, Object> response = new java.util.HashMap<>();
            response.put("message", "개인정보 동의 현황이 업데이트되었습니다.");
            response.put("consentName", Boolean.TRUE.equals(user.getConsentName()));
            response.put("consentAddress", Boolean.TRUE.equals(user.getConsentAddress()));
            response.put("consentPhone", Boolean.TRUE.equals(user.getConsentPhone()));
            response.put("loyaltyConsent", Boolean.TRUE.equals(user.getLoyaltyConsent()));

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}

