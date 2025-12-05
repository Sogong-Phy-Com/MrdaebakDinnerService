package com.mrdabak.dinnerservice.service;

import com.mrdabak.dinnerservice.dto.AuthRequest;
import com.mrdabak.dinnerservice.dto.AuthResponse;
import com.mrdabak.dinnerservice.dto.UserDto;
import com.mrdabak.dinnerservice.model.User;
import com.mrdabak.dinnerservice.repository.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder, JwtService jwtService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    public AuthResponse register(AuthRequest request) {
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new RuntimeException("User already exists");
        }

        // Validate role
        String role = request.getRole();
        if (role == null || role.isEmpty()) {
            role = "customer"; // Default to customer
        }
        if (!role.equals("customer") && !role.equals("employee") && !role.equals("admin")) {
            throw new RuntimeException("Invalid role. Must be customer, employee, or admin");
        }

        User user = new User();
        user.setEmail(request.getEmail());
        user.setPassword(passwordEncoder.encode(request.getPassword()));
        user.setName(request.getName());
        user.setAddress(request.getAddress());
        user.setPhone(request.getPhone());
        user.setRole(role);
        user.setSecurityQuestion(request.getSecurityQuestion());
        user.setSecurityAnswer(request.getSecurityAnswer());
        
        // 직원/관리자는 모든 개인정보 동의 자동 설정
        if (role.equals("employee") || role.equals("admin")) {
            user.setConsentName(true);
            user.setConsentAddress(true);
            user.setConsentPhone(true);
            user.setLoyaltyConsent(true);
        } else {
            user.setConsentName(Boolean.TRUE.equals(request.getConsentName()));
            user.setConsentAddress(Boolean.TRUE.equals(request.getConsentAddress()));
            user.setConsentPhone(Boolean.TRUE.equals(request.getConsentPhone()));
            user.setLoyaltyConsent(Boolean.TRUE.equals(request.getLoyaltyConsent()));
        }
        
        // Set approval status: customer is auto-approved, employee/admin need approval
        if (role.equals("customer")) {
            user.setApprovalStatus("approved");
        } else {
            user.setApprovalStatus("pending");
        }

        User savedUser = userRepository.save(user);
        
        // Only generate token if approved
        String token = null;
        if (savedUser.getApprovalStatus().equals("approved")) {
            token = jwtService.generateToken(savedUser.getId(), savedUser.getEmail(), savedUser.getRole());
        }

        String message = "User registered successfully";
        if (savedUser.getApprovalStatus().equals("pending")) {
            message = "회원가입이 완료되었습니다. 관리자 승인 후 로그인할 수 있습니다.";
        }
        
        return new AuthResponse(
                message,
                token,
                new UserDto(savedUser.getId(), savedUser.getEmail(), savedUser.getName(),
                        savedUser.getAddress(), savedUser.getPhone(), savedUser.getRole(), savedUser.getApprovalStatus())
        );
    }

    public AuthResponse login(AuthRequest request) {
        User user = userRepository.findByEmail(request.getEmail())
                .orElseThrow(() -> new RuntimeException("Invalid credentials"));

        if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            throw new RuntimeException("Invalid credentials");
        }

        // 승인 대기 상태의 직원/관리자도 토큰 발급 (프론트엔드에서 접근 제한)
        // 거부된 경우만 로그인 차단
        if (user.getApprovalStatus().equals("rejected")) {
            throw new RuntimeException("회원가입이 거부되었습니다. 관리자에게 문의하세요.");
        }

        String token = jwtService.generateToken(user.getId(), user.getEmail(), user.getRole());
        
        String message = "Login successful";
        if (user.getApprovalStatus().equals("pending")) {
            message = "승인 중입니다";
        }

        return new AuthResponse(
                message,
                token,
                new UserDto(user.getId(), user.getEmail(), user.getName(),
                        user.getAddress(), user.getPhone(), user.getRole(), user.getApprovalStatus())
        );
    }

    public void forgotPassword(String email, String securityQuestion, String securityAnswer, String newPassword) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("존재하지 않는 계정입니다."));

        if (user.getSecurityQuestion() == null || !user.getSecurityQuestion().equals(securityQuestion)) {
            throw new RuntimeException("보안 질문이 일치하지 않습니다.");
        }

        // 보안 답변은 평문으로 저장되어 있으므로 단순 문자열 비교
        if (user.getSecurityAnswer() == null || !user.getSecurityAnswer().equals(securityAnswer)) {
            throw new RuntimeException("보안 질문 답변이 올바르지 않습니다.");
        }

        user.setPassword(passwordEncoder.encode(newPassword));
        userRepository.save(user);
    }
}

