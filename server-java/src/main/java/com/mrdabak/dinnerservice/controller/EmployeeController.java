package com.mrdabak.dinnerservice.controller;

import com.mrdabak.dinnerservice.model.*;
import com.mrdabak.dinnerservice.repository.*;
import com.mrdabak.dinnerservice.repository.order.OrderRepository;
import com.mrdabak.dinnerservice.repository.order.OrderItemRepository;
import com.mrdabak.dinnerservice.repository.schedule.EmployeeWorkAssignmentRepository;
import com.mrdabak.dinnerservice.model.EmployeeWorkAssignment;
import com.mrdabak.dinnerservice.service.DeliverySchedulingService;
import com.mrdabak.dinnerservice.service.OrderService;
import com.mrdabak.dinnerservice.service.InventoryService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import jakarta.annotation.PostConstruct;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/employee")
// @PreAuthorize는 SecurityConfig에서 이미 처리하므로 제거
public class EmployeeController {

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final UserRepository userRepository;
    private final DinnerTypeRepository dinnerTypeRepository;
    private final MenuItemRepository menuItemRepository;
    private final DinnerMenuItemRepository dinnerMenuItemRepository;
    private final DeliverySchedulingService deliverySchedulingService;
    private final OrderService orderService;
    private final InventoryService inventoryService;
    private final com.mrdabak.dinnerservice.repository.schedule.DeliveryScheduleRepository deliveryScheduleRepository;
    private final EmployeeWorkAssignmentRepository employeeWorkAssignmentRepository;

    public EmployeeController(OrderRepository orderRepository, OrderItemRepository orderItemRepository,
                             UserRepository userRepository, DinnerTypeRepository dinnerTypeRepository,
                             MenuItemRepository menuItemRepository,
                             DinnerMenuItemRepository dinnerMenuItemRepository,
                             DeliverySchedulingService deliverySchedulingService,
                             OrderService orderService,
                             InventoryService inventoryService,
                             com.mrdabak.dinnerservice.repository.schedule.DeliveryScheduleRepository deliveryScheduleRepository,
                             EmployeeWorkAssignmentRepository employeeWorkAssignmentRepository) {
        System.out.println("[EmployeeController] 생성자 호출 - 컨트롤러 초기화");
        this.orderRepository = orderRepository;
        this.orderItemRepository = orderItemRepository;
        this.userRepository = userRepository;
        this.dinnerTypeRepository = dinnerTypeRepository;
        this.menuItemRepository = menuItemRepository;
        this.dinnerMenuItemRepository = dinnerMenuItemRepository;
        this.deliverySchedulingService = deliverySchedulingService;
        this.orderService = orderService;
        this.inventoryService = inventoryService;
        this.deliveryScheduleRepository = deliveryScheduleRepository;
        this.employeeWorkAssignmentRepository = employeeWorkAssignmentRepository;
        System.out.println("[EmployeeController] 생성자 완료");
    }

    @PostConstruct
    public void init() {
        System.out.println("========== [EmployeeController] @PostConstruct 호출 - 빈 초기화 완료 ==========");
        System.out.println("[EmployeeController] 컨트롤러가 Spring에 등록되었습니다!");
    }

    @GetMapping("/orders")
    public ResponseEntity<List<Map<String, Object>>> getOrders(
            @RequestParam(required = false) String status,
            Authentication authentication) {
        System.out.println("========== [EmployeeController] getOrders 메서드 호출 ==========");
        System.out.println("[EmployeeController] 주문 목록 조회 요청 - status: " + status);
        if (authentication == null || authentication.getAuthorities() == null) {
            System.out.println("[EmployeeController] 인증 실패");
            return ResponseEntity.status(401).build();
        }
        boolean isAdmin = authentication.getAuthorities().stream()
                .anyMatch(auth -> "ROLE_ADMIN".equals(auth.getAuthority()));
        System.out.println("[EmployeeController] 관리자 여부: " + isAdmin);

        // Get all orders (no date filtering)
        List<Order> orders;
        if (status != null && !status.isEmpty()) {
            orders = orderRepository.findByStatus(status);
        } else {
            orders = orderRepository.findAll();
        }

        // 관리자는 모든 주문을 볼 수 있고, 직원은 APPROVED 주문만 볼 수 있음
        if (!isAdmin) {
            orders = orders.stream()
                    .filter(order -> "APPROVED".equalsIgnoreCase(order.getAdminApprovalStatus()))
                    .toList();
        }

        // 정렬: 날짜/시간 빠른 순, 처리 늦어진 순
        orders = orders.stream().sorted((a, b) -> {
            // 먼저 배달 시간 빠른 순
            try {
                java.time.LocalDateTime aTime = java.time.LocalDateTime.parse(a.getDeliveryTime(), java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME);
                java.time.LocalDateTime bTime = java.time.LocalDateTime.parse(b.getDeliveryTime(), java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME);
                int timeCompare = aTime.compareTo(bTime);
                if (timeCompare != 0) {
                    return timeCompare;
                }
            } catch (Exception e) {
                // 파싱 실패 시 무시하고 다음 정렬 기준 사용
            }
            
            // 같은 시간이면 처리 늦어진 순 (pending > cooking > ready > out_for_delivery > delivered > cancelled)
            java.util.Map<String, Integer> statusOrder = new java.util.HashMap<>();
            statusOrder.put("pending", 0);
            statusOrder.put("cooking", 1);
            statusOrder.put("ready", 2);
            statusOrder.put("out_for_delivery", 3);
            statusOrder.put("delivered", 4);
            statusOrder.put("cancelled", 5);
            
            int aStatusOrder = statusOrder.getOrDefault(a.getStatus() != null ? a.getStatus().toLowerCase() : "", 999);
            int bStatusOrder = statusOrder.getOrDefault(b.getStatus() != null ? b.getStatus().toLowerCase() : "", 999);
            return Integer.compare(aStatusOrder, bStatusOrder);
        }).toList();

        List<Map<String, Object>> orderDtos = orders.stream().map(order -> {
            Map<String, Object> orderMap = new HashMap<>();
            orderMap.put("id", order.getId());
            orderMap.put("user_id", order.getUserId());
            orderMap.put("dinner_type_id", order.getDinnerTypeId());
            orderMap.put("serving_style", order.getServingStyle());
            orderMap.put("delivery_time", order.getDeliveryTime());
            // delivery_address는 아래에서 customer 정보와 함께 마스킹 처리됨
            orderMap.put("total_price", order.getTotalPrice());
            orderMap.put("status", order.getStatus());
            orderMap.put("payment_status", order.getPaymentStatus());
            orderMap.put("created_at", order.getCreatedAt());
            orderMap.put("cooking_employee_id", order.getCookingEmployeeId());
            orderMap.put("delivery_employee_id", order.getDeliveryEmployeeId());
            // adminApprovalStatus가 null이거나 빈 문자열인 경우 "PENDING"으로 설정
            String approvalStatus = order.getAdminApprovalStatus();
            if (approvalStatus == null || approvalStatus.trim().isEmpty()) {
                approvalStatus = "PENDING";
            }
            orderMap.put("admin_approval_status", approvalStatus);
            
            // Add employee names if assigned
            if (order.getCookingEmployeeId() != null) {
                User cookingEmployee = userRepository.findById(order.getCookingEmployeeId()).orElse(null);
                if (cookingEmployee != null) {
                    orderMap.put("cooking_employee_name", cookingEmployee.getName());
                }
            }
            if (order.getDeliveryEmployeeId() != null) {
                User deliveryEmployee = userRepository.findById(order.getDeliveryEmployeeId()).orElse(null);
                if (deliveryEmployee != null) {
                    orderMap.put("delivery_employee_name", deliveryEmployee.getName());
                }
            }

            // Add customer information (주문표에서는 정보 표시)
            User customer = userRepository.findById(order.getUserId()).orElse(null);
            if (customer != null) {
                orderMap.put("customer_name", customer.getName());
                orderMap.put("customer_phone", customer.getPhone());
            }
            orderMap.put("delivery_address", order.getDeliveryAddress());

            // Add dinner type information
            DinnerType dinner = dinnerTypeRepository.findById(order.getDinnerTypeId()).orElse(null);
            if (dinner != null) {
                orderMap.put("dinner_name", dinner.getName());
                orderMap.put("dinner_name_en", dinner.getNameEn());
            }
            
            // 할인 정보 계산 및 추가
            if (customer != null) {
                List<Order> previousOrders = orderRepository.findByUserIdOrderByCreatedAtDesc(order.getUserId());
                long deliveredOrders = previousOrders.stream()
                        .filter(o -> "delivered".equalsIgnoreCase(o.getStatus()))
                        .count();
                // 모든 개인정보 동의(consentName, consentAddress, consentPhone)가 true여야 할인 적용
                boolean allConsentsGiven = Boolean.TRUE.equals(customer.getConsentName()) 
                        && Boolean.TRUE.equals(customer.getConsentAddress()) 
                        && Boolean.TRUE.equals(customer.getConsentPhone());
                boolean loyaltyEligible = Boolean.TRUE.equals(customer.getLoyaltyConsent()) 
                        && allConsentsGiven 
                        && deliveredOrders >= 4;
                
                if (loyaltyEligible && dinner != null) {
                    // 할인이 적용된 경우: 주문 항목을 기반으로 원래 가격 재계산 (기본 제공 항목 제외)
                    Map<String, Double> styleMultipliers = Map.of(
                            "simple", 1.0,
                            "grand", 1.3,
                            "deluxe", 1.6
                    );
                    double basePrice = dinner.getBasePrice() * styleMultipliers.getOrDefault(order.getServingStyle(), 1.0);
                    
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
                    List<OrderItem> items = orderItemRepository.findByOrderId(order.getId());
                    for (OrderItem item : items) {
                        MenuItem menuItem = menuItemRepository.findById(item.getMenuItemId()).orElse(null);
                        if (menuItem != null) {
                            // 기본 제공 수량 확인
                            int defaultQuantity = defaultQuantities.getOrDefault(item.getMenuItemId(), 0);
                            // 추가 수량만 계산 (현재 수량 - 기본 제공 수량)
                            int additionalQuantity = Math.max(0, item.getQuantity() - defaultQuantity);
                            additionalItemsPrice += menuItem.getPrice() * additionalQuantity;
                        }
                    }
                    
                    double originalPrice = basePrice + additionalItemsPrice;
                    double discountedPrice = order.getTotalPrice();
                    int discountAmount = (int) Math.round(originalPrice - discountedPrice);
                    
                    orderMap.put("loyalty_discount_applied", true);
                    orderMap.put("original_price", (int) Math.round(originalPrice));
                    orderMap.put("discount_amount", discountAmount);
                    orderMap.put("discount_percentage", 10);
                } else {
                    orderMap.put("loyalty_discount_applied", false);
                }
            } else {
                orderMap.put("loyalty_discount_applied", false);
            }

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

        return ResponseEntity.ok(orderDtos);
    }

    @GetMapping("/delivery-schedule")
    public ResponseEntity<?> getDeliverySchedule(
            @RequestParam(required = false) String date,
            @RequestParam(required = false) Long employeeId,
            Authentication authentication) {
        if (authentication == null || authentication.getName() == null) {
            return ResponseEntity.status(401).body(Map.of("error", "인증이 필요합니다."));
        }
        
        LocalDate targetDate;
        try {
            targetDate = date != null && !date.trim().isEmpty() 
                    ? LocalDate.parse(date) 
                    : LocalDate.now();
        } catch (Exception e) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "잘못된 날짜 형식입니다. (예: 2025-01-15)"));
        }
        
        try {
            Long requesterId = Long.parseLong(authentication.getName());
            boolean isAdmin = authentication.getAuthorities().stream()
                    .anyMatch(auth -> "ROLE_ADMIN".equals(auth.getAuthority()));

            // If admin selects specific employee, return only that employee's schedule
            List<com.mrdabak.dinnerservice.model.DeliverySchedule> schedules;
            if (isAdmin && employeeId != null) {
                // Admin selected specific employee - return only that employee's schedule
                // Shift times from application.properties or use default values
                java.time.LocalTime shiftStart = java.time.LocalTime.parse("15:00");
                java.time.LocalTime shiftEnd = java.time.LocalTime.parse("22:00");
                java.time.LocalDateTime start = java.time.LocalDateTime.of(targetDate, shiftStart);
                java.time.LocalDateTime end = java.time.LocalDateTime.of(targetDate, shiftEnd);
                schedules = deliveryScheduleRepository.findByEmployeeIdAndDepartureTimeBetween(employeeId, start, end);
            } else {
                // General query (admin sees all, employee sees own schedule)
                schedules = deliverySchedulingService.getSchedulesForUser(requesterId, isAdmin, targetDate);
            }

            List<Map<String, Object>> response = schedules.stream()
                    .map(schedule -> {
                        Map<String, Object> map = new HashMap<>();
                        map.put("id", schedule.getId());
                        map.put("order_id", schedule.getOrderId());
                        map.put("employee_id", schedule.getEmployeeId());
                        map.put("delivery_address", schedule.getDeliveryAddress());
                        map.put("departure_time", schedule.getDepartureTime());
                        map.put("arrival_time", schedule.getArrivalTime());
                        map.put("return_time", schedule.getReturnTime());
                        map.put("one_way_minutes", schedule.getOneWayMinutes());
                        map.put("status", schedule.getStatus());
                        userRepository.findById(schedule.getEmployeeId())
                                .ifPresent(user -> {
                                    map.put("employee_name", user.getName());
                                    map.put("employee_phone", user.getPhone());
                                });
                        return map;
                    }).toList();

            return ResponseEntity.ok(response);
        } catch (NumberFormatException e) {
            return ResponseEntity.status(401).body(Map.of("error", "유효하지 않은 사용자 ID입니다."));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", "배달 스케줄 조회 중 오류가 발생했습니다: " + e.getMessage()));
        }
    }

    @PatchMapping("/delivery-schedule/{id}/status")
    public ResponseEntity<?> updateDeliveryStatus(@PathVariable Long id,
                                                  @RequestBody Map<String, String> request,
                                                  Authentication authentication) {
        if (authentication == null || authentication.getName() == null) {
            return ResponseEntity.status(401).body(Map.of("error", "인증이 필요합니다."));
        }
        
        try {
            Long requesterId = Long.parseLong(authentication.getName());
            boolean isAdmin = authentication.getAuthorities().stream()
                    .anyMatch(auth -> "ROLE_ADMIN".equals(auth.getAuthority()));

            String status = request.get("status");
            if (status == null || status.trim().isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "상태 값은 필수입니다."));
            }

            DeliverySchedule updated = deliverySchedulingService.updateStatus(id, status, requesterId, isAdmin);
            return ResponseEntity.ok(Map.of(
                    "id", updated.getId(),
                    "status", updated.getStatus()
            ));
        } catch (NumberFormatException e) {
            return ResponseEntity.status(401).body(Map.of("error", "유효하지 않은 사용자 ID입니다."));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", "배달 스케줄 상태 업데이트 중 오류가 발생했습니다: " + e.getMessage()));
        }
    }

    @PatchMapping("/orders/{id}/status")
    public ResponseEntity<?> updateOrderStatus(@PathVariable Long id, 
                                                @RequestBody Map<String, String> request,
                                                Authentication authentication) {
        try {
            if (id == null) {
                return ResponseEntity.badRequest().body(Map.of("error", "주문 ID는 필수입니다."));
            }

            if (authentication == null || authentication.getName() == null) {
                return ResponseEntity.status(401).body(Map.of("error", "인증이 필요합니다."));
            }

            Long employeeId = Long.parseLong(authentication.getName());
            boolean isAdmin = authentication.getAuthorities().stream()
                    .anyMatch(auth -> "ROLE_ADMIN".equals(auth.getAuthority()));

            String status = request.get("status");
            if (status == null || status.trim().isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "상태 값은 필수입니다."));
            }

            status = status.trim().toLowerCase();
            if (!List.of("pending", "cooking", "ready", "out_for_delivery", "delivered", "cancelled").contains(status)) {
                return ResponseEntity.badRequest().body(Map.of("error", "유효하지 않은 상태입니다. (pending, cooking, ready, out_for_delivery, delivered, cancelled 중 하나여야 합니다.)"));
            }

            Order order = orderRepository.findById(id)
                    .orElseThrow(() -> new RuntimeException("주문을 찾을 수 없습니다: " + id));

            // 권한 체크: 관리자는 주문 상태 변경 불가, 할당받은 직원만 상태 변경 가능
            if (isAdmin) {
                return ResponseEntity.status(403).body(Map.of("error", "관리자는 주문 상태를 변경할 수 없습니다. 할당받은 직원만 상태를 변경할 수 있습니다."));
            }
            
            // 주문의 배달 시간에서 날짜 추출
            LocalDate orderDate = null;
            try {
                LocalDateTime deliveryDateTime = LocalDateTime.parse(order.getDeliveryTime(), DateTimeFormatter.ISO_LOCAL_DATE_TIME);
                orderDate = deliveryDateTime.toLocalDate();
            } catch (Exception e) {
                try {
                    // 다른 형식 시도
                    orderDate = LocalDate.parse(order.getDeliveryTime().split("T")[0]);
                } catch (Exception e2) {
                    return ResponseEntity.status(400).body(Map.of("error", "주문의 배달 시간 형식이 올바르지 않습니다."));
                }
            }
            
            // 해당 날짜에 관리자가 할당한 작업 확인
            List<EmployeeWorkAssignment> assignments = employeeWorkAssignmentRepository.findByEmployeeIdAndWorkDate(employeeId, orderDate);
            
            boolean hasCookingAssignment = assignments.stream()
                .anyMatch(a -> "COOKING".equalsIgnoreCase(a.getTaskType()));
            boolean hasDeliveryAssignment = assignments.stream()
                .anyMatch(a -> "DELIVERY".equalsIgnoreCase(a.getTaskType()));
            
            if ("cooking".equals(status) || "ready".equals(status)) {
                // 조리 관련 상태는 조리 작업이 할당된 직원만 변경 가능
                if (!hasCookingAssignment) {
                    return ResponseEntity.status(403).body(Map.of("error", "이 주문의 조리 담당 직원만 상태를 변경할 수 있습니다."));
                }
            } else if ("out_for_delivery".equals(status) || "delivered".equals(status)) {
                // 배달 관련 상태는 배달 작업이 할당된 직원만 변경 가능
                if (!hasDeliveryAssignment) {
                    return ResponseEntity.status(403).body(Map.of("error", "이 주문의 배달 담당 직원만 상태를 변경할 수 있습니다."));
                }
            }

            // Prevent invalid status transitions
            if ("cancelled".equals(order.getStatus()) && !"cancelled".equals(status)) {
                return ResponseEntity.badRequest().body(Map.of("error", "취소된 주문의 상태를 변경할 수 없습니다."));
            }
            if ("delivered".equals(order.getStatus()) && !"delivered".equals(status)) {
                return ResponseEntity.badRequest().body(Map.of("error", "배달 완료된 주문의 상태를 변경할 수 없습니다."));
            }

            // If status is being changed to cooking, consume inventory (조리 시작 시 재고 소진)
            if ("cooking".equals(status) && !"cooking".equals(order.getStatus())) {
                System.out.println("[EmployeeController] ========== 조리 시작 (updateOrderStatus) ==========");
                System.out.println("[EmployeeController] 주문 ID: " + id + "의 상태를 cooking으로 변경 - 재고 소진 시작");
                try {
                    inventoryService.consumeReservationsForOrder(id);
                    System.out.println("[EmployeeController] 주문 ID: " + id + "의 재고 소진 완료");
                } catch (Exception e) {
                    System.err.println("[EmployeeController] 주문 ID: " + id + "의 재고 소진 실패: " + e.getMessage());
                    e.printStackTrace();
                    return ResponseEntity.status(500).body(Map.of("error", "재고 소진 처리 중 오류가 발생했습니다: " + e.getMessage()));
                }
            }
            
            // If status is being changed to delivered, consume inventory
            if ("delivered".equals(status) && !"delivered".equals(order.getStatus())) {
                try {
                    orderService.markOrderAsDelivered(id);
                    return ResponseEntity.ok(Map.of("message", "주문이 배달 완료로 처리되었습니다."));
                } catch (IllegalArgumentException e) {
                    return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
                } catch (RuntimeException e) {
                    return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
                } catch (Exception e) {
                    return ResponseEntity.status(500).body(Map.of("error", "배달 완료 처리 중 오류가 발생했습니다: " + e.getMessage()));
                }
            }

            // For other status changes, just update the status
            order.setStatus(status);
            orderRepository.save(order);

            return ResponseEntity.ok(Map.of("message", "주문 상태가 업데이트되었습니다.", "status", status));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", "주문 상태 업데이트 중 오류가 발생했습니다: " + e.getMessage()));
        }
    }

    @PostMapping("/orders/{id}/cancel")
    // @PreAuthorize는 SecurityConfig에서 이미 처리하므로 제거
    public ResponseEntity<?> cancelOrder(@PathVariable Long id, Authentication authentication) {
        try {
            if (id == null) {
                return ResponseEntity.badRequest().body(Map.of("error", "주문 ID는 필수입니다."));
            }

            if (authentication == null || authentication.getName() == null) {
                return ResponseEntity.status(401).body(Map.of("error", "인증이 필요합니다."));
            }

            Long adminId;
            try {
                adminId = Long.parseLong(authentication.getName());
            } catch (NumberFormatException e) {
                return ResponseEntity.status(401).body(Map.of("error", "유효하지 않은 사용자 ID입니다."));
            }

            Order cancelledOrder = orderService.cancelOrder(id, adminId);

            return ResponseEntity.ok(Map.of(
                    "message", "주문이 취소되었습니다. 재고 예약과 배달 스케줄도 함께 취소되었습니다.",
                    "order_id", cancelledOrder.getId(),
                    "status", cancelledOrder.getStatus()
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", "주문 취소 중 오류가 발생했습니다: " + e.getMessage()));
        }
    }


    @GetMapping("/schedule/assignments")
    public ResponseEntity<?> getEmployeeScheduleAssignments(
            @RequestParam(required = false) String date,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate,
            Authentication authentication) {
        if (authentication == null || authentication.getName() == null) {
            return ResponseEntity.status(401).body(Map.of("error", "인증이 필요합니다."));
        }
        
        try {
            Long employeeId = Long.parseLong(authentication.getName());
            
            // 날짜 범위로 조회하는 경우 (월 전체 할당 조회)
            if (startDate != null && endDate != null && !startDate.trim().isEmpty() && !endDate.trim().isEmpty()) {
                java.time.LocalDate start = java.time.LocalDate.parse(startDate);
                java.time.LocalDate end = java.time.LocalDate.parse(endDate);
                
                List<EmployeeWorkAssignment> assignments = employeeWorkAssignmentRepository.findByEmployeeIdAndWorkDateBetween(employeeId, start, end);
                
                Map<String, Map<String, Object>> assignmentsByDate = new HashMap<>();
                for (EmployeeWorkAssignment assignment : assignments) {
                    String dateStr = assignment.getWorkDate().toString();
                    if (!assignmentsByDate.containsKey(dateStr)) {
                        assignmentsByDate.put(dateStr, new HashMap<>());
                        assignmentsByDate.get(dateStr).put("date", dateStr);
                        assignmentsByDate.get(dateStr).put("isWorking", true);
                        assignmentsByDate.get(dateStr).put("tasks", new java.util.ArrayList<String>());
                    }
                    @SuppressWarnings("unchecked")
                    List<String> tasks = (List<String>) assignmentsByDate.get(dateStr).get("tasks");
                    if ("COOKING".equals(assignment.getTaskType())) {
                        if (!tasks.contains("조리")) {
                            tasks.add("조리");
                        }
                    } else if ("DELIVERY".equals(assignment.getTaskType())) {
                        if (!tasks.contains("배달")) {
                            tasks.add("배달");
                        }
                    }
                }
                
                return ResponseEntity.ok(assignmentsByDate);
            }
            
            // 단일 날짜로 조회하는 경우
            java.time.LocalDate targetDate;
            if (date != null && !date.trim().isEmpty()) {
                targetDate = java.time.LocalDate.parse(date);
            } else {
                targetDate = java.time.LocalDate.now();
            }
            
            // 스케줄 데이터베이스에서 직원 할당 조회
            List<EmployeeWorkAssignment> assignments = employeeWorkAssignmentRepository.findByEmployeeIdAndWorkDate(employeeId, targetDate);
            
            List<String> tasks = new java.util.ArrayList<>();
            for (EmployeeWorkAssignment assignment : assignments) {
                if ("COOKING".equals(assignment.getTaskType())) {
                    tasks.add("조리");
                } else if ("DELIVERY".equals(assignment.getTaskType())) {
                    tasks.add("배달");
                }
            }
            
            // 출근 여부 확인 (할당된 작업이 있으면 출근)
            boolean isWorking = !assignments.isEmpty();
            
            Map<String, Object> response = new HashMap<>();
            response.put("date", targetDate.toString());
            response.put("isWorking", isWorking);
            response.put("tasks", tasks);
            response.put("orderCount", isWorking ? 1 : 0);
            
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", "스케줄 조회 중 오류가 발생했습니다: " + e.getMessage()));
        }
    }

    /**
     * 조리 시작: 승인 완료 → 조리 중
     * 전제 조건: adminApprovalStatus == "APPROVED" && status == "pending"
     * 권한: 조리 담당 직원 (employeeType == "cooking" 또는 COOKING 작업 할당)
     * 액션: status → "cooking", 재고 차감
     */
    @PostMapping("/orders/{orderId}/start-cooking")
    public ResponseEntity<?> startCooking(@PathVariable Long orderId, Authentication authentication) {
        try {
            if (orderId == null) {
                return ResponseEntity.badRequest().body(Map.of("error", "주문 ID는 필수입니다."));
            }

            if (authentication == null || authentication.getName() == null) {
                return ResponseEntity.status(401).body(Map.of("error", "인증이 필요합니다."));
            }

            Long employeeId = Long.parseLong(authentication.getName());
            Order order = orderRepository.findById(orderId)
                    .orElseThrow(() -> new RuntimeException("주문을 찾을 수 없습니다: " + orderId));

            // 전제 조건 검증: 승인 완료 상태여야 함
            if (!"APPROVED".equalsIgnoreCase(order.getAdminApprovalStatus())) {
                return ResponseEntity.badRequest().body(Map.of("error", "관리자 승인 완료된 주문만 조리를 시작할 수 있습니다. (현재 상태: " + order.getAdminApprovalStatus() + ")"));
            }
            if (!"pending".equalsIgnoreCase(order.getStatus())) {
                return ResponseEntity.badRequest().body(Map.of("error", "주문 접수 상태의 주문만 조리를 시작할 수 있습니다. (현재 상태: " + order.getStatus() + ")"));
            }

            // 권한 검증: 조리 담당 직원인지 확인
            User employee = userRepository.findById(employeeId)
                    .orElseThrow(() -> new RuntimeException("직원을 찾을 수 없습니다."));
            
            // 주문의 배달 시간에서 날짜 추출
            LocalDate orderDate = null;
            try {
                LocalDateTime deliveryDateTime = LocalDateTime.parse(order.getDeliveryTime(), DateTimeFormatter.ISO_LOCAL_DATE_TIME);
                orderDate = deliveryDateTime.toLocalDate();
            } catch (Exception e) {
                try {
                    orderDate = LocalDate.parse(order.getDeliveryTime().split("T")[0]);
                } catch (Exception e2) {
                    return ResponseEntity.status(400).body(Map.of("error", "주문의 배달 시간 형식이 올바르지 않습니다."));
                }
            }
            
            List<EmployeeWorkAssignment> assignments = employeeWorkAssignmentRepository.findByEmployeeIdAndWorkDate(employeeId, orderDate);
            boolean hasCookingAssignment = assignments.stream()
                .anyMatch(a -> "COOKING".equalsIgnoreCase(a.getTaskType()));
            
            // 조리 담당 직원이 아니면 에러
            if (!hasCookingAssignment && !"cooking".equalsIgnoreCase(employee.getEmployeeType())) {
                return ResponseEntity.status(403).body(Map.of("error", "조리 담당 직원만 조리를 시작할 수 있습니다."));
            }

            // 재고 차감 (트랜잭션 내에서 처리)
            System.out.println("[EmployeeController] ========== 조리 시작 (startCooking) ==========");
            System.out.println("[EmployeeController] 주문 ID: " + orderId + "의 조리 시작 - 재고 소진 시작");
            try {
                inventoryService.consumeReservationsForOrder(orderId);
                System.out.println("[EmployeeController] 주문 ID: " + orderId + "의 재고 소진 완료");
            } catch (Exception e) {
                System.err.println("[EmployeeController] 주문 ID: " + orderId + "의 재고 소진 실패: " + e.getMessage());
                e.printStackTrace();
                return ResponseEntity.status(500).body(Map.of("error", "재고 차감 처리 중 오류가 발생했습니다: " + e.getMessage()));
            }

            // 상태 변경
            order.setStatus("cooking");
            order.setCookingEmployeeId(employeeId);
            orderRepository.save(order);

            return ResponseEntity.ok(Map.of(
                    "message", "조리가 시작되었습니다. 재고가 차감되었습니다.",
                    "status", "cooking",
                    "order", order
            ));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", "조리 시작 처리 중 오류가 발생했습니다: " + e.getMessage()));
        }
    }

    /**
     * 배달 시작: 조리 중 → 배달 중
     * 전제 조건: status == "cooking"
     * 권한: 배달 담당 직원 (employeeType == "delivery" 또는 DELIVERY 작업 할당)
     * 액션: status → "out_for_delivery", 재고 차감 없음
     */
    @PostMapping("/orders/{orderId}/start-delivery")
    public ResponseEntity<?> startDelivery(@PathVariable Long orderId, Authentication authentication) {
        try {
            if (orderId == null) {
                return ResponseEntity.badRequest().body(Map.of("error", "주문 ID는 필수입니다."));
            }

            if (authentication == null || authentication.getName() == null) {
                return ResponseEntity.status(401).body(Map.of("error", "인증이 필요합니다."));
            }

            Long employeeId = Long.parseLong(authentication.getName());
            Order order = orderRepository.findById(orderId)
                    .orElseThrow(() -> new RuntimeException("주문을 찾을 수 없습니다: " + orderId));

            // 전제 조건 검증: 조리 중 상태여야 함
            if (!"cooking".equalsIgnoreCase(order.getStatus())) {
                return ResponseEntity.badRequest().body(Map.of("error", "조리 중인 주문만 배달을 시작할 수 있습니다. (현재 상태: " + order.getStatus() + ")"));
            }

            // 권한 검증: 배달 담당 직원인지 확인
            User employee = userRepository.findById(employeeId)
                    .orElseThrow(() -> new RuntimeException("직원을 찾을 수 없습니다."));
            
            // 주문의 배달 시간에서 날짜 추출
            LocalDate orderDate = null;
            try {
                LocalDateTime deliveryDateTime = LocalDateTime.parse(order.getDeliveryTime(), DateTimeFormatter.ISO_LOCAL_DATE_TIME);
                orderDate = deliveryDateTime.toLocalDate();
            } catch (Exception e) {
                try {
                    orderDate = LocalDate.parse(order.getDeliveryTime().split("T")[0]);
                } catch (Exception e2) {
                    return ResponseEntity.status(400).body(Map.of("error", "주문의 배달 시간 형식이 올바르지 않습니다."));
                }
            }
            
            List<EmployeeWorkAssignment> assignments = employeeWorkAssignmentRepository.findByEmployeeIdAndWorkDate(employeeId, orderDate);
            boolean hasDeliveryAssignment = assignments.stream()
                .anyMatch(a -> "DELIVERY".equalsIgnoreCase(a.getTaskType()));
            
            // 배달 담당 직원이 아니면 에러
            if (!hasDeliveryAssignment && !"delivery".equalsIgnoreCase(employee.getEmployeeType())) {
                return ResponseEntity.status(403).body(Map.of("error", "배달 담당 직원만 배달을 시작할 수 있습니다."));
            }

            // 상태 변경 (재고 차감 없음)
            order.setStatus("out_for_delivery");
            order.setDeliveryEmployeeId(employeeId);
            orderRepository.save(order);

            return ResponseEntity.ok(Map.of(
                    "message", "배달이 시작되었습니다.",
                    "status", "out_for_delivery",
                    "order", order
            ));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", "배달 시작 처리 중 오류가 발생했습니다: " + e.getMessage()));
        }
    }

    /**
     * 배달 완료: 배달 중 → 배달 완료
     * 전제 조건: status == "out_for_delivery"
     * 권한: 조리원 또는 배달 담당 직원 (요구사항에 따라 조리원이 배달 완료를 누르는 것으로 명시됨)
     * 액션: status → "delivered", 재고 차감 없음
     */
    @PostMapping("/orders/{orderId}/complete-delivery")
    public ResponseEntity<?> completeDelivery(@PathVariable Long orderId, Authentication authentication) {
        try {
            if (orderId == null) {
                return ResponseEntity.badRequest().body(Map.of("error", "주문 ID는 필수입니다."));
            }

            if (authentication == null || authentication.getName() == null) {
                return ResponseEntity.status(401).body(Map.of("error", "인증이 필요합니다."));
            }

            Long employeeId = Long.parseLong(authentication.getName());
            Order order = orderRepository.findById(orderId)
                    .orElseThrow(() -> new RuntimeException("주문을 찾을 수 없습니다: " + orderId));

            // 전제 조건 검증: 배달 중 상태여야 함
            if (!"out_for_delivery".equalsIgnoreCase(order.getStatus())) {
                return ResponseEntity.badRequest().body(Map.of("error", "배달 중인 주문만 배달 완료 처리할 수 있습니다. (현재 상태: " + order.getStatus() + ")"));
            }

            // 권한 검증: 조리원 또는 배달 담당 직원
            User employee = userRepository.findById(employeeId)
                    .orElseThrow(() -> new RuntimeException("직원을 찾을 수 없습니다."));
            
            // 주문의 배달 시간에서 날짜 추출
            LocalDate orderDate = null;
            try {
                LocalDateTime deliveryDateTime = LocalDateTime.parse(order.getDeliveryTime(), DateTimeFormatter.ISO_LOCAL_DATE_TIME);
                orderDate = deliveryDateTime.toLocalDate();
            } catch (Exception e) {
                try {
                    orderDate = LocalDate.parse(order.getDeliveryTime().split("T")[0]);
                } catch (Exception e2) {
                    return ResponseEntity.status(400).body(Map.of("error", "주문의 배달 시간 형식이 올바르지 않습니다."));
                }
            }
            
            List<EmployeeWorkAssignment> assignments = employeeWorkAssignmentRepository.findByEmployeeIdAndWorkDate(employeeId, orderDate);
            boolean hasCookingAssignment = assignments.stream()
                .anyMatch(a -> "COOKING".equalsIgnoreCase(a.getTaskType()));
            boolean hasDeliveryAssignment = assignments.stream()
                .anyMatch(a -> "DELIVERY".equalsIgnoreCase(a.getTaskType()));
            
            // 조리원 또는 배달 담당 직원이 아니면 에러
            if (!hasCookingAssignment && !hasDeliveryAssignment && 
                !"cooking".equalsIgnoreCase(employee.getEmployeeType()) && 
                !"delivery".equalsIgnoreCase(employee.getEmployeeType())) {
                return ResponseEntity.status(403).body(Map.of("error", "조리원 또는 배달 담당 직원만 배달 완료를 처리할 수 있습니다."));
            }

            // 상태 변경 (재고 차감 없음)
            try {
                orderService.markOrderAsDelivered(orderId);
                return ResponseEntity.ok(Map.of(
                        "message", "배달이 완료되었습니다.",
                        "status", "delivered",
                        "order", orderRepository.findById(orderId).orElse(order)
                ));
            } catch (IllegalArgumentException e) {
                return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
            } catch (RuntimeException e) {
                return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
            } catch (Exception e) {
                return ResponseEntity.status(500).body(Map.of("error", "배달 완료 처리 중 오류가 발생했습니다: " + e.getMessage()));
            }
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", "배달 완료 처리 중 오류가 발생했습니다: " + e.getMessage()));
        }
    }
}
