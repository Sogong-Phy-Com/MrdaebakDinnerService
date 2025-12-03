package com.mrdabak.dinnerservice.controller;

import com.mrdabak.dinnerservice.dto.AuthRequest;
import com.mrdabak.dinnerservice.dto.AuthResponse;
import com.mrdabak.dinnerservice.dto.UserDto;
import com.mrdabak.dinnerservice.model.User;
import com.mrdabak.dinnerservice.model.Order;
import com.mrdabak.dinnerservice.model.OrderItem;
import com.mrdabak.dinnerservice.model.DinnerType;
import com.mrdabak.dinnerservice.model.MenuItem;
import com.mrdabak.dinnerservice.repository.UserRepository;
import com.mrdabak.dinnerservice.repository.order.OrderRepository;
import com.mrdabak.dinnerservice.repository.order.OrderItemRepository;
import com.mrdabak.dinnerservice.repository.DinnerTypeRepository;
import com.mrdabak.dinnerservice.repository.MenuItemRepository;
import com.mrdabak.dinnerservice.service.JwtService;
import com.mrdabak.dinnerservice.service.DeliverySchedulingService;
import com.mrdabak.dinnerservice.service.OrderService;
import com.mrdabak.dinnerservice.repository.schedule.DeliveryScheduleRepository;
import com.mrdabak.dinnerservice.repository.schedule.EmployeeWorkAssignmentRepository;
import com.mrdabak.dinnerservice.model.EmployeeWorkAssignment;
import com.mrdabak.dinnerservice.repository.inventory.InventoryReservationRepository;
import com.mrdabak.dinnerservice.util.DeliveryTimeUtils;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin")
@PreAuthorize("hasRole('ADMIN')")
public class AdminController {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final OrderRepository orderRepository;
    private final DeliverySchedulingService deliverySchedulingService;
    private final DeliveryScheduleRepository deliveryScheduleRepository;
    private final EmployeeWorkAssignmentRepository employeeWorkAssignmentRepository;
    private final OrderService orderService;
    private final OrderItemRepository orderItemRepository;
    private final DinnerTypeRepository dinnerTypeRepository;
    private final MenuItemRepository menuItemRepository;
    private final InventoryReservationRepository inventoryReservationRepository;

    public AdminController(UserRepository userRepository, PasswordEncoder passwordEncoder, 
                          JwtService jwtService, OrderRepository orderRepository,
                          DeliverySchedulingService deliverySchedulingService,
                          DeliveryScheduleRepository deliveryScheduleRepository,
                          EmployeeWorkAssignmentRepository employeeWorkAssignmentRepository,
                          OrderService orderService,
                          OrderItemRepository orderItemRepository,
                          DinnerTypeRepository dinnerTypeRepository,
                          MenuItemRepository menuItemRepository,
                          InventoryReservationRepository inventoryReservationRepository) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.orderRepository = orderRepository;
        this.deliverySchedulingService = deliverySchedulingService;
        this.deliveryScheduleRepository = deliveryScheduleRepository;
        this.employeeWorkAssignmentRepository = employeeWorkAssignmentRepository;
        this.orderService = orderService;
        this.orderItemRepository = orderItemRepository;
        this.dinnerTypeRepository = dinnerTypeRepository;
        this.menuItemRepository = menuItemRepository;
        this.inventoryReservationRepository = inventoryReservationRepository;
    }

    @PostMapping("/create-employee")
    public ResponseEntity<?> createEmployee(@Valid @RequestBody AuthRequest request, Authentication authentication) {
        try {
            if (userRepository.existsByEmail(request.getEmail())) {
                return ResponseEntity.badRequest().body(Map.of("error", "User already exists"));
            }

            User employee = new User();
            employee.setEmail(request.getEmail());
            employee.setPassword(passwordEncoder.encode(request.getPassword()));
            employee.setName(request.getName());
            employee.setAddress(request.getAddress() != null ? request.getAddress() : "");
            employee.setPhone(request.getPhone() != null ? request.getPhone() : "");
            employee.setRole("employee");

            User savedEmployee = userRepository.save(employee);
            String token = jwtService.generateToken(savedEmployee.getId(), savedEmployee.getEmail(), savedEmployee.getRole());

            return ResponseEntity.status(HttpStatus.CREATED).body(new AuthResponse(
                    "Employee created successfully",
                    token,
                    new UserDto(savedEmployee.getId(), savedEmployee.getEmail(), savedEmployee.getName(),
                            savedEmployee.getAddress(), savedEmployee.getPhone(), savedEmployee.getRole(), savedEmployee.getApprovalStatus())
            ));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/users")
    public ResponseEntity<?> getAllUsers() {
        return ResponseEntity.ok(userRepository.findAll().stream()
                .map(user -> {
                    UserDto dto = new UserDto(user.getId(), user.getEmail(), user.getName(),
                            user.getAddress(), user.getPhone(), user.getRole(), user.getApprovalStatus());
                    if (user.getEmployeeType() != null) {
                        dto.setEmployeeType(user.getEmployeeType());
                    }
                    return dto;
                })
                .toList());
    }

    @GetMapping("/employees")
    public ResponseEntity<?> getEmployees() {
        return ResponseEntity.ok(userRepository.findAll().stream()
                .filter(user -> "employee".equals(user.getRole()))
                .map(user -> {
                    UserDto dto = new UserDto(user.getId(), user.getEmail(), user.getName(),
                            user.getAddress(), user.getPhone(), user.getRole(), user.getApprovalStatus());
                    if (user.getEmployeeType() != null) {
                        dto.setEmployeeType(user.getEmployeeType());
                    }
                    return dto;
                })
                .toList());
    }
    
    @PatchMapping("/employees/{employeeId}/type")
    public ResponseEntity<?> updateEmployeeType(@PathVariable Long employeeId, @RequestBody Map<String, String> request) {
        try {
            User employee = userRepository.findById(employeeId)
                    .orElseThrow(() -> new RuntimeException("Employee not found"));
            
            if (!"employee".equals(employee.getRole()) && !"admin".equals(employee.getRole())) {
                return ResponseEntity.badRequest().body(Map.of("error", "User is not an employee or admin"));
            }
            
            String employeeType = request.get("employeeType");
            if (employeeType != null && !employeeType.isEmpty()) {
                if (!"cooking".equals(employeeType) && !"delivery".equals(employeeType)) {
                    return ResponseEntity.badRequest().body(Map.of("error", "Invalid employee type. Must be 'cooking' or 'delivery'"));
                }
                employee.setEmployeeType(employeeType);
                userRepository.save(employee);
            } else {
                // Remove employee type if null or empty
                employee.setEmployeeType(null);
                userRepository.save(employee);
            }
            
            UserDto dto = new UserDto(employee.getId(), employee.getEmail(), employee.getName(),
                    employee.getAddress(), employee.getPhone(), employee.getRole(), employee.getApprovalStatus());
            if (employee.getEmployeeType() != null) {
                dto.setEmployeeType(employee.getEmployeeType());
            }
            
            return ResponseEntity.ok(dto);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/customers")
    public ResponseEntity<?> getCustomers() {
        return ResponseEntity.ok(userRepository.findAll().stream()
                .filter(user -> "customer".equals(user.getRole()))
                .map(user -> new UserDto(user.getId(), user.getEmail(), user.getName(),
                        user.getAddress(), user.getPhone(), user.getRole(), user.getApprovalStatus()))
                .toList());
    }

    @GetMapping("/pending-approvals")
    public ResponseEntity<?> getPendingApprovals() {
        return ResponseEntity.ok(userRepository.findAll().stream()
                .filter(user -> "pending".equals(user.getApprovalStatus()))
                .map(user -> Map.of(
                        "id", user.getId(),
                        "email", user.getEmail(),
                        "name", user.getName(),
                        "phone", user.getPhone(),
                        "address", user.getAddress(),
                        "role", user.getRole(),
                        "approvalStatus", user.getApprovalStatus(),
                        "createdAt", user.getCreatedAt() != null ? user.getCreatedAt().toString() : ""
                ))
                .toList());
    }

    @PostMapping("/approve-user/{userId}")
    public ResponseEntity<?> approveUser(@PathVariable Long userId) {
        try {
            User user = userRepository.findById(userId)
                    .orElseThrow(() -> new RuntimeException("User not found"));
            
            if (!"pending".equals(user.getApprovalStatus())) {
                return ResponseEntity.badRequest().body(Map.of("error", "User is not pending approval"));
            }
            
            user.setApprovalStatus("approved");
            userRepository.save(user);
            
            return ResponseEntity.ok(Map.of(
                    "message", "User approved successfully",
                    "user", Map.of(
                            "id", user.getId(),
                            "email", user.getEmail(),
                            "name", user.getName(),
                            "role", user.getRole(),
                            "approvalStatus", user.getApprovalStatus()
                    )
            ));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/reject-user/{userId}")
    public ResponseEntity<?> rejectUser(@PathVariable Long userId) {
        try {
            User user = userRepository.findById(userId)
                    .orElseThrow(() -> new RuntimeException("User not found"));
            
            if (!"pending".equals(user.getApprovalStatus())) {
                return ResponseEntity.badRequest().body(Map.of("error", "User is not pending approval"));
            }
            
            user.setApprovalStatus("rejected");
            userRepository.save(user);
            
            return ResponseEntity.ok(Map.of(
                    "message", "User rejected successfully",
                    "user", Map.of(
                            "id", user.getId(),
                            "email", user.getEmail(),
                            "name", user.getName(),
                            "role", user.getRole(),
                            "approvalStatus", user.getApprovalStatus()
                    )
            ));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/orders/{orderId}/assign")
    public ResponseEntity<?> assignOrderEmployees(
            @PathVariable Long orderId,
            @RequestBody Map<String, Long> request) {
        try {
            System.out.println("[AdminController] assignOrderEmployees called - orderId: " + orderId +
                    ", cookingEmployeeId: " + request.get("cookingEmployeeId") +
                    ", deliveryEmployeeId: " + request.get("deliveryEmployeeId"));
            Order order = orderRepository.findById(orderId)
                    .orElseThrow(() -> new RuntimeException("Order not found"));
            
            Long cookingEmployeeId = request.get("cookingEmployeeId");
            Long deliveryEmployeeId = request.get("deliveryEmployeeId");
            
            // Validate employees exist and are employees
            if (cookingEmployeeId != null) {
                User cookingEmployee = userRepository.findById(cookingEmployeeId)
                        .orElseThrow(() -> new RuntimeException("Cooking employee not found"));
                if (!"employee".equals(cookingEmployee.getRole()) && !"admin".equals(cookingEmployee.getRole())) {
                    return ResponseEntity.badRequest().body(Map.of("error", "Cooking employee must be an employee or admin"));
                }
                if (!"approved".equals(cookingEmployee.getApprovalStatus())) {
                    return ResponseEntity.badRequest().body(Map.of("error", "Cooking employee is not approved"));
                }
                if (cookingEmployee.getEmployeeType() != null &&
                        !"cooking".equalsIgnoreCase(cookingEmployee.getEmployeeType())) {
                    return ResponseEntity.badRequest().body(Map.of("error", "해당 직원은 조리 담당으로 배정될 수 없습니다."));
                }
            }
            
            if (deliveryEmployeeId != null) {
                User deliveryEmployee = userRepository.findById(deliveryEmployeeId)
                        .orElseThrow(() -> new RuntimeException("Delivery employee not found"));
                if (!"employee".equals(deliveryEmployee.getRole()) && !"admin".equals(deliveryEmployee.getRole())) {
                    return ResponseEntity.badRequest().body(Map.of("error", "Delivery employee must be an employee or admin"));
                }
                if (!"approved".equals(deliveryEmployee.getApprovalStatus())) {
                    return ResponseEntity.badRequest().body(Map.of("error", "Delivery employee is not approved"));
                }
                if (deliveryEmployee.getEmployeeType() != null &&
                        !"delivery".equalsIgnoreCase(deliveryEmployee.getEmployeeType())) {
                    return ResponseEntity.badRequest().body(Map.of("error", "해당 직원은 배달 담당으로 배정될 수 없습니다."));
                }
            }
            
            order.setCookingEmployeeId(cookingEmployeeId);
            order.setDeliveryEmployeeId(deliveryEmployeeId);
            orderRepository.saveAndFlush(order);

            if (deliveryEmployeeId != null && order.getDeliveryTime() != null && order.getDeliveryAddress() != null) {
                java.time.LocalDateTime deliveryDateTime = DeliveryTimeUtils.parseDeliveryTime(order.getDeliveryTime());
                deliverySchedulingService.commitAssignmentForOrder(orderId, deliveryEmployeeId, deliveryDateTime, order.getDeliveryAddress());
                System.out.println("[AdminController] Delivery schedule committed - orderId " + orderId + ", employeeId " + deliveryEmployeeId);
            } else if (deliveryEmployeeId == null) {
                deliverySchedulingService.releaseAssignmentForOrder(orderId);
                System.out.println("[AdminController] Delivery schedule released - orderId " + orderId);
            }
            
            return ResponseEntity.ok(Map.of(
                    "message", "Employees assigned successfully",
                    "orderId", order.getId(),
                    "cookingEmployeeId", cookingEmployeeId != null ? cookingEmployeeId : "null",
                    "deliveryEmployeeId", deliveryEmployeeId != null ? deliveryEmployeeId : "null"
            ));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PatchMapping("/users/{userId}/promote")
    public ResponseEntity<?> promoteToAdmin(@PathVariable Long userId) {
        try {
            User user = userRepository.findById(userId)
                    .orElseThrow(() -> new RuntimeException("User not found"));
            
            if (!"employee".equals(user.getRole())) {
                return ResponseEntity.badRequest().body(Map.of("error", "Only employees can be promoted to admin"));
            }
            
            user.setRole("admin");
            userRepository.save(user);
            
            UserDto dto = new UserDto(user.getId(), user.getEmail(), user.getName(),
                    user.getAddress(), user.getPhone(), user.getRole(), user.getApprovalStatus());
            
            return ResponseEntity.ok(dto);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/orders/{orderId}/approve")
    public ResponseEntity<?> approveOrder(@PathVariable Long orderId) {
        try {
            Order order = orderRepository.findById(orderId)
                    .orElseThrow(() -> new RuntimeException("주문을 찾을 수 없습니다."));
            if ("cancelled".equalsIgnoreCase(order.getStatus())) {
                return ResponseEntity.badRequest().body(Map.of("error", "취소된 주문은 승인할 수 없습니다."));
            }
            if ("delivered".equalsIgnoreCase(order.getStatus())) {
                return ResponseEntity.badRequest().body(Map.of("error", "이미 배달 완료된 주문입니다."));
            }
            if ("APPROVED".equalsIgnoreCase(order.getAdminApprovalStatus())) {
                return ResponseEntity.ok(Map.of(
                        "message", "이미 승인된 주문입니다.",
                        "order_id", order.getId()
                ));
            }
            order.setAdminApprovalStatus("APPROVED");
            orderRepository.saveAndFlush(order);
            
            // 주문 승인 시 배달 직원이 이미 할당되어 있으면 배달 스케줄 생성
            if (order.getDeliveryEmployeeId() != null && order.getDeliveryTime() != null && order.getDeliveryAddress() != null) {
                try {
                    java.time.LocalDateTime deliveryDateTime = DeliveryTimeUtils.parseDeliveryTime(order.getDeliveryTime());
                    deliverySchedulingService.commitAssignmentForOrder(orderId, order.getDeliveryEmployeeId(), deliveryDateTime, order.getDeliveryAddress());
                    System.out.println("[AdminController] 주문 승인 시 배달 스케줄 생성 완료 - 주문 ID: " + orderId + ", 직원 ID: " + order.getDeliveryEmployeeId());
                } catch (Exception e) {
                    System.err.println("[AdminController] 주문 승인 시 배달 스케줄 생성 실패: " + e.getMessage());
                    // 배달 스케줄 생성 실패해도 주문 승인은 완료
                }
            }
            
            return ResponseEntity.ok(Map.of(
                    "message", "주문이 승인되었습니다.",
                    "order_id", order.getId()
            ));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", "주문 승인 중 오류가 발생했습니다: " + e.getMessage()));
        }
    }

    @PostMapping("/orders/{orderId}/reject")
    public ResponseEntity<?> rejectOrder(
            @PathVariable Long orderId,
            Authentication authentication,
            @RequestBody(required = false) Map<String, String> requestBody
    ) {
        try {
            if (authentication == null || authentication.getName() == null) {
                return ResponseEntity.status(401).body(Map.of("error", "인증이 필요합니다."));
            }
            Long adminId = Long.parseLong(authentication.getName());
            Order cancelledOrder = orderService.cancelOrder(orderId, adminId);
            cancelledOrder.setAdminApprovalStatus("REJECTED");
            orderRepository.save(cancelledOrder);
            String reason = requestBody != null ? requestBody.getOrDefault("reason", "") : "";
            return ResponseEntity.ok(Map.of(
                    "message", "주문이 반려되었습니다.",
                    "order_id", cancelledOrder.getId(),
                    "reason", reason
            ));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", "주문 반려 중 오류가 발생했습니다: " + e.getMessage()));
        }
    }

    @GetMapping("/schedule/assignments")
    public ResponseEntity<?> getScheduleAssignments(@RequestParam String date) {
        try {
            java.time.LocalDate workDate = java.time.LocalDate.parse(date);
            
            List<EmployeeWorkAssignment> cookingAssignments = employeeWorkAssignmentRepository.findByWorkDateAndTaskType(workDate, "COOKING");
            List<EmployeeWorkAssignment> deliveryAssignments = employeeWorkAssignmentRepository.findByWorkDateAndTaskType(workDate, "DELIVERY");
            
            List<Integer> cookingEmployeeIds = cookingAssignments.stream()
                .map(a -> a.getEmployeeId().intValue())
                .collect(java.util.stream.Collectors.toList());
            
            List<Integer> deliveryEmployeeIds = deliveryAssignments.stream()
                .map(a -> a.getEmployeeId().intValue())
                .collect(java.util.stream.Collectors.toList());
            
            return ResponseEntity.ok(Map.of(
                "date", date,
                "cookingEmployees", cookingEmployeeIds,
                "deliveryEmployees", deliveryEmployeeIds
            ));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", "할당 조회 실패: " + e.getMessage()));
        }
    }

    @PostMapping("/schedule/assign")
    @org.springframework.transaction.annotation.Transactional(transactionManager = "scheduleTransactionManager")
    public ResponseEntity<?> assignEmployeesForDate(@RequestBody Map<String, Object> request) {
        try {
            String dateStr = (String) request.get("date");
            @SuppressWarnings("unchecked")
            List<Integer> cookingEmployees = (List<Integer>) request.get("cookingEmployees");
            @SuppressWarnings("unchecked")
            List<Integer> deliveryEmployees = (List<Integer>) request.get("deliveryEmployees");

            if (dateStr == null || cookingEmployees == null || deliveryEmployees == null) {
                return ResponseEntity.badRequest().body(Map.of("error", "날짜와 직원 목록이 필요합니다."));
            }

            java.time.LocalDate workDate = java.time.LocalDate.parse(dateStr);
            
            // 기존 할당 삭제
            employeeWorkAssignmentRepository.deleteByWorkDate(workDate);
            
            // 모든 할당을 리스트에 모아서 배치 저장
            java.util.List<EmployeeWorkAssignment> assignmentsToSave = new java.util.ArrayList<>();
            
            // 조리 담당 직원 할당 준비
            for (Integer empId : cookingEmployees) {
                EmployeeWorkAssignment assignment = new EmployeeWorkAssignment();
                assignment.setEmployeeId(Long.valueOf(empId));
                assignment.setWorkDate(workDate);
                assignment.setTaskType("COOKING");
                assignmentsToSave.add(assignment);
            }
            
            // 배달 담당 직원 할당 준비
            for (Integer empId : deliveryEmployees) {
                EmployeeWorkAssignment assignment = new EmployeeWorkAssignment();
                assignment.setEmployeeId(Long.valueOf(empId));
                assignment.setWorkDate(workDate);
                assignment.setTaskType("DELIVERY");
                assignmentsToSave.add(assignment);
            }
            
            // 배치 저장 (한 번의 트랜잭션으로 모든 할당 저장) - 즉시 반영을 위해 saveAllAndFlush 사용
            employeeWorkAssignmentRepository.saveAllAndFlush(assignmentsToSave);

            System.out.println("[AdminController] 직원 할당 저장 완료 - 날짜: " + dateStr + 
                ", 조리: " + cookingEmployees.size() + "명, 배달: " + deliveryEmployees.size() + "명");

            return ResponseEntity.ok(Map.of(
                "message", "직원 할당이 저장되었습니다.", 
                "date", dateStr,
                "cookingEmployees", cookingEmployees.size(),
                "deliveryEmployees", deliveryEmployees.size()
            ));
        } catch (IllegalArgumentException e) {
            System.err.println("[AdminController] 할당 저장 실패 (잘못된 입력): " + e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", "잘못된 입력: " + e.getMessage()));
        } catch (Exception e) {
            System.err.println("[AdminController] 할당 저장 실패: " + e.getMessage());
            System.err.println("[AdminController] 예외 타입: " + e.getClass().getName());
            e.printStackTrace();
            // 예외 메시지가 너무 짧거나 의미 없는 경우 더 자세한 정보 제공
            String errorMessage = e.getMessage();
            if (errorMessage == null || errorMessage.trim().isEmpty() || errorMessage.length() < 3) {
                errorMessage = "직원 할당 저장 중 오류가 발생했습니다. (" + e.getClass().getSimpleName() + ")";
            }
            return ResponseEntity.status(500).body(Map.of("error", "직원 할당 저장 실패: " + errorMessage));
        }
    }

    @GetMapping("/users/{userId}/orders")
    public ResponseEntity<?> getCustomerOrders(@PathVariable Long userId) {
        try {
            // Verify user exists
            if (!userRepository.existsById(userId)) {
                throw new RuntimeException("User not found");
            }
            
            // Get orders for this user
            List<Order> orders = orderRepository.findByUserIdOrderByCreatedAtDesc(userId);
            
            // Convert to DTOs with order items
            List<Map<String, Object>> orderDtos = orders.stream().map(order -> {
                Map<String, Object> orderMap = new HashMap<>();
                orderMap.put("id", order.getId());
                orderMap.put("user_id", order.getUserId());
                orderMap.put("dinner_type_id", order.getDinnerTypeId());
                orderMap.put("serving_style", order.getServingStyle());
                orderMap.put("delivery_time", order.getDeliveryTime());
                orderMap.put("delivery_address", order.getDeliveryAddress());
                orderMap.put("total_price", order.getTotalPrice());
                orderMap.put("status", order.getStatus());
                orderMap.put("payment_status", order.getPaymentStatus());
                orderMap.put("created_at", order.getCreatedAt());
                
                // Add dinner type information
                DinnerType dinner = dinnerTypeRepository.findById(order.getDinnerTypeId()).orElse(null);
                if (dinner != null) {
                    orderMap.put("dinner_name", dinner.getName());
                    orderMap.put("dinner_name_en", dinner.getNameEn());
                }
                
                // Add order items
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
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", "주문 내역 조회 실패: " + e.getMessage()));
        }
    }

    // 관리자는 주문 상태 변경 불가 - 할당받은 직원만 변경 가능
    // 이 엔드포인트는 제거되었습니다. 주문 상태 변경은 /api/employee/orders/{id}/status를 사용하세요.


    @GetMapping("/orders/pending")
    public ResponseEntity<?> getPendingOrders() {
        try {
            List<Order> pendingOrders = orderRepository.findAll().stream()
                    .filter(order -> "PENDING".equalsIgnoreCase(order.getAdminApprovalStatus()))
                    .collect(java.util.stream.Collectors.toList());
            
            List<Map<String, Object>> orderDtos = pendingOrders.stream().map(order -> {
                Map<String, Object> orderMap = new HashMap<>();
                orderMap.put("id", order.getId());
                orderMap.put("user_id", order.getUserId());
                orderMap.put("dinner_type_id", order.getDinnerTypeId());
                orderMap.put("serving_style", order.getServingStyle());
                orderMap.put("delivery_time", order.getDeliveryTime());
                orderMap.put("delivery_address", order.getDeliveryAddress());
                orderMap.put("total_price", order.getTotalPrice());
                orderMap.put("status", order.getStatus());
                orderMap.put("payment_status", order.getPaymentStatus());
                orderMap.put("admin_approval_status", order.getAdminApprovalStatus());
                orderMap.put("created_at", order.getCreatedAt());
                
                // Add user info
                User user = userRepository.findById(order.getUserId()).orElse(null);
                if (user != null) {
                    orderMap.put("user_name", user.getName());
                    orderMap.put("user_email", user.getEmail());
                    orderMap.put("user_phone", user.getPhone());
                }
                
                // Add order items
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
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", "Failed to fetch pending orders: " + e.getMessage()));
        }
    }

    @PostMapping("/orders/reset")
    public ResponseEntity<?> resetAllOrders() {
        try {
            System.out.println("[AdminController] 주문 내역 초기화 시작...");
            
            // 1. 모든 재고 예약 삭제 (inventory DB)
            long reservationCount = inventoryReservationRepository.count();
            inventoryReservationRepository.deleteAll();
            System.out.println("[AdminController] 재고 예약 " + reservationCount + "개 삭제 완료");
            
            // 2. 모든 배달 스케줄 삭제 (schedule DB)
            long scheduleCount = deliveryScheduleRepository.count();
            deliveryScheduleRepository.deleteAll();
            System.out.println("[AdminController] 배달 스케줄 " + scheduleCount + "개 삭제 완료");
            
            // 3. 모든 주문 아이템 삭제 (order DB)
            long orderItemCount = orderItemRepository.count();
            orderItemRepository.deleteAll();
            System.out.println("[AdminController] 주문 아이템 " + orderItemCount + "개 삭제 완료");
            
            // 4. 모든 주문 삭제 (order DB)
            long orderCount = orderRepository.count();
            orderRepository.deleteAll();
            System.out.println("[AdminController] 주문 " + orderCount + "개 삭제 완료");
            
            System.out.println("[AdminController] 주문 내역 초기화 완료!");
            
            return ResponseEntity.ok(Map.of(
                "message", "모든 주문 내역이 초기화되었습니다.",
                "deleted_orders", orderCount,
                "deleted_order_items", orderItemCount,
                "deleted_reservations", reservationCount,
                "deleted_schedules", scheduleCount
            ));
        } catch (Exception e) {
            System.err.println("[AdminController] 주문 초기화 실패: " + e.getMessage());
            e.printStackTrace();
            return ResponseEntity.status(500).body(Map.of("error", "주문 초기화 중 오류가 발생했습니다: " + e.getMessage()));
        }
    }
}

