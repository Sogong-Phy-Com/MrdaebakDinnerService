package com.mrdabak.dinnerservice.service;

import com.mrdabak.dinnerservice.dto.OrderItemDto;
import com.mrdabak.dinnerservice.dto.ReservationChangeRequestCreateDto;
import com.mrdabak.dinnerservice.dto.ReservationChangeRequestDecisionDto;
import com.mrdabak.dinnerservice.dto.ReservationChangeRequestItemResponseDto;
import com.mrdabak.dinnerservice.dto.ReservationChangeRequestResponseDto;
import com.mrdabak.dinnerservice.model.DinnerType;
import com.mrdabak.dinnerservice.model.MenuItem;
import com.mrdabak.dinnerservice.model.Order;
import com.mrdabak.dinnerservice.model.OrderChangeRequest;
import com.mrdabak.dinnerservice.model.OrderChangeRequestItem;
import com.mrdabak.dinnerservice.model.OrderChangeRequestStatus;
import com.mrdabak.dinnerservice.model.OrderItem;
import com.mrdabak.dinnerservice.model.User;
import com.mrdabak.dinnerservice.repository.DinnerTypeRepository;
import com.mrdabak.dinnerservice.repository.DinnerMenuItemRepository;
import com.mrdabak.dinnerservice.repository.MenuItemRepository;
import com.mrdabak.dinnerservice.repository.UserRepository;
import com.mrdabak.dinnerservice.repository.order.OrderChangeRequestItemRepository;
import com.mrdabak.dinnerservice.repository.order.OrderChangeRequestRepository;
import com.mrdabak.dinnerservice.repository.order.OrderItemRepository;
import com.mrdabak.dinnerservice.repository.order.OrderRepository;
import com.mrdabak.dinnerservice.util.DeliveryTimeUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class OrderChangeRequestService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final int CHANGE_FEE_AMOUNT = 30_000;
    private static final Set<OrderChangeRequestStatus> ACTIVE_REQUEST_STATUSES =
            EnumSet.of(OrderChangeRequestStatus.REQUESTED, OrderChangeRequestStatus.PAYMENT_FAILED, OrderChangeRequestStatus.REFUND_FAILED);

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final OrderChangeRequestRepository changeRequestRepository;
    private final OrderChangeRequestItemRepository changeRequestItemRepository;
    private final DinnerTypeRepository dinnerTypeRepository;
    private final DinnerMenuItemRepository dinnerMenuItemRepository;
    private final MenuItemRepository menuItemRepository;
    private final UserRepository userRepository;
    private final InventoryService inventoryService;
    private final PaymentService paymentService;
    private final DeliverySchedulingService deliverySchedulingService;

    public OrderChangeRequestService(OrderRepository orderRepository,
                                     OrderItemRepository orderItemRepository,
                                     OrderChangeRequestRepository changeRequestRepository,
                                     OrderChangeRequestItemRepository changeRequestItemRepository,
                                     DinnerTypeRepository dinnerTypeRepository,
                                     DinnerMenuItemRepository dinnerMenuItemRepository,
                                     MenuItemRepository menuItemRepository,
                                     UserRepository userRepository,
                                     InventoryService inventoryService,
                                     PaymentService paymentService,
                                     DeliverySchedulingService deliverySchedulingService) {
        this.orderRepository = orderRepository;
        this.orderItemRepository = orderItemRepository;
        this.changeRequestRepository = changeRequestRepository;
        this.changeRequestItemRepository = changeRequestItemRepository;
        this.dinnerTypeRepository = dinnerTypeRepository;
        this.dinnerMenuItemRepository = dinnerMenuItemRepository;
        this.menuItemRepository = menuItemRepository;
        this.userRepository = userRepository;
        this.inventoryService = inventoryService;
        this.paymentService = paymentService;
        this.deliverySchedulingService = deliverySchedulingService;
    }

    /**
     * 변경 요청을 생성합니다.
     * 활성 상태(REQUESTED, PAYMENT_FAILED, REFUND_FAILED)의 변경 요청이 이미 존재하면,
     * 새로 생성하지 않고 기존 요청을 수정합니다.
     */
    @Transactional(transactionManager = "orderTransactionManager")
    public ReservationChangeRequestResponseDto createChangeRequest(Long orderId,
                                                                   Long userId,
                                                                   ReservationChangeRequestCreateDto command) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new RuntimeException("주문을 찾을 수 없습니다."));
        if (!Objects.equals(order.getUserId(), userId)) {
            throw new RuntimeException("해당 예약을 수정할 권한이 없습니다.");
        }

        validateOrderState(order);

        LocalDate today = LocalDate.now(KST);
        if (!order.canModify(today)) {
            throw new RuntimeException("예약 변경 가능 기한이 지났습니다. (배달 1일 전 00:00 이후에는 변경 불가)");
        }

        // 활성 상태의 변경 요청이 이미 있는지 확인
        java.util.Optional<OrderChangeRequest> existingRequest = 
                changeRequestRepository.findByOrderIdAndUserIdAndStatusIn(orderId, userId, ACTIVE_REQUEST_STATUSES);
        
        if (existingRequest.isPresent()) {
            // 기존 활성 요청이 있으면 수정
            return updateChangeRequest(existingRequest.get().getId(), userId, command);
        }

        // 새 변경 요청 생성
        LocalDateTime newDeliveryTime = DeliveryTimeUtils.parseDeliveryTime(command.getDeliveryTime());
        inventoryService.validateChangePlan(orderId, command.getItems(), newDeliveryTime);

        int recalculatedAmount = calculateNewSubtotal(order, command);
        boolean changeFeeRequired = order.requiresChangeFee(today);
        int changeFee = changeFeeRequired ? CHANGE_FEE_AMOUNT : 0;
        ReservationChangeQuote quote = new ReservationChangeQuote(order.getTotalPrice(), recalculatedAmount, changeFee);

        OrderChangeRequest request = new OrderChangeRequest();
        request.setOrderId(orderId);
        request.setUserId(userId);
        request.setStatus(OrderChangeRequestStatus.REQUESTED);
        request.setNewDinnerTypeId(command.getDinnerTypeId());
        request.setNewServingStyle(command.getServingStyle());
        request.setNewDeliveryTime(command.getDeliveryTime());
        request.setNewDeliveryAddress(command.getDeliveryAddress());
        request.setOriginalTotalAmount(order.getTotalPrice());
        request.setRecalculatedAmount(recalculatedAmount);
        request.setChangeFeeAmount(changeFee);
        request.setNewTotalAmount(quote.newTotalAmount());
        request.setAlreadyPaidAmount(order.getTotalPrice());
        request.setExtraChargeAmount(quote.extraChargeAmount());
        request.setChangeFeeApplied(changeFeeRequired);
        request.setReason(command.getReason());
        request.setRequestedAt(LocalDateTime.now());

        List<OrderChangeRequestItem> items = buildRequestItems(request, command.getItems());
        request.setItems(items);

        OrderChangeRequest saved = changeRequestRepository.save(request);
        // 트랜잭션 내에서 이미 로드된 items를 사용하여 잠금 문제 방지
        return toResponse(saved, items);
    }

    /**
     * PENDING 상태(REQUESTED, PAYMENT_FAILED, REFUND_FAILED)의 변경 요청을 수정합니다.
     * 
     * 비즈니스 규칙:
     * - PENDING 상태의 변경 요청만 수정 가능
     * - 원래 주문이 변경 불가 시점을 지나지 않았을 것
     * - 변경 수수료는 최종 승인 시점에 한 번만 적용되므로, 수정 시에도 재계산
     * - 재고/예약 상태를 다시 검증
     */
    @Transactional(transactionManager = "orderTransactionManager")
    public ReservationChangeRequestResponseDto updateChangeRequest(Long changeRequestId,
                                                                   Long userId,
                                                                   ReservationChangeRequestCreateDto command) {
        OrderChangeRequest request = changeRequestRepository.findById(changeRequestId)
                .orElseThrow(() -> new RuntimeException("변경 요청을 찾을 수 없습니다."));
        
        if (!Objects.equals(request.getUserId(), userId)) {
            throw new RuntimeException("해당 변경 요청을 수정할 권한이 없습니다.");
        }

        // PENDING 상태인지 확인
        if (!ACTIVE_REQUEST_STATUSES.contains(request.getStatus())) {
            throw new RuntimeException("이미 처리된 변경 요청은 수정할 수 없습니다. (상태: " + request.getStatus() + ")");
        }

        Order order = orderRepository.findById(request.getOrderId())
                .orElseThrow(() -> new RuntimeException("주문을 찾을 수 없습니다."));

        validateOrderState(order);

        LocalDate today = LocalDate.now(KST);
        if (!order.canModify(today)) {
            throw new RuntimeException("예약 변경 가능 기한이 지났습니다. (배달 1일 전 00:00 이후에는 변경 불가)");
        }

        // 재고 검증
        LocalDateTime newDeliveryTime = DeliveryTimeUtils.parseDeliveryTime(command.getDeliveryTime());
        inventoryService.validateChangePlan(order.getId(), command.getItems(), newDeliveryTime);

        // 가격 재계산 (변경 수수료는 최종 승인 시점에 한 번만 적용되므로 재계산)
        int recalculatedAmount = calculateNewSubtotal(order, command);
        boolean changeFeeRequired = order.requiresChangeFee(today);
        int changeFee = changeFeeRequired ? CHANGE_FEE_AMOUNT : 0;
        ReservationChangeQuote quote = new ReservationChangeQuote(order.getTotalPrice(), recalculatedAmount, changeFee);

        // 기존 items를 먼저 로드 (orphan removal을 위해 컬렉션 참조 유지 필요)
        List<OrderChangeRequestItem> existingItems = changeRequestItemRepository.findByChangeRequestId(request.getId());
        
        // 변경 요청 내용 업데이트
        request.setNewDinnerTypeId(command.getDinnerTypeId());
        request.setNewServingStyle(command.getServingStyle());
        request.setNewDeliveryTime(command.getDeliveryTime());
        request.setNewDeliveryAddress(command.getDeliveryAddress());
        request.setRecalculatedAmount(recalculatedAmount);
        request.setChangeFeeAmount(changeFee);
        request.setNewTotalAmount(quote.newTotalAmount());
        request.setExtraChargeAmount(quote.extraChargeAmount());
        request.setChangeFeeApplied(changeFeeRequired);
        request.setReason(command.getReason());
        // requestedAt은 유지 (최초 요청 시점)
        // updatedAt은 @PreUpdate로 자동 갱신됨

        // 기존 컬렉션을 clear()로 비우기 (orphan removal이 자동으로 처리됨)
        existingItems.clear();
        
        // 새 items 생성 및 추가
        List<OrderChangeRequestItem> newItems = buildRequestItems(request, command.getItems());
        for (OrderChangeRequestItem item : newItems) {
            request.addItem(item);
        }

        OrderChangeRequest saved = changeRequestRepository.save(request);
        // 트랜잭션 내에서 이미 로드된 items를 사용하여 잠금 문제 방지
        return toResponse(saved, newItems);
    }

    public List<ReservationChangeRequestResponseDto> getRequestsForOrder(Long orderId, Long userId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new RuntimeException("주문을 찾을 수 없습니다."));
        if (!Objects.equals(order.getUserId(), userId)) {
            throw new RuntimeException("해당 예약의 변경 요청을 조회할 권한이 없습니다.");
        }
        return changeRequestRepository.findByOrderIdOrderByRequestedAtDesc(orderId).stream()
                .map(this::toResponse)
                .toList();
    }

    public ReservationChangeRequestResponseDto getRequestDetail(Long requestId, Long userId) {
        OrderChangeRequest request = changeRequestRepository.findByIdAndUserId(requestId, userId)
                .orElseThrow(() -> new RuntimeException("예약 변경 요청을 찾을 수 없습니다."));
        return toResponse(request);
    }

    public List<ReservationChangeRequestResponseDto> getAdminRequests(OrderChangeRequestStatus status,
                                                                      LocalDate from,
                                                                      LocalDate to) {
        LocalDateTime fromDate = from != null ? from.atStartOfDay() : null;
        LocalDateTime toDate = to != null ? to.plusDays(1).atStartOfDay() : null;

        List<OrderChangeRequest> requests;
        if (status != null && fromDate != null && toDate != null) {
            requests = changeRequestRepository.findByStatusInAndRequestedAtBetween(
                    EnumSet.of(status), fromDate, toDate);
        } else if (status != null) {
            requests = changeRequestRepository.findByStatusOrderByRequestedAtDesc(status);
        } else if (fromDate != null && toDate != null) {
            requests = changeRequestRepository.findByRequestedAtBetween(fromDate, toDate);
        } else {
            requests = changeRequestRepository.findAllByOrderByRequestedAtDesc();
        }
        return requests.stream().map(this::toResponse).toList();
    }

    @Transactional(transactionManager = "orderTransactionManager")
    public ReservationChangeRequestResponseDto approve(Long requestId, ReservationChangeRequestDecisionDto decisionDto) {
        OrderChangeRequest request = changeRequestRepository.findById(requestId)
                .orElseThrow(() -> new RuntimeException("예약 변경 요청을 찾을 수 없습니다."));
        if (!ACTIVE_REQUEST_STATUSES.contains(request.getStatus())) {
            throw new RuntimeException("이미 처리된 변경 요청입니다.");
        }

        Order order = orderRepository.findById(request.getOrderId())
                .orElseThrow(() -> new RuntimeException("주문을 찾을 수 없습니다."));

        List<OrderChangeRequestItem> persistedItems = changeRequestItemRepository.findByChangeRequestId(request.getId());
        List<OrderItemDto> newItems = toOrderItemDtos(persistedItems);
        LocalDateTime deliveryTime = DeliveryTimeUtils.parseDeliveryTime(request.getNewDeliveryTime());
        inventoryService.validateChangePlan(order.getId(), newItems, deliveryTime);

        int delta = request.getExtraChargeAmount();
        try {
            if (delta > 0) {
                paymentService.chargeAdditionalAmount(order, delta);
            } else if (delta < 0) {
                paymentService.refundAmount(order, Math.abs(delta));
            }
        } catch (PaymentException e) {
            request.setStatus(delta >= 0 ? OrderChangeRequestStatus.PAYMENT_FAILED : OrderChangeRequestStatus.REFUND_FAILED);
            request.setAdminComment(e.getMessage());
            changeRequestRepository.save(request);
            throw e;
        }

        inventoryService.replaceReservationsForOrder(order.getId(), newItems, deliveryTime);
        replaceOrderItems(order.getId(), newItems);

        order.setDinnerTypeId(request.getNewDinnerTypeId());
        order.setServingStyle(request.getNewServingStyle());
        order.setDeliveryTime(request.getNewDeliveryTime());
        order.setDeliveryAddress(request.getNewDeliveryAddress());
        order.setTotalPrice(request.getNewTotalAmount());
        order.setDeliveryEmployeeId(null);
        orderRepository.save(order);
        deliverySchedulingService.cancelScheduleForOrder(order.getId());

        request.setStatus(OrderChangeRequestStatus.APPROVED);
        request.setApprovedAt(LocalDateTime.now());
        request.setAdminComment(decisionDto != null ? decisionDto.getAdminComment() : null);
        OrderChangeRequest saved = changeRequestRepository.save(request);
        // 이미 로드된 items를 사용하여 잠금 문제 방지
        return toResponse(saved, persistedItems);
    }

    @Transactional(transactionManager = "orderTransactionManager")
    public ReservationChangeRequestResponseDto reject(Long requestId, ReservationChangeRequestDecisionDto decisionDto) {
        OrderChangeRequest request = changeRequestRepository.findById(requestId)
                .orElseThrow(() -> new RuntimeException("예약 변경 요청을 찾을 수 없습니다."));
        if (!ACTIVE_REQUEST_STATUSES.contains(request.getStatus())) {
            throw new RuntimeException("이미 처리된 변경 요청입니다.");
        }
        // items를 먼저 로드하여 잠금 문제 방지
        List<OrderChangeRequestItem> persistedItems = changeRequestItemRepository.findByChangeRequestId(request.getId());
        request.setStatus(OrderChangeRequestStatus.REJECTED);
        request.setRejectedAt(LocalDateTime.now());
        request.setAdminComment(decisionDto != null ? decisionDto.getAdminComment() : null);
        OrderChangeRequest saved = changeRequestRepository.save(request);
        return toResponse(saved, persistedItems);
    }

    private void validateOrderState(Order order) {
        if (!"APPROVED".equalsIgnoreCase(order.getAdminApprovalStatus())) {
            throw new RuntimeException("관리자 승인 완료 상태의 예약만 수정할 수 있습니다.");
        }
        // 결제 상태 검증 제거: 관리자 승인 완료된 예약은 결제 상태와 관계없이 변경 가능
        // (주문 생성 시점에 결제가 이루어지지만, 관리자 승인 후에도 변경 요청을 허용)
        if ("cancelled".equalsIgnoreCase(order.getStatus()) || "delivered".equalsIgnoreCase(order.getStatus())) {
            throw new RuntimeException("이미 처리된 예약은 수정할 수 없습니다.");
        }
    }

    /**
     * @deprecated 이 메소드는 더 이상 사용되지 않습니다.
     * createChangeRequest에서 활성 요청이 있으면 자동으로 수정하도록 변경되었습니다.
     */
    @Deprecated
    private void ensureNoActiveRequest(Long orderId) {
        if (changeRequestRepository.existsByOrderIdAndStatusIn(orderId, ACTIVE_REQUEST_STATUSES)) {
            throw new RuntimeException("처리 중인 예약 변경 요청이 이미 존재합니다.");
        }
    }

    private int calculateNewSubtotal(Order order, ReservationChangeRequestCreateDto command) {
        DinnerType dinner = dinnerTypeRepository.findById(command.getDinnerTypeId())
                .orElseThrow(() -> new RuntimeException("유효하지 않은 디너 타입입니다."));
        if (dinner.getName().contains("샴페인") &&
                !List.of("grand", "deluxe").contains(command.getServingStyle())) {
            throw new RuntimeException("샴페인 디너는 그랜드 또는 디럭스 스타일만 가능합니다.");
        }

        Map<String, Double> styleMultipliers = Map.of(
                "simple", 1.0,
                "grand", 1.3,
                "deluxe", 1.6
        );
        double basePrice = dinner.getBasePrice() * styleMultipliers.getOrDefault(command.getServingStyle(), 1.0);

        // 기본 제공 메뉴 항목 정보 가져오기
        List<com.mrdabak.dinnerservice.model.DinnerMenuItem> defaultMenuItems = 
                dinnerMenuItemRepository.findByDinnerTypeId(dinner.getId());
        
        // 기본 제공 항목의 기본 수량을 Map으로 저장
        Map<Long, Integer> defaultQuantities = new java.util.HashMap<>();
        for (com.mrdabak.dinnerservice.model.DinnerMenuItem dmi : defaultMenuItems) {
            defaultQuantities.put(dmi.getMenuItemId(), dmi.getQuantity());
        }

        // 추가 수량만 계산 (기본 제공 항목의 기본 수량은 제외)
        double additionalItemsPrice = 0;
        for (OrderItemDto itemDto : command.getItems()) {
            MenuItem menuItem = menuItemRepository.findById(itemDto.getMenuItemId())
                    .orElseThrow(() -> new RuntimeException("메뉴 아이템을 찾을 수 없습니다: " + itemDto.getMenuItemId()));
            if (itemDto.getQuantity() == null || itemDto.getQuantity() <= 0) {
                throw new RuntimeException("메뉴 수량은 1 이상이어야 합니다.");
            }
            
            // 기본 제공 수량 확인
            int defaultQuantity = defaultQuantities.getOrDefault(itemDto.getMenuItemId(), 0);
            // 추가 수량만 계산 (현재 수량 - 기본 제공 수량)
            int additionalQuantity = Math.max(0, itemDto.getQuantity() - defaultQuantity);
            additionalItemsPrice += menuItem.getPrice() * additionalQuantity;
        }

        // 기본 가격 + 추가 항목 가격 (기본 제공 항목은 기본 가격에 이미 포함됨)
        double subtotal = basePrice + additionalItemsPrice;
        User user = userRepository.findById(order.getUserId())
                .orElseThrow(() -> new RuntimeException("사용자를 찾을 수 없습니다."));
        List<Order> previousOrders = orderRepository.findByUserIdOrderByCreatedAtDesc(order.getUserId());
        long deliveredOrders = previousOrders.stream()
                .filter(o -> "delivered".equalsIgnoreCase(o.getStatus()))
                .count();
        // 모든 개인정보 동의(consentName, consentAddress, consentPhone)가 true여야 할인 적용
        boolean allConsentsGiven = Boolean.TRUE.equals(user.getConsentName()) 
                && Boolean.TRUE.equals(user.getConsentAddress()) 
                && Boolean.TRUE.equals(user.getConsentPhone());
        boolean loyaltyEligible = Boolean.TRUE.equals(user.getLoyaltyConsent()) 
                && allConsentsGiven 
                && deliveredOrders >= 5;
        if (loyaltyEligible) {
            subtotal *= 0.9;
        }
        return (int) Math.round(subtotal);
    }

    private List<OrderChangeRequestItem> buildRequestItems(OrderChangeRequest request, List<OrderItemDto> items) {
        Map<Long, MenuItem> menuItems = menuItemRepository.findAllById(
                items.stream().map(OrderItemDto::getMenuItemId).toList())
                .stream()
                .collect(Collectors.toMap(MenuItem::getId, Function.identity()));

        List<OrderChangeRequestItem> entities = new ArrayList<>();
        for (OrderItemDto dto : items) {
            MenuItem menuItem = menuItems.get(dto.getMenuItemId());
            if (menuItem == null) {
                throw new RuntimeException("메뉴 아이템을 찾을 수 없습니다: " + dto.getMenuItemId());
            }
            OrderChangeRequestItem item = new OrderChangeRequestItem();
            item.setChangeRequest(request);
            item.setMenuItemId(dto.getMenuItemId());
            item.setQuantity(dto.getQuantity());
            item.setUnitPrice(menuItem.getPrice());
            entities.add(item);
        }
        return entities;
    }

    private List<OrderItemDto> toOrderItemDtos(List<OrderChangeRequestItem> items) {
        return items.stream()
                .map(item -> new OrderItemDto(item.getMenuItemId(), item.getQuantity()))
                .toList();
    }

    private void replaceOrderItems(Long orderId, List<OrderItemDto> newItems) {
        orderItemRepository.deleteByOrderId(orderId);
        for (OrderItemDto dto : newItems) {
            OrderItem item = new OrderItem();
            item.setOrderId(orderId);
            item.setMenuItemId(dto.getMenuItemId());
            item.setQuantity(dto.getQuantity());
            orderItemRepository.save(item);
        }
    }

    private ReservationChangeRequestResponseDto toResponse(OrderChangeRequest request) {
        List<OrderChangeRequestItem> items = changeRequestItemRepository.findByChangeRequestId(request.getId());
        return toResponse(request, items);
    }

    private ReservationChangeRequestResponseDto toResponse(OrderChangeRequest request, List<OrderChangeRequestItem> items) {
        Map<Long, MenuItem> lookup = menuItemRepository.findAllById(
                        items.stream().map(OrderChangeRequestItem::getMenuItemId).toList())
                .stream()
                .collect(Collectors.toMap(MenuItem::getId, Function.identity()));

        ReservationChangeQuote quote = new ReservationChangeQuote(
                request.getAlreadyPaidAmount(),
                request.getRecalculatedAmount(),
                request.getChangeFeeAmount()
        );

        List<ReservationChangeRequestItemResponseDto> itemDtos = items.stream()
                .map(item -> {
                    MenuItem menuItem = lookup.get(item.getMenuItemId());
                    return ReservationChangeRequestItemResponseDto.builder()
                            .menuItemId(item.getMenuItemId())
                            .quantity(item.getQuantity())
                            .name(menuItem != null ? menuItem.getName() : null)
                            .unitPrice(item.getUnitPrice())
                            .lineTotal(item.getUnitPrice() * item.getQuantity())
                            .build();
                })
                .toList();

        return ReservationChangeRequestResponseDto.builder()
                .id(request.getId())
                .orderId(request.getOrderId())
                .status(request.getStatus().name())
                .originalTotalAmount(request.getOriginalTotalAmount())
                .recalculatedAmount(request.getRecalculatedAmount())
                .changeFeeAmount(request.getChangeFeeAmount())
                .newTotalAmount(request.getNewTotalAmount())
                .alreadyPaidAmount(request.getAlreadyPaidAmount())
                .extraChargeAmount(request.getExtraChargeAmount())
                .expectedRefundAmount(quote.expectedRefundAmount())
                .requiresAdditionalPayment(quote.requiresAdditionalPayment())
                .requiresRefund(quote.requiresRefund())
                .changeFeeApplied(Boolean.TRUE.equals(request.getChangeFeeApplied()))
                .newDinnerTypeId(request.getNewDinnerTypeId())
                .newServingStyle(request.getNewServingStyle())
                .newDeliveryTime(request.getNewDeliveryTime())
                .newDeliveryAddress(request.getNewDeliveryAddress())
                .reason(request.getReason())
                .adminComment(request.getAdminComment())
                .requestedAt(request.getRequestedAt())
                .approvedAt(request.getApprovedAt())
                .rejectedAt(request.getRejectedAt())
                .items(itemDtos)
                .build();
    }
}

