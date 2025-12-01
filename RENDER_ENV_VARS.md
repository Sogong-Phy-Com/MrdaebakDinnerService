# Render 배포 환경 변수 설정 가이드

## 필수 환경 변수

Render 대시보드에서 다음 환경 변수를 설정해야 합니다:

### 1. Groq API 설정

```
VOICE_LLM_API_KEY=your-groq-api-key-here
```

**설정 방법:**
1. Render 대시보드에서 서비스 선택
2. "Environment" 탭 클릭
3. "Add Environment Variable" 클릭
4. Key: `VOICE_LLM_API_KEY`
5. Value: Groq API 키를 입력 (https://console.groq.com/keys 에서 발급)
6. "Save Changes" 클릭

**참고:** Groq API 키는 `gsk-`로 시작하는 문자열입니다.

### 2. 선택적 환경 변수

다음 변수들은 기본값이 설정되어 있지만, 필요시 변경할 수 있습니다:

```
VOICE_LLM_API_URL=https://api.groq.com/openai/v1/chat/completions
VOICE_LLM_MODEL=llama-3.1-8b-instant
VOICE_ORDER_SESSION_TTL_MINUTES=45
VOICE_ORDER_HISTORY_LIMIT=40
```

### 3. 기타 필수 환경 변수

```
JWT_SECRET=your-secret-key-change-in-production-make-it-long-and-secure
PORT=5000
```

## 환경 변수 설정 확인

배포 후 로그에서 다음 메시지를 확인하세요:
- "Groq API 키가 설정되었습니다" (정상)
- "LLM API 키가 설정되지 않았습니다" (환경 변수 미설정)

## 문제 해결

### API 키 오류가 발생하는 경우:
1. Render 대시보드에서 환경 변수가 올바르게 설정되었는지 확인
2. 변수 이름이 정확한지 확인 (대소문자 구분)
3. 서비스를 재배포하여 환경 변수 적용 확인

