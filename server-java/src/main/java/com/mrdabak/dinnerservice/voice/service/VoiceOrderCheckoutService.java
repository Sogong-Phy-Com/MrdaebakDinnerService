package com.mrdabak.dinnerservice.voice.service;

import com.mrdabak.dinnerservice.dto.OrderRequest;
import com.mrdabak.dinnerservice.model.Order;
import com.mrdabak.dinnerservice.model.User;
import com.mrdabak.dinnerservice.repository.UserRepository;
import com.mrdabak.dinnerservice.repository.order.OrderRepository;
import com.mrdabak.dinnerservice.service.OrderService;
import com.mrdabak.dinnerservice.voice.VoiceOrderException;
import com.mrdabak.dinnerservice.voice.model.VoiceOrderSession;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class VoiceOrderCheckoutService {

    private final VoiceOrderMapper voiceOrderMapper;
    private final OrderService orderService;
    private final OrderRepository orderRepository;
    private final UserRepository userRepository;

    public VoiceOrderCheckoutService(VoiceOrderMapper voiceOrderMapper,
                                     OrderService orderService,
                                     OrderRepository orderRepository,
                                     UserRepository userRepository) {
        this.voiceOrderMapper = voiceOrderMapper;
        this.orderService = orderService;
        this.orderRepository = orderRepository;
        this.userRepository = userRepository;
    }

    @Transactional
    public Order finalizeVoiceOrder(VoiceOrderSession session) {
        if (session.isOrderPlaced()) {
            throw new VoiceOrderException("이미 주문이 완료되었습니다.");
        }
        
        // 카드 정보 확인
        User user = userRepository.findById(session.getUserId())
                .orElseThrow(() -> new VoiceOrderException("사용자 정보를 찾을 수 없습니다."));
        
        if (user.getCardNumber() == null || user.getCardNumber().trim().isEmpty()) {
            throw new VoiceOrderException("주문을 하려면 카드 정보가 필요합니다. 내 정보에서 카드 정보를 등록해주세요.");
        }
        
        OrderRequest request = voiceOrderMapper.toOrderRequest(session);
        Order order = orderService.createOrder(session.getUserId(), request);
        order.setPaymentStatus("paid");
        order.setPaymentMethod("voice-bot-card");
        Order saved = orderRepository.save(order);
        session.markOrderPlaced(saved.getId());
        return saved;
    }
}


