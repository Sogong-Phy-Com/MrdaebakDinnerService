package com.mrdabak.dinnerservice.service;

import com.mrdabak.dinnerservice.model.DeliverySchedule;
import com.mrdabak.dinnerservice.model.User;
import com.mrdabak.dinnerservice.repository.schedule.DeliveryScheduleRepository;
import com.mrdabak.dinnerservice.repository.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class DeliverySchedulingService {

    private static final Set<String> SUPPORTED_STATUSES = Set.of("SCHEDULED", "IN_PROGRESS", "COMPLETED", "CANCELLED");

    private final DeliveryScheduleRepository deliveryScheduleRepository;
    private final UserRepository userRepository;
    private final TravelTimeEstimator travelTimeEstimator;
    private final LocalTime shiftStart;
    private final LocalTime shiftEnd;

    public DeliverySchedulingService(DeliveryScheduleRepository deliveryScheduleRepository,
                                     UserRepository userRepository,
                                     TravelTimeEstimator travelTimeEstimator,
                                     @Value("${delivery.shift.start:15:00}") String shiftStartProperty,
                                     @Value("${delivery.shift.end:22:00}") String shiftEndProperty) {
        this.deliveryScheduleRepository = deliveryScheduleRepository;
        this.userRepository = userRepository;
        this.travelTimeEstimator = travelTimeEstimator;
        this.shiftStart = LocalTime.parse(shiftStartProperty);
        this.shiftEnd = LocalTime.parse(shiftEndProperty);
    }

    public DeliveryAssignmentPlan prepareAssignment(String address, LocalDateTime arrivalTime) {
        if (arrivalTime == null) {
            throw new IllegalArgumentException("배달 도착 시간은 필수입니다.");
        }
        if (address == null || address.trim().isEmpty()) {
            throw new IllegalArgumentException("배달 주소는 필수입니다.");
        }

        int oneWayMinutes = travelTimeEstimator.estimateOneWayMinutes(address, arrivalTime);
        LocalDateTime departure = arrivalTime.minusMinutes(oneWayMinutes);
        LocalDateTime returnTime = arrivalTime.plusMinutes(oneWayMinutes);

        validateWithinShift(departure, returnTime);

        List<User> couriers = userRepository.findByRole("employee");
        if (couriers == null || couriers.isEmpty()) {
            throw new RuntimeException("등록된 배달 직원이 없습니다.");
        }

        LocalDate date = arrivalTime.toLocalDate();
        LocalDateTime dayStart = LocalDateTime.of(date, shiftStart);
        LocalDateTime dayEnd = LocalDateTime.of(date, shiftEnd);

        Map<Long, Long> workloads = couriers.stream()
                .collect(Collectors.toMap(
                        User::getId,
                        courier -> (long) deliveryScheduleRepository
                                .findByEmployeeIdAndDepartureTimeBetween(courier.getId(), dayStart, dayEnd)
                                .size()
                ));

        return couriers.stream()
                .sorted(Comparator
                        .comparing((User courier) -> workloads.getOrDefault(courier.getId(), 0L))
                        .thenComparing(User::getId))
                .filter(courier -> !deliveryScheduleRepository
                        .existsByEmployeeIdAndReturnTimeAfterAndDepartureTimeBefore(
                                courier.getId(), departure, returnTime))
                .findFirst()
                .map(courier -> new DeliveryAssignmentPlan(
                        courier.getId(),
                        courier.getName(),
                        departure,
                        arrivalTime,
                        returnTime,
                        oneWayMinutes,
                        address
                ))
                .orElseThrow(() -> new RuntimeException("요청하신 시간에 배달 가능한 직원이 없습니다."));
    }

    @Transactional("scheduleTransactionManager")
    public DeliverySchedule commitAssignmentForOrder(Long orderId, Long employeeId, LocalDateTime deliveryTime, String deliveryAddress) {
        if (orderId == null || employeeId == null || deliveryTime == null || deliveryAddress == null) {
            throw new IllegalArgumentException("주문 ID, 직원 ID, 배달 시간, 배달 주소는 필수입니다.");
        }

        int oneWayMinutes = travelTimeEstimator.estimateOneWayMinutes(deliveryAddress, deliveryTime);
        LocalDateTime departure = deliveryTime.minusMinutes(oneWayMinutes);
        LocalDateTime returnTime = deliveryTime.plusMinutes(oneWayMinutes);

        validateWithinShift(departure, returnTime);

        if (deliveryScheduleRepository.existsActiveOverlap(employeeId, orderId, departure, returnTime)) {
            throw new RuntimeException("해당 시간대에는 이미 다른 배달이 배정되어 있습니다.");
        }

        // Check if schedule already exists - update instead of delete/create
        DeliverySchedule schedule = deliveryScheduleRepository.findByOrderId(orderId)
            .orElse(new DeliverySchedule());
        
        // If it's a new schedule, set the order ID
        if (schedule.getId() == null) {
            schedule.setOrderId(orderId);
        }
        
        // Update schedule fields
        schedule.setEmployeeId(employeeId);
        schedule.setDeliveryAddress(deliveryAddress);
        schedule.setDepartureTime(departure);
        schedule.setArrivalTime(deliveryTime);
        schedule.setReturnTime(returnTime);
        schedule.setOneWayMinutes(oneWayMinutes);
        if (!"CANCELLED".equals(schedule.getStatus())) {
            schedule.setStatus("SCHEDULED");
        }

        return deliveryScheduleRepository.saveAndFlush(schedule);
    }

    @Transactional("scheduleTransactionManager")
    public DeliverySchedule commitAssignment(Long orderId, DeliveryAssignmentPlan plan) {
        if (orderId == null) {
            throw new IllegalArgumentException("주문 ID는 필수입니다.");
        }
        if (plan == null) {
            throw new IllegalArgumentException("배달 할당 계획은 필수입니다.");
        }

        // Check if schedule already exists for this order - update instead of create
        DeliverySchedule schedule = deliveryScheduleRepository.findByOrderId(orderId)
            .orElse(new DeliverySchedule());
        
        // If it's a new schedule, set the order ID
        if (schedule.getId() == null) {
            schedule.setOrderId(orderId);
        }

        // Double-check availability (race condition prevention) - only for new assignments
        if (schedule.getId() == null && deliveryScheduleRepository.existsByEmployeeIdAndReturnTimeAfterAndDepartureTimeBefore(
                plan.employeeId(), plan.departureTime(), plan.returnTime())) {
            throw new RuntimeException("해당 시간대에 배달 직원이 이미 배정되었습니다. 다시 시도해주세요.");
        }

        // Verify employee still exists
        if (!userRepository.findById(plan.employeeId()).isPresent()) {
            throw new RuntimeException("배달 직원을 찾을 수 없습니다.");
        }

        // Update schedule fields
        schedule.setEmployeeId(plan.employeeId());
        schedule.setDeliveryAddress(plan.deliveryAddress());
        schedule.setDepartureTime(plan.departureTime());
        schedule.setArrivalTime(plan.arrivalTime());
        schedule.setReturnTime(plan.returnTime());
        schedule.setOneWayMinutes(plan.oneWayMinutes());
        if (!"CANCELLED".equals(schedule.getStatus())) {
            schedule.setStatus("SCHEDULED");
        }
        
        System.out.println("[DeliverySchedulingService] 주문 ID " + orderId + "에 대한 배달 스케줄 저장/업데이트");
        return deliveryScheduleRepository.saveAndFlush(schedule);
    }

    @Transactional("scheduleTransactionManager")
    public void releaseAssignmentForOrder(Long orderId) {
        deliveryScheduleRepository.deleteByOrderId(orderId);
    }

    @Transactional("scheduleTransactionManager")
    public void cancelScheduleForOrder(Long orderId) {
        if (orderId == null) {
            throw new IllegalArgumentException("주문 ID는 필수입니다.");
        }
        
        try {
            DeliverySchedule schedule = deliveryScheduleRepository.findByOrderId(orderId)
                    .orElse(null);
            if (schedule == null) {
                System.out.println("[DeliverySchedulingService] 주문 " + orderId + "에 대한 배달 스케줄을 찾을 수 없습니다. (이미 취소되었거나 없었습니다.)");
                // This is not necessarily an error - order might not have had a delivery schedule
                return;
            }

            String previousStatus = schedule.getStatus();
            if ("CANCELLED".equals(previousStatus)) {
                System.out.println("[DeliverySchedulingService] 주문 " + orderId + "의 배달 스케줄은 이미 취소되었습니다.");
                return;
            }

            schedule.setStatus("CANCELLED");
            deliveryScheduleRepository.saveAndFlush(schedule);
            System.out.println("[DeliverySchedulingService] 주문 " + orderId + "의 배달 스케줄이 취소되었습니다. (이전 상태: " + previousStatus + ")");
        } catch (Exception e) {
            System.err.println("[DeliverySchedulingService] 배달 스케줄 취소 중 오류 발생: " + e.getMessage());
            e.printStackTrace();
            throw new RuntimeException("배달 스케줄 취소 중 오류가 발생했습니다: " + e.getMessage(), e);
        }
    }

    @Transactional(value = "scheduleTransactionManager", readOnly = true)
    public List<DeliverySchedule> getSchedulesForUser(Long requesterId, boolean isAdmin, LocalDate date) {
        if (requesterId == null) {
            throw new IllegalArgumentException("요청자 ID는 필수입니다.");
        }
        LocalDate targetDate = date != null ? date : LocalDate.now();
        LocalDateTime start = LocalDateTime.of(targetDate, shiftStart);
        LocalDateTime end = LocalDateTime.of(targetDate, shiftEnd);
        if (isAdmin) {
            return deliveryScheduleRepository.findByDepartureTimeBetween(start, end);
        }
        return deliveryScheduleRepository.findByEmployeeIdAndDepartureTimeBetween(requesterId, start, end);
    }

    @Transactional("scheduleTransactionManager")
    public DeliverySchedule updateStatus(Long scheduleId, String status, Long requesterId, boolean isAdmin) {
        if (scheduleId == null) {
            throw new IllegalArgumentException("스케줄 ID는 필수입니다.");
        }
        if (requesterId == null) {
            throw new IllegalArgumentException("요청자 ID는 필수입니다.");
        }
        
        String targetStatus = status != null ? status.toUpperCase() : null;
        if (targetStatus == null || !SUPPORTED_STATUSES.contains(targetStatus)) {
            throw new IllegalArgumentException("유효하지 않은 배달 스케줄 상태입니다: " + status);
        }

        DeliverySchedule schedule = deliveryScheduleRepository.findById(scheduleId)
                .orElseThrow(() -> new RuntimeException("배달 스케줄을 찾을 수 없습니다: " + scheduleId));

        if (!isAdmin && !schedule.getEmployeeId().equals(requesterId)) {
            throw new RuntimeException("이 스케줄을 수정할 권한이 없습니다.");
        }

        schedule.setStatus(targetStatus);
        return deliveryScheduleRepository.saveAndFlush(schedule);
    }

    private void validateWithinShift(LocalDateTime departure, LocalDateTime returnTime) {
        LocalTime departureTime = departure.toLocalTime();
        LocalTime returnTimeValue = returnTime.toLocalTime();
        if (departureTime.isBefore(shiftStart) || returnTimeValue.isAfter(shiftEnd)) {
            throw new RuntimeException(String.format(
                    "배달 가능 시간은 %s ~ %s 사이입니다. 요청된 일정으로는 배달이 불가능합니다.",
                    shiftStart, shiftEnd
            ));
        }
    }

    public record DeliveryAssignmentPlan(Long employeeId,
                                         String employeeName,
                                         LocalDateTime departureTime,
                                         LocalDateTime arrivalTime,
                                         LocalDateTime returnTime,
                                         Integer oneWayMinutes,
                                         String deliveryAddress) { }
}

