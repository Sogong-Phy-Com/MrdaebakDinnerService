package com.mrdabak.dinnerservice.controller;

import com.mrdabak.dinnerservice.dto.OrderRequest;
import com.mrdabak.dinnerservice.dto.ReservationChangeRequestCreateDto;
import com.mrdabak.dinnerservice.dto.ReservationChangeRequestResponseDto;
import com.mrdabak.dinnerservice.model.MenuItem;
import com.mrdabak.dinnerservice.model.Order;
import com.mrdabak.dinnerservice.model.OrderItem;
import com.mrdabak.dinnerservice.model.User;
import com.mrdabak.dinnerservice.model.DinnerType;
import com.mrdabak.dinnerservice.repository.MenuItemRepository;
import com.mrdabak.dinnerservice.repository.UserRepository;
import com.mrdabak.dinnerservice.repository.DinnerTypeRepository;
import com.mrdabak.dinnerservice.repository.order.OrderItemRepository;
import com.mrdabak.dinnerservice.repository.order.OrderRepository;
import com.mrdabak.dinnerservice.service.OrderChangeRequestService;
import com.mrdabak.dinnerservice.service.OrderService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/orders")
public class OrderController {
    
    // 중복 주문 생성 방지를 위한 임시 저장소 (요청 ID 기반)
    private final java.util.concurrent.ConcurrentHashMap<String, Long> pendingOrders = new java.util.concurrent.ConcurrentHashMap<>();
    
    // 사용자별 마지막 주문 시간 추적 (50초 제한)
    private final java.util.concurrent.ConcurrentHashMap<Long, Long> userLastOrderTime = new java.util.concurrent.ConcurrentHashMap<>();
    
    // 사용자별 주문 생성 락 (동시 주문 완전 차단)
    private final java.util.concurrent.ConcurrentHashMap<Long, Object> userOrderLocks = new java.util.concurrent.ConcurrentHashMap<>();

    private final OrderService orderService;
    private final OrderItemRepository orderItemRepository;
    private final MenuItemRepository menuItemRepository;
    private final OrderChangeRequestService orderChangeRequestService;
    private final UserRepository userRepository;
    private final DinnerTypeRepository dinnerTypeRepository;
    private final OrderRepository orderRepository;

    public OrderController(OrderService orderService, OrderItemRepository orderItemRepository,
                          MenuItemRepository menuItemRepository,
                          OrderChangeRequestService orderChangeRequestService,
                          UserRepository userRepository,
                          DinnerTypeRepository dinnerTypeRepository,
                          OrderRepository orderRepository) {
        this.orderService = orderService;
        this.orderItemRepository = orderItemRepository;
        this.menuItemRepository = menuItemRepository;
        this.orderChangeRequestService = orderChangeRequestService;
        this.userRepository = userRepository;
        this.dinnerTypeRepository = dinnerTypeRepository;
        this.orderRepository = orderRepository;
    }

    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> getUserOrders(Authentication authentication) {
        System.out.println("[주문 목록 조회 API] 요청 시작");
        
        try {
            // 1단계: 인증 정보 확인
            if (authentication == null) {
                System.out.println("[에러 1] Authentication 객체가 null입니다.");
                return ResponseEntity.status(401).body(List.of(Map.of("error", "Authentication is null")));
            }
            
            String authName = authentication.getName();
            System.out.println("[1단계] Authentication.getName(): " + authName);
            
            if (authName == null || authName.isEmpty()) {
                System.out.println("[에러 2] Authentication.getName()이 null이거나 비어있습니다.");
                return ResponseEntity.status(401).body(List.of(Map.of("error", "User ID is null or empty")));
            }
            
            // 2단계: 사용자 ID 파싱
            Long userId;
            try {
                userId = Long.parseLong(authName);
                System.out.println("[2단계] 사용자 ID 파싱 성공: " + userId);
            } catch (NumberFormatException e) {
                System.out.println("[에러 3] 사용자 ID 파싱 실패: " + authName);
                System.out.println("[에러 3] 예외: " + e.getMessage());
                return ResponseEntity.status(401).body(List.of(Map.of("error", "Invalid user ID format: " + authName)));
            }
            
            // 3단계: 주문 조회
            System.out.println("[3단계] 주문 조회 시작 (사용자 ID: " + userId + ")");
            List<Order> orders = orderService.getUserOrders(userId);
            System.out.println("[3단계] 주문 조회 완료: " + orders.size() + "개 주문 발견");
            
            // 4단계: 주문 데이터 변환
            System.out.println("[4단계] 주문 데이터 변환 시작");
            List<Map<String, Object>> orderDtos = orders.stream().map(order -> {
            Map<String, Object> orderMap = new HashMap<>();
            orderMap.put("id", order.getId());
            orderMap.put("dinner_type_id", order.getDinnerTypeId());
            orderMap.put("serving_style", order.getServingStyle());
            orderMap.put("delivery_time", order.getDeliveryTime());
            orderMap.put("delivery_address", order.getDeliveryAddress());
            orderMap.put("total_price", order.getTotalPrice());
            orderMap.put("status", order.getStatus());
            orderMap.put("payment_status", order.getPaymentStatus());
            orderMap.put("created_at", order.getCreatedAt());
            orderMap.put("admin_approval_status", order.getAdminApprovalStatus());

            List<OrderItem> items = orderItemRepository.findByOrderId(order.getId());
            List<Map<String, Object>> itemDtos = items.stream().map(item -> {
                MenuItem menuItem = menuItemRepository.findById(item.getMenuItemId()).orElse(null);
                Map<String, Object> itemMap = new HashMap<>();
                itemMap.put("id", item.getId());
                itemMap.put("menu_item_id", item.getMenuItemId());
                itemMap.put("quantity", item.getQuantity());
                if (menuItem != null) {
                    itemMap.put("name", menuItem.getName());
                    itemMap.put("name_en", menuItem.getNameEn());
                    itemMap.put("price", menuItem.getPrice());
                }
                return itemMap;
            }).toList();
            orderMap.put("items", itemDtos);
            return orderMap;
        }).toList();
        
        System.out.println("[4단계] 주문 데이터 변환 완료: " + orderDtos.size() + "개");
        System.out.println("[성공] 주문 목록 조회 API 완료");
        
        return ResponseEntity.ok(orderDtos);
        } catch (Exception e) {
            System.out.println("[에러 4] 예상치 못한 오류 발생");
            System.out.println("[에러 4] 예외 타입: " + e.getClass().getName());
            System.out.println("[에러 4] 예외 메시지: " + e.getMessage());
            e.printStackTrace();
            return ResponseEntity.status(500).body(List.of(Map.of("error", "Internal server error: " + e.getMessage())));
        }
    }

    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getOrderStats(Authentication authentication) {
        try {
            if (authentication == null || authentication.getName() == null || authentication.getName().isEmpty()) {
                return ResponseEntity.status(401).body(Map.of("error", "Authentication required"));
            }

            Long userId = Long.parseLong(authentication.getName());
            List<Order> orders = orderService.getUserOrders(userId);
            
            long totalOrders = orders.size();
            long deliveredOrders = orders.stream().filter(o -> "delivered".equals(o.getStatus())).count();
            long pendingOrders = orders.stream().filter(o -> 
                "pending".equals(o.getStatus()) || 
                "cooking".equals(o.getStatus()) || 
                "ready".equals(o.getStatus()) || 
                "out_for_delivery".equals(o.getStatus())
            ).count();
            
            return ResponseEntity.ok(Map.of(
                "totalOrders", totalOrders,
                "deliveredOrders", deliveredOrders,
                "pendingOrders", pendingOrders
            ));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping
    public ResponseEntity<?> createOrder(
            @Valid @RequestBody OrderRequest request, 
            Authentication authentication,
            @RequestHeader(value = "X-Request-ID", required = false) String requestId) {
        String threadId = Thread.currentThread().getName() + "-" + Thread.currentThread().getId();
        System.out.println("========== [주문 생성 API] 요청 시작 ==========");
        System.out.println("[주문 생성 API] 스레드: " + threadId);
        System.out.println("[주문 생성 API] Request ID: " + (requestId != null ? requestId : "없음"));
        System.out.println("[주문 생성 API] Authentication 객체: " + (authentication != null ? "존재" : "null"));
        
        // SecurityContext에서 직접 확인
        org.springframework.security.core.Authentication contextAuth = 
            org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        System.out.println("[주문 생성 API] SecurityContext 인증: " + (contextAuth != null ? "존재" : "null"));
        if (contextAuth != null) {
            System.out.println("[주문 생성 API] SecurityContext 사용자: " + contextAuth.getName());
            System.out.println("[주문 생성 API] SecurityContext 권한: " + contextAuth.getAuthorities());
        }
        
        try {
            // authentication 파라미터가 null이면 SecurityContext에서 가져오기 시도
            if (authentication == null) {
                System.out.println("[주문 생성 API] 경고: Authentication 파라미터가 null입니다. SecurityContext에서 확인합니다.");
                authentication = contextAuth;
            }
            
            if (authentication == null) {
                System.out.println("[주문 생성 API] 에러: Authentication 객체가 null입니다.");
                System.out.println("==========================================");
                return ResponseEntity.status(401).body(Map.of("error", "Authentication required"));
            }
            
            if (authentication.getName() == null || authentication.getName().isEmpty()) {
                System.out.println("[주문 생성 API] 에러: Authentication.getName()이 null이거나 비어있습니다.");
                System.out.println("[주문 생성 API] Authentication 정보: " + authentication);
                System.out.println("[주문 생성 API] Authentication 권한: " + authentication.getAuthorities());
                System.out.println("==========================================");
                return ResponseEntity.status(401).body(Map.of("error", "User ID not found in authentication"));
            }
            
            System.out.println("[주문 생성 API] 인증된 사용자: " + authentication.getName());
            System.out.println("[주문 생성 API] 인증 권한: " + authentication.getAuthorities());
            
            Long userId = Long.parseLong(authentication.getName());
            
            // 사용자별 락 객체 가져오기 (없으면 생성)
            Object userLock = userOrderLocks.computeIfAbsent(userId, k -> new Object());
            
            // 사용자별 락을 사용하여 동시 주문 완전 차단 (최초 주문 포함)
            synchronized (userLock) {
                // 같은 계정으로 50초 이내에 하나의 주문만 가능하도록 제한
                long currentTime = System.currentTimeMillis();
                Long lastOrderTime = userLastOrderTime.get(userId);
                if (lastOrderTime != null) {
                    long timeSinceLastOrder = currentTime - lastOrderTime;
                    if (timeSinceLastOrder < 50000) { // 50초 미만
                        long remainingSeconds = (50000 - timeSinceLastOrder) / 1000;
                        System.out.println("[주문 생성 API] 중복 주문 방지 - 마지막 주문으로부터 " + timeSinceLastOrder + "ms 경과, " + remainingSeconds + "초 후 가능");
                        return ResponseEntity.status(429).body(Map.of(
                                "error", "같은 계정으로 50초 이내에는 하나의 주문만 가능합니다. " + remainingSeconds + "초 후 다시 시도해주세요."
                        ));
                    }
                }
                
                // 사용자별 마지막 주문 시간을 즉시 업데이트 (다른 요청이 들어오기 전에 차단)
                userLastOrderTime.put(userId, currentTime);
            
                // Validate request
                if (request.getDinnerTypeId() == null) {
                    return ResponseEntity.badRequest().body(Map.of("error", "Dinner type is required"));
                }
                if (request.getServingStyle() == null || request.getServingStyle().isEmpty()) {
                    return ResponseEntity.badRequest().body(Map.of("error", "Serving style is required"));
                }
                if (request.getDeliveryTime() == null || request.getDeliveryTime().isEmpty()) {
                    return ResponseEntity.badRequest().body(Map.of("error", "Delivery time is required"));
                }
                if (request.getDeliveryAddress() == null || request.getDeliveryAddress().isEmpty()) {
                    return ResponseEntity.badRequest().body(Map.of("error", "Delivery address is required"));
                }
                if (request.getItems() == null || request.getItems().isEmpty()) {
                    return ResponseEntity.badRequest().body(Map.of("error", "Order items are required"));
                }
                
                // 중복 주문 생성 방지: Request ID 또는 동일한 요청이 50초 이내에 들어오면 거부
                String requestKey;
                
                if (requestId != null && !requestId.trim().isEmpty()) {
                    // Request ID가 있으면 이를 사용 (프론트엔드에서 전송한 고유 ID)
                    requestKey = userId + "|" + requestId.trim();
                    System.out.println("[주문 생성 API] Request ID 사용: " + requestId);
                } else {
                    // Request ID가 없으면 더 정확한 키 생성 (밀리초 단위)
                    requestKey = userId + "|" + request.getDeliveryTime() + "|" + request.getDeliveryAddress() + "|" + currentTime;
                    System.out.println("[주문 생성 API] Request ID 없음, 타임스탬프 기반 키 사용: " + requestKey);
                }
                
                // 먼저 pendingOrders에서 확인
                Long existingOrderId = pendingOrders.get(requestKey);
                if (existingOrderId != null && existingOrderId != -1L) {
                    System.out.println("[주문 생성 API] 중복 요청 감지 - 요청 키: " + requestKey + ", 기존 주문 ID: " + existingOrderId);
                    return ResponseEntity.status(409).body(Map.of(
                            "error", "동일한 주문이 이미 처리 중입니다.",
                            "order_id", existingOrderId
                    ));
                }
                
                // 처리 중인 주문 확인 (-1L은 처리 중임을 나타냄)
                if (existingOrderId != null && existingOrderId == -1L) {
                    System.out.println("[주문 생성 API] 동일한 주문이 이미 처리 중입니다 - 요청 키: " + requestKey);
                    return ResponseEntity.status(409).body(Map.of(
                            "error", "동일한 주문이 이미 처리 중입니다. 잠시 후 다시 시도해주세요."
                    ));
                }
                
                // 추가 검증: 최근 50초 이내에 동일한 사용자가 주문을 생성했는지 확인
                String baseRequestKey = userId + "|" + request.getDeliveryTime() + "|" + request.getDeliveryAddress();
                long fiftySecondsAgo = currentTime - 50000;
                for (Map.Entry<String, Long> entry : pendingOrders.entrySet()) {
                    if (entry.getKey().startsWith(baseRequestKey + "|")) {
                        // 키에서 타임스탬프 추출
                        String[] parts = entry.getKey().split("\\|");
                        if (parts.length >= 4) {
                            try {
                                long entryTime = Long.parseLong(parts[3]);
                                if (entryTime > fiftySecondsAgo) {
                                    System.out.println("[주문 생성 API] 중복 요청 감지 (기본 키) - 요청 키: " + entry.getKey() + ", 기존 주문 ID: " + entry.getValue());
                                    return ResponseEntity.status(409).body(Map.of(
                                            "error", "동일한 주문이 이미 처리 중입니다.",
                                            "order_id", entry.getValue()
                                    ));
                                }
                            } catch (NumberFormatException e) {
                                // 타임스탬프 파싱 실패 시 무시
                            }
                        }
                    }
                }
                
                // pendingOrders에 추가 (처리 시작 표시) - 락 내부에서 즉시 추가
                pendingOrders.put(requestKey, -1L); // -1은 처리 중임을 나타냄
            
                System.out.println("[주문 생성 API] 주문 서비스 호출 전 - 사용자 ID: " + userId);
                System.out.println("[주문 생성 API] 스레드: " + threadId);
                System.out.println("[주문 생성 API] Request ID: " + (requestId != null ? requestId : "없음"));
                System.out.println("[주문 생성 API] 배달 시간: " + request.getDeliveryTime());
                System.out.println("[주문 생성 API] 배달 주소: " + request.getDeliveryAddress());
                
                Order order = orderService.createOrder(userId, request);
                
                System.out.println("[주문 생성 API] 주문 서비스 호출 완료 - 주문 ID: " + order.getId());
                System.out.println("[주문 생성 API] 스레드: " + threadId);
                System.out.println("[주문 생성 API] Request ID: " + (requestId != null ? requestId : "없음"));
                System.out.println("[주문 생성 API] 주문은 1개만 생성되었습니다.");
                
                // 주문 생성 완료 후 pendingOrders 업데이트 및 50초 후에 제거
                pendingOrders.put(requestKey, order.getId());
                new java.util.Timer().schedule(new java.util.TimerTask() {
                    @Override
                    public void run() {
                        pendingOrders.remove(requestKey);
                        System.out.println("[주문 생성 API] 중복 방지 키 제거: " + requestKey);
                    }
                }, 50000); // 50초
                
                // 사용자별 마지막 주문 시간도 50초 후에 제거 (선택적)
                final long finalCurrentTime = currentTime;
                new java.util.Timer().schedule(new java.util.TimerTask() {
                    @Override
                    public void run() {
                        Long storedTime = userLastOrderTime.get(userId);
                        if (storedTime != null && storedTime.equals(finalCurrentTime)) {
                            userLastOrderTime.remove(userId);
                            System.out.println("[주문 생성 API] 사용자 " + userId + "의 주문 제한 해제");
                        }
                    }
                }, 50000); // 50초
                
            // 할인 정보 계산
            User user = userRepository.findById(userId).orElse(null);
            List<Order> previousOrders = orderRepository.findByUserIdOrderByCreatedAtDesc(userId);
            long deliveredOrders = previousOrders.stream()
                    .filter(o -> "delivered".equalsIgnoreCase(o.getStatus()))
                    .count();
            boolean loyaltyEligible = user != null && Boolean.TRUE.equals(user.getLoyaltyConsent()) && deliveredOrders >= 4;
            
            // 원래 가격 계산 (할인 전)
            DinnerType dinner = dinnerTypeRepository.findById(request.getDinnerTypeId()).orElse(null);
            double originalPrice = 0;
            if (dinner != null) {
                Map<String, Double> styleMultipliers = Map.of(
                        "simple", 1.0,
                        "grand", 1.3,
                        "deluxe", 1.6
                );
                double basePrice = dinner.getBasePrice() * styleMultipliers.getOrDefault(request.getServingStyle(), 1.0);
                double itemsPrice = 0;
                for (com.mrdabak.dinnerservice.dto.OrderItemDto item : request.getItems()) {
                    MenuItem menuItem = menuItemRepository.findById(item.getMenuItemId()).orElse(null);
                    if (menuItem != null) {
                        itemsPrice += menuItem.getPrice() * item.getQuantity();
                    }
                }
                originalPrice = basePrice + itemsPrice;
            }
            
            Map<String, Object> responseBody = new HashMap<>();
            responseBody.put("message", "Order created successfully");
            responseBody.put("order_id", order.getId());
            responseBody.put("total_price", order.getTotalPrice());
            responseBody.put("loyalty_discount_applied", loyaltyEligible);
            if (loyaltyEligible) {
                responseBody.put("original_price", (int) Math.round(originalPrice));
                responseBody.put("discount_amount", (int) Math.round(originalPrice - order.getTotalPrice()));
                responseBody.put("discount_percentage", 10);
                responseBody.put("delivered_orders_count", deliveredOrders);
            }
            
            return ResponseEntity.status(201).body(responseBody);
            } // synchronized 블록 종료
        } catch (NumberFormatException e) {
            return ResponseEntity.status(401).body(Map.of("error", "Invalid user ID"));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", "Internal server error: " + e.getMessage()));
        }
    }

    @PostMapping("/{orderId}/modify")
    public ResponseEntity<?> modifyOrder(@PathVariable Long orderId, 
                                        @Valid @RequestBody OrderRequest request, 
                                        Authentication authentication) {
        try {
            if (authentication == null || authentication.getName() == null || authentication.getName().isEmpty()) {
                return ResponseEntity.status(401).body(Map.of("error", "Authentication required"));
            }

            Long userId = Long.parseLong(authentication.getName());
            
            // 기존 주문 삭제 후 신규 주문 생성 방식으로 수정
            Order newOrder = orderService.modifyOrder(orderId, userId, request);
            
            return ResponseEntity.ok(Map.of(
                    "message", "주문이 수정되었습니다. 기존 주문은 취소되었고, 새 주문이 생성되었습니다. 관리자 승인을 기다려주세요.",
                    "order_id", newOrder.getId(),
                    "admin_approval_status", newOrder.getAdminApprovalStatus(),
                    "status", newOrder.getStatus(),
                    "new_order_total_price", newOrder.getTotalPrice()
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", "Internal server error: " + e.getMessage()));
        }
    }

    @PostMapping("/{orderId}/cancel")
    public ResponseEntity<?> cancelOrder(@PathVariable Long orderId, Authentication authentication) {
        try {
            if (authentication == null || authentication.getName() == null || authentication.getName().isEmpty()) {
                return ResponseEntity.status(401).body(Map.of("error", "Authentication required"));
            }

            Long userId = Long.parseLong(authentication.getName());
            Order cancelledOrder = orderService.cancelOrder(orderId, userId);
            
            return ResponseEntity.ok(Map.of(
                    "message", "Order cancelled successfully",
                    "order_id", cancelledOrder.getId()
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", "Internal server error: " + e.getMessage()));
        }
    }
}

