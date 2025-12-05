package com.mrdabak.dinnerservice.controller;

import com.mrdabak.dinnerservice.model.User;
import com.mrdabak.dinnerservice.model.Order;
import com.mrdabak.dinnerservice.repository.UserRepository;
import com.mrdabak.dinnerservice.repository.order.OrderRepository;
import com.mrdabak.dinnerservice.voice.VoiceOrderException;
import com.mrdabak.dinnerservice.voice.dto.VoiceMessageDto;
import com.mrdabak.dinnerservice.voice.dto.VoiceOrderConfirmRequest;
import com.mrdabak.dinnerservice.voice.dto.VoiceOrderConfirmResponse;
import com.mrdabak.dinnerservice.voice.dto.VoiceOrderStartResponse;
import com.mrdabak.dinnerservice.voice.dto.VoiceOrderSummaryDto;
import com.mrdabak.dinnerservice.voice.dto.VoiceUtteranceRequest;
import com.mrdabak.dinnerservice.voice.dto.VoiceUtteranceResponse;
import com.mrdabak.dinnerservice.voice.model.VoiceConversationMessage;
import com.mrdabak.dinnerservice.voice.model.VoiceOrderSession;
import com.mrdabak.dinnerservice.voice.service.VoiceConversationResult;
import com.mrdabak.dinnerservice.voice.service.VoiceConversationService;
import com.mrdabak.dinnerservice.voice.service.VoiceOrderCheckoutService;
import com.mrdabak.dinnerservice.voice.service.VoiceOrderSessionService;
import com.mrdabak.dinnerservice.voice.service.VoiceOrderSummaryMapper;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/voice-orders")
public class VoiceOrderController {

    private final UserRepository userRepository;
    private final OrderRepository orderRepository;
    private final VoiceConversationService conversationService;
    private final VoiceOrderSummaryMapper summaryMapper;
    private final VoiceOrderSessionService sessionService;
    private final VoiceOrderCheckoutService checkoutService;
    private final PasswordEncoder passwordEncoder;

    public VoiceOrderController(UserRepository userRepository,
                                OrderRepository orderRepository,
                                VoiceConversationService conversationService,
                                VoiceOrderSummaryMapper summaryMapper,
                                VoiceOrderSessionService sessionService,
                                VoiceOrderCheckoutService checkoutService,
                                PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.orderRepository = orderRepository;
        this.conversationService = conversationService;
        this.summaryMapper = summaryMapper;
        this.sessionService = sessionService;
        this.checkoutService = checkoutService;
        this.passwordEncoder = passwordEncoder;
    }

    @PostMapping("/start")
    public ResponseEntity<VoiceOrderStartResponse> start(Authentication authentication) {
        User user = resolveUser(authentication);
        VoiceConversationResult result = conversationService.startSession(user);
        VoiceOrderSummaryDto summary = summaryMapper.toSummary(result.session());
        return ResponseEntity.ok(new VoiceOrderStartResponse(
                result.session().getSessionId(),
                toMessageDtos(result.session().getVisibleMessages()),
                summary));
    }

    @PostMapping("/utterance")
    public ResponseEntity<VoiceUtteranceResponse> utterance(
            Authentication authentication,
            @Valid @RequestBody VoiceUtteranceRequest request) {
        User user = resolveUser(authentication);
        VoiceOrderSession session = sessionService.requireSession(request.getSessionId());
        ensureOwner(session, user.getId());

        VoiceConversationResult result = conversationService.handleUtterance(
                request.getSessionId(),
                request.getUserText(),
                true);
        VoiceOrderSummaryDto summary = summaryMapper.toSummary(result.session());

        return ResponseEntity.ok(new VoiceUtteranceResponse(
                result.session().getSessionId(),
                toDto(result.userMessage()),
                toDto(result.agentMessage()),
                summary,
                summary.isReadyForConfirmation()));
    }

    @PostMapping("/confirm")
    public ResponseEntity<VoiceOrderConfirmResponse> confirm(
            Authentication authentication,
            @Valid @RequestBody VoiceOrderConfirmRequest request) {
        User user = resolveUser(authentication);
        VoiceOrderSession session = sessionService.requireSession(request.getSessionId());
        ensureOwner(session, user.getId());
        
        // 비밀번호 검증
        if (request.getPassword() == null || request.getPassword().isBlank()) {
            throw new VoiceOrderException("비밀번호를 입력해주세요.");
        }
        
        // 비밀번호 확인
        if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            throw new VoiceOrderException("비밀번호가 올바르지 않습니다.");
        }
        
        // 비밀번호 검증 후 일반 주문과 동일하게 처리
        var order = checkoutService.finalizeVoiceOrder(session);
        
        // 주문 완료 후 주문 정보를 포함한 요약 생성 (즉시 반영)
        VoiceOrderSummaryDto summary = summaryMapper.toSummaryWithOrder(session, order.getId(), order.getTotalPrice());

        // 할인 정보 확인
        List<Order> previousOrders = 
                orderRepository.findByUserIdOrderByCreatedAtDesc(user.getId());
        long deliveredOrders = previousOrders.stream()
                .filter(o -> "delivered".equalsIgnoreCase(o.getStatus()))
                .count();
        boolean allConsentsGiven = Boolean.TRUE.equals(user.getConsentName()) 
                && Boolean.TRUE.equals(user.getConsentAddress()) 
                && Boolean.TRUE.equals(user.getConsentPhone());
        boolean loyaltyEligible = Boolean.TRUE.equals(user.getLoyaltyConsent()) 
                && allConsentsGiven 
                && deliveredOrders >= 4;
        
        String confirmation;
        Integer originalPrice = null;
        Integer discountAmount = null;
        if (loyaltyEligible) {
            // 원래 가격 계산 (할인 전)
            originalPrice = (int) Math.round(order.getTotalPrice() / 0.9);
            discountAmount = originalPrice - order.getTotalPrice();
            confirmation = "주문이 완료되었습니다. 주문 번호 #%d, 10%% 할인 혜택이 적용되어 총 금액 %s원입니다."
                    .formatted(order.getId(), order.getTotalPrice());
        } else {
            confirmation = "주문이 완료되었습니다. 주문 번호 #%d, 총 금액 %s원입니다."
                    .formatted(order.getId(), order.getTotalPrice());
        }

        return ResponseEntity.ok(new VoiceOrderConfirmResponse(
                session.getSessionId(),
                order.getId(),
                order.getTotalPrice(),
                summary,
                confirmation,
                loyaltyEligible,
                originalPrice,
                discountAmount,
                loyaltyEligible ? 10 : null));
    }

    private User resolveUser(Authentication authentication) {
        if (authentication == null || authentication.getName() == null) {
            throw new VoiceOrderException("로그인이 필요합니다.");
        }
        Long userId = Long.parseLong(authentication.getName());
        return userRepository.findById(userId)
                .orElseThrow(() -> new VoiceOrderException("사용자 정보를 찾을 수 없습니다."));
    }

    private void ensureOwner(VoiceOrderSession session, Long userId) {
        if (!session.getUserId().equals(userId)) {
            throw new VoiceOrderException("세션이 만료되었거나 다른 사용자에게 속해 있습니다.");
        }
    }

    private List<VoiceMessageDto> toMessageDtos(List<VoiceConversationMessage> messages) {
        return messages.stream().map(this::toDto).toList();
    }

    private VoiceMessageDto toDto(VoiceConversationMessage message) {
        return new VoiceMessageDto(
                message.getId(),
                message.getRole(),
                message.getContent(),
                message.getTimestamp().toString());
    }
}


