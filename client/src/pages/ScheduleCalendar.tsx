import React, { useState, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import axios from 'axios';
import { useAuth } from '../contexts/AuthContext';
import TopLogo from '../components/TopLogo';
import './ScheduleCalendar.css';

const API_URL = process.env.REACT_APP_API_URL || (window.location.protocol === 'https:' ? '/api' : 'http://localhost:5000/api');

interface DeliverySchedule {
  id: number;
  order_id: number;
  employee_id: number;
  employee_name?: string;
  employee_phone?: string;
  delivery_address: string;
  departure_time: string;
  arrival_time: string;
  return_time: string;
  one_way_minutes: number;
  status: string;
}

interface OrderItem {
  id: number;
  menu_item_id: number;
  quantity: number;
  name?: string;
  name_en?: string;
  price?: number;
}

interface Order {
  id: number;
  customer_name?: string;
  customer_phone?: string;
  dinner_name?: string;
  delivery_time: string;
  delivery_address: string;
  status: string;
  cooking_employee_id?: number;
  delivery_employee_id?: number;
  cooking_employee_name?: string;
  delivery_employee_name?: string;
  items?: OrderItem[];
  admin_approval_status?: string;
  loyalty_discount_applied?: boolean;
  original_price?: number;
  discount_amount?: number;
  discount_percentage?: number;
}

interface User {
  id: number;
  name: string;
  email: string;
  role: string;
}

interface ScheduleCalendarProps {
  type?: 'schedule' | 'orders';
}

const ScheduleCalendar: React.FC<ScheduleCalendarProps> = ({ type: propType }) => {
  const navigate = useNavigate();
  const { user } = useAuth();
  const [currentDate, setCurrentDate] = useState<Date>(new Date());
  const [selectedEmployeeId, setSelectedEmployeeId] = useState<number | null>(null);
  const [schedules, setSchedules] = useState<DeliverySchedule[]>([]);
  const [orders, setOrders] = useState<Order[]>([]);
  const [employees, setEmployees] = useState<User[]>([]);
  const [workAssignments, setWorkAssignments] = useState<{[key: string]: {tasks: string[]}}>({});
  // eslint-disable-next-line @typescript-eslint/no-unused-vars
  const [loading, setLoading] = useState(false);
  // eslint-disable-next-line @typescript-eslint/no-unused-vars
  const [error, setError] = useState('');
  const [selectedDate, setSelectedDate] = useState<Date | null>(null);
  const [selectedSchedules, setSelectedSchedules] = useState<DeliverySchedule[]>([]);
  const [selectedOrders, setSelectedOrders] = useState<Order[]>([]);
  const [showScheduleModal, setShowScheduleModal] = useState(false);
  
  // Get calendar type from prop or URL parameter
  const [searchParams] = React.useState(() => {
    const params = new URLSearchParams(window.location.search);
    return params.get('type') || 'schedule'; // default to 'schedule'
  });
  const calendarType = propType || (searchParams === 'orders' ? 'orders' : 'schedule');

  const isAdmin = user?.role === 'admin';

  useEffect(() => {
    if (isAdmin) {
      fetchEmployees();
    }
    if (calendarType === 'schedule') {
      fetchSchedules();
      fetchWorkAssignments();
    } else {
      fetchOrders();
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [currentDate.getMonth(), currentDate.getFullYear(), selectedEmployeeId, isAdmin, calendarType]);

  const getAuthHeaders = () => {
    const token = localStorage.getItem('token');
    if (!token) {
      throw new Error('로그인이 필요합니다.');
    }
    return {
      Authorization: `Bearer ${token}`
    };
  };

  const fetchEmployees = async () => {
    try {
      const headers = getAuthHeaders();
      const response = await axios.get(`${API_URL}/admin/users`, { headers });
      if (response.data && Array.isArray(response.data)) {
        const employeeList = response.data.filter((u: User) => u && u.role === 'employee');
        setEmployees(employeeList);
      } else {
        setEmployees([]);
      }
    } catch (err: any) {
      console.error('직원 목록 조회 실패:', err);
      // Don't show error to user for employee list fetch failure
      // It's not critical - they can still use the calendar
      setEmployees([]);
    }
  };

  const fetchSchedules = async () => {
    try {
      if (!user) {
        return;
      }

      const headers = getAuthHeaders();
      // 로컬 날짜 문자열 생성 (UTC 변환 없이)
      const year = currentDate.getFullYear();
      const month = (currentDate.getMonth() + 1).toString().padStart(2, '0');
      const day = currentDate.getDate().toString().padStart(2, '0');
      const dateStr = `${year}-${month}-${day}`;
      let url = `${API_URL}/employee/delivery-schedule?date=${dateStr}`;
      
      if (isAdmin && selectedEmployeeId) {
        url += `&employeeId=${selectedEmployeeId}`;
      }

      const response = await axios.get(url, { headers });
      if (response.data && Array.isArray(response.data)) {
        const validSchedules = response.data.filter((schedule: any) => 
          schedule && 
          typeof schedule.id === 'number' &&
          typeof schedule.order_id === 'number' &&
          schedule.departure_time
        );
        setSchedules(validSchedules);
      } else {
        setSchedules([]);
      }
    } catch (err: any) {
      console.error('배달 스케줄 조회 실패:', err);
      setSchedules([]);
    }
  };

  const fetchWorkAssignments = async () => {
    try {
      if (!user) {
        return;
      }

      const headers = getAuthHeaders();
      const year = currentDate.getFullYear();
      const month = currentDate.getMonth();
      const firstDay = new Date(year, month, 1);
      const lastDay = new Date(year, month + 1, 0);
      
      // 로컬 날짜 문자열 생성 (UTC 변환 없이)
      const startYear = firstDay.getFullYear();
      const startMonth = (firstDay.getMonth() + 1).toString().padStart(2, '0');
      const startDay = firstDay.getDate().toString().padStart(2, '0');
      const startDateStr = `${startYear}-${startMonth}-${startDay}`;
      
      const endYear = lastDay.getFullYear();
      const endMonth = (lastDay.getMonth() + 1).toString().padStart(2, '0');
      const endDay = lastDay.getDate().toString().padStart(2, '0');
      const endDateStr = `${endYear}-${endMonth}-${endDay}`;
      
      // 한 번의 API 호출로 월 전체 할당 조회
      try {
        const response = await axios.get(`${API_URL}/employee/schedule/assignments?startDate=${startDateStr}&endDate=${endDateStr}`, { headers });
        if (response.data && typeof response.data === 'object') {
          const assignments: {[key: string]: {tasks: string[]}} = {};
          Object.keys(response.data).forEach((dateStr: string) => {
            const data = response.data[dateStr];
            if (data && data.isWorking && data.tasks) {
              assignments[dateStr] = {
                tasks: data.tasks || []
              };
            }
          });
          setWorkAssignments(assignments);
        } else {
          setWorkAssignments({});
        }
      } catch (err: any) {
        console.error('작업 할당 조회 실패:', err);
        setWorkAssignments({});
      }
    } catch (err: any) {
      console.error('작업 할당 조회 실패:', err);
      setWorkAssignments({});
    }
  };

  const fetchOrders = async () => {
    try {
      if (!user) {
        return;
      }

      const headers = getAuthHeaders();
      // 관리자는 모든 주문, 직원은 모든 주문을 가져와서 필터링
      const url = `${API_URL}/employee/orders`;
      
      const response = await axios.get(url, { headers });
      if (response.data && Array.isArray(response.data)) {
        // 현재 월의 주문만 필터링
        const currentMonth = currentDate.getMonth();
        const currentYear = currentDate.getFullYear();
        const filteredOrders = response.data.filter((order: Order) => {
          if (!order.delivery_time) return false;
          try {
            // delivery_time이 "YYYY-MM-DDTHH:mm" 형식이면 직접 파싱
            let orderDate: Date;
            if (order.delivery_time.includes('T')) {
              const parts = order.delivery_time.split('T');
              if (parts.length === 2) {
                const datePart = parts[0].split('-');
                const timePart = parts[1].split(':');
                if (datePart.length === 3 && timePart.length >= 2) {
                  orderDate = new Date(
                    parseInt(datePart[0]),
                    parseInt(datePart[1]) - 1, // month는 0부터 시작
                    parseInt(datePart[2]),
                    parseInt(timePart[0]),
                    parseInt(timePart[1])
                  );
                } else {
                  orderDate = new Date(order.delivery_time);
                }
              } else {
                orderDate = new Date(order.delivery_time);
              }
            } else {
              orderDate = new Date(order.delivery_time);
            }
            if (isNaN(orderDate.getTime())) return false;
            return orderDate.getMonth() === currentMonth && orderDate.getFullYear() === currentYear;
          } catch {
            return false;
          }
        });
        const approvedOnly = filteredOrders.filter((order: Order) =>
          (order.admin_approval_status || '').toUpperCase() === 'APPROVED'
        );
        
        // 정렬: 날짜/시간 빠른 순, 처리 늦어진 순
        approvedOnly.sort((a: Order, b: Order) => {
          // 먼저 날짜/시간 빠른 순
          const aTime = new Date(a.delivery_time).getTime();
          const bTime = new Date(b.delivery_time).getTime();
          if (aTime !== bTime) {
            return aTime - bTime;
          }
          
          // 같은 시간이면 처리 늦어진 순 (pending > cooking > ready > out_for_delivery > delivered)
          const statusOrder: { [key: string]: number } = {
            'pending': 0,
            'cooking': 1,
            'ready': 2,
            'out_for_delivery': 3,
            'delivered': 4,
            'cancelled': 5
          };
          const aStatusOrder = statusOrder[a.status] ?? 999;
          const bStatusOrder = statusOrder[b.status] ?? 999;
          return aStatusOrder - bStatusOrder;
        });
        
        setOrders(approvedOnly);
      } else {
        setOrders([]);
      }
    } catch (err: any) {
      console.error('주문 목록 조회 실패:', err);
      setOrders([]);
    }
  };

  const getDaysInMonth = (date: Date): (Date | null)[] => {
    try {
      if (!date || isNaN(date.getTime())) {
        date = new Date(); // Fallback to current date if invalid
      }
      const year = date.getFullYear();
      const month = date.getMonth();
      
      // Validate year and month
      if (year < 1900 || year > 2100 || month < 0 || month > 11) {
        date = new Date(); // Fallback to current date if invalid
        return getDaysInMonth(date);
      }
      
      const firstDay = new Date(year, month, 1);
      const lastDay = new Date(year, month + 1, 0);
      const daysInMonth = lastDay.getDate();
      const startingDayOfWeek = firstDay.getDay();

      const days: (Date | null)[] = [];
      // Add empty cells for days before the first day of the month
      for (let i = 0; i < startingDayOfWeek; i++) {
        days.push(null);
      }
      // Add all days of the month
      for (let day = 1; day <= daysInMonth; day++) {
        const dayDate = new Date(year, month, day);
        if (isNaN(dayDate.getTime())) {
          continue; // Skip invalid dates
        }
        days.push(dayDate);
      }
      return days;
    } catch {
      // Fallback: return current month
      return getDaysInMonth(new Date());
    }
  };

  const getOrdersForDate = (date: Date | null): Order[] => {
    if (!date) return [];
    try {
      // 로컬 날짜 문자열 생성 (UTC 변환 없이)
      const year = date.getFullYear();
      const month = (date.getMonth() + 1).toString().padStart(2, '0');
      const day = date.getDate().toString().padStart(2, '0');
      const dateStr = `${year}-${month}-${day}`;
      return orders.filter(order => {
        if (!order || !order.delivery_time) return false;
        try {
          // delivery_time이 "YYYY-MM-DDTHH:mm" 형식이면 직접 파싱 (UTC 변환 없이)
          let orderDateStr: string;
          if (order.delivery_time.includes('T')) {
            // "YYYY-MM-DDTHH:mm" 형식인 경우 날짜 부분만 추출
            orderDateStr = order.delivery_time.split('T')[0];
          } else {
            // 다른 형식인 경우 Date 객체로 파싱
            const orderDate = new Date(order.delivery_time);
            if (isNaN(orderDate.getTime())) return false;
            // 로컬 날짜로 변환 (UTC 변환 없이)
            const year = orderDate.getFullYear();
            const month = (orderDate.getMonth() + 1).toString().padStart(2, '0');
            const day = orderDate.getDate().toString().padStart(2, '0');
            orderDateStr = `${year}-${month}-${day}`;
          }
          return orderDateStr === dateStr;
        } catch {
          return false;
        }
      });
    } catch {
      return [];
    }
  };

  const getSchedulesForDate = (date: Date | null): DeliverySchedule[] => {
    if (!date) return [];
    try {
      // 로컬 날짜 문자열 생성 (UTC 변환 없이)
      const year = date.getFullYear();
      const month = (date.getMonth() + 1).toString().padStart(2, '0');
      const day = date.getDate().toString().padStart(2, '0');
      const dateStr = `${year}-${month}-${day}`;
      return schedules.filter(schedule => {
        if (!schedule || !schedule.departure_time) return false;
        try {
          const scheduleDate = new Date(schedule.departure_time);
          if (isNaN(scheduleDate.getTime())) return false;
          // 로컬 날짜로 변환 (UTC 변환 없이)
          const scheduleYear = scheduleDate.getFullYear();
          const scheduleMonth = (scheduleDate.getMonth() + 1).toString().padStart(2, '0');
          const scheduleDay = scheduleDate.getDate().toString().padStart(2, '0');
          const scheduleDateStr = `${scheduleYear}-${scheduleMonth}-${scheduleDay}`;
          return scheduleDateStr === dateStr;
        } catch {
          return false;
        }
      });
    } catch {
      return [];
    }
  };

  const hasMySchedule = (date: Date | null): boolean => {
    if (!date || !user) return false;
    if (calendarType === 'schedule') {
      // For schedule calendar, check if employee has work assignments
      // 로컬 날짜 문자열 생성 (UTC 변환 없이)
      const year = date.getFullYear();
      const month = (date.getMonth() + 1).toString().padStart(2, '0');
      const day = date.getDate().toString().padStart(2, '0');
      const dateStr = `${year}-${month}-${day}`;
      const assignment = workAssignments[dateStr];
      if (assignment && assignment.tasks && assignment.tasks.length > 0) {
        return true;
      }
      // Fallback to old logic for backward compatibility
      const daySchedules = getSchedulesForDate(date);
      const dayOrders = getOrdersForDate(date);
      return daySchedules.some(schedule => schedule.employee_id === user.id) ||
             dayOrders.some(order => 
               order.cooking_employee_id === user.id || 
               order.delivery_employee_id === user.id
             );
    } else {
      // For orders calendar, check if there are any orders
      const dayOrders = getOrdersForDate(date);
      return dayOrders.length > 0;
    }
  };

  const getOrderColor = (order: Order, date: Date | null): 'red' | 'green' | 'gray' => {
    if (!date) return 'green';
    
    // 조리 시작 이후 취소된 주문은 회색
    if (order.status === 'cancelled' && (order.admin_approval_status === 'APPROVED' || order.cooking_employee_id != null)) {
      return 'gray';
    }
    
    // 끝난 주문은 초록색
    if (order.status === 'delivered' || order.status === 'cancelled') {
      return 'green';
    }
    
    // 해당 날짜에 근무하는 직원인지 확인
    const dateStr = date.toISOString().split('T')[0];
    const assignment = workAssignments[dateStr];
    
    if (!assignment || !assignment.tasks || assignment.tasks.length === 0) {
      // 할당된 작업이 없으면 초록색
      return 'green';
    }
    
    // 할당된 작업이 있는 경우, 작업 완료 여부 확인
    const hasCookingTask = assignment.tasks.includes('조리');
    const hasDeliveryTask = assignment.tasks.includes('배달');
    
    // 조리 작업이 할당된 경우: ready 상태 이상이면 초록색
    if (hasCookingTask) {
      if (order.status === 'ready' || order.status === 'out_for_delivery' || order.status === 'delivered') {
        return 'green';
      }
    }
    
    // 배달 작업이 할당된 경우: delivered 상태면 초록색
    if (hasDeliveryTask) {
      if (order.status === 'delivered') {
        return 'green';
      }
    }
    
    // 할당된 작업이 있지만 아직 완료되지 않았으면 빨간색
    return 'red';
  };

  const handleDateClick = (date: Date | null) => {
    if (!date) return;
    if (calendarType === 'orders') {
      // 주문 캘린더: 하단 대시보드에 표시
      const dayOrders = getOrdersForDate(date);
      if (dayOrders.length > 0) {
        setSelectedDate(date);
        setSelectedOrders(dayOrders);
        setSelectedSchedules([]);
        // 모달 대신 하단 대시보드에 표시
      }
    } else {
      // 스케줄 캘린더: 기존 모달 방식
      const dayOrders = getOrdersForDate(date);
      const daySchedules = getSchedulesForDate(date);
      if (dayOrders.length > 0 || daySchedules.length > 0) {
        setSelectedDate(date);
        setSelectedOrders(dayOrders);
        setSelectedSchedules(daySchedules);
        setShowScheduleModal(true);
      }
    }
  };

  const navigateMonth = (direction: 'prev' | 'next') => {
    setCurrentDate(prev => {
      try {
        const newDate = new Date(prev);
        if (isNaN(newDate.getTime())) {
          return new Date(); // Fallback to current date if invalid
        }
        if (direction === 'prev') {
          newDate.setMonth(prev.getMonth() - 1);
        } else {
          newDate.setMonth(prev.getMonth() + 1);
        }
        // Validate the new date
        if (isNaN(newDate.getTime())) {
          return new Date(); // Fallback to current date if invalid
        }
        return newDate;
      } catch {
        return new Date(); // Fallback to current date on error
      }
    });
  };

  // eslint-disable-next-line @typescript-eslint/no-unused-vars
  const goToToday = () => {
    setCurrentDate(new Date());
  };

  const formatTime = (dateString: string) => {
    try {
      if (!dateString) return '--:--';
      const date = new Date(dateString);
      if (isNaN(date.getTime())) return '--:--';
      return date.toLocaleTimeString('ko-KR', { 
        hour: '2-digit', 
        minute: '2-digit',
        hour12: false 
      });
    } catch {
      return '--:--';
    }
  };

  const getStatusColor = (status: string) => {
    const colors: { [key: string]: string } = {
      SCHEDULED: '#4CAF50',
      IN_PROGRESS: '#2196F3',
      COMPLETED: '#9E9E9E',
      CANCELLED: '#F44336'
    };
    return colors[status] || '#757575';
  };

  const getStatusLabel = (status: string) => {
    const labels: { [key: string]: string } = {
      SCHEDULED: '배정됨',
      IN_PROGRESS: '배달 중',
      COMPLETED: '완료',
      CANCELLED: '취소'
    };
    return labels[status] || status;
  };

  const days = getDaysInMonth(currentDate);
  const monthYear = (() => {
    try {
      if (!currentDate || isNaN(currentDate.getTime())) {
        return new Date().toLocaleDateString('ko-KR', { year: 'numeric', month: 'long' });
      }
      return currentDate.toLocaleDateString('ko-KR', { year: 'numeric', month: 'long' });
    } catch {
      return new Date().toLocaleDateString('ko-KR', { year: 'numeric', month: 'long' });
    }
  })();

  // Don't show navigation if used as component
  const showNavigation = !propType;

  return (
    <div className="schedule-calendar-page">
      {showNavigation && <TopLogo />}
      {showNavigation && (
        <div style={{ marginBottom: '20px', paddingLeft: '20px', display: 'flex', gap: '10px', alignItems: 'center' }}>
          <button onClick={() => navigate('/employee/orders')} className="btn btn-secondary">
            ← 스케줄 탭으로
          </button>
          <div style={{ display: 'flex', gap: '10px' }}>
            <button
              className={`btn ${calendarType === 'schedule' ? 'btn-primary' : 'btn-secondary'}`}
              onClick={() => navigate('/schedule?type=schedule')}
            >
              📅 스케줄 캘린더
            </button>
            <button
              className={`btn ${calendarType === 'orders' ? 'btn-primary' : 'btn-secondary'}`}
              onClick={() => navigate('/schedule?type=orders')}
            >
              📋 주문 캘린더
            </button>
          </div>
        </div>
      )}

      <div className="container">
        {error && <div className="error">{error}</div>}

        {/* Employee Filter (Admin only) */}
        {isAdmin && (
          <div className="employee-filter">
            <label>직원 선택:</label>
            <select
              value={selectedEmployeeId || ''}
              onChange={(e) => setSelectedEmployeeId(e.target.value ? Number(e.target.value) : null)}
              className="filter-select"
            >
              <option value="">전체 직원</option>
              {employees.map(emp => (
                <option key={emp.id} value={emp.id}>
                  {emp.name} ({emp.email})
                </option>
              ))}
            </select>
          </div>
        )}

        {/* Calendar Controls - Same as Admin */}
        <div style={{ marginBottom: '20px', display: 'flex', gap: '10px', alignItems: 'center' }}>
          <button onClick={() => navigateMonth('prev')} className="btn btn-secondary">
            이전 달
          </button>
          <h3 style={{ margin: 0, minWidth: '150px', textAlign: 'center' }}>
            {monthYear}
          </h3>
          <button onClick={() => navigateMonth('next')} className="btn btn-secondary">
            다음 달
          </button>
        </div>

        {/* Color Legend for Schedule Calendar */}
        {calendarType === 'schedule' && !isAdmin && (
          <div style={{ 
            marginBottom: '20px', 
            padding: '15px', 
            background: '#1a1a1a', 
            borderRadius: '8px',
            border: '1px solid #d4af37',
            display: 'flex',
            gap: '20px',
            alignItems: 'center',
            flexWrap: 'wrap'
          }}>
            <div style={{ display: 'flex', gap: '10px', alignItems: 'center' }}>
              <div style={{ 
                width: '20px', 
                height: '20px', 
                background: '#4CAF50', 
                border: '1px solid #000',
                borderRadius: '4px'
              }}></div>
              <span style={{ color: '#fff' }}>비번</span>
            </div>
            <div style={{ display: 'flex', gap: '10px', alignItems: 'center' }}>
              <div style={{ 
                width: '20px', 
                height: '20px', 
                background: '#ff4444', 
                border: '1px solid #000',
                borderRadius: '4px'
              }}></div>
              <span style={{ color: '#fff' }}>출근일</span>
            </div>
          </div>
        )}

        {/* Calendar Grid - Same as Admin */}
        {loading ? (
          <div className="loading">스케줄을 불러오는 중...</div>
        ) : (
          <div style={{ 
            display: 'grid', 
            gridTemplateColumns: 'repeat(7, 1fr)', 
            gap: '5px',
            marginBottom: '30px'
          }}>
            {['일', '월', '화', '수', '목', '금', '토'].map(day => (
              <div key={day} style={{ 
                padding: '10px', 
                textAlign: 'center', 
                fontWeight: 'bold',
                background: '#d4af37',
                color: '#000'
              }}>
                {day}
              </div>
            ))}
            {days.map((date: Date | null, index: number) => {
              if (date === null) {
                return <div key={`empty-${index}`} style={{ padding: '20px' }} />;
              }
              const dayOrders = getOrdersForDate(date);
              const daySchedules = getSchedulesForDate(date);
              const isToday = date && 
                date.toDateString() === new Date().toDateString() &&
                !isNaN(date.getTime());
              const isPast = date && date < new Date() && date.toDateString() !== new Date().toDateString();

              const hasMySchedules = hasMySchedule(date);
              const isClickable = date && !isPast && (calendarType === 'schedule' ? hasMySchedules : (dayOrders.length > 0 || daySchedules.length > 0));
              
              // For schedule calendar: red if employee has work, green if not
              // For orders calendar: red if there are incomplete tasks, green if all tasks completed
              const getDayColor = () => {
                if (!date) return '';
                if (calendarType === 'schedule') {
                  // 해당 날에 할당된 작업이 있으면 빨간색
                  // 로컬 날짜 문자열 생성 (UTC 변환 없이)
                  const year = date.getFullYear();
                  const month = (date.getMonth() + 1).toString().padStart(2, '0');
                  const day = date.getDate().toString().padStart(2, '0');
                  const dateStr = `${year}-${month}-${day}`;
                  const assignment = workAssignments[dateStr];
                  if (assignment && assignment.tasks && assignment.tasks.length > 0) {
                    return 'red';
                  }
                  return hasMySchedules ? 'red' : 'green';
                } else {
                  // Orders calendar: check if there are incomplete tasks
                  const year = date.getFullYear();
                  const month = (date.getMonth() + 1).toString().padStart(2, '0');
                  const day = date.getDate().toString().padStart(2, '0');
                  const dateStr = `${year}-${month}-${day}`;
                  const assignment = workAssignments[dateStr];
                  const dayOrders = getOrdersForDate(date);
                  
                  if (assignment && assignment.tasks && assignment.tasks.length > 0 && dayOrders.length > 0) {
                    // Check if all assigned tasks are completed
                    const allCompleted = dayOrders.every(order => {
                      const isCompleted = order.status === 'delivered' || order.status === 'cancelled';
                      return isCompleted;
                    });
                    return allCompleted ? 'green' : 'red';
                  }
                  return '';
                }
              };
              const dayColor = getDayColor();

              return (
                <div
                  key={index}
                  onClick={() => isClickable && handleDateClick(date)}
                  style={{
                    padding: '15px',
                    textAlign: 'center',
                    cursor: isPast ? 'not-allowed' : (isClickable ? 'pointer' : 'default'),
                    background: isPast ? '#ccc' : 
                      (dayColor === 'red' ? '#ff4444' : dayColor === 'green' ? '#4CAF50' : '#f5f5f5'),
                    color: isPast ? '#666' : 
                      (dayColor ? '#fff' : '#000'),
                    border: isToday ? '2px solid #FFD700' : '1px solid #ddd',
                    borderRadius: '4px',
                    opacity: isPast ? 0.5 : 1
                  }}
                >
                  <div style={{ fontWeight: 'bold' }}>{date.getDate()}</div>
                  {calendarType === 'orders' && dayOrders.length > 0 && (
                    <div style={{
                      fontSize: '14px',
                      fontWeight: 'bold',
                      color: '#000',
                      marginTop: '5px'
                    }}>
                      {dayOrders.length}개
                    </div>
                  )}
                </div>
              );
            })}
          </div>
        )}

        {/* Orders Dashboard (Bottom) - Only for orders calendar */}
        {calendarType === 'orders' && selectedDate && selectedOrders.length > 0 && (
          <div style={{
            position: 'fixed',
            bottom: 0,
            left: 0,
            right: 0,
            background: '#1a1a1a',
            borderTop: '2px solid #d4af37',
            maxHeight: '50vh',
            overflowY: 'auto',
            zIndex: 1000,
            padding: '20px'
          }}>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '15px' }}>
              <h3 style={{ color: '#d4af37', margin: 0 }}>
                {selectedDate.toLocaleDateString('ko-KR', { 
                  year: 'numeric', 
                  month: 'long', 
                  day: 'numeric',
                  weekday: 'long'
                })} 작업 목록
              </h3>
              <button 
                onClick={() => {
                  setSelectedDate(null);
                  setSelectedOrders([]);
                }}
                className="btn btn-secondary"
                style={{ padding: '5px 15px' }}
              >
                닫기
              </button>
            </div>
            <div style={{ display: 'flex', flexDirection: 'column', gap: '10px' }}>
              {(() => {
                // 로컬 날짜 문자열 생성
                let dateStr = '';
                if (selectedDate) {
                  const year = selectedDate.getFullYear();
                  const month = (selectedDate.getMonth() + 1).toString().padStart(2, '0');
                  const day = selectedDate.getDate().toString().padStart(2, '0');
                  dateStr = `${year}-${month}-${day}`;
                }
                const assignment = workAssignments[dateStr];
                
                // 주문 정렬: 배달 시간 순, 미완료 우선
                const sortedOrders = [...selectedOrders].sort((a, b) => {
                  // 완료된 주문은 하단으로
                  const aCompleted = a.status === 'delivered' || a.status === 'cancelled';
                  const bCompleted = b.status === 'delivered' || b.status === 'cancelled';
                  if (aCompleted && !bCompleted) return 1;
                  if (!aCompleted && bCompleted) return -1;
                  
                  // 배달 시간 순
                  const aTime = new Date(a.delivery_time).getTime();
                  const bTime = new Date(b.delivery_time).getTime();
                  return aTime - bTime;
                });
                
                return sortedOrders.map(order => {
                  const tasks = assignment?.tasks || [];
                  const hasCookingTask = tasks.includes('조리');
                  const hasDeliveryTask = tasks.includes('배달');
                  
                  // 작업 상태에 따른 색깔 결정
                  const getTaskColor = () => {
                    const isCompleted = order.status === 'delivered' || order.status === 'cancelled';
                    if (isCompleted) return '#808080'; // 회색 (완료)
                    
                    if (hasCookingTask) {
                      if (order.status === 'pending') return '#ff4444'; // 빨간색 (조리 시작 전)
                      if (order.status === 'cooking') return '#ff8800'; // 주황색 (조리 중)
                      if (order.status === 'ready') return '#ff8800'; // 주황색 (조리 완료, 배달 대기)
                      return '#808080'; // 회색 (기타)
                    }
                    
                    if (hasDeliveryTask) {
                      // 조리가 완료되지 않았으면 흐리게
                      if (order.status !== 'ready' && order.status !== 'out_for_delivery' && order.status !== 'delivered') {
                        return '#ff4444'; // 빨간색 (흐리게 표시는 opacity로)
                      }
                      if (order.status === 'ready') return '#ff4444'; // 빨간색 (배달 시작 전)
                      if (order.status === 'out_for_delivery') return '#ff8800'; // 주황색 (배달 중)
                      return '#808080'; // 회색 (기타)
                    }
                    
                    return '#808080'; // 할당되지 않은 작업
                  };
                  
                  const taskColor = getTaskColor();
                  const isCompleted = order.status === 'delivered' || order.status === 'cancelled';
                  const isDeliveryTaskWaiting = hasDeliveryTask && order.status !== 'ready' && order.status !== 'out_for_delivery' && order.status !== 'delivered';
                  
                  return (
                    <div
                      key={order.id}
                      style={{
                        background: taskColor,
                        padding: '15px',
                        borderRadius: '8px',
                        border: '1px solid #d4af37',
                        opacity: isDeliveryTaskWaiting ? 0.5 : 1,
                        order: isCompleted ? 999 : 0 // 완료된 작업은 하단으로
                      }}
                    >
                      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '10px' }}>
                        <div>
                          <h4 style={{ color: '#fff', margin: 0 }}>주문 #{order.id}</h4>
                          <p style={{ color: '#fff', margin: '5px 0', fontSize: '14px' }}>
                            {order.customer_name && `고객: ${order.customer_name}`}
                            {order.dinner_name && ` | ${order.dinner_name}`}
                          </p>
                          <p style={{ color: '#fff', margin: '5px 0', fontSize: '12px' }}>
                            배달 주소: {order.delivery_address || '주소 없음'}
                          </p>
                          {order.items && order.items.length > 0 && (
                            <p style={{ color: '#fff', margin: '5px 0', fontSize: '12px' }}>
                              주문 구성 (총 {order.items.length}개 항목): {order.items.map((item: any) => 
                                `${item.name || item.name_en || '항목'} ${item.quantity}개`
                              ).join(', ')}
                            </p>
                          )}
                          <p style={{ color: '#fff', margin: '5px 0', fontSize: '12px' }}>
                            배달 시간: {formatTime(order.delivery_time || '')}
                          </p>
                          {order.loyalty_discount_applied && (
                            <p style={{ color: '#4aaf4a', margin: '5px 0', fontSize: '12px', fontWeight: 'bold' }}>
                              💰 단골 할인: {order.discount_amount?.toLocaleString()}원 할인 ({order.discount_percentage}%)
                            </p>
                          )}
                          {hasCookingTask && (
                            <p style={{ color: '#FFD700', margin: '5px 0', fontSize: '12px', fontWeight: 'bold' }}>
                              🔧 조리 담당
                            </p>
                          )}
                          {hasDeliveryTask && (
                            <p style={{ color: '#FFD700', margin: '5px 0', fontSize: '12px', fontWeight: 'bold' }}>
                              🚚 배달 담당
                            </p>
                          )}
                        </div>
                        <span style={{
                          background: '#000',
                          color: '#fff',
                          padding: '5px 10px',
                          borderRadius: '4px',
                          fontSize: '12px'
                        }}>
                          {order.status === 'delivered' ? '배달 완료' : 
                           order.status === 'cancelled' ? '주문 취소' :
                           order.status === 'cooking' ? '조리 중' :
                           order.status === 'out_for_delivery' ? '배달 중' :
                           order.status === 'ready' ? '준비 완료' : '주문 접수'}
                        </span>
                      </div>
                      {!isAdmin && (hasCookingTask || hasDeliveryTask) && (
                        <div style={{ marginTop: '10px' }}>
                          {(() => {
                            const getNextStatus = () => {
                              if (order.status === 'pending' && hasCookingTask) return 'cooking';
                              if (order.status === 'cooking' && hasCookingTask) return 'ready';
                              if (order.status === 'ready' && hasDeliveryTask) return 'out_for_delivery';
                              if (order.status === 'out_for_delivery' && hasDeliveryTask) return 'delivered';
                              return null;
                            };
                            
                            const nextStatus = getNextStatus();
                            if (!nextStatus) return null;
                            
                            // 배달 작업인데 조리가 완료되지 않았으면 버튼 비활성화
                            if (hasDeliveryTask && order.status !== 'ready' && order.status !== 'out_for_delivery' && order.status !== 'delivered') {
                              return (
                                <button className="btn btn-secondary" disabled style={{ width: '100%' }}>
                                  조리 완료 대기 중
                                </button>
                              );
                            }
                            
                            const statusLabels: { [key: string]: string } = {
                              'cooking': '조리 시작',
                              'ready': '조리 완료',
                              'out_for_delivery': '배달 시작',
                              'delivered': '배달 완료'
                            };
                            
                            return (
                              <button
                                className="btn btn-primary"
                                onClick={async () => {
                                  try {
                                    const headers = getAuthHeaders();
                                    const statusMessages: { [key: string]: string } = {
                                      'cooking': '조리를 시작합니다',
                                      'ready': '조리를 완료했습니다',
                                      'out_for_delivery': '배달을 시작합니다',
                                      'delivered': '배달을 완료했습니다'
                                    };
                                    const message = statusMessages[nextStatus] || '주문 상태가 변경되었습니다';
                                    
                                    setOrders(prevOrders => 
                                      prevOrders.map(o => 
                                        o.id === order.id ? { ...o, status: nextStatus } : o
                                      )
                                    );
                                    
                                    alert(message);
                                    
                                    await axios.patch(`${API_URL}/employee/orders/${order.id}/status`, 
                                      { status: nextStatus }, 
                                      { headers }
                                    );
                                    
                                    // 완료된 작업은 하단으로 이동하기 위해 재정렬
                                    await fetchOrders();
                                    await fetchWorkAssignments();
                                    
                                    // 선택된 주문 목록도 업데이트
                                    setSelectedOrders(prev => {
                                      const updated = prev.map(o => 
                                        o.id === order.id ? { ...o, status: nextStatus } : o
                                      );
                                      // 완료된 작업은 하단으로
                                      return updated.sort((a, b) => {
                                        const aCompleted = a.status === 'delivered' || a.status === 'cancelled';
                                        const bCompleted = b.status === 'delivered' || b.status === 'cancelled';
                                        if (aCompleted && !bCompleted) return 1;
                                        if (!aCompleted && bCompleted) return -1;
                                        const aTime = new Date(a.delivery_time).getTime();
                                        const bTime = new Date(b.delivery_time).getTime();
                                        return aTime - bTime;
                                      });
                                    });
                                  } catch (err: any) {
                                    setOrders(prevOrders => 
                                      prevOrders.map(o => 
                                        o.id === order.id ? { ...o, status: order.status } : o
                                      )
                                    );
                                    alert(err.response?.data?.error || '주문 상태 변경에 실패했습니다.');
                                  }
                                }}
                                style={{ width: '100%' }}
                              >
                                {statusLabels[nextStatus]}
                              </button>
                            );
                          })()}
                        </div>
                      )}
                    </div>
                  );
                });
              })()}
            </div>
          </div>
        )}

        {/* Schedule Modal - Only for schedule calendar */}
        {showScheduleModal && selectedDate && calendarType === 'schedule' && (
          <div className="schedule-modal-overlay" onClick={() => setShowScheduleModal(false)}>
            <div className="schedule-modal" onClick={(e) => e.stopPropagation()}>
              <div className="schedule-modal-header">
                <h2>
                  {selectedDate.toLocaleDateString('ko-KR', { 
                    year: 'numeric', 
                    month: 'long', 
                    day: 'numeric',
                    weekday: 'long'
                  })}
                </h2>
                <button 
                  className="modal-close-btn"
                  onClick={() => setShowScheduleModal(false)}
                >
                  ×
                </button>
              </div>
              <div className="schedule-modal-content">
                {selectedOrders.length === 0 && selectedSchedules.length === 0 ? (
                  <p className="no-schedules">이 날짜에 주문이 없습니다.</p>
                ) : (
                  <div className="schedule-list">
                    {selectedOrders.map(order => {
                      const orderColor = getOrderColor(order, selectedDate);
                      // 로컬 날짜 문자열 생성 (UTC 변환 없이)
                      let dateStr = '';
                      if (selectedDate) {
                        const year = selectedDate.getFullYear();
                        const month = (selectedDate.getMonth() + 1).toString().padStart(2, '0');
                        const day = selectedDate.getDate().toString().padStart(2, '0');
                        dateStr = `${year}-${month}-${day}`;
                      }
                      const assignment = workAssignments[dateStr];
                      const tasks = assignment?.tasks || [];
                      return (
                        <div 
                          key={order.id} 
                          className={`schedule-card ${orderColor === 'red' ? 'my-schedule-card' : orderColor === 'gray' ? 'cancelled-schedule-card' : 'other-schedule-card'}`}
                          style={orderColor === 'gray' ? { backgroundColor: '#9e9e9e', opacity: 0.7 } : {}}
                        >
                          <div className="schedule-header">
                            <div>
                              <h4>주문 #{order.id || 'N/A'}</h4>
                              <p className="employee-name">
                                {order.customer_name && `고객: ${order.customer_name}`}
                                {order.dinner_name && ` | ${order.dinner_name}`}
                              </p>
                              {calendarType === 'schedule' && tasks.length > 0 && (
                                <p className="employee-name" style={{ fontSize: '14px', marginTop: '8px', color: '#FFD700', fontWeight: 'bold' }}>
                                  {tasks.includes('조리') && tasks.includes('배달') ? '🔧 조리 / 🚚 배달 담당' : 
                                   tasks.includes('조리') ? '🔧 조리 담당' : 
                                   tasks.includes('배달') ? '🚚 배달 담당' : ''}
                                </p>
                              )}
                            </div>
                            <span 
                              className="status-badge"
                              style={{ 
                                backgroundColor: orderColor === 'red' ? '#ff4444' : orderColor === 'gray' ? '#9e9e9e' : '#4CAF50' 
                              }}
                            >
                              {order.status === 'delivered' ? '배달 완료' : 
                               order.status === 'cancelled' ? '주문 취소' :
                               order.status === 'cooking' ? '조리 중' :
                               order.status === 'out_for_delivery' ? '배달 중' :
                               order.status === 'ready' ? '준비 완료' : '주문 접수'}
                            </span>
                          </div>
                          <div className="schedule-details">
                            <div className="detail-item">
                              <span className="detail-label">배달 주소:</span>
                              <span className="detail-value">{order.delivery_address || '주소 없음'}</span>
                            </div>
                            <div className="detail-item">
                              <span className="detail-label">배달 시간:</span>
                              <span className="detail-value">{formatTime(order.delivery_time || '')}</span>
                            </div>
                            <div className="detail-item">
                              <span className="detail-label">주문 항목:</span>
                              <span className="detail-value">
                                {order.items && order.items.length > 0
                                  ? order.items.map((item: any) => 
                                      `${item.name || item.name_en || '항목'} x${item.quantity || 0}`
                                    ).join(', ')
                                  : '항목 없음'}
                              </span>
                            </div>
                            <div className="detail-item">
                              <span className="detail-label">상태:</span>
                              <span className="detail-value">
                                {order.status === 'delivered' ? '배달 완료' : 
                                 order.status === 'cancelled' ? '주문 취소' :
                                 order.status === 'cooking' ? '조리 중' :
                                 order.status === 'out_for_delivery' ? '배달 중' :
                                 order.status === 'ready' ? '준비 완료' : '주문 접수'}
                              </span>
                            </div>
                            {order.loyalty_discount_applied && (
                              <div className="detail-item" style={{ background: '#2a3a2a', padding: '10px', borderRadius: '8px', border: '1px solid #4aaf4a', marginTop: '10px' }}>
                                <span className="detail-label" style={{ color: '#4aaf4a' }}>단골 할인:</span>
                                <span className="detail-value" style={{ color: '#4aaf4a' }}>
                                  {order.discount_amount?.toLocaleString()}원 할인 ({order.discount_percentage}%)
                                  {order.original_price && (
                                    <span style={{ fontSize: '12px', color: '#ccc', marginLeft: '8px' }}>
                                      (원가: {order.original_price.toLocaleString()}원)
                                    </span>
                                  )}
                                </span>
                              </div>
                            )}
                          </div>
                          {!isAdmin && (() => {
                            // 로컬 날짜 문자열 생성 (UTC 변환 없이)
                            let dateStr = '';
                            if (selectedDate) {
                              const year = selectedDate.getFullYear();
                              const month = (selectedDate.getMonth() + 1).toString().padStart(2, '0');
                              const day = selectedDate.getDate().toString().padStart(2, '0');
                              dateStr = `${year}-${month}-${day}`;
                            }
                            const assignment = workAssignments[dateStr];
                            const tasks = assignment?.tasks || [];
                            const hasCookingTask = tasks.includes('조리');
                            const hasDeliveryTask = tasks.includes('배달');
                            
                            // 승인 완료 → 조리 중: 조리원만 가능
                            if (order.status === 'pending' && order.admin_approval_status === 'APPROVED') {
                              if (!hasCookingTask) {
                                return (
                                  <div style={{ marginTop: '15px' }}>
                                    <button className="btn btn-secondary" disabled>
                                      조리 담당 직원만 조리를 시작할 수 있습니다
                                    </button>
                                  </div>
                                );
                              }
                              return (
                                <div style={{ marginTop: '15px', display: 'flex', gap: '10px', flexWrap: 'wrap' }}>
                                  <button
                                    className="btn btn-primary"
                                    onClick={async () => {
                                      try {
                                        const headers = getAuthHeaders();
                                        
                                        // 즉시 로컬 상태 업데이트하여 UI에 반영
                                        setOrders(prevOrders => 
                                          prevOrders.map(o => 
                                            o.id === order.id ? { ...o, status: 'cooking' } : o
                                          )
                                        );
                                        
                                        await axios.post(`${API_URL}/employee/orders/${order.id}/start-cooking`, {}, { headers });
                                        
                                        alert('조리가 시작되었습니다. 재고가 차감되었습니다.');
                                        
                                        // 팝업 닫고 주문 캘린더로 돌아가기
                                        setShowScheduleModal(false);
                                        setSelectedDate(null);
                                        setSelectedOrders([]);
                                        
                                        // 서버에서 최신 데이터 가져오기 (백그라운드)
                                        fetchOrders().catch(console.error);
                                        fetchWorkAssignments().catch(console.error);
                                      } catch (err: any) {
                                        // 실패 시 이전 상태로 복원
                                        setOrders(prevOrders => 
                                          prevOrders.map(o => 
                                            o.id === order.id ? { ...o, status: order.status } : o
                                          )
                                        );
                                        alert(err.response?.data?.error || '조리 시작에 실패했습니다.');
                                      }
                                    }}
                                  >
                                    조리 시작
                                  </button>
                                </div>
                              );
                            }
                            
                            // 조리 중 → 배달 중: 배달원만 가능
                            if (order.status === 'cooking') {
                              if (!hasDeliveryTask) {
                                return (
                                  <div style={{ marginTop: '15px' }}>
                                    <button className="btn btn-secondary" disabled>
                                      배달 담당 직원만 배달을 시작할 수 있습니다
                                    </button>
                                  </div>
                                );
                              }
                              return (
                                <div style={{ marginTop: '15px', display: 'flex', gap: '10px', flexWrap: 'wrap' }}>
                                  <button
                                    className="btn btn-primary"
                                    onClick={async () => {
                                      try {
                                        const headers = getAuthHeaders();
                                        
                                        // 즉시 로컬 상태 업데이트하여 UI에 반영
                                        setOrders(prevOrders => 
                                          prevOrders.map(o => 
                                            o.id === order.id ? { ...o, status: 'out_for_delivery' } : o
                                          )
                                        );
                                        
                                        await axios.post(`${API_URL}/employee/orders/${order.id}/start-delivery`, {}, { headers });
                                        
                                        alert('배달이 시작되었습니다.');
                                        
                                        // 팝업 닫고 주문 캘린더로 돌아가기
                                        setShowScheduleModal(false);
                                        setSelectedDate(null);
                                        setSelectedOrders([]);
                                        
                                        // 서버에서 최신 데이터 가져오기 (백그라운드)
                                        fetchOrders().catch(console.error);
                                        fetchWorkAssignments().catch(console.error);
                                      } catch (err: any) {
                                        // 실패 시 이전 상태로 복원
                                        setOrders(prevOrders => 
                                          prevOrders.map(o => 
                                            o.id === order.id ? { ...o, status: order.status } : o
                                          )
                                        );
                                        alert(err.response?.data?.error || '배달 시작에 실패했습니다.');
                                      }
                                    }}
                                  >
                                    배달 시작
                                  </button>
                                </div>
                              );
                            }
                            
                            // 배달 중 → 배달 완료: 조리원 또는 배달원 가능 (요구사항에 따라 조리원이 배달 완료를 누름)
                            if (order.status === 'out_for_delivery') {
                              if (!hasCookingTask && !hasDeliveryTask) {
                                return (
                                  <div style={{ marginTop: '15px' }}>
                                    <button className="btn btn-secondary" disabled>
                                      조리원 또는 배달 담당 직원만 배달 완료를 처리할 수 있습니다
                                    </button>
                                  </div>
                                );
                              }
                              return (
                                <div style={{ marginTop: '15px', display: 'flex', gap: '10px', flexWrap: 'wrap' }}>
                                  <button
                                    className="btn btn-primary"
                                    onClick={async () => {
                                      try {
                                        const headers = getAuthHeaders();
                                        
                                        // 즉시 로컬 상태 업데이트하여 UI에 반영
                                        setOrders(prevOrders => 
                                          prevOrders.map(o => 
                                            o.id === order.id ? { ...o, status: 'delivered' } : o
                                          )
                                        );
                                        
                                        await axios.post(`${API_URL}/employee/orders/${order.id}/complete-delivery`, {}, { headers });
                                        
                                        alert('배달이 완료되었습니다.');
                                        
                                        // 팝업 닫고 주문 캘린더로 돌아가기
                                        setShowScheduleModal(false);
                                        setSelectedDate(null);
                                        setSelectedOrders([]);
                                        
                                        // 서버에서 최신 데이터 가져오기 (백그라운드)
                                        fetchOrders().catch(console.error);
                                        fetchWorkAssignments().catch(console.error);
                                      } catch (err: any) {
                                        // 실패 시 이전 상태로 복원
                                        setOrders(prevOrders => 
                                          prevOrders.map(o => 
                                            o.id === order.id ? { ...o, status: order.status } : o
                                          )
                                        );
                                        alert(err.response?.data?.error || '배달 완료 처리에 실패했습니다.');
                                      }
                                    }}
                                  >
                                    배달 완료
                                  </button>
                                </div>
                              );
                            }
                            
                            // 다른 상태에서는 버튼 없음
                            return null;
                          })()}
                        </div>
                      );
                    })}
                    {selectedSchedules.map(schedule => (
                      <div 
                        key={`schedule-${schedule.id}`} 
                        className={`schedule-card ${schedule.employee_id === user?.id ? 'my-schedule-card' : ''}`}
                      >
                        <div className="schedule-header">
                          <div>
                            <h4>배달 스케줄 #{schedule.id} (주문 #{schedule.order_id || 'N/A'})</h4>
                            <p className="employee-name">
                              {schedule.employee_name || `직원 ${schedule.employee_id || 'N/A'}`}
                              {schedule.employee_phone && ` (${schedule.employee_phone})`}
                              {schedule.employee_id === user?.id && (
                                <span className="my-badge">내 배달</span>
                              )}
                            </p>
                          </div>
                          <span 
                            className="status-badge"
                            style={{ backgroundColor: getStatusColor(schedule.status || 'SCHEDULED') }}
                          >
                            {getStatusLabel(schedule.status || 'SCHEDULED')}
                          </span>
                        </div>
                        <div className="schedule-details">
                          <div className="detail-item">
                            <span className="detail-label">배달 주소:</span>
                            <span className="detail-value">{schedule.delivery_address || '주소 없음'}</span>
                          </div>
                          <div className="detail-item">
                            <span className="detail-label">출발:</span>
                            <span className="detail-value">{formatTime(schedule.departure_time || '')}</span>
                          </div>
                          <div className="detail-item">
                            <span className="detail-label">도착:</span>
                            <span className="detail-value">{formatTime(schedule.arrival_time || '')}</span>
                          </div>
                          <div className="detail-item">
                            <span className="detail-label">복귀:</span>
                            <span className="detail-value">{formatTime(schedule.return_time || '')}</span>
                          </div>
                          <div className="detail-item">
                            <span className="detail-label">소요 시간:</span>
                            <span className="detail-value">{schedule.one_way_minutes || 0}분 (편도)</span>
                          </div>
                        </div>
                      </div>
                    ))}
                  </div>
                )}
              </div>
            </div>
          </div>
        )}
      </div>
    </div>
  );
};

export default ScheduleCalendar;

