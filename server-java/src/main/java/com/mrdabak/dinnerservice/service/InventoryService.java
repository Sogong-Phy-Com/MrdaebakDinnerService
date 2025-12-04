package com.mrdabak.dinnerservice.service;

import com.mrdabak.dinnerservice.dto.OrderItemDto;
import com.mrdabak.dinnerservice.model.InventoryReservation;
import com.mrdabak.dinnerservice.model.MenuInventory;
import com.mrdabak.dinnerservice.model.MenuItem;
import com.mrdabak.dinnerservice.repository.inventory.InventoryReservationRepository;
import com.mrdabak.dinnerservice.repository.inventory.MenuInventoryRepository;
import com.mrdabak.dinnerservice.repository.MenuItemRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class InventoryService {

    private final MenuInventoryRepository menuInventoryRepository;
    private final InventoryReservationRepository inventoryReservationRepository;
    private final MenuItemRepository menuItemRepository;

    private final List<DayOfWeek> restockDays;
    private final LocalTime restockTime;
    private final int defaultCapacity;

    public InventoryService(MenuInventoryRepository menuInventoryRepository,
                            InventoryReservationRepository inventoryReservationRepository,
                            MenuItemRepository menuItemRepository,
                            @Value("${inventory.restock.days:MONDAY,FRIDAY}") String restockDaysProperty,
                            @Value("${inventory.restock.time:06:00}") String restockTimeProperty,
                            @Value("${inventory.default.capacity:20}") int defaultCapacity) {
        this.menuInventoryRepository = menuInventoryRepository;
        this.inventoryReservationRepository = inventoryReservationRepository;
        this.menuItemRepository = menuItemRepository;
        this.restockDays = parseRestockDays(restockDaysProperty);
        this.restockTime = LocalTime.parse(restockTimeProperty);
        this.defaultCapacity = defaultCapacity;
    }

    public InventoryReservationPlan prepareReservations(List<OrderItemDto> items, LocalDateTime deliveryTime) {
        if (items == null || items.isEmpty()) {
            throw new IllegalArgumentException("At least one menu item is required for reservation");
        }
        if (deliveryTime == null) {
            throw new IllegalArgumentException("Delivery time is required for inventory reservation");
        }

        RestockWindow window = resolveWindow(deliveryTime);
        Map<Long, Integer> aggregated = aggregateQuantities(items);

        Map<Long, MenuInventory> inventoryMap = new HashMap<>();
        for (Long menuItemId : aggregated.keySet()) {
            MenuInventory inventory = getInventory(menuItemId);
            inventoryMap.put(menuItemId, inventory);
            validateCapacity(menuItemId, inventory, aggregated.get(menuItemId), window, deliveryTime);
        }

        return new InventoryReservationPlan(window, aggregated, deliveryTime);
    }

    public void validateChangePlan(Long orderId, List<OrderItemDto> newItems, LocalDateTime deliveryTime) {
        if (orderId == null) {
            throw new IllegalArgumentException("주문 ID는 필수입니다.");
        }
        if (deliveryTime == null) {
            throw new IllegalArgumentException("배달 시간은 필수입니다.");
        }
        Map<Long, Integer> aggregated = aggregateQuantities(newItems);
        RestockWindow window = resolveWindow(deliveryTime);
        List<InventoryReservation> existingReservations = inventoryReservationRepository.findByOrderId(orderId);
        validateChangeCapacity(orderId, aggregated, window, deliveryTime, existingReservations);
    }

    @Transactional("inventoryTransactionManager")
    public void replaceReservationsForOrder(Long orderId, List<OrderItemDto> newItems, LocalDateTime deliveryTime) {
        if (orderId == null) {
            throw new IllegalArgumentException("주문 ID는 필수입니다.");
        }
        if (deliveryTime == null) {
            throw new IllegalArgumentException("배달 시간은 필수입니다.");
        }

        Map<Long, Integer> aggregated = aggregateQuantities(newItems);
        RestockWindow window = resolveWindow(deliveryTime);
        List<InventoryReservation> existingReservations = inventoryReservationRepository.findByOrderId(orderId);
        validateChangeCapacity(orderId, aggregated, window, deliveryTime, existingReservations);

        inventoryReservationRepository.deleteByOrderId(orderId);

        for (Map.Entry<Long, Integer> entry : aggregated.entrySet()) {
            Long menuItemId = entry.getKey();
            Integer quantity = entry.getValue();

            // Verify menu item exists
            MenuItem menuItem = menuItemRepository.findById(menuItemId)
                    .orElseThrow(() -> new RuntimeException("메뉴 아이템을 찾을 수 없습니다: " + menuItemId));

            InventoryReservation reservation = new InventoryReservation();
            reservation.setOrderId(orderId);
            reservation.setMenuItemId(menuItemId);
            reservation.setQuantity(quantity);
            reservation.setWindowStart(window.start());
            reservation.setWindowEnd(window.end());
            reservation.setDeliveryTime(deliveryTime);
            reservation.setConsumed(false);

            if (!isAlcoholCategory(menuItem.getCategory())) {
                reservation.setExpiresAt(deliveryTime.plusDays(3));
            }

            inventoryReservationRepository.save(reservation);
        }
    }

    @Transactional("inventoryTransactionManager")
    public void commitReservations(Long orderId, InventoryReservationPlan plan) {
        if (orderId == null) {
            throw new IllegalArgumentException("주문 ID는 필수입니다.");
        }
        if (plan == null || plan.quantities() == null || plan.quantities().isEmpty()) {
            throw new IllegalArgumentException("재고 예약 계획은 필수입니다.");
        }

        for (Map.Entry<Long, Integer> entry : plan.quantities().entrySet()) {
            Long menuItemId = entry.getKey();
            Integer quantity = entry.getValue();
            
            // Verify menu item exists
            if (!menuItemRepository.existsById(menuItemId)) {
                throw new RuntimeException("메뉴 아이템을 찾을 수 없습니다: " + menuItemId);
            }
            
            // Re-validate capacity (race condition prevention)
            MenuInventory inventory = getInventory(menuItemId);
            validateCapacity(menuItemId, inventory, quantity, plan.window(), plan.deliveryTime());

            // 주문 시 재고 예약 저장 (조리 시작 시 소진)
            InventoryReservation reservation = new InventoryReservation();
            reservation.setOrderId(orderId);
            reservation.setMenuItemId(menuItemId);
            reservation.setQuantity(quantity);
            reservation.setWindowStart(plan.window().start());
            reservation.setWindowEnd(plan.window().end());
            reservation.setDeliveryTime(plan.deliveryTime());
            reservation.setConsumed(false);
            
            // 주류가 아닌 경우 3일 후 만료 설정
            com.mrdabak.dinnerservice.model.MenuItem menuItem = menuItemRepository.findById(menuItemId).orElse(null);
            if (menuItem != null && !isAlcoholCategory(menuItem.getCategory())) {
                reservation.setExpiresAt(plan.deliveryTime().plusDays(3));
            }
            
            InventoryReservation savedReservation = inventoryReservationRepository.save(reservation);
            System.out.println("[InventoryService] 주문 " + orderId + " - 메뉴 아이템 " + menuItemId + " 재고 " + quantity + "개 예약 완료 (예약 ID: " + savedReservation.getId() + ")");
            
            // 이번주 예약 수량에 즉시 반영 확인
            System.out.println("[InventoryService] 예약 저장 확인 - Order ID: " + orderId + ", Menu Item ID: " + menuItemId + ", Quantity: " + quantity);
        }
        System.out.println("[InventoryService] 주문 " + orderId + "의 모든 재고 예약이 완료되었습니다.");
    }
    
    private boolean isAlcoholCategory(String category) {
        if (category == null) return false;
        String lowerCategory = category.toLowerCase();
        return lowerCategory.contains("주류") || lowerCategory.contains("alcohol") || 
               lowerCategory.contains("wine") || lowerCategory.contains("beer") ||
               lowerCategory.contains("drink") || lowerCategory.contains("음료");
    }

    @Transactional("inventoryTransactionManager")
    public void releaseReservationsForOrder(Long orderId) {
        if (orderId == null) {
            throw new IllegalArgumentException("주문 ID는 필수입니다.");
        }
        
        try {
            List<InventoryReservation> reservations = inventoryReservationRepository.findByOrderId(orderId);
            if (reservations.isEmpty()) {
                System.out.println("[InventoryService] 주문 " + orderId + "에 대한 재고 예약이 없습니다.");
                return;
            }
            
            int count = reservations.size();
            inventoryReservationRepository.deleteByOrderId(orderId);
            System.out.println("[InventoryService] 주문 " + orderId + "의 재고 예약 " + count + "개가 취소되었습니다.");
        } catch (Exception e) {
            System.err.println("[InventoryService] 재고 예약 취소 중 오류 발생: " + e.getMessage());
            e.printStackTrace();
            throw new RuntimeException("재고 예약 취소 중 오류가 발생했습니다: " + e.getMessage(), e);
        }
    }

    @Transactional("inventoryTransactionManager")
    public void consumeReservationsForOrder(Long orderId) {
        if (orderId == null) {
            throw new IllegalArgumentException("주문 ID는 필수입니다.");
        }
        
        try {
            List<InventoryReservation> reservations = inventoryReservationRepository.findUnconsumedByOrderId(orderId);
            if (reservations.isEmpty()) {
                System.out.println("[InventoryService] 주문 " + orderId + "에 대한 미소진 재고 예약이 없습니다.");
                return;
            }

            // Mark reservations as consumed and deduct from current stock (조리 시작 시 재고 소진)
            // consumed=1로 설정하여 이번주 예약 수량에서 자동으로 제외됨
            // Entity를 직접 업데이트하여 확실하게 저장
            System.out.println("[InventoryService] 주문 " + orderId + "의 재고 예약 " + reservations.size() + "개를 consumed=1로 업데이트 시작");
            
            // 예약 정보를 저장 (수량 차감용)
            Map<Long, Integer> menuItemQuantities = reservations.stream()
                .collect(Collectors.groupingBy(
                    InventoryReservation::getMenuItemId,
                    Collectors.summingInt(r -> r.getQuantity() != null ? r.getQuantity() : 0)
                ));
            
            // Native Query로 consumed=1로 직접 업데이트 (SQLite Boolean 저장 문제 방지)
            int updatedCount = inventoryReservationRepository.markAsConsumedByOrderId(orderId);
            System.out.println("[InventoryService] 주문 " + orderId + "의 재고 예약 " + updatedCount + "개를 consumed=1로 업데이트 완료 (Native Query)");
            
            // 업데이트 확인: 각 예약이 제대로 업데이트되었는지 확인
            for (InventoryReservation reservation : reservations) {
                InventoryReservation verified = inventoryReservationRepository.findById(reservation.getId()).orElse(null);
                if (verified != null) {
                    // SQLite에서는 Boolean이 INTEGER로 저장되므로 1이면 true
                    boolean isConsumed = verified.getConsumed() != null && verified.getConsumed();
                    System.out.println("[InventoryService] 예약 ID " + reservation.getId() + " - consumed 확인: " + verified.getConsumed() + " (Boolean: " + isConsumed + ")");
                    if (!isConsumed) {
                        System.err.println("[InventoryService] 경고: 예약 ID " + reservation.getId() + "의 consumed가 제대로 저장되지 않았습니다!");
                    }
                }
            }
            
            // 현재 보유량 차감 (예약 정보를 기반으로)
            // 조리 시작 시: 이번주 예약 수량 감소 (consumed=1로 설정) + 현재보유량 감소
            int count = 0;
            for (Map.Entry<Long, Integer> entry : menuItemQuantities.entrySet()) {
                Long menuItemId = entry.getKey();
                Integer quantityToDeduct = entry.getValue();
                
            // 조리 시작 전 상태 확인 (이번 주 - 월요일 ~ 일요일)
            LocalDateTime now = LocalDateTime.now();
            LocalDate today = now.toLocalDate();
            DayOfWeek dayOfWeek = today.getDayOfWeek();
            int daysFromMonday = (dayOfWeek.getValue() - 1) % 7;
            LocalDate weekStart = today.minusDays(daysFromMonday);
            LocalDate weekEnd = weekStart.plusWeeks(1);
            LocalDateTime weekStartDateTime = LocalDateTime.of(weekStart, LocalTime.MIN);
            LocalDateTime weekEndDateTime = LocalDateTime.of(weekEnd, LocalTime.MIN);
            Integer weeklyReservedBefore = inventoryReservationRepository
                .sumWeeklyReservedByMenuItemId(menuItemId, weekStartDateTime, weekEndDateTime);
                
                MenuInventory inventory = getInventory(menuItemId);
                int currentCapacity = inventory.getCapacityPerWindow() != null ? inventory.getCapacityPerWindow() : 0;
                
                System.out.println("[InventoryService] ========== 조리 시작 전 상태 ==========");
                System.out.println("[InventoryService] 메뉴 아이템 " + menuItemId + " - 현재보유량: " + currentCapacity);
                System.out.println("[InventoryService] 메뉴 아이템 " + menuItemId + " - 이번주 예약 수량: " + (weeklyReservedBefore != null ? weeklyReservedBefore : 0));
                
                // 현재 보유량 차감
                int newCapacity = Math.max(0, currentCapacity - quantityToDeduct);
                inventory.setCapacityPerWindow(newCapacity);
                menuInventoryRepository.saveAndFlush(inventory);
                
                count++;
            }
            
            // 모든 예약을 업데이트한 후 이번주 예약 수량 확인
            if (count > 0) {
                // 이번 주 (월요일 ~ 일요일)
                LocalDateTime now = LocalDateTime.now();
                LocalDate today = now.toLocalDate();
                DayOfWeek dayOfWeek = today.getDayOfWeek();
                int daysFromMonday = (dayOfWeek.getValue() - 1) % 7;
                LocalDate weekStart = today.minusDays(daysFromMonday);
                LocalDate weekEnd = weekStart.plusWeeks(1);
                LocalDateTime weekStartDateTime = LocalDateTime.of(weekStart, LocalTime.MIN);
                LocalDateTime weekEndDateTime = LocalDateTime.of(weekEnd, LocalTime.MIN);
                
                System.out.println("[InventoryService] ========== 조리 시작 후 최종 상태 ==========");
                for (Map.Entry<Long, Integer> entry : menuItemQuantities.entrySet()) {
                    Long menuItemId = entry.getKey();
                    Integer deductedQuantity = entry.getValue();
                    
                    MenuInventory finalInventory = getInventory(menuItemId);
                    int finalCapacity = finalInventory.getCapacityPerWindow() != null ? finalInventory.getCapacityPerWindow() : 0;
                    
                    // consumed=1로 설정된 예약은 이번주 예약 수량에서 제외됨
                    Integer weeklyReservedAfter = inventoryReservationRepository
                        .sumWeeklyReservedByMenuItemId(menuItemId, weekStartDateTime, weekEndDateTime);
                    
                    System.out.println("[InventoryService] 메뉴 아이템 " + menuItemId + " - 현재보유량: " + finalCapacity);
                    System.out.println("[InventoryService] 메뉴 아이템 " + menuItemId + " - 이번주 예약 수량: " + (weeklyReservedAfter != null ? weeklyReservedAfter : 0));
                    System.out.println("[InventoryService] 메뉴 아이템 " + menuItemId + " - 차감된 수량: " + deductedQuantity);
                    System.out.println("[InventoryService] 메뉴 아이템 " + menuItemId + " - 예비 수량: " + (finalCapacity - (weeklyReservedAfter != null ? weeklyReservedAfter : 0)));
                }
            }
            
            System.out.println("[InventoryService] 주문 " + orderId + "의 재고 예약 " + count + "개가 소진되었습니다. (이번주 예약 수량에서도 차감됨)");
        } catch (Exception e) {
            System.err.println("[InventoryService] 재고 소진 중 오류 발생: " + e.getMessage());
            e.printStackTrace();
            throw new RuntimeException("재고 소진 처리 중 오류가 발생했습니다: " + e.getMessage(), e);
        }
    }

    @Transactional(value = "inventoryTransactionManager")
    public List<InventorySnapshot> getInventorySnapshots() {
        LocalDate today = LocalDate.now();
        DayOfWeek dayOfWeek = today.getDayOfWeek();
        int daysFromMonday = (dayOfWeek.getValue() - 1) % 7;
        LocalDate weekStart = today.minusDays(daysFromMonday);
        return getInventorySnapshots(weekStart);
    }

    @Transactional(value = "inventoryTransactionManager")
    public List<InventorySnapshot> getInventorySnapshots(LocalDate weekStartDate) {
        LocalDateTime now = LocalDateTime.now();
        RestockWindow currentWindow = resolveWindow(now);

        // Get all menu items and ensure inventory exists for each
        List<MenuItem> allMenuItems = menuItemRepository.findAll();
        
        // Create inventory for menu items that don't have one yet
        for (MenuItem menuItem : allMenuItems) {
            if (!menuInventoryRepository.findByMenuItemId(menuItem.getId()).isPresent()) {
                // Auto-create inventory if it doesn't exist
                MenuInventory newInventory = new MenuInventory();
                newInventory.setMenuItemId(menuItem.getId());
                newInventory.setCapacityPerWindow(defaultCapacity);
                newInventory.setSafetyStock(0);
                newInventory.setNotes("auto-initialized");
                try {
                    menuInventoryRepository.save(newInventory);
                } catch (Exception e) {
                    // If save fails, ignore (might be created by another thread)
                    System.err.println("[InventoryService] Failed to auto-create inventory for menu item " + menuItem.getId() + ": " + e.getMessage());
                }
            }
        }

        // Now get all inventories (including newly created ones)
        // Calculate weekly reserved (선택된 주의 예약만 - 월요일 00:00 ~ 다음 주 월요일 00:00)
        LocalDate weekStart = weekStartDate; // 선택된 주의 월요일
        LocalDate weekEnd = weekStart.plusWeeks(1); // 다음 주 월요일
        LocalDateTime weekStartDateTime = LocalDateTime.of(weekStart, LocalTime.MIN); // 선택된 주 월요일 00:00
        LocalDateTime weekEndDateTime = LocalDateTime.of(weekEnd, LocalTime.MIN); // 다음 주 월요일 00:00
        
        return menuInventoryRepository.findAll().stream().map(inventory -> {
            // 현재 날짜의 예약 수량
            Integer reserved = inventoryReservationRepository
                    .sumQuantityByMenuItemIdAndWindowStart(inventory.getMenuItemId(), currentWindow.start());
            if (reserved == null) {
                reserved = 0;
            }
            
            // 선택된 주의 예약 수량 (선택된 주의 모든 예약 합산)
            Integer weeklyReserved = inventoryReservationRepository
                    .sumWeeklyReservedByMenuItemId(inventory.getMenuItemId(), weekStartDateTime, weekEndDateTime);
            if (weeklyReserved == null) {
                weeklyReserved = 0;
            }
            // 디버깅: 계산된 예약 수량 출력
            System.out.println("[InventoryService] 메뉴 아이템 " + inventory.getMenuItemId() + 
                    " - 선택된 주(" + weekStartDateTime + " ~ " + weekEndDateTime + ") 예약 수량: " + weeklyReserved);
            
            return new InventorySnapshot(
                    inventory,
                    reserved,
                    inventory.getCapacityPerWindow() - reserved,
                    currentWindow.start(),
                    currentWindow.end(),
                    weeklyReserved
            );
        }).toList();
    }

    @Transactional("inventoryTransactionManager")
    public MenuInventory restock(Long menuItemId, int newCapacity, String notes) {
        if (menuItemId == null) {
            throw new IllegalArgumentException("메뉴 아이템 ID는 필수입니다.");
        }
        if (newCapacity <= 0) {
            throw new IllegalArgumentException("보충 용량은 0보다 커야 합니다.");
        }
        
        // Verify menu item exists
        if (!menuItemRepository.existsById(menuItemId)) {
            throw new RuntimeException("메뉴 아이템을 찾을 수 없습니다: " + menuItemId);
        }
        
        MenuInventory inventory = getInventory(menuItemId);
        inventory.setCapacityPerWindow(newCapacity);
        inventory.setNotes(notes != null ? notes : "");
        inventory.setLastRestockedAt(LocalDateTime.now());
        return menuInventoryRepository.save(inventory);
    }

    @Transactional("inventoryTransactionManager")
    public MenuInventory setOrderedQuantity(Long menuItemId, int orderedQuantity) {
        if (menuItemId == null) {
            throw new IllegalArgumentException("메뉴 아이템 ID는 필수입니다.");
        }
        if (orderedQuantity < 0) {
            throw new IllegalArgumentException("주문 수량은 0 이상이어야 합니다.");
        }
        
        // Verify menu item exists
        if (!menuItemRepository.existsById(menuItemId)) {
            throw new RuntimeException("메뉴 아이템을 찾을 수 없습니다: " + menuItemId);
        }
        
        MenuInventory inventory = getInventory(menuItemId);
        inventory.setOrderedQuantity(orderedQuantity);
        return menuInventoryRepository.save(inventory);
    }

    @Transactional("inventoryTransactionManager")
    public MenuInventory receiveOrderedInventory(Long menuItemId) {
        if (menuItemId == null) {
            throw new IllegalArgumentException("메뉴 아이템 ID는 필수입니다.");
        }
        
        // Verify menu item exists
        if (!menuItemRepository.existsById(menuItemId)) {
            throw new RuntimeException("메뉴 아이템을 찾을 수 없습니다: " + menuItemId);
        }
        
        MenuInventory inventory = getInventory(menuItemId);
        int orderedQuantity = inventory.getOrderedQuantity() != null ? inventory.getOrderedQuantity() : 0;
        
        if (orderedQuantity <= 0) {
            throw new IllegalArgumentException("수령할 주문 재고가 없습니다.");
        }
        
        // 주문 재고를 현재 보유량에 추가
        int currentCapacity = inventory.getCapacityPerWindow() != null ? inventory.getCapacityPerWindow() : 0;
        inventory.setCapacityPerWindow(currentCapacity + orderedQuantity);
        
        // 주문 재고를 0으로 초기화
        inventory.setOrderedQuantity(0);
        
        return menuInventoryRepository.save(inventory);
    }

    private MenuInventory autoCreateInventory(Long menuItemId) {
        MenuInventory inventory = new MenuInventory();
        inventory.setMenuItemId(menuItemId);
        inventory.setCapacityPerWindow(defaultCapacity);
        inventory.setSafetyStock(0);
        inventory.setNotes("auto-initialized");
        try {
            return menuInventoryRepository.save(inventory);
        } catch (DataIntegrityViolationException e) {
            // Another thread created it first; fetch existing row
            return menuInventoryRepository.findByMenuItemId(menuItemId)
                    .orElseThrow(() -> e);
        }
    }

    private Map<Long, Integer> aggregateQuantities(List<OrderItemDto> items) {
        if (items == null || items.isEmpty()) {
            throw new IllegalArgumentException("주문 항목이 비어있습니다.");
        }
        Map<Long, Integer> aggregated = new HashMap<>();
        for (OrderItemDto item : items) {
            if (item.getMenuItemId() == null) {
                throw new IllegalArgumentException("메뉴 아이템 ID는 필수입니다.");
            }
            if (item.getQuantity() == null || item.getQuantity() <= 0) {
                throw new IllegalArgumentException("메뉴 아이템 수량은 1 이상이어야 합니다.");
            }
            aggregated.merge(item.getMenuItemId(), item.getQuantity(), Integer::sum);
        }
        return aggregated;
    }

    private void validateCapacity(Long menuItemId, MenuInventory inventory, Integer requestedQuantity, RestockWindow window) {
        validateCapacity(menuItemId, inventory, requestedQuantity, window, null);
    }
    
    private void validateCapacity(Long menuItemId, MenuInventory inventory, Integer requestedQuantity, RestockWindow window, LocalDateTime deliveryTime) {
        Integer alreadyReserved = inventoryReservationRepository
                .sumQuantityByMenuItemIdAndWindowStart(menuItemId, window.start());
        if (alreadyReserved == null) {
            alreadyReserved = 0;
        }
        int projected = alreadyReserved + requestedQuantity;
        
        int maxCapacity = resolveMaxCapacity(inventory, deliveryTime);
        
        if (projected > maxCapacity) {
            String menuName = menuItemRepository.findById(menuItemId)
                    .map(item -> item.getName() + "(" + item.getNameEn() + ")")
                    .orElse("menu item " + menuItemId);
            throw new RuntimeException(String.format(
                    "%s 재고가 부족합니다. (요청: %d, 현재 예약: %d, 최대: %d)",
                    menuName,
                    requestedQuantity,
                    alreadyReserved,
                    maxCapacity
            ));
        }
    }

    private void validateChangeCapacity(Long orderId,
                                        Map<Long, Integer> aggregated,
                                        RestockWindow window,
                                        LocalDateTime deliveryTime,
                                        List<InventoryReservation> existingReservations) {
        Map<Long, Integer> existingByItem = existingReservations == null ? Collections.emptyMap()
                : existingReservations.stream()
                .filter(reservation -> reservation.getWindowStart() != null && reservation.getWindowStart().equals(window.start()))
                .collect(Collectors.groupingBy(
                        InventoryReservation::getMenuItemId,
                        Collectors.summingInt(reservation -> reservation.getQuantity() != null ? reservation.getQuantity() : 0)
                ));

        for (Map.Entry<Long, Integer> entry : aggregated.entrySet()) {
            Long menuItemId = entry.getKey();
            Integer requestedQuantity = entry.getValue();

            MenuInventory inventory = getInventory(menuItemId);
            Integer alreadyReserved = inventoryReservationRepository
                    .sumQuantityByMenuItemIdAndWindowStart(menuItemId, window.start());
            if (alreadyReserved == null) {
                alreadyReserved = 0;
            }

            Integer currentContribution = existingByItem.getOrDefault(menuItemId, 0);
            int effectiveReserved = Math.max(0, alreadyReserved - currentContribution);
            int projected = effectiveReserved + requestedQuantity;
            int maxCapacity = resolveMaxCapacity(inventory, deliveryTime);

            if (projected > maxCapacity) {
                String menuName = menuItemRepository.findById(menuItemId)
                        .map(item -> item.getName() + "(" + item.getNameEn() + ")")
                        .orElse("menu item " + menuItemId);
                throw new RuntimeException(String.format(
                        "주문 %d 변경 시 %s 재고가 부족합니다. (요청: %d, 현재 예약: %d, 허용 잔여: %d)",
                        orderId,
                        menuName,
                        requestedQuantity,
                        effectiveReserved,
                        maxCapacity
                ));
            }
        }
    }

    private int resolveMaxCapacity(MenuInventory inventory, LocalDateTime deliveryTime) {
        if (deliveryTime == null) {
            return inventory.getCapacityPerWindow();
        }
        LocalDate deliveryDate = deliveryTime.toLocalDate();
        LocalDate today = LocalDate.now();
        long daysUntilDelivery = java.time.temporal.ChronoUnit.DAYS.between(today, deliveryDate);
        if (daysUntilDelivery >= 3) {
            return Integer.MAX_VALUE;
        }
        return inventory.getCapacityPerWindow();
    }

    private MenuInventory getInventory(Long menuItemId) {
        return menuInventoryRepository.findByMenuItemId(menuItemId)
                .orElseGet(() -> autoCreateInventory(menuItemId));
    }

    private List<DayOfWeek> parseRestockDays(String property) {
        if (property == null || property.isBlank()) {
            return List.of(DayOfWeek.MONDAY, DayOfWeek.FRIDAY);
        }
        return Arrays.stream(property.split(","))
                .map(String::trim)
                .filter(str -> !str.isEmpty())
                .map(String::toUpperCase)
                .map(DayOfWeek::valueOf)
                .toList();
    }

    private RestockWindow resolveWindow(LocalDateTime deliveryTime) {
        // 매일 기준으로 당일 윈도우 설정 (00:00 ~ 23:59:59)
        LocalDate deliveryDate = deliveryTime.toLocalDate();
        LocalDateTime start = LocalDateTime.of(deliveryDate, LocalTime.MIN); // 00:00:00
        LocalDateTime end = LocalDateTime.of(deliveryDate, LocalTime.MAX); // 23:59:59.999999999
        return new RestockWindow(start, end);
    }

    private LocalDateTime findLastRestock(LocalDateTime reference) {
        // 매일 기준이므로 당일 00:00 반환
        LocalDate date = reference.toLocalDate();
        return LocalDateTime.of(date, LocalTime.MIN);
    }

    private LocalDateTime findNextRestock(LocalDateTime after) {
        // 매일 기준이므로 다음 날 00:00 반환
        LocalDate nextDate = after.toLocalDate().plusDays(1);
        return LocalDateTime.of(nextDate, LocalTime.MIN);
    }

    public record InventoryReservationPlan(RestockWindow window,
                                           Map<Long, Integer> quantities,
                                           LocalDateTime deliveryTime) { }

    public record RestockWindow(LocalDateTime start, LocalDateTime end) { }

    public Integer getReservedByDate(Long menuItemId, LocalDateTime targetDate) {
        return inventoryReservationRepository.sumReservedByMenuItemIdAndDate(menuItemId, targetDate);
    }

    public record InventorySnapshot(MenuInventory inventory,
                                    int reserved,
                                    int remaining,
                                    LocalDateTime windowStart,
                                    LocalDateTime windowEnd,
                                    int weeklyReserved) { }
}

