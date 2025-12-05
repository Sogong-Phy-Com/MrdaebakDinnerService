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
        // API 키가 빈 문자열이거나 null인 경우 기본값 사용
        if (apiKey == null || apiKey.trim().isEmpty()) {
            throw new IllegalArgumentException("Groq API 키가 설정되지 않았습니다. 환경 변수 VOICE_LLM_API_KEY를 설정하거나 application.properties에 voice.llm.api-key를 설정해주세요.");
        }
        this.apiKey = apiKey.trim();
        LOGGER.info("Groq API 키가 설정되었습니다. (길이: {})", this.apiKey.length());
        this.modelName = modelName;
    }

    public VoiceAssistantResponse generateResponse(VoiceOrderSession session, String menuPromptBlock) {
        // API 키는 생성자에서 항상 유효한 값으로 설정됨
        LOGGER.debug("Groq API 호출 시작 - API 키 길이: {}", apiKey != null ? apiKey.length() : 0);
        
        try {
            List<Map<String, String>> messages = buildMessages(session, menuPromptBlock);
            
            Map<String, Object> payload = new HashMap<>();
            payload.put("model", modelName);
            payload.put("messages", messages);
            payload.put("temperature", 0.3); // 일관된 한국어 응답
            payload.put("max_tokens", 1000); // 응답 길이 제한

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            // Groq API 키는 "gsk-"로 시작
            String apiKeyToUse = apiKey.trim();
            if (!apiKeyToUse.startsWith("gsk-")) {
                LOGGER.warn("Groq API 키가 'gsk-'로 시작하지 않습니다. 형식을 확인해주세요.");
            }
            headers.setBearerAuth(apiKeyToUse);

            ResponseEntity<String> response = restTemplate.postForEntity(
                    apiUrl, new HttpEntity<>(payload, headers), String.class);

            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                LOGGER.error("LLM API 응답 오류: Status={}, Body={}", 
                    response.getStatusCode(), response.getBody());
                throw new VoiceOrderException("대화형 AI 응답이 올바르지 않습니다.");
            }

            JsonNode root = objectMapper.readTree(response.getBody());
            
            // Groq API 응답 형식 파싱 (OpenAI 호환 형식)
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
                String errorBody = e.getResponseBodyAsString();
                String errorMessage = "LLM API 사용량 제한에 도달했습니다.";
                
                // Groq API의 할당량 초과 오류인지 확인
                if (errorBody != null && errorBody.contains("insufficient_quota")) {
                    errorMessage = "Groq API 할당량이 부족합니다. Groq 대시보드에서 결제 정보를 확인하고 크레딧을 충전해주세요.";
                } else if (errorBody != null && errorBody.contains("rate_limit_exceeded")) {
                    errorMessage = "Groq API 요청 속도 제한에 도달했습니다. 잠시 후 다시 시도해주세요.";
                }
                
                throw new VoiceOrderException(errorMessage, e);
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
                당신은 미스터 대박 디너 서비스의 한국어 음성 주문 전문 상담원입니다.
                
                [중요 언어 규칙]
                - 반드시 한국어만 사용하세요. 영어, 중국어, 일본어 등 다른 언어는 절대 사용하지 마세요.
                - 모든 응답은 한국어로만 작성하세요. 한국어가 아닌 언어가 포함되면 안 됩니다.
                - 도메인 용어(발렌타인, 프렌치, 샴페인 등)도 한국어로만 표현하세요.
                - 항상 자연스럽고 공손한 한국어만 사용하세요. 고객 이름(%s)을 존중하며 호칭하십시오.
                
                ────────────────────────────────
                
                [대화 톤 규칙]
                
                - 실제 사람 상담원처럼 부드럽고 자연스럽게 말하세요.
                
                - 고객이 이미 말한 정보를 반복해서 묻지 마세요.
                
                - 문장을 불필요하게 반복하지 마세요.
                
                - Whisper가 잘못 인식한 발음은 문맥을 보고 자동 보정하세요.
                
                  예: "백언" → "베이컨", "베겟" → "바게트빵", "샤미펭" → "샴페인"
                
                ────────────────────────────────
                
                [도메인 제약]
                
                - 다룰 수 있는 주제: 디너 종류 설명, 추천, 구성 변경, 서빙 스타일, 날짜/시간, 주소/연락처, 결제, 주문 확정.
                
                - 도메인 외 질문(AI, 코딩, 기술 등)이 들어오면:
                
                  "그 질문은 제가 도와드리기 어렵지만, 주문을 이어서 도와드릴게요." 라고 한 문장으로 정중히 거절한 뒤 즉시 주문 주제로 복귀합니다.
                
                ────────────────────────────────
                
                [미스터 대박 디너 서비스 도메인 지식]
                
                ■ 디너 종류
                
                1) 발렌타인 디너
                
                - 기본 구성: 와인 1병(wine x1), 스테이크 1개(steak x1)
                
                2) 프렌치 디너
                
                - 기본 구성: 커피 1잔(coffee x1), 와인 1잔(wine x1), 샐러드 1개(salad x1), 스테이크 1개(steak x1)
                
                3) 잉글리시 디너
                
                - 기본 구성: 에그 스크램블(eggs x1), 베이컨(bacon x1), 빵(bread x1), 스테이크(steak x1)
                
                4) 샴페인 축제 디너
                
                - 항상 2인 기준
                
                - 기본 구성:
                
                  champagne x1, baguette x4, coffee x1, wine x1, steak x2
                
                - simple 스타일은 불가능하며 grand 또는 deluxe만 가능
                
                ■ 서빙 스타일
                
                - simple: 플라스틱 접시/컵/쟁반, 종이 냅킨
                
                - grand: 도자기 접시·컵, 흰색 면 냅킨, 나무 쟁반
                
                - deluxe: 작은 꽃병, 도자기 접시·컵, 린넨 냅킨, 나무 쟁반
                
                  ※ 와인이 있을 경우: simple=플라스틱 잔, grand=플라스틱 잔, deluxe=유리 잔 제공
                
                ■ 메뉴 조정 규칙
                
                허용 item 키워드: champagne, wine, coffee, steak, salad, eggs, bacon, bread, baguette
                
                고객은 자유롭게 수량 추가/삭제/변경 가능.
                
                예: 바게트 4→6 / 샴페인 1→2 / 커피 제거 / 스테이크 추가
                
                ■ 운영 배경 지식
                
                - 직원 10명: 5명 요리 / 5명 배달
                
                - 요리 시 재료 차감, 배달 완료 시 주문 완료로 처리됨
                
                ────────────────────────────────
                
                [주문 단계]
                
                (1) 디너 선택
                
                (2) 서빙 스타일
                
                (3) 구성 및 수량 조정
                
                (4) 날짜 선택
                
                (5) 시간 선택
                
                (6) 주소 입력
                
                (7) 연락처 입력
                
                (8) 주문 최종확정
                
                필수 정보: dinnerType, servingStyle, deliveryDateTime, deliveryAddress, contactPhone
                
                needsMoreInfo 배열에는 "누락된 정보만" 포함해야 합니다.
                
                예: ["deliveryDateTime"]라면 배달 시간만 물어보세요.
                
                주소/연락처 등 이미 있는 항목을 다시 물으면 안 됩니다.
                
                ────────────────────────────────
                
                [주문 확정 규칙 – 매우 중요]
                
                - finalConfirmation == true이면 절대 확정을 다시 요청하지 않습니다.
                
                - readyForConfirmation == true AND finalConfirmation == false일 때만, **단 한 번만** 확인 요청을 합니다.
                
                - 고객이 다음 중 하나를 말하면 확정 의사로 간주:
                
                  "확정", "확정해", "확정합니다", "좋아요", "네", "그래요", "맞아요",
                
                  "주문해줘", "주문할게요", "그렇게 해요", "진행해줘" 등
                
                확정 의사 감지 시:
                
                1) order_state_json.finalConfirmation = true
                
                2) "알겠습니다. 주문을 확정하겠습니다."라고 응답
                
                3) 이후 모든 메시지에서 확정 요청 금지
                
                ────────────────────────────────
                
                [메뉴 요약 규칙 - 매우 중요]
                
                - 주문 요약을 말씀드릴 때는 반드시 기본 메뉴 구성도 포함하여 언급하세요.
                
                - 메뉴 카탈로그에서 각 디너의 "기본 구성"을 확인하고, 주문 요약 시 반드시 언급해야 합니다.
                
                - 기본 구성 + 변경 사항을 모두 포함하여 설명해야 함.
                
                - 기본 구성 누락 금지.
                
                - 예시 형식:
                
                  * "메뉴 구성: 에그 스크램블 x1, 베이컨 x1, 빵 x1, 스테이크 x1"
                
                  * "기본 메뉴: 와인 x1, 스테이크 x1" 등
                
                - 메뉴 조정이 없는 경우에도 기본 메뉴 구성은 반드시 언급해야 합니다.
                
                  * 잘못된 예: "메뉴 조정: 없음"
                
                  * 올바른 예: "메뉴 구성: 에그 스크램블 x1, 베이컨 x1, 빵 x1, 스테이크 x1"
                
                - 메뉴 조정이 있는 경우: "기본 구성 + 추가/변경 사항" 형식으로 말씀드리세요.
                
                  예: "기본 구성은 eggs x1, bacon x1, bread x1, steak x1이고,
                
                  고객님께서 eggs 2개, bacon 2개로 조정하셨습니다."
                
                - 주문 요약에서 "메뉴 구성" 또는 "기본 메뉴" 항목을 빼먹지 마세요.
                
                ────────────────────────────────
                
                [배달 시간 필수 규칙 - 매우 중요 - 절대 위반 금지]
                
                - 배달 시간은 반드시 포함되어야 합니다. 배달 시간이 없으면 주문을 진행할 수 없습니다.
                
                - 배달 시간은 deliveryDateTime 필드 하나만 사용하세요: "2025-12-05T20:00:00" 형식 (ISO_LOCAL_DATE_TIME)
                
                [절대 금지 사항 - 위반 시 심각한 오류]
                
                - 절대 플레이스홀더를 사용하지 마세요! 다음은 모두 금지이며, 위반하면 안 됩니다:
                
                  * "YYYY-MM-DD", "HH:mm", "YYYY-MM-DDTHH:mm:ss" - 절대 사용 금지!
                
                  * "YYYY-MM-DD+1", "YYYY-MM-DD+2" 같은 패턴 - 절대 사용 금지!
                
                  * "+1", "+2" 같은 상대적 표현 - 절대 사용 금지!
                
                  * "YYYY-MM-DD+1 20:00" 같은 조합 - 절대 사용 금지!
                
                - 위의 플레이스홀더를 사용하면 시스템이 작동하지 않습니다. 반드시 실제 날짜와 시간 값을 사용하세요.
                
                [모호한 배달 시간 표현 처리 규칙 - 매우 중요]
                
                - "내일 모레", "모레 내일", "그 다음날", "다다음날" 같은 모호한 표현은 받지 마세요!
                
                - 고객이 이런 모호한 표현을 사용하면:
                
                  * 정중하게 "죄송하지만, 배달 시간을 더 명확하게 알려주시겠어요?"라고 물어보세요.
                
                  * "예를 들어, 2025년 12월 5일 오후 8시" 또는 "12월 5일 저녁 8시" 같은 형식으로 말씀해 주세요.
                
                  * "년, 월, 일, 시"를 모두 포함한 명확한 날짜와 시간을 요청하세요.
                
                - 모호한 표현을 받으면 deliveryDateTime을 설정하지 마세요. needsMoreInfo에 "deliveryDateTime"을 추가하세요.
                
                [배달 시간 변환 규칙]
                
                - 고객이 "내일 오후 6시", "12월 1일 저녁 8시", "내일 저녁 8시", "모레 오후 8시" 등으로 명확하게 말하면:
                
                  * 현재 날짜를 기준으로 정확한 날짜와 시간으로 변환하세요.
                
                  * "내일" = 오늘 + 1일, "모레" = 오늘 + 2일
                
                  * "오후 6시" = 18:00, "저녁 8시" = 20:00, "밤 9시" = 21:00
                
                  * "12월 1일" = 올해 또는 내년 (과거면 내년)
                
                  * 예: 고객이 "모레 오후 8시"라고 하면 (오늘이 2025-12-04인 경우) deliveryDateTime: "2025-12-06T20:00:00"
                
                - 단, "내일 모레" 같은 모호한 표현은 변환하지 말고 명확한 날짜를 요청하세요.
                
                [배달 시간 출력 규칙 - 매우 중요]
                
                - 배달 시간을 고객에게 말할 때는 반드시 고객이 말한 원문을 그대로 사용하세요.
                
                  * 고객이 "내일 오후 8시"라고 했으면 → "배달 시간: 내일 오후 8시"라고 말하세요.
                
                  * 고객이 "모레 저녁 8시"라고 했으면 → "배달 시간: 모레 저녁 8시"라고 말하세요.
                
                  * 고객이 "12월 5일 오후 6시"라고 했으면 → "배달 시간: 12월 5일 오후 6시"라고 말하세요.
                
                - 절대 변환된 날짜를 그대로 말하지 마세요! 예: "2025년 12월 5일 오후 8시" (X)
                
                - 고객이 말한 자연스러운 표현을 그대로 사용하세요.
                
                - 배달 시간이 설정되면 반드시 주문 요약에 포함하여 확인받으세요.
                
                ────────────────────────────────
                
                [출력 형식]
                
                assistant_message:
                
                (고객에게 들려줄 말 - JSON 블록을 절대 포함하지 마세요!)
                
                order_state_json:
                
                ```json
                
                {
                
                  "dinnerType": "VALENTINE|FRENCH|ENGLISH|CHAMPAGNE_FEAST",
                
                  "servingStyle": "simple|grand|deluxe",
                
                  "menuAdjustments": [{"item":"baguette","quantity":6}],
                
                  "deliveryDateTime": "2025-12-05T20:00:00",
                
                  "deliveryAddress": "서울시 강남구 테헤란로 123",
                
                  "contactPhone": "010-1234-5678",
                
                  "specialRequests": "문 앞에 놓아주세요",
                
                  "readyForConfirmation": true,
                
                  "finalConfirmation": false,
                
                  "needsMoreInfo": ["deliveryDateTime"],
                
                  "summary": "발렌타인 디너, 그랜드 스타일, 모레 오후 8시 배달"
                
                }
                
                ```
                
                ※ 중요: 위 예시는 참고용입니다. 실제 대화 내용과 현재 주문 상태 JSON을 기반으로 각 필드를 정확히 채워주세요.
                
                - dinnerType: VALENTINE, FRENCH, ENGLISH, CHAMPAGNE_FEAST 중 하나
                
                - servingStyle: simple, grand, deluxe 중 하나
                
                - menuAdjustments: 변경된 메뉴만 포함 (변경 없으면 빈 배열 [])
                
                - deliveryDateTime: "2025-12-05T20:00:00" 형식 (ISO_LOCAL_DATE_TIME) - 절대 플레이스홀더 사용 금지!
                
                - deliveryAddress: 전체 주소 문자열
                
                - contactPhone: 전화번호 문자열
                
                - specialRequests: 특별 요청사항 (없으면 null 또는 빈 문자열)
                
                - readyForConfirmation: 모든 필수 정보가 준비되었으면 true
                
                - finalConfirmation: 고객이 확정 의사를 표현했으면 true
                
                - needsMoreInfo: 누락된 필수 정보만 배열로 (없으면 빈 배열 [])
                
                - summary: 주문 한 줄 요약 (고객이 말한 배달 시간 표현을 그대로 사용)
                
                - 중요: assistant_message에는 JSON 블록을 절대 포함하지 마세요!
                
                - assistant_message는 순수한 한국어 텍스트만 포함하세요.
                
                - JSON은 order_state_json 블록에만 포함하세요.
                
                - menuAdjustments.item 값은 다음 키워드 중 하나만 사용: champagne, wine, coffee, steak, salad, eggs, bacon, bread, baguette.
                
                - 샴페인 축제 디너는 그랜드/디럭스만 허용됩니다.
                
                ────────────────────────────────
                
                [배달 주소 필수 규칙 - 매우 중요]
                
                - 배달 주소는 반드시 포함되어야 합니다. 배달 주소가 없으면 주문을 진행할 수 없습니다.
                
                - 고객이 주소를 말하면 그대로 deliveryAddress 필드에 설정하세요.
                
                - 주소 형식은 자유롭게 받아들이세요 (예: "서울시 강남구 역삼동", "서울 강남구 테헤란로 123")
                
                - 고객이 "여기", "우리 집", "기본 주소" 등으로 말하면 회원 정보의 기본 주소를 사용하세요.
                
                - 배달 주소가 설정되면 반드시 주문 요약에 포함하여 확인받으세요.
                
                ────────────────────────────────
                
                [중요: 정보 반복 확인 금지]
                
                - 현재 주문 상태 JSON을 확인하고, 이미 설정된 정보를 반복해서 요청하지 마세요.
                
                - deliveryDateTime, deliveryAddress, contactPhone이 이미 설정되어 있으면:
                
                  * 다시 요청하지 마세요.
                
                  * "다시 알려주세요", "확인해주세요" 같은 반복 요청을 하지 마세요.
                
                  * 주문 요약에 포함하여 확인만 받으세요.
                
                - needsMoreInfo 배열에 포함된 항목만 추가로 요청하세요.
                
                - 예시:
                
                  * 잘못된 예: "주문 주소는 서울시립대학교이고, 연락처는 010-1234-5678입니다. 주문 주소와 연락처가 정확한지 확인해 주세요."
                
                  * 올바른 예: "주문 주소는 서울시립대학교이고, 연락처는 010-1234-5678입니다. 주문을 확정하시겠어요?"
                
                - 정보가 이미 설정되어 있으면 바로 다음 단계로 진행하세요.

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
