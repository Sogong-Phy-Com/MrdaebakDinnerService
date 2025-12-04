package com.mrdabak.dinnerservice.voice.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mrdabak.dinnerservice.voice.VoiceOrderException;
import com.mrdabak.dinnerservice.voice.client.VoiceAssistantResponse;
import com.mrdabak.dinnerservice.voice.model.VoiceOrderSession;
import com.mrdabak.dinnerservice.voice.model.VoiceOrderState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class VoiceOrderAssistantClient {

    private static final Logger LOGGER = LoggerFactory.getLogger(VoiceOrderAssistantClient.class);
    private static final Pattern JSON_BLOCK_PATTERN =
            Pattern.compile("order_state_json\\s*:?\\s*```json\\s*(\\{.*?})\\s*```", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final String apiUrl;
    private final String apiKey;
    private final String modelName;

    public VoiceOrderAssistantClient(RestTemplate restTemplate,
                                     ObjectMapper objectMapper,
                                     @Value("${voice.llm.api-url:https://api.groq.com/openai/v1/chat/completions}") String apiUrl,
                                     @Value("${voice.llm.api-key:}") String apiKey,
                                     @Value("${voice.llm.model:llama-3.1-8b-instant}") String modelName) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.apiUrl = apiUrl;
        this.apiKey = apiKey;
        this.modelName = modelName;
    }

    public VoiceAssistantResponse generateResponse(VoiceOrderSession session, String menuPromptBlock) {
        if (apiKey == null || apiKey.isEmpty()) {
            throw new VoiceOrderException("LLM API 키가 설정되지 않았습니다. 환경 변수 VOICE_LLM_API_KEY를 설정해주세요.");
        }
        
        try {
            List<Map<String, String>> messages = buildMessages(session, menuPromptBlock);
            
            Map<String, Object> payload = new HashMap<>();
            payload.put("model", modelName);
            payload.put("messages", messages);
            payload.put("temperature", 0.3); // 일관된 한국어 응답
            payload.put("max_tokens", 1000); // 응답 길이 제한

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(apiKey);

            ResponseEntity<String> response = restTemplate.postForEntity(
                    apiUrl, new HttpEntity<>(payload, headers), String.class);

            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                LOGGER.error("LLM API 응답 오류: Status={}, Body={}", 
                    response.getStatusCode(), response.getBody());
                throw new VoiceOrderException("대화형 AI 응답이 올바르지 않습니다.");
            }

            JsonNode root = objectMapper.readTree(response.getBody());
            
            // Groq API 응답 형식 파싱 (OpenAI 호환)
            JsonNode choices = root.path("choices");
            if (!choices.isArray() || choices.isEmpty()) {
                throw new VoiceOrderException("대화형 AI 응답에 선택지가 없습니다.");
            }
            
            JsonNode messageNode = choices.get(0).path("message").path("content");
            if (messageNode.isMissingNode() || !messageNode.isTextual()) {
                throw new VoiceOrderException("대화형 AI 응답을 읽을 수 없습니다.");
            }

            String content = messageNode.asText();
            return parseContent(content);
        } catch (VoiceOrderException e) {
            throw e;
        } catch (org.springframework.web.client.HttpClientErrorException e) {
            LOGGER.error("LLM API HTTP 오류: Status={}, Body={}", 
                e.getStatusCode(), e.getResponseBodyAsString(), e);
            
            if (e.getStatusCode().value() == 401) {
                throw new VoiceOrderException("LLM API 키가 유효하지 않습니다. API 키를 확인해주세요.", e);
            } else if (e.getStatusCode().value() == 429) {
                throw new VoiceOrderException("LLM API 사용량 제한에 도달했습니다. 잠시 후 다시 시도해주세요.", e);
            }
            throw new VoiceOrderException("LLM API 호출 중 오류가 발생했습니다.", e);
        } catch (org.springframework.web.client.HttpServerErrorException e) {
            LOGGER.error("LLM API 서버 오류: Status={}, Body={}", 
                e.getStatusCode(), e.getResponseBodyAsString(), e);
            throw new VoiceOrderException("LLM 서비스에 일시적인 문제가 있습니다. 잠시 후 다시 시도해주세요.", e);
        } catch (Exception e) {
            LOGGER.error("LLM 호출 실패: URL={}", apiUrl, e);
            throw new VoiceOrderException("상담원 응답을 생성하지 못했습니다. 잠시 후 다시 시도해주세요.", e);
        }
    }

    private List<Map<String, String>> buildMessages(VoiceOrderSession session, String menuPromptBlock) throws Exception {
        List<Map<String, String>> messages = new ArrayList<>();
        String systemPrompt = buildSystemPrompt(session, menuPromptBlock);
        
        // System prompt 추가
        Map<String, String> systemMsg = new HashMap<>();
        systemMsg.put("role", "system");
        systemMsg.put("content", systemPrompt);
        messages.add(systemMsg);
        
        // 대화 기록 추가
        session.getMessages().forEach(msg -> {
            Map<String, String> userMsg = new HashMap<>();
            userMsg.put("role", msg.getRole());
            userMsg.put("content", msg.getContent());
            messages.add(userMsg);
        });
        
        return messages;
    }

    private String buildSystemPrompt(VoiceOrderSession session, String menuPromptBlock) throws Exception {
        String stateJson = objectMapper.writeValueAsString(session.getCurrentState());
        return """
                당신은 미스터 대박 디너 서비스의 한국어 음성 주문 상담원입니다.
                
                [중요 언어 규칙]
                - 반드시 한국어만 사용하세요. 영어, 중국어, 일본어 등 다른 언어는 절대 사용하지 마세요.
                - 모든 응답은 한국어로만 작성하세요. 한국어가 아닌 언어가 포함되면 안 됩니다.
                - 도메인 용어(발렌타인, 프렌치, 샴페인 등)도 한국어로만 표현하세요.
                
                [기본 규칙]
                - 항상 존댓말을 사용하고 고객 이름(%s)으로 호칭하세요.
                - 다룰 수 있는 주제: 디너 설명, 추천, 주문 변경, 결제, 배달 안내.
                - 도메인 외 질문은 정중하게 거절하고 다시 미스터 대박 디너 이야기로 이끌어 주세요.
                - 주문 단계: (1) 디너 선택 -> (2) 서빙 스타일 -> (3) 구성/수량 조정 -> (4) 날짜/시간 -> (5) 주소/연락처 -> (6) 최종 확인.
                
                [주문 확정 처리 규칙 - 매우 중요 - 반드시 준수]
                - 현재 주문 상태의 finalConfirmation이 true이면, 절대 확정을 다시 요청하지 마세요. 이미 확정된 상태입니다.
                - readyForConfirmation이 true이고 finalConfirmation이 false일 때만 한 번 확정을 요청하세요.
                - 고객이 다음 중 하나를 말하면 확정 의사로 간주하고 즉시 finalConfirmation을 true로 설정하세요:
                  * "확정", "주문 확정", "확정할게", "확정해줘", "확정하자", "확정해", "확정하겠습니다"
                  * "좋아", "네", "그래", "확인", "주문할게", "주문하자", "주문해줘", "주문해", "주문하겠습니다"
                  * "맞아", "그렇게 해줘", "그거로 해줘", "주문할게", "주문하자", "진행해줘"
                - 고객이 확정 의사를 표현하면:
                  * 반드시 finalConfirmation을 true로 설정하세요 (JSON에 포함).
                  * "알겠습니다. 주문을 확정하겠습니다." 또는 "주문 확정을 진행하겠습니다."라고 응답하세요.
                  * 절대 "확정하시겠어요?", "주문 확정을 하실래요?", "다시 한번 주문 확정을 하실래요?"라고 물어보지 마세요.
                  * 확정 의사가 표현되면 그 이후 모든 응답에서 확정 요청을 절대 반복하지 마세요.
                - 대화 기록을 확인하여 고객이 이미 확정 의사를 표현했다면, 더 이상 확정을 요청하지 마세요.
                
                [주문 요약 규칙 - 매우 중요]
                - 주문 요약을 말씀드릴 때는 반드시 기본 메뉴 구성도 포함하여 언급하세요.
                - 메뉴 카탈로그에서 각 디너의 "기본 구성"을 확인하고, 주문 요약 시 반드시 언급해야 합니다.
                - 예시 형식:
                  * "메뉴 구성: 에그 스크램블 x1, 베이컨 x1, 빵 x1, 스테이크 x1"
                  * "기본 메뉴: 와인 x1, 스테이크 x1" 등
                - 메뉴 조정이 없는 경우에도 기본 메뉴 구성은 반드시 언급해야 합니다.
                  * 잘못된 예: "메뉴 조정: 없음"
                  * 올바른 예: "메뉴 구성: 에그 스크램블 x1, 베이컨 x1, 빵 x1, 스테이크 x1"
                - 메뉴 조정이 있는 경우: "기본 구성 + 추가/변경 사항" 형식으로 말씀드리세요.
                - 주문 요약에서 "메뉴 구성" 또는 "기본 메뉴" 항목을 빼먹지 마세요.
                
                - 마지막 응답에서는 아래 형식을 반드시 지켜주세요:
                  assistant_message:
                  (고객에게 들려줄 멘트)

                  order_state_json:
                  ```json
                  {
                    "dinnerType": "VALENTINE|FRENCH|ENGLISH|CHAMPAGNE_FEAST",
                    "servingStyle": "simple|grand|deluxe",
                    "menuAdjustments": [{"item":"baguette","quantity":6}],
                    "deliveryDate": "YYYY-MM-DD",
                    "deliveryTime": "HH:mm",
                    "deliveryAddress": "...",
                    "contactPhone": "...",
                    "specialRequests": "...",
                    "readyForConfirmation": true|false,
                    "finalConfirmation": true|false,
                    "needsMoreInfo": ["deliveryAddress"],
                    "summary": "한 줄 요약"
                  }
                  ```
                - finalConfirmation: 고객이 "확정", "주문 확정", "좋아", "네" 등의 확정 의사를 표현했을 때 true로 설정 (필수 필드)
                - readyForConfirmation: 모든 필수 정보가 준비되었을 때 true로 설정
                - 고객이 확정 의사를 표현하면 반드시 finalConfirmation을 true로 설정하고, 더 이상 확정을 요청하지 마세요.
                - finalConfirmation이 true이면 절대 확정을 다시 요청하지 마세요. 이미 확정된 상태입니다.
                - menuAdjustments.item 값은 다음 키워드 중 하나만 사용: champagne, wine, coffee, steak, salad, eggs, bacon, bread, baguette.
                - 샴페인 축제 디너는 그랜드/디럭스만 허용됩니다.

                [메뉴 카탈로그]
                %s

                [현재 주문 상태 JSON]
                %s
                """.formatted(session.getCustomerName(), menuPromptBlock, stateJson);
    }

    private VoiceAssistantResponse parseContent(String content) {
        Matcher matcher = JSON_BLOCK_PATTERN.matcher(content);
        VoiceOrderState state = new VoiceOrderState();
        String assistantMessage = content;
        if (matcher.find()) {
            String json = matcher.group(1);
            assistantMessage = content.substring(0, matcher.start())
                    .replace("assistant_message:", "")
                    .trim();
            try {
                state = objectMapper.readValue(json, VoiceOrderState.class);
            } catch (Exception e) {
                LOGGER.warn("주문 상태 JSON 파싱 실패: {}", e.getMessage());
            }
        } else {
            assistantMessage = assistantMessage.replace("assistant_message:", "").trim();
        }
        
        // 한국어만 추출 (다른 언어 제거)
        assistantMessage = filterKoreanOnly(assistantMessage);
        
        return new VoiceAssistantResponse(assistantMessage, state, content);
    }
    
    /**
     * 외국어(중국어, 일본어 등) 섞임 제거 - 한국어 문장 유지
     */
    private String filterKoreanOnly(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        
        // JSON 블록이나 코드 블록은 그대로 유지
        StringBuilder result = new StringBuilder();
        boolean inJsonBlock = false;
        boolean inCodeBlock = false;
        int jsonDepth = 0;
        int codeBlockCount = 0;
        
        String[] lines = text.split("\n");
        
        for (String line : lines) {
            String trimmedLine = line.trim();
            
            // JSON 블록이나 코드 블록 체크
            if (trimmedLine.contains("```json") || trimmedLine.contains("```")) {
                codeBlockCount++;
                result.append(line).append("\n");
                continue;
            }
            
            if (codeBlockCount > 0 && codeBlockCount % 2 == 1) {
                // 코드 블록 내부
                result.append(line).append("\n");
                continue;
            }
            
            // JSON 블록 내부 체크
            for (char c : trimmedLine.toCharArray()) {
                if (c == '{' || c == '[') jsonDepth++;
                if (c == '}' || c == ']') jsonDepth--;
            }
            
            if (jsonDepth > 0) {
                // JSON 내부
                result.append(line).append("\n");
                continue;
            }
            
            // 한국어 비율 체크 (한국어가 30% 이상이면 문장 유지)
            int koreanCharCount = 0;
            int totalCharCount = 0;
            
            for (char c : trimmedLine.toCharArray()) {
                if (!Character.isWhitespace(c)) {
                    totalCharCount++;
                    // 한글 범위: 가(0xAC00) ~ 힣(0xD7AF)
                    if (c >= '\uAC00' && c <= '\uD7AF') {
                        koreanCharCount++;
                    }
                }
            }
            
            // 한국어 비율이 30% 이상이거나, 전체가 짧은 경우(20자 이하) 유지
            if (totalCharCount == 0 || 
                (koreanCharCount * 100.0 / totalCharCount) >= 30 ||
                trimmedLine.length() <= 20) {
                result.append(line).append("\n");
            } else {
                // 외국어가 많은 문장은 제거
                LOGGER.debug("외국어 문장 필터링: {}", trimmedLine);
            }
        }
        
        String filtered = result.toString().replaceAll("\n+", "\n").trim();
        
        // 필터링 후 너무 짧아지면 원본 반환 (최소 50%는 유지되어야 함)
        if (filtered.length() < text.length() * 0.5 && text.length() > 50) {
            LOGGER.warn("필터링 결과가 너무 짧음. 원본 반환. 원본: {}", text.substring(0, Math.min(100, text.length())));
            return text;
        }
        
        return filtered.isEmpty() ? text : filtered;
    }
}
