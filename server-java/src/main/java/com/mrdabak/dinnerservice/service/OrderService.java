package com.mrdabak.dinnerservice.service;

import com.mrdabak.dinnerservice.dto.OrderItemDto;
import com.mrdabak.dinnerservice.dto.OrderRequest;
import com.mrdabak.dinnerservice.model.*;
import com.mrdabak.dinnerservice.repository.*;
import com.mrdabak.dinnerservice.repository.order.OrderRepository;
import com.mrdabak.dinnerservice.repository.order.OrderItemRepository;
import com.mrdabak.dinnerservice.util.DeliveryTimeUtils;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Service
public class OrderService {

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final DinnerTypeRepository dinnerTypeRepository;
    private final MenuItemRepository menuItemRepository;
    private final InventoryService inventoryService;
    private final DeliverySchedulingService deliverySchedulingService;
    private final UserRepository userRepository;
    private final TransactionTemplate orderTxTemplate;

    public OrderService(OrderRepository orderRepository, OrderItemRepository orderItemRepository,
                       DinnerTypeRepository dinnerTypeRepository, MenuItemRepository menuItemRepository,
                       InventoryService inventoryService, DeliverySchedulingService deliverySchedulingService,
                       UserRepository userRepository,
                       @Qualifier("orderTransactionManager") PlatformTransactionManager orderTransactionManager) {
        this.orderRepository = orderRepository;
        this.orderItemRepository = orderItemRepository;
        this.dinnerTypeRepository = dinnerTypeRepository;
        this.menuItemRepository = menuItemRepository;
        this.inventoryService = inventoryService;
        this.deliverySchedulingService = deliverySchedulingService;
        this.userRepository = userRepository;
        this.orderTxTemplate = new TransactionTemplate(orderTransactionManager);
    }

    public Order createOrder(Long userId, OrderRequest request) {
        int maxRetries = 10;
        int retryCount = 0;
        long baseDelay = 100; // Start with 100ms
        
        while (retryCount < maxRetries) {
            int attempt = retryCount + 1;
            System.out.println("[OrderService] createOrder attempt " + attempt + "/" + maxRetries + " for user " + userId);
            try {
                return orderTxTemplate.execute(status -> createOrderInternal(userId, request));
            } catch (Exception e) {
                String errorMessage = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
                String causeMessage = "";
                if (e.getCause() != null && e.getCause().getMessage() != null) {
                    causeMessage = e.getCause().getMessage().toLowerCase();
                }
                
                // Check for various SQLite lock errors
                boolean isLocked = errorMessage.contains("database is locked") 
                    || errorMessage.contains("sqlite_busy")
                    || errorMessage.contains("sqlite_busy_snapshot")
                    || causeMessage.contains("database is locked")
                    || causeMessage.contains("sqlite_busy")
                    || causeMessage.contains("sqlite_busy_snapshot");
                
                if (isLocked && retryCount < maxRetries - 1) {
                    retryCount++;
                    long delay = baseDelay * (long) Math.pow(2, retryCount - 1); // Exponential backoff: 100ms, 200ms, 400ms, 800ms...
                    System.out.println("[OrderService] Database locked (SQLITE_BUSY_SNAPSHOT), retrying... (" + retryCount + "/" + maxRetries + ") after " + delay + "ms");
                    try {
                        Thread.sleep(delay);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("Order creation interrupted", ie);
                    }
                } else {
                    // Not a lock error or max retries reached
                    if (isLocked) {
                        throw new RuntimeException("Failed to create order after " + maxRetries + " retries due to database lock", e);
                    } else {
                        throw e;
                    }
                }
            }
        }
        throw new RuntimeException("Failed to create order after " + maxRetries + " retries");
    }
    private Order createOrderInternal(Long userId, OrderRequest request) {
        // Validate input
        if (request.getDeliveryAddress() == null || request.getDeliveryAddress().trim().isEmpty()) {
            throw new RuntimeException("배달 주소는 필수입니다.");
        }
        if (request.getDeliveryTime() == null || request.getDeliveryTime().trim().isEmpty()) {
            throw new RuntimeException("배달 시간은 필수입니다.");
        }

        // Read from main database (menu, dinner types)
        DinnerType dinner = dinnerTypeRepository.findById(request.getDinnerTypeId())
                .orElseThrow(() -> new RuntimeException("유효하지 않은 디너 타입입니다."));

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("사용자를 찾을 수 없습니다."));

        // Validate serving style for Champagne Feast
        if (dinner.getName().contains("샴페인") && !request.getServingStyle().equals("grand") && !request.getServingStyle().equals("deluxe")) {
            throw new RuntimeException("샴페인 축제 디너는 그랜드 또는 디럭스 스타일만 주문 가능합니다.");
        }

        LocalDateTime deliveryDateTime = DeliveryTimeUtils.parseDeliveryTime(request.getDeliveryTime());

        // Calculate price
        Map<String, Double> styleMultipliers = Map.of(
                "simple", 1.0,
                "grand", 1.3,
                "deluxe", 1.6
        );
        double basePrice = dinner.getBasePrice() * styleMultipliers.getOrDefault(request.getServingStyle(), 1.0);

        // Add item prices
        double itemsPrice = 0;
        for (OrderItemDto item : request.getItems()) {
            if (item.getMenuItemId() == null) {
                throw new RuntimeException("Menu item ID is required");
            }
            if (item.getQuantity() == null || item.getQuantity() <= 0) {
                throw new RuntimeException("Menu item quantity must be greater than 0");
            }
            MenuItem menuItem = menuItemRepository.findById(item.getMenuItemId())
                    .orElseThrow(() -> new RuntimeException("Invalid menu item ID: " + item.getMenuItemId()));
            itemsPrice += menuItem.getPrice() * item.getQuantity();
        }

        double totalPrice = basePrice + itemsPrice;

        InventoryService.InventoryReservationPlan inventoryPlan =
                inventoryService.prepareReservations(request.getItems(), deliveryDateTime);
        
        // 주문 생성 시 자동 배달 스케줄 할당 제거 - 관리자가 나중에 할당하도록 함
        // DeliverySchedulingService.DeliveryAssignmentPlan assignmentPlan =
        //         deliverySchedulingService.prepareAssignment(request.getDeliveryAddress(), deliveryDateTime);

        List<Order> previousOrders = orderRepository.findByUserIdOrderByCreatedAtDesc(userId);
        long deliveredOrders = previousOrders.stream()
                .filter(o -> "delivered".equalsIgnoreCase(o.getStatus()))
                .count();
        // 배달 완료 4회 이상부터 (5번째 주문부터) 할인 적용
        boolean loyaltyEligible = Boolean.TRUE.equals(user.getLoyaltyConsent()) && deliveredOrders >= 4;
        double originalPrice = totalPrice;
        if (loyaltyEligible) {
            totalPrice = totalPrice * 0.9;
        }

        // Create order - save to order database
        Order order = new Order();
        order.setUserId(userId);
        order.setDinnerTypeId(request.getDinnerTypeId());
        order.setServingStyle(request.getServingStyle());
        order.setDeliveryTime(request.getDeliveryTime());
        order.setDeliveryAddress(request.getDeliveryAddress());
        order.setTotalPrice((int) Math.round(totalPrice));
        order.setPaymentStatus("pending");
        order.setPaymentMethod(request.getPaymentMethod());
        order.setAdminApprovalStatus("PENDING");

        // 주문 생성 시 직원 자동 할당 제거 - 관리자가 나중에 할당하도록 함
        // 주문은 하나만 생성되며, 직원 할당은 관리자가 스케줄 관리에서 할당
        String threadId = Thread.currentThread().getName() + "-" + Thread.currentThread().getId();
        System.out.println("[OrderService] 주문 생성 시작 - 사용자 ID: " + userId + ", 디너 타입: " + request.getDinnerTypeId());
        System.out.println("[OrderService] 스레드: " + threadId);
        System.out.println("[OrderService] 배달 시간: " + request.getDeliveryTime());
        System.out.println("[OrderService] 배달 주소: " + request.getDeliveryAddress());
        
        // 중복 주문 확인: 동일한 사용자가 동일한 배달 시간과 주소로 최근 5초 이내에 주문을 생성했는지 확인
        // 주의: 첫 주문인 경우에도 이 검사를 통과해야 하므로, createdAt이 null인 경우는 제외
        String deliveryTimeStr = request.getDeliveryTime();
        String deliveryAddressStr = request.getDeliveryAddress();
        List<Order> recentOrders = orderRepository.findByUserIdAndDeliveryTimeAndDeliveryAddress(userId, deliveryTimeStr, deliveryAddressStr);
        
        // 최근 5초 이내에 동일한 주문이 있는지 확인 (10초에서 5초로 단축하여 더 정확하게)
        long fiveSecondsAgo = System.currentTimeMillis() - 5000;
        for (Order recentOrder : recentOrders) {
            // createdAt이 null이거나 최근 5초 이내가 아닌 경우는 중복이 아님
            if (recentOrder.getCreatedAt() != null) {
                try {
                    long createdAtMillis = recentOrder.getCreatedAt().toInstant(java.time.ZoneOffset.UTC).toEpochMilli();
                    if (createdAtMillis > fiveSecondsAgo) {
                        System.out.println("[OrderService] 경고: 최근 5초 이내에 동일한 주문이 이미 존재합니다 - 주문 ID: " + recentOrder.getId());
                        System.out.println("[OrderService] 기존 주문 생성 시간: " + recentOrder.getCreatedAt());
                        System.out.println("[OrderService] 기존 주문 배달 시간: " + recentOrder.getDeliveryTime());
                        System.out.println("[OrderService] 기존 주문 배달 주소: " + recentOrder.getDeliveryAddress());
                        // 중복 주문이면 예외 발생 (중복 방지)
                        throw new RuntimeException("동일한 주문이 최근에 생성되었습니다. 주문 ID: " + recentOrder.getId());
                    }
                } catch (Exception e) {
                    // 시간 변환 실패 시 무시 (첫 주문일 수 있음)
                    System.out.println("[OrderService] 시간 변환 실패, 중복 검사 건너뜀: " + e.getMessage());
                }
            }
        }
        
        Order savedOrder = orderRepository.save(order);
        System.out.println("[OrderService] 주문 저장 완료 - 주문 ID: " + savedOrder.getId());
        System.out.println("[OrderService] 스레드: " + threadId);

        // Add order items - save to order database
        for (OrderItemDto item : request.getItems()) {
            OrderItem orderItem = new OrderItem();
            orderItem.setOrderId(savedOrder.getId());
            orderItem.setMenuItemId(item.getMenuItemId());
            orderItem.setQuantity(item.getQuantity());
            orderItemRepository.save(orderItem);
        }

        inventoryService.commitReservations(savedOrder.getId(), inventoryPlan);

        return savedOrder;
    }

    public List<Order> getUserOrders(Long userId) {
        System.out.println("[OrderService] getUserOrders 호출 - 사용자 ID: " + userId);
        
        try {
            List<Order> orders = orderRepository.findByUserIdOrderByCreatedAtDesc(userId);
            System.out.println("[OrderService] 주문 조회 완료: " + orders.size() + "개");
            
            if (orders.isEmpty()) {
                System.out.println("[OrderService] 주의: 주문이 없습니다.");
            } else {
                System.out.println("[OrderService] 주문 ID 목록: " + orders.stream()
                    .map(o -> o.getId().toString())
                    .collect(java.util.stream.Collectors.joining(", ")));
            }
            
            return orders;
        } catch (Exception e) {
            System.out.println("[OrderService] 주문 조회 중 오류 발생");
            System.out.println("[OrderService] 예외 타입: " + e.getClass().getName());
            System.out.println("[OrderService] 예외 메시지: " + e.getMessage());
            e.printStackTrace();
            throw new RuntimeException("Failed to fetch user orders: " + e.getMessage(), e);
        }
    }

    public Order getOrder(Long orderId, Long userId) {
        return orderRepository.findByIdAndUserId(orderId, userId)
                .orElseThrow(() -> new RuntimeException("Order not found"));
    }

    @Transactional(transactionManager = "orderTransactionManager", rollbackFor = Exception.class)
    public Order cancelOrder(Long orderId, Long userId) {
        if (orderId == null) {
            throw new IllegalArgumentException("주문 ID는 필수입니다.");
        }
        if (userId == null) {
            throw new IllegalArgumentException("사용자 ID는 필수입니다.");
        }

        // For admin, allow cancelling any order; for regular users, only their own orders
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new RuntimeException("주문을 찾을 수 없습니다: " + orderId));

        if ("delivered".equals(order.getStatus())) {
            throw new RuntimeException("이미 배달 완료된 주문은 취소할 수 없습니다.");
        }
        if ("cancelled".equals(order.getStatus())) {
            throw new RuntimeException("이미 취소된 주문입니다.");
        }

        // 조리 시작 이후 취소 시 재고는 이미 소진되었으므로 재고 예약 취소하지 않음
        boolean isCookingStarted = "cooking".equals(order.getStatus()) || 
                                   "ready".equals(order.getStatus()) || 
                                   "out_for_delivery".equals(order.getStatus());
        
        boolean inventoryCancelled = false;
        if (!isCookingStarted) {
            // 조리 시작 전이면 재고 예약 취소
            try {
                inventoryService.releaseReservationsForOrder(orderId);
                inventoryCancelled = true;
                System.out.println("[OrderService] 주문 " + orderId + "의 재고 예약이 취소되었습니다.");
            } catch (Exception e) {
                System.err.println("[OrderService] 재고 예약 취소 실패: " + e.getMessage());
                e.printStackTrace();
                // Continue with cancellation even if inventory release fails
            }
        } else {
            System.out.println("[OrderService] 주문 " + orderId + "는 조리 시작 이후 취소로 인해 재고는 이미 소진되었습니다.");
        }

        // Cancel delivery schedule (main database)
        boolean scheduleCancelled = false;
        try {
            deliverySchedulingService.cancelScheduleForOrder(orderId);
            scheduleCancelled = true;
            System.out.println("[OrderService] 주문 " + orderId + "의 배달 스케줄이 취소되었습니다.");
        } catch (Exception e) {
            System.err.println("[OrderService] 배달 스케줄 취소 실패: " + e.getMessage());
            e.printStackTrace();
            // Continue with cancellation even if schedule cancellation fails
        }

        // Update order status (order database)
        try {
            order.setStatus("cancelled");
            if (!"REJECTED".equalsIgnoreCase(order.getAdminApprovalStatus())) {
                order.setAdminApprovalStatus("CANCELLED");
            }
            Order cancelledOrder = orderRepository.save(order);
            System.out.println("[OrderService] 주문 " + orderId + "가 취소되었습니다. (재고: " + 
                    (inventoryCancelled ? "취소됨" : "실패") + ", 스케줄: " + 
                    (scheduleCancelled ? "취소됨" : "실패") + ")");
            return cancelledOrder;
        } catch (Exception e) {
            System.err.println("[OrderService] 주문 상태 업데이트 실패: " + e.getMessage());
            e.printStackTrace();
            throw new RuntimeException("주문 취소 처리 중 오류가 발생했습니다: " + e.getMessage(), e);
        }
    }

    @Transactional(transactionManager = "orderTransactionManager", rollbackFor = Exception.class)
    public void markOrderAsDelivered(Long orderId) {
        if (orderId == null) {
            throw new IllegalArgumentException("주문 ID는 필수입니다.");
        }

        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new RuntimeException("주문을 찾을 수 없습니다: " + orderId));

        if ("cancelled".equals(order.getStatus())) {
            throw new RuntimeException("취소된 주문은 배달 완료 처리할 수 없습니다.");
        }
        if ("delivered".equals(order.getStatus())) {
            System.out.println("[OrderService] 주문 " + orderId + "는 이미 배달 완료 상태입니다.");
            return; // Already delivered, no need to process again
        }

        // 재고는 주문 생성 시 이미 차감되었으므로, 배달 완료 시에는 추가 처리 불필요
        // (매일 자정에 전날 예약이 자동으로 삭제됨)
        System.out.println("[OrderService] 주문 " + orderId + " 배달 완료 - 재고는 주문 시 이미 차감되었습니다.");

        // Update order status (order database)
        try {
            order.setStatus("delivered");
            orderRepository.save(order);
            System.out.println("[OrderService] 주문 " + orderId + "가 배달 완료로 처리되었습니다.");
        } catch (Exception e) {
            System.err.println("[OrderService] 주문 상태 업데이트 실패: " + e.getMessage());
            e.printStackTrace();
            // If order status update fails after inventory consumption, 
            // we should ideally rollback, but since we're using separate databases,
            // we'll log the error and throw exception
            throw new RuntimeException("주문 상태 업데이트 중 오류가 발생했습니다: " + e.getMessage(), e);
        }
    }

    @Transactional(transactionManager = "orderTransactionManager", rollbackFor = Exception.class)
    public Order modifyOrder(Long orderId, Long userId, OrderRequest request) {
        if (orderId == null) {
            throw new IllegalArgumentException("주문 ID는 필수입니다.");
        }
        if (userId == null) {
            throw new IllegalArgumentException("사용자 ID는 필수입니다.");
        }

        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new RuntimeException("주문을 찾을 수 없습니다: " + orderId));

        // 주문 소유자 확인
        if (!order.getUserId().equals(userId)) {
            throw new RuntimeException("이 주문을 수정할 권한이 없습니다.");
        }

        // 주문 상태 확인: PENDING 또는 APPROVED 상태만 수정 가능
        String approvalStatus = order.getAdminApprovalStatus() != null ? order.getAdminApprovalStatus().toUpperCase() : "PENDING";
        if (!"PENDING".equals(approvalStatus) && !"APPROVED".equals(approvalStatus)) {
            throw new RuntimeException("이 주문은 수정할 수 없습니다. (승인 상태: " + approvalStatus + ")");
        }

        // 취소되거나 배달 완료된 주문은 수정 불가
        if ("cancelled".equalsIgnoreCase(order.getStatus()) || "delivered".equalsIgnoreCase(order.getStatus())) {
            throw new RuntimeException("취소되거나 배달 완료된 주문은 수정할 수 없습니다.");
        }

        // APPROVED 상태의 주문은 배달 3시간 전까지 수정 가능
        if ("APPROVED".equals(approvalStatus)) {
            LocalDateTime deliveryDateTime = order.getDeliveryDateTime();
            LocalDateTime now = LocalDateTime.now();
            long hoursUntilDelivery = java.time.Duration.between(now, deliveryDateTime).toHours();
            
            if (hoursUntilDelivery < 3) {
                throw new RuntimeException("주문 수정 가능 기한이 지났습니다. (배달 시간 3시간 전 이후에는 변경 불가)");
            }
        }
        // PENDING 상태의 주문은 항상 수정 가능 (아직 승인 전이므로)

        // 새 배달 시간 검증: 최소 3시간 전에 주문 가능
        LocalDateTime newDeliveryDateTime = DeliveryTimeUtils.parseDeliveryTime(request.getDeliveryTime());
        LocalDateTime now = LocalDateTime.now();
        long hoursUntilDelivery = java.time.Duration.between(now, newDeliveryDateTime).toHours();
        
        if (hoursUntilDelivery < 3) {
            throw new RuntimeException("배달 시간은 최소 3시간 전에 주문해야 합니다.");
        }

        System.out.println("[OrderService] 주문 수정 시작 - 기존 주문 ID: " + orderId + ", 사용자 ID: " + userId);

        // 당일 예약 변경 수수료 계산 (만원)
        LocalDate today = LocalDate.now();
        LocalDate deliveryDate = newDeliveryDateTime.toLocalDate();
        boolean isSameDayModification = today.equals(deliveryDate);
        int modificationFee = isSameDayModification ? 10000 : 0; // 당일 변경 시 만원 추가
        
        System.out.println("[OrderService] 주문 수정 수수료 계산 - 당일 변경: " + isSameDayModification + 
                ", 수수료: " + modificationFee + "원");

        // 기존 주문 취소 처리 (재귀 호출 방지를 위해 직접 처리)
        order.setStatus("cancelled");
        order.setAdminApprovalStatus("CANCELLED");
        
        // 재고 예약 취소
        try {
            inventoryService.releaseReservationsForOrder(orderId);
            System.out.println("[OrderService] 기존 주문 " + orderId + "의 재고 예약 취소 완료");
        } catch (Exception e) {
            System.err.println("[OrderService] 재고 예약 취소 실패: " + e.getMessage());
            e.printStackTrace();
        }
        
        // 배달 스케줄 취소
        try {
            deliverySchedulingService.cancelScheduleForOrder(orderId);
            System.out.println("[OrderService] 기존 주문 " + orderId + "의 배달 스케줄 취소 완료");
        } catch (Exception e) {
            System.err.println("[OrderService] 배달 스케줄 취소 실패: " + e.getMessage());
            e.printStackTrace();
        }
        
        orderRepository.save(order);
        System.out.println("[OrderService] 기존 주문 " + orderId + " 취소 완료");
        
        // 새 주문 생성 (관리자 승인 필요 - PENDING 상태로 생성)
        // 수수료가 있으면 주문 금액에 추가
        Order newOrder = createOrderInternal(userId, request);
        if (modificationFee > 0) {
            int newTotalPrice = newOrder.getTotalPrice() + modificationFee;
            newOrder.setTotalPrice(newTotalPrice);
            newOrder = orderRepository.save(newOrder);
            System.out.println("[OrderService] 주문 수정 수수료 적용 - 기존 금액: " + 
                    (newOrder.getTotalPrice() - modificationFee) + "원, 수수료: " + modificationFee + 
                    "원, 최종 금액: " + newOrder.getTotalPrice() + "원");
        }
        System.out.println("[OrderService] 신규 주문 생성 완료 - 주문 ID: " + newOrder.getId() + 
                ", 승인 상태: " + newOrder.getAdminApprovalStatus() + 
                ", 총 금액: " + newOrder.getTotalPrice() + "원 (수수료 포함)");
        return newOrder;
    }

}
