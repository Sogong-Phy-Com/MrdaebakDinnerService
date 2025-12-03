package com.mrdabak.dinnerservice.controller;

import com.mrdabak.dinnerservice.model.User;
import com.mrdabak.dinnerservice.repository.UserRepository;
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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/voice-orders")
public class VoiceOrderController {

    private final UserRepository userRepository;
    private final VoiceConversationService conversationService;
    private final VoiceOrderSummaryMapper summaryMapper;
    private final VoiceOrderSessionService sessionService;
    private final VoiceOrderCheckoutService checkoutService;

    public VoiceOrderController(UserRepository userRepository,
                                VoiceConversationService conversationService,
                                VoiceOrderSummaryMapper summaryMapper,
                                VoiceOrderSessionService sessionService,
                                VoiceOrderCheckoutService checkoutService) {
        this.userRepository = userRepository;
        this.conversationService = conversationService;
        this.summaryMapper = summaryMapper;
        this.sessionService = sessionService;
        this.checkoutService = checkoutService;
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
        var order = checkoutService.finalizeVoiceOrder(session);
        VoiceOrderSummaryDto summary = summaryMapper.toSummary(session);

        String confirmation = "주문이 완료되었습니다. 주문 번호 #%d, 총 금액 %s원입니다."
                .formatted(order.getId(), order.getTotalPrice());

        return ResponseEntity.ok(new VoiceOrderConfirmResponse(
                session.getSessionId(),
                order.getId(),
                order.getTotalPrice(),
                summary,
                confirmation));
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


