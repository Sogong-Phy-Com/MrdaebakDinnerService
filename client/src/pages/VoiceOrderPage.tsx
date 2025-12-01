import React, { useCallback, useEffect, useRef, useState } from 'react';
import axios from 'axios';
import TopLogo from '../components/TopLogo';
import './VoiceOrder.css';

const API_URL =
  process.env.REACT_APP_API_URL ||
  (window.location.protocol === 'https:' ? '/api' : 'http://localhost:5000/api');

interface VoiceMessage {
  id: string;
  role: 'user' | 'assistant';
  content: string;
  timestamp: string;
}

interface SummaryItem {
  name: string;
  quantity: number;
}

interface VoiceOrderSummary {
  dinnerName?: string;
  servingStyle?: string;
  deliverySlot?: string;
  deliveryAddress?: string;
  contactPhone?: string;
  specialRequests?: string;
  items: SummaryItem[];
  readyForConfirmation: boolean;
  missingFields: string[];
}

const VoiceOrderPage: React.FC = () => {
  const [sessionId, setSessionId] = useState<string>('');
  const [messages, setMessages] = useState<VoiceMessage[]>([]);
  const [summary, setSummary] = useState<VoiceOrderSummary | null>(null);
  const [loading, setLoading] = useState<boolean>(false);
  const [recording, setRecording] = useState<boolean>(false);
  const [textInput, setTextInput] = useState<string>('');
  const [error, setError] = useState<string>('');
  const [confirmation, setConfirmation] = useState<string | null>(null);
  const [isSpeaking, setIsSpeaking] = useState<boolean>(false);
  const recognitionRef = useRef<SpeechRecognition | null>(null);
  const synthesisRef = useRef<SpeechSynthesisUtterance | null>(null);
  const lastSpokenMessageIdRef = useRef<string>('');

  // TTS (Text-to-Speech) - 상담원 음성 재생
  const speakText = useCallback((text: string) => {
    if (!text || text.trim().length === 0) {
      return;
    }

    if (!('speechSynthesis' in window)) {
      console.warn('이 브라우저는 음성 합성을 지원하지 않습니다.');
      return;
    }

    try {
      // 이전 재생 중지
      window.speechSynthesis.cancel();

      const utterance = new SpeechSynthesisUtterance(text);
      utterance.lang = 'ko-KR';
      utterance.rate = 1.0; // 말하는 속도 (0.1 ~ 10)
      utterance.pitch = 1.0; // 음성 높이 (0 ~ 2)
      utterance.volume = 1.0; // 볼륨 (0 ~ 1)

      // 한국어 음성 선택 (음성 목록이 로드될 때까지 대기)
      const selectKoreanVoice = () => {
        try {
          const voices = window.speechSynthesis.getVoices();
          // 한국어 음성 찾기 (여성 목소리 우선)
          const koreanVoices = voices.filter(voice => 
            voice.lang.startsWith('ko') || voice.lang.includes('Korean')
          );
          
          if (koreanVoices.length > 0) {
            // 여성 목소리 우선 선택
            const femaleVoice = koreanVoices.find(voice => 
              voice.name.toLowerCase().includes('female') ||
              voice.name.toLowerCase().includes('여성')
            );
            utterance.voice = femaleVoice || koreanVoices[0];
          }
        } catch (err) {
          console.warn('음성 선택 오류:', err);
        }
      };

      // 음성 목록이 이미 로드되어 있으면 바로 선택
      if (window.speechSynthesis.getVoices().length > 0) {
        selectKoreanVoice();
      } else {
        // 음성 목록 로드 대기
        window.speechSynthesis.onvoiceschanged = () => {
          selectKoreanVoice();
          window.speechSynthesis.onvoiceschanged = null;
        };
      }

      utterance.onstart = () => {
        setIsSpeaking(true);
      };

      utterance.onend = () => {
        setIsSpeaking(false);
        synthesisRef.current = null;
      };

      utterance.onerror = (event) => {
        // TTS 오류를 조용히 처리 (너무 많은 로그 방지)
        console.warn('TTS 재생 오류:', event.error);
        setIsSpeaking(false);
        synthesisRef.current = null;
      };

      synthesisRef.current = utterance;
      window.speechSynthesis.speak(utterance);
    } catch (err) {
      console.error('TTS 초기화 오류:', err);
      setIsSpeaking(false);
    }
  }, []);

  useEffect(() => {
    startSession();
    return () => {
      if (recognitionRef.current) {
        recognitionRef.current.stop();
        recognitionRef.current = null;
      }
      if (synthesisRef.current) {
        window.speechSynthesis.cancel();
        synthesisRef.current = null;
      }
    };
  }, []);

  // 상담원 메시지가 추가될 때마다 자동으로 음성 재생
  useEffect(() => {
    if (messages.length === 0) return;
    
    const lastMessage = messages[messages.length - 1];
    
    // 상담원 메시지이고, 아직 재생하지 않은 메시지인 경우
    if (
      lastMessage.role === 'assistant' &&
      lastMessage.id !== lastSpokenMessageIdRef.current &&
      lastMessage.content &&
      !isSpeaking &&
      !recording
    ) {
      lastSpokenMessageIdRef.current = lastMessage.id;
      // 약간의 지연을 두어 메시지가 완전히 렌더링된 후 재생
      setTimeout(() => {
        speakText(lastMessage.content);
      }, 300);
    }
  }, [messages, isSpeaking, recording, speakText]);

  const authHeaders = () => {
    const token = localStorage.getItem('token');
    if (!token) throw new Error('로그인이 필요합니다.');
    return { Authorization: `Bearer ${token}` };
  };

  const startSession = async () => {
    try {
      setLoading(true);
      setError('');
      const response = await axios.post(
        `${API_URL}/voice-orders/start`,
        {},
        { headers: authHeaders() }
      );
      setSessionId(response.data.sessionId);
      const initialMessages = response.data.messages || [];
      setMessages(initialMessages);
      setSummary(response.data.summary || null);
      
      // 초기 인사 메시지는 useEffect에서 자동으로 음성 재생됨
    } catch (err: any) {
      console.error(err);
      setError(
        err.response?.data?.error ||
          '음성 주문 세션을 시작할 수 없습니다. 잠시 후 다시 시도해주세요.'
      );
    } finally {
      setLoading(false);
    }
  };

  const handleTextSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!textInput.trim()) return;
    await sendUtterance(textInput.trim());
    setTextInput('');
  };

  const sendUtterance = async (text: string) => {
    if (!sessionId) return;
    const tempId = `local-${Date.now()}`;
    const optimisticMessage: VoiceMessage = {
      id: tempId,
      role: 'user',
      content: text,
      timestamp: new Date().toISOString(),
    };
    setMessages((prev) => [...prev, optimisticMessage]);
    try {
      setLoading(true);
      setError('');
      const response = await axios.post(
        `${API_URL}/voice-orders/utterance`,
        {
          sessionId,
          userText: text,
        },
        { headers: authHeaders() }
      );
      setMessages((prev) => {
        const replaced = prev.map((message) =>
          message.id === tempId ? response.data.userMessage : message
        );
        return [...replaced, response.data.agentMessage];
      });
      setSummary(response.data.summary);
      
      // 상담원 응답은 useEffect에서 자동으로 음성 재생됨
    } catch (err: any) {
      console.error(err);
      setMessages((prev) => prev.filter((message) => message.id !== tempId));
      setError(
        err.response?.data?.error ||
          '상담원과 연결할 수 없습니다. 잠시 후 다시 시도해주세요.'
      );
    } finally {
      setLoading(false);
    }
  };

  // Web Speech API 지원 확인
  const isSpeechRecognitionAvailable = () => {
    return (
      'SpeechRecognition' in window ||
      'webkitSpeechRecognition' in window
    );
  };

  const stopSpeaking = () => {
    if (window.speechSynthesis) {
      window.speechSynthesis.cancel();
      setIsSpeaking(false);
      synthesisRef.current = null;
    }
  };

  const startRecording = async () => {
    if (!sessionId) return;
    
    // Web Speech API 지원 확인
    if (!isSpeechRecognitionAvailable()) {
      setError('이 브라우저는 음성 인식을 지원하지 않습니다. Chrome, Edge, Safari를 사용해주세요.');
      return;
    }

    try {
      setError('');
      setConfirmation(null);

      // SpeechRecognition 초기화
      const SpeechRecognition =
        (window as any).SpeechRecognition ||
        (window as any).webkitSpeechRecognition;
      
      const recognition = new SpeechRecognition();
      recognition.lang = 'ko-KR';
      recognition.continuous = true; // 연속 인식 모드
      recognition.interimResults = true; // 중간 결과도 받기

      let finalTranscript = '';

      recognition.onstart = () => {
        setRecording(true);
        finalTranscript = '';
        // 녹음 시작 시 상담원 음성 중지
        stopSpeaking();
      };

      recognition.onresult = (event: SpeechRecognitionEvent) => {
        let interimTranscript = '';
        
        // resultIndex가 타입에 없을 수 있으므로 안전하게 처리
        const resultIndex = (event as any).resultIndex ?? 0;
        for (let i = resultIndex; i < event.results.length; i++) {
          const transcript = event.results[i][0].transcript;
          if (event.results[i].isFinal) {
            finalTranscript += transcript + ' ';
          } else {
            interimTranscript += transcript;
          }
        }
        
        // 실시간으로 화면에 표시 (선택사항)
        if (interimTranscript) {
          console.log('인식 중:', interimTranscript);
        }
      };

      recognition.onerror = (event: SpeechRecognitionErrorEvent) => {
        console.error('Speech recognition error:', event.error);
        setRecording(false);
        
        if (event.error === 'no-speech') {
          setError('음성이 감지되지 않았습니다. 다시 시도해주세요.');
        } else if (event.error === 'audio-capture') {
          setError('마이크를 찾을 수 없습니다. 마이크 연결을 확인해주세요.');
        } else if (event.error === 'not-allowed') {
          setError('마이크 권한이 필요합니다. 브라우저 설정을 확인해주세요.');
        } else {
          setError('음성 인식 중 오류가 발생했습니다: ' + event.error);
        }
      };

      recognition.onend = async () => {
        setRecording(false);
        
        // 최종 결과가 있으면 서버로 전송
        if (finalTranscript.trim()) {
          await sendUtterance(finalTranscript.trim());
        } else if (!error) {
          // 에러 메시지가 설정되지 않았다면
          setError('음성이 감지되지 않았습니다. 다시 시도해주세요.');
        }
      };

      recognitionRef.current = recognition;
      recognition.start();
    } catch (err: any) {
      console.error(err);
      setError('음성 인식을 시작할 수 없습니다. 브라우저를 확인해주세요.');
      setRecording(false);
    }
  };

  const stopRecording = () => {
    if (recognitionRef.current) {
      recognitionRef.current.stop();
      recognitionRef.current = null;
    }
    setRecording(false);
  };

  const handleConfirm = async () => {
    if (!summary?.readyForConfirmation || !sessionId) return;
    try {
      setLoading(true);
      setError('');
      const response = await axios.post(
        `${API_URL}/voice-orders/confirm`,
        { sessionId },
        { headers: authHeaders() }
      );
      setConfirmation(response.data.confirmationMessage);
      setSummary(response.data.summary);
      setMessages((prev) => [
        ...prev,
        {
          id: `confirmation-${response.data.orderId}`,
          role: 'assistant',
          content: response.data.confirmationMessage,
          timestamp: new Date().toISOString(),
        },
      ]);
      
      // 주문 확인 메시지는 useEffect에서 자동으로 음성 재생됨
    } catch (err: any) {
      console.error(err);
      setError(err.response?.data?.error || '주문 확정에 실패했습니다.');
    } finally {
      setLoading(false);
    }
  };

  const renderMessage = (message: VoiceMessage) => (
    <div key={message.id} className={`voice-message ${message.role}`}>
      <div className="bubble">{message.content}</div>
      <div className="timestamp">
        {new Date(message.timestamp).toLocaleTimeString('ko-KR', {
          hour: '2-digit',
          minute: '2-digit',
        })}
      </div>
    </div>
  );

  const renderSummaryItems = () => {
    if (!summary?.items?.length) {
      return <p className="muted">아직 구성 정보가 없습니다.</p>;
    }
    return summary.items.map((item) => (
      <div key={item.name} className="summary-item">
        <span>{item.name}</span>
        <span className="quantity">x{item.quantity}</span>
      </div>
    ));
  };

  return (
    <div className="voice-order-page">
      <TopLogo />
      <div className="voice-order-layout">
        <section className="chat-panel">
          <div className="panel-header">
            <div>
              <h2>음성 주문 상담</h2>
              <p className="muted">
                상담원과 자연스럽게 대화하며 주문을 완성하세요. (존댓말 응답)
              </p>
            </div>
            <div className="status-badges">
              <span className={`badge ${summary?.readyForConfirmation ? 'ready' : 'pending'}`}>
                {summary?.readyForConfirmation ? '주문 정보 준비 완료' : '추가 정보 필요'}
              </span>
              {loading && <span className="badge subtle">처리 중...</span>}
            </div>
          </div>

          <div className="messages-window">
            {messages.length === 0 && (
              <div className="placeholder">
                상담원이 인사를 준비 중입니다. 잠시만 기다려 주세요.
              </div>
            )}
            {messages.map(renderMessage)}
          </div>

          {error && <div className="error-banner">{error}</div>}
          {confirmation && (
            <div className="success-banner">
              {confirmation}
            </div>
          )}

          <div className="controls">
            <button
              className={`btn ${recording ? 'btn-danger' : 'btn-primary'}`}
              onClick={recording ? stopRecording : startRecording}
              disabled={!sessionId || loading}
            >
              {recording ? '■ 녹음 중지' : '🎙️ 음성 녹음'}
            </button>
            {isSpeaking && (
              <button
                className="btn btn-secondary"
                onClick={stopSpeaking}
                title="상담원 음성 중지"
              >
                🔊 음성 중지
              </button>
            )}
            <form onSubmit={handleTextSubmit} className="text-input-form">
              <input
                type="text"
                value={textInput}
                onChange={(e) => setTextInput(e.target.value)}
                placeholder="텍스트로도 말씀하실 수 있어요."
              />
              <button type="submit" className="btn btn-secondary" disabled={!textInput.trim()}>
                전송
              </button>
            </form>
          </div>

          <div className="examples">
            <p className="muted">예시 발화: "맛있는 디너 추천해 주세요", "샴페인 축제 디너 디럭스로 바꿀게요", "바게트빵 6개로 늘려 주세요"</p>
          </div>
        </section>

        <aside className="summary-panel">
          <h3>주문 요약</h3>
          <div className="summary-card">
            <div className="summary-row">
              <span>디너</span>
              <strong>{summary?.dinnerName || '-'}</strong>
            </div>
            <div className="summary-row">
              <span>서빙 스타일</span>
              <strong>{summary?.servingStyle || '-'}</strong>
            </div>
            <div className="summary-row">
              <span>배달 시간</span>
              <strong>{summary?.deliverySlot || '-'}</strong>
            </div>
            <div className="summary-row">
              <span>주소</span>
              <strong>{summary?.deliveryAddress || '-'}</strong>
            </div>
            <div className="summary-row">
              <span>연락처</span>
              <strong>{summary?.contactPhone || '-'}</strong>
            </div>
            <div className="summary-section">
              <h4>구성</h4>
              {renderSummaryItems()}
            </div>
            {summary?.missingFields?.length ? (
              <div className="missing-fields">
                <h4>필요 정보</h4>
                <ul>
                  {summary.missingFields.map((field) => (
                    <li key={field}>{field}</li>
                  ))}
                </ul>
              </div>
            ) : (
              <p className="muted tiny">모든 필수 정보가 채워졌습니다.</p>
            )}
            {summary?.readyForConfirmation && (
              <div className="confirmation-notice" style={{
                padding: '12px',
                marginTop: '12px',
                marginBottom: '12px',
                backgroundColor: '#fff3cd',
                border: '1px solid #ffc107',
                borderRadius: '8px',
                color: '#856404',
                fontSize: '14px',
                textAlign: 'center'
              }}>
                주문 확정 버튼을 눌러 주문을 확정해주세요
              </div>
            )}
            <button
              className="btn btn-primary confirm-button"
              onClick={handleConfirm}
              disabled={!summary?.readyForConfirmation || loading || !!confirmation}
            >
              주문 확정하기
            </button>
          </div>
        </aside>
      </div>
    </div>
  );
};

export default VoiceOrderPage;

