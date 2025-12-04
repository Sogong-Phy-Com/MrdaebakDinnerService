package com.mrdabak.dinnerservice.voice.service;

import com.mrdabak.dinnerservice.model.User;
import com.mrdabak.dinnerservice.voice.client.VoiceAssistantResponse;
import com.mrdabak.dinnerservice.voice.model.VoiceConversationMessage;
import com.mrdabak.dinnerservice.voice.model.VoiceOrderSession;
import com.mrdabak.dinnerservice.voice.model.VoiceOrderState;
import com.mrdabak.dinnerservice.voice.util.DomainVocabularyNormalizer;
import org.springframework.stereotype.Service;

@Service
public class VoiceConversationService {

    private final VoiceOrderSessionService sessionService;
    private final VoiceOrderAssistantClient assistantClient;
    private final VoiceOrderStateMerger stateMerger;
    private final DomainVocabularyNormalizer normalizer;
    private final VoiceMenuCatalogService menuCatalogService;

    public VoiceConversationService(VoiceOrderSessionService sessionService,
                                    VoiceOrderAssistantClient assistantClient,
                                    VoiceOrderStateMerger stateMerger,
                                    DomainVocabularyNormalizer normalizer,
                                    VoiceMenuCatalogService menuCatalogService) {
        this.sessionService = sessionService;
        this.assistantClient = assistantClient;
        this.stateMerger = stateMerger;
        this.normalizer = normalizer;
        this.menuCatalogService = menuCatalogService;
    }

    public VoiceConversationResult startSession(User user) {
        VoiceOrderSession session = sessionService.createSession(
                user.getId(), user.getName(), user.getAddress(), user.getPhone());
        String greetingPrompt = """
                고객 이름: %s
                상황: 고객이 음성 주문 페이지에 접속했습니다. 정중하게 인사하고 기념일/행사 여부를 물어보며 대화를 시작하세요.
                """.formatted(user.getName());
        return sendToAssistant(session, greetingPrompt, false);
    }

    public VoiceConversationResult handleUtterance(String sessionId, String userText, boolean visibleToClient) {
        VoiceOrderSession session = sessionService.requireSession(sessionId);
        String cleaned = normalizer.cleanupTranscript(userText);
        // JSON 필터링
        cleaned = filterJsonFromUserInput(cleaned);
        return sendToAssistant(session, cleaned, visibleToClient);
    }
    
    /**
     * 고객 입력에서 JSON 블록 제거 (안전하게)
     */
    private String filterJsonFromUserInput(String input) {
        if (input == null || input.isEmpty()) {
            return input;
        }
        
        // 코드 블록 형태의 JSON 제거
        input = input.replaceAll("```json[\\s\\S]*?```", "");
        input = input.replaceAll("```[\\s\\S]*?```", "");
        
        // 명확한 JSON 블록 패턴만 제거 (여러 줄에 걸친 경우)
        if (input.contains("{") && input.contains("}") && input.contains("\"") && 
            (input.contains("order_state_json") || input.contains("assistant_message"))) {
            // JSON 블록 전체 제거
            input = input.replaceAll("(?s)assistant_message\\s*:.*?order_state_json\\s*:.*?```json[\\s\\S]*?```", "");
            input = input.replaceAll("(?s)order_state_json\\s*:.*?```json[\\s\\S]*?```", "");
            input = input.replaceAll("(?i)order_state_json\\s*:?\\s*", "");
            input = input.replaceAll("(?i)assistant_message\\s*:?\\s*", "");
        }
        
        return input.trim();
    }
    
    /**
     * 주문 확정 의사 표현 감지 및 설정
     */
    private void detectAndSetConfirmationIntent(VoiceOrderState state, String userText) {
        if (userText == null || userText.isBlank() || state == null) {
            return;
        }
        
        String lowerText = userText.toLowerCase().trim();
        
        // 주문 확정 의사 표현 패턴 (더 포괄적으로)
        java.util.regex.Pattern confirmationPattern = java.util.regex.Pattern.compile(
            "(확정|주문\\s*확정|확정할게|확정해줘|확정하자|확정해|확정하겠|확정합니다|" +
            "좋아|네|그래|확인|주문할게|주문하자|주문해줘|주문해|주문하겠|주문합니다|" +
            "맞아|그렇게\\s*해줘|그거로\\s*해줘|주문\\s*할게|주문\\s*하자|" +
            "그대로|그걸로|주문\\s*확인|진행|해줘|할게|하겠)",
            java.util.regex.Pattern.CASE_INSENSITIVE
        );
        
        // readyForConfirmation이 true이거나 모든 필수 정보가 준비되었으면 확정 의사를 받아들임
        boolean isReady = Boolean.TRUE.equals(state.getReadyForConfirmation()) || state.isReadyForCheckout();
        if (confirmationPattern.matcher(lowerText).find() && isReady) {
            // 주문 확정 의사 표현이 감지되고 확정 준비가 된 경우
            state.setFinalConfirmation(true);
            // 확정 의사가 표현되면 readyForConfirmation도 true로 유지
            state.setReadyForConfirmation(true);
        }
    }

    private VoiceConversationResult sendToAssistant(VoiceOrderSession session, String userText, boolean visible) {
        VoiceConversationMessage userMessage = VoiceConversationMessage.of("user", userText, visible);
        session.addMessage(userMessage, sessionService.historyLimit());

        // 확정 의사 표현을 먼저 감지 (LLM 응답 전에)
        if (session.getCurrentState() == null) {
            session.setCurrentState(new VoiceOrderState());
        }
        boolean hadFinalConfirmation = Boolean.TRUE.equals(session.getCurrentState().getFinalConfirmation());
        detectAndSetConfirmationIntent(session.getCurrentState(), userText);

        VoiceAssistantResponse response = assistantClient.generateResponse(session, menuCatalogService.buildPromptBlock());
        VoiceConversationMessage agentMessage = VoiceConversationMessage.of("assistant", response.assistantMessage(), true);
        session.addMessage(agentMessage, sessionService.historyLimit());

        stateMerger.merge(session.getCurrentState(), response.orderState());
        
        // 확정 의사 표현이 감지되었거나 이전에 이미 확정되었으면 유지
        if (hadFinalConfirmation || Boolean.TRUE.equals(session.getCurrentState().getFinalConfirmation())) {
            session.getCurrentState().setFinalConfirmation(true);
            // 확정 의사가 있으면 readyForConfirmation도 true로 유지
            if (session.getCurrentState().isReadyForCheckout()) {
                session.getCurrentState().setReadyForConfirmation(true);
            }
        }
        
        // 주문 확정 의사 표현 다시 확인 (LLM이 덮어쓴 경우 대비)
        detectAndSetConfirmationIntent(session.getCurrentState(), userText);
        
        // 배달 주소나 전화번호가 없으면 회원 정보 사용
        VoiceOrderState state = session.getCurrentState();
        if (state.getDeliveryAddress() == null || state.getDeliveryAddress().isBlank()) {
            if (session.getCustomerDefaultAddress() != null && !session.getCustomerDefaultAddress().isBlank()) {
                state.setDeliveryAddress(session.getCustomerDefaultAddress());
            }
        }
        if (state.getContactPhone() == null || state.getContactPhone().isBlank()) {
            if (session.getCustomerPhone() != null && !session.getCustomerPhone().isBlank()) {
                state.setContactPhone(session.getCustomerPhone());
            }
        }
        
        return new VoiceConversationResult(session, userMessage, agentMessage, response);
    }
}


