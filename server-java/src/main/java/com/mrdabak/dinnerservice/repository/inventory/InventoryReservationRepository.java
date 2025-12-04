package com.mrdabak.dinnerservice.repository.inventory;

import com.mrdabak.dinnerservice.model.InventoryReservation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface InventoryReservationRepository extends JpaRepository<InventoryReservation, Long> {

    @Query("SELECT COALESCE(SUM(r.quantity), 0) FROM InventoryReservation r " +
            "WHERE r.menuItemId = :menuItemId " +
            "AND r.windowStart = :windowStart")
    Integer sumQuantityByMenuItemIdAndWindowStart(@Param("menuItemId") Long menuItemId,
                                                  @Param("windowStart") LocalDateTime windowStart);

    List<InventoryReservation> findByOrderId(Long orderId);

    void deleteByOrderId(Long orderId);

    @Query("SELECT r FROM InventoryReservation r WHERE r.windowStart >= :start AND r.windowStart < :end")
    List<InventoryReservation> findByWindowStartBetween(@Param("start") LocalDateTime start, 
                                                         @Param("end") LocalDateTime end);
    
    @Query("SELECT r FROM InventoryReservation r WHERE r.consumed = false AND r.expiresAt < :now")
    List<InventoryReservation> findExpiredUnconsumed(@Param("now") LocalDateTime now);
    
    @Query("SELECT r FROM InventoryReservation r WHERE r.consumed = false AND r.orderId = :orderId")
    List<InventoryReservation> findUnconsumedByOrderId(@Param("orderId") Long orderId);
    
    // consumed를 1로 직접 업데이트 (SQLite Boolean 저장 문제 방지)
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.transaction.annotation.Transactional("inventoryTransactionManager")
    @Query(value = "UPDATE inventory_reservations SET consumed = 1 WHERE order_id = :orderId AND (consumed IS NULL OR consumed = 0)", nativeQuery = true)
    int markAsConsumedByOrderId(@Param("orderId") Long orderId);
    
    // 이번주 예약 수량 계산 (오늘부터 7일 후까지의 미소진 예약만 합산 - consumed가 1이 아닌 것만)
    // SQLite에서는 Boolean이 INTEGER로 저장되므로 consumed = 1이면 조리 시작된 것, consumed = 0 또는 NULL이면 미소진 예약
    // delivery_time 기준으로 오늘부터 7일 후까지 예약을 계산 (조리 시작 시 consumed=1로 변경되면 자동으로 제외됨)
    // consumed = 1인 경우를 명시적으로 제외 (COALESCE로 NULL을 0으로 처리하고, 0인 것만 포함)
    @Query(value = "SELECT COALESCE(SUM(r.quantity), 0) FROM inventory_reservations r " +
            "WHERE r.menu_item_id = :menuItemId " +
            "AND r.delivery_time >= :weekStart " +
            "AND r.delivery_time < :weekEnd " +
            "AND (r.consumed IS NULL OR r.consumed = 0)", 
            nativeQuery = true)
    Integer sumWeeklyReservedByMenuItemId(@Param("menuItemId") Long menuItemId,
                                         @Param("weekStart") LocalDateTime weekStart,
                                         @Param("weekEnd") LocalDateTime weekEnd);

    // 특정 날짜의 예약 수량 계산 (해당 날짜의 미소진 예약만)
    @Query(value = "SELECT COALESCE(SUM(r.quantity), 0) FROM inventory_reservations r " +
            "WHERE r.menu_item_id = :menuItemId " +
            "AND DATE(r.delivery_time) = DATE(:targetDate) " +
            "AND (r.consumed IS NULL OR r.consumed = 0)", 
            nativeQuery = true)
    Integer sumReservedByMenuItemIdAndDate(@Param("menuItemId") Long menuItemId,
                                          @Param("targetDate") LocalDateTime targetDate);
}

