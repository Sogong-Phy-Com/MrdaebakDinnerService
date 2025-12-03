import React, { useState, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import axios from 'axios';
import TopLogo from '../components/TopLogo';

const API_URL = process.env.REACT_APP_API_URL || (window.location.protocol === 'https:' ? '/api' : 'http://localhost:5000/api');

interface Employee {
  id: number;
  name: string;
  email: string;
  employeeType?: string;
}

interface DayAssignment {
  date: string;
  cookingEmployees: number[];
  deliveryEmployees: number[];
}

interface OrderItem {
  id?: number;
  menu_item_id?: number;
  quantity?: number;
  name?: string;
  name_en?: string;
  price?: number;
}

interface Order {
  id: number;
  customer_name?: string;
  delivery_time: string;
  delivery_address: string;
  status: string;
  dinner_name?: string;
  cooking_employee_id?: number;
  delivery_employee_id?: number;
  admin_approval_status?: string;
  items?: OrderItem[];
}

const AdminScheduleManagement: React.FC = () => {
  const navigate = useNavigate();
  const [employees, setEmployees] = useState<Employee[]>([]);
  const [currentMonth, setCurrentMonth] = useState(new Date().getMonth());
  const [currentYear, setCurrentYear] = useState(new Date().getFullYear());
  const [selectedDate, setSelectedDate] = useState<string | null>(null);
  const [dayAssignments, setDayAssignments] = useState<{ [key: string]: DayAssignment }>({});
  const [loading, setLoading] = useState(false);
  const [loadingAssignments, setLoadingAssignments] = useState(false);
  const [error, setError] = useState('');
  const [calendarType, setCalendarType] = useState<'schedule' | 'orders'>('schedule');
  // eslint-disable-next-line @typescript-eslint/no-unused-vars
  const [orders, setOrders] = useState<Order[]>([]);
  const [selectedDateForOrders, setSelectedDateForOrders] = useState<string | null>(null);

  useEffect(() => {
    fetchEmployees();
    if (calendarType === 'schedule') {
      fetchDayAssignments();
    } else {
      fetchOrders();
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [currentMonth, currentYear, calendarType]);

  const getAuthHeaders = () => {
    const token = localStorage.getItem('token');
    if (!token) {
      throw new Error('Admin login required');
    }
    return {
      Authorization: `Bearer ${token}`
    };
  };

  const fetchEmployees = async () => {
    try {
      const headers = getAuthHeaders();
      const response = await axios.get(`${API_URL}/admin/employees`, { headers });
      setEmployees(response.data || []);
    } catch (err: any) {
      console.error('Failed to fetch employees:', err);
      // Fallback to users endpoint
      try {
        const headers = getAuthHeaders();
        const response = await axios.get(`${API_URL}/admin/users`, { headers });
        const employeeList = response.data.filter((u: any) => (u.role === 'employee' || u.role === 'admin') && u.approvalStatus === 'approved');
        setEmployees(employeeList);
      } catch (err2: any) {
        setError('직원 목록을 불러오는데 실패했습니다.');
      }
    }
  };

  const fetchOrders = async () => {
    try {
      const headers = getAuthHeaders();
      const response = await axios.get(`${API_URL}/employee/orders`, { headers });
      if (response.data && Array.isArray(response.data)) {
        // 현재 월의 주문만 필터링
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
        setOrders(approvedOnly);
      } else {
        setOrders([]);
      }
    } catch (err: any) {
      console.error('주문 목록 조회 실패:', err);
      setOrders([]);
    }
  };

  const getOrdersForDate = (dateKey: string): Order[] => {
    if (!dateKey) return [];
    return orders.filter(order => {
      if (!order.delivery_time) return false;
      try {
        // delivery_time이 "YYYY-MM-DDTHH:mm" 형식이면 그대로 사용, 아니면 파싱
        let orderDateStr: string;
        if (order.delivery_time.includes('T')) {
          // "YYYY-MM-DDTHH:mm" 형식인 경우 날짜 부분만 추출
          orderDateStr = order.delivery_time.split('T')[0];
        } else {
          // 다른 형식인 경우 Date 객체로 파싱 (로컬 날짜로 변환)
          const orderDate = new Date(order.delivery_time);
          if (isNaN(orderDate.getTime())) return false;
          // 로컬 날짜 문자열 생성 (UTC 변환 없이)
          const year = orderDate.getFullYear();
          const month = (orderDate.getMonth() + 1).toString().padStart(2, '0');
          const day = orderDate.getDate().toString().padStart(2, '0');
          orderDateStr = `${year}-${month}-${day}`;
        }
        return orderDateStr === dateKey;
      } catch {
        return false;
      }
    });
  };

  // eslint-disable-next-line @typescript-eslint/no-unused-vars
  const updateOrderStatus = async (orderId: number, newStatus: string) => {
    try {
      setLoading(true);
      const headers = getAuthHeaders();
      await axios.patch(`${API_URL}/admin/orders/${orderId}/status`, { status: newStatus }, { headers });
      await fetchOrders();
      alert('주문 상태가 변경되었습니다.');
      setSelectedDateForOrders(null);
      setSelectedDateForOrders(selectedDateForOrders); // Refresh modal
    } catch (err: any) {
      setError(err.response?.data?.error || err.message || '주문 상태 변경에 실패했습니다.');
    } finally {
      setLoading(false);
    }
  };

  const fetchDayAssignments = async () => {
    try {
      setLoadingAssignments(true);
      const headers = getAuthHeaders();
      const year = currentYear;
      const month = currentMonth;
      const lastDay = new Date(year, month + 1, 0);
      
      const assignments: { [key: string]: DayAssignment } = {};
      
      // 해당 월의 모든 날짜에 대해 할당 조회
      for (let day = 1; day <= lastDay.getDate(); day++) {
        // 로컬 날짜 문자열 생성 (UTC 변환 없이)
        const dateStr = `${year}-${(month + 1).toString().padStart(2, '0')}-${day.toString().padStart(2, '0')}`;
        
        try {
          const response = await axios.get(`${API_URL}/admin/schedule/assignments?date=${dateStr}`, { headers });
          if (response.data && response.data.cookingEmployees && response.data.deliveryEmployees) {
            assignments[dateStr] = {
              date: dateStr,
              cookingEmployees: response.data.cookingEmployees || [],
              deliveryEmployees: response.data.deliveryEmployees || []
            };
          }
        } catch (err: any) {
          // Ignore errors for individual dates
        }
      }
      
      setDayAssignments(assignments);
    } catch (err: any) {
      console.error('할당 조회 실패:', err);
    } finally {
      setLoadingAssignments(false);
    }
  };

  const getDaysInMonth = (year: number, month: number): number => {
    return new Date(year, month + 1, 0).getDate();
  };

  const getFirstDayOfMonth = (year: number, month: number): number => {
    return new Date(year, month, 1).getDay();
  };

  const isDateInPast = (year: number, month: number, day: number): boolean => {
    const date = new Date(year, month, day);
    const today = new Date();
    today.setHours(0, 0, 0, 0);
    date.setHours(0, 0, 0, 0);
    return date < today;
  };

  const getDateKey = (year: number, month: number, day: number): string => {
    return `${year}-${(month + 1).toString().padStart(2, '0')}-${day.toString().padStart(2, '0')}`;
  };

  const getAssignmentStatus = (dateKey: string): 'full' | 'partial' | 'empty' => {
    const assignment = dayAssignments[dateKey];
    if (!assignment) return 'empty';
    const totalAssigned = (assignment.cookingEmployees?.length || 0) + (assignment.deliveryEmployees?.length || 0);
    if (totalAssigned >= 10) return 'full';
    if (totalAssigned > 0) return 'partial';
    return 'empty';
  };

  const handleDateClick = (dateKey: string) => {
    setSelectedDate(dateKey);
  };

  const handleSaveAssignment = async () => {
    if (!selectedDate) return;
    
    const assignment = dayAssignments[selectedDate] || {
      date: selectedDate,
      cookingEmployees: [],
      deliveryEmployees: []
    };
    
    // Check minimum 5 employees for each type
    if (assignment.cookingEmployees.length < 5) {
      alert('작업 할당이 완료되지 않았습니다. 조리 담당 직원은 최소 5명이 필요합니다.');
      return;
    }
    
    if (assignment.deliveryEmployees.length < 5) {
      alert('작업 할당이 완료되지 않았습니다. 배달 담당 직원은 최소 5명이 필요합니다.');
      return;
    }
    
    // Check if any employee is assigned to both tasks
    const duplicateEmployees = assignment.cookingEmployees.filter(id => 
      assignment.deliveryEmployees.includes(id)
    );
    if (duplicateEmployees.length > 0) {
      alert('한 명의 직원이 하루에 두 가지 일을 할 수 없습니다.');
      return;
    }
    
    try {
      setLoading(true);
      setError('');
      const headers = getAuthHeaders();
      
      // Save assignment to backend
      const response = await axios.post(`${API_URL}/admin/schedule/assign`, {
        date: selectedDate,
        cookingEmployees: assignment.cookingEmployees,
        deliveryEmployees: assignment.deliveryEmployees
      }, { headers });
      
      // 응답 확인 - 성공적으로 저장되었는지 확인
      if (response.status === 200 || response.status === 201) {
        // 데이터베이스에 저장 완료 후 할당 정보 다시 불러오기 (즉시 반영)
        try {
          setLoadingAssignments(true);
          await fetchDayAssignments();
        } catch (fetchErr) {
          console.error('할당 정보 다시 불러오기 실패:', fetchErr);
          // 할당 정보를 다시 불러오는 데 실패해도 할당 자체는 성공했을 수 있으므로 계속 진행
        } finally {
          setLoadingAssignments(false);
        }
        
        // 할당 정보가 제대로 저장되었는지 확인 (최대 3번 재시도)
        let retryCount = 0;
        let assignmentVerified = false;
        while (retryCount < 3 && !assignmentVerified) {
          try {
            const updatedAssignments = await axios.get(`${API_URL}/admin/schedule/assignments?date=${selectedDate}`, { headers });
            if (updatedAssignments.data) {
              const hasCooking = updatedAssignments.data.cookingEmployees && updatedAssignments.data.cookingEmployees.length > 0;
              const hasDelivery = updatedAssignments.data.deliveryEmployees && updatedAssignments.data.deliveryEmployees.length > 0;
              // 할당이 하나라도 있으면 성공으로 간주
              if (hasCooking || hasDelivery) {
                assignmentVerified = true;
                alert('직원 할당이 저장되었습니다.');
                setSelectedDate(null);
                break;
              }
            }
          } catch (err) {
            console.log(`할당 확인 재시도 ${retryCount + 1}/3:`, err);
          }
          if (!assignmentVerified && retryCount < 2) {
            await new Promise(resolve => setTimeout(resolve, 1000)); // 1초 대기
          }
          retryCount++;
        }
        
        // 검증 실패해도 할당 자체는 성공했을 수 있으므로 경고만 표시 (알람 제거)
        if (!assignmentVerified) {
          console.warn('할당 정보 검증 실패, 하지만 할당은 저장되었을 수 있습니다.');
          // 알람 제거 - 할당은 성공했을 수 있으므로 조용히 처리
          setSelectedDate(null);
        }
      } else {
        // 할당 저장 실패 시에도 알람 제거 (에러는 setError로만 표시)
        setError('할당 저장에 실패했습니다.');
      }
    } catch (err: any) {
      console.error('할당 저장 실패:', err);
      const errorMessage = err.response?.data?.error || err.message || '할당 저장에 실패했습니다.';
      setError(errorMessage);
      // 알람 제거 - 에러는 화면에만 표시
    } finally {
      setLoading(false);
    }
  };

  const updateDayAssignment = (dateKey: string, type: 'cooking' | 'delivery', employeeId: number, add: boolean) => {
    const assignment = dayAssignments[dateKey] || {
      date: dateKey,
      cookingEmployees: [],
      deliveryEmployees: []
    };

    const targetArray = type === 'cooking' ? assignment.cookingEmployees : assignment.deliveryEmployees;
    
    if (add) {
      if (!targetArray.includes(employeeId) && targetArray.length < 5) {
        targetArray.push(employeeId);
      }
    } else {
      const index = targetArray.indexOf(employeeId);
      if (index > -1) {
        targetArray.splice(index, 1);
      }
    }

    setDayAssignments({
      ...dayAssignments,
      [dateKey]: assignment
    });
  };

  const daysInMonth = getDaysInMonth(currentYear, currentMonth);
  const firstDay = getFirstDayOfMonth(currentYear, currentMonth);
  const monthNames = ['1월', '2월', '3월', '4월', '5월', '6월', '7월', '8월', '9월', '10월', '11월', '12월'];
  const dayNames = ['일', '월', '화', '수', '목', '금', '토'];

  const selectedAssignment = selectedDate ? dayAssignments[selectedDate] : null;

  return (
    <div className="admin-dashboard">
      <TopLogo showBackButton={false} />
      <div className="container">
        <div style={{ marginBottom: '20px' }}>
          <button onClick={() => navigate('/')} className="btn btn-secondary">
            ← 홈으로
          </button>
        </div>

        <h2>스케줄 관리 / 주문 관리</h2>
        {error && <div className="error">{error}</div>}

        {/* Tab Menu for Calendar Views - Only Schedule and Order Calendar */}
        <div style={{ 
          display: 'flex', 
          gap: '10px', 
          marginBottom: '20px',
          borderBottom: '2px solid #FFD700',
          paddingBottom: '10px'
        }}>
          <button
            className={`btn ${calendarType === 'schedule' ? 'btn-primary' : 'btn-secondary'}`}
            onClick={() => setCalendarType('schedule')}
            style={{
              borderBottom: calendarType === 'schedule' ? '3px solid #FFD700' : 'none',
              borderRadius: '4px 4px 0 0'
            }}
          >
            📅 스케줄 캘린더 (작업 할당)
          </button>
          <button
            className={`btn ${calendarType === 'orders' ? 'btn-primary' : 'btn-secondary'}`}
            onClick={() => setCalendarType('orders')}
            style={{
              borderBottom: calendarType === 'orders' ? '3px solid #FFD700' : 'none',
              borderRadius: '4px 4px 0 0'
            }}
          >
            📋 주문 캘린더
          </button>
        </div>

        {loadingAssignments && (
          <div style={{ 
            textAlign: 'center', 
            padding: '20px', 
            background: '#1a1a1a', 
            borderRadius: '8px',
            marginBottom: '20px',
            border: '1px solid #d4af37'
          }}>
            <div style={{ color: '#d4af37', fontSize: '16px' }}>로딩 중...</div>
            <div style={{ color: '#fff', fontSize: '14px', marginTop: '5px' }}>스케줄 정보를 불러오는 중입니다.</div>
          </div>
        )}

        <div style={{ marginBottom: '20px', display: 'flex', gap: '10px', alignItems: 'center' }}>
          <button
            onClick={() => {
              if (currentMonth === 0) {
                setCurrentMonth(11);
                setCurrentYear(currentYear - 1);
              } else {
                setCurrentMonth(currentMonth - 1);
              }
            }}
            className="btn btn-secondary"
          >
            이전 달
          </button>
          <h3 style={{ margin: 0, minWidth: '150px', textAlign: 'center' }}>
            {currentYear}년 {monthNames[currentMonth]}
          </h3>
          <button
            onClick={() => {
              if (currentMonth === 11) {
                setCurrentMonth(0);
                setCurrentYear(currentYear + 1);
              } else {
                setCurrentMonth(currentMonth + 1);
              }
            }}
            className="btn btn-secondary"
          >
            다음 달
          </button>
        </div>

        <div style={{ 
          display: 'grid', 
          gridTemplateColumns: 'repeat(7, 1fr)', 
          gap: '5px',
          marginBottom: '30px'
        }}>
          {dayNames.map(day => (
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
          {Array.from({ length: firstDay }).map((_, i) => (
            <div key={`empty-${i}`} style={{ padding: '20px' }} />
          ))}
          {Array.from({ length: daysInMonth }).map((_, i) => {
            const day = i + 1;
            const dateKey = getDateKey(currentYear, currentMonth, day);
            const isPast = isDateInPast(currentYear, currentMonth, day);
            const status = calendarType === 'schedule' ? getAssignmentStatus(dateKey) : null;
            // 로컬 날짜로 비교 (UTC 변환 없이)
            const today = new Date();
            const todayKey = `${today.getFullYear()}-${(today.getMonth() + 1).toString().padStart(2, '0')}-${today.getDate().toString().padStart(2, '0')}`;
            const isToday = dateKey === todayKey;
            const dayOrders = calendarType === 'orders' ? getOrdersForDate(dateKey) : [];

            return (
              <div
                key={day}
                onClick={() => {
                  if (calendarType === 'schedule') {
                    !isPast && handleDateClick(dateKey);
                  } else {
                    !isPast && setSelectedDateForOrders(dateKey);
                  }
                }}
                style={{
                  padding: '15px',
                  textAlign: 'center',
                  cursor: isPast ? 'not-allowed' : 'pointer',
                  background: isPast ? '#ccc' : 
                    calendarType === 'schedule' 
                      ? (status === 'full' ? '#4CAF50' : status === 'partial' ? '#ff4444' : '#f5f5f5')
                      : (dayOrders.length > 0 ? '#2196F3' : '#f5f5f5'),
                  color: isPast ? '#666' : 
                    calendarType === 'schedule'
                      ? (status === 'empty' ? '#000' : '#fff')
                      : (dayOrders.length > 0 ? '#fff' : '#000'),
                  border: isToday ? '2px solid #FFD700' : '1px solid #ddd',
                  borderRadius: '4px',
                  opacity: isPast ? 0.5 : 1
                }}
              >
                <div style={{ fontWeight: 'bold' }}>{day}</div>
                {!isPast && calendarType === 'schedule' && (
                  <div style={{ fontSize: '10px', marginTop: '5px' }}>
                    {status === 'full' ? '10명 할당' : status === 'partial' ? '부분 할당' : '미할당'}
                  </div>
                )}
                {!isPast && calendarType === 'orders' && dayOrders.length > 0 && (
                  <div style={{ fontSize: '10px', marginTop: '5px' }}>
                    {dayOrders.length}개 주문
                  </div>
                )}
              </div>
            );
          })}
        </div>

        {selectedDate && (
          <div style={{
            position: 'fixed',
            top: 0,
            left: 0,
            right: 0,
            bottom: 0,
            background: 'rgba(0,0,0,0.8)',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            zIndex: 1000
          }}>
            <div style={{
              background: '#1a1a1a',
              color: '#fff',
              padding: '30px',
              borderRadius: '12px',
              maxWidth: '600px',
              width: '90%',
              maxHeight: '80vh',
              overflow: 'auto',
              border: '2px solid #d4af37'
            }}>
              <h3>{selectedDate} 직원 할당</h3>
              {loading && (
                <div style={{ 
                  position: 'absolute', 
                  top: 0, 
                  left: 0, 
                  right: 0, 
                  bottom: 0, 
                  background: 'rgba(0,0,0,0.8)', 
                  display: 'flex', 
                  alignItems: 'center', 
                  justifyContent: 'center',
                  zIndex: 1001,
                  borderRadius: '12px'
                }}>
                  <div style={{ 
                    background: '#1a1a1a', 
                    padding: '20px', 
                    borderRadius: '8px',
                    border: '2px solid #d4af37'
                  }}>
                    <div style={{ color: '#d4af37', fontSize: '18px', marginBottom: '10px' }}>할당 저장 중...</div>
                    <div style={{ color: '#fff' }}>데이터베이스에 저장하고 있습니다.</div>
                  </div>
                </div>
              )}
              <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '20px', marginTop: '20px' }}>
                <div>
                  <h4>조리 담당 (5명 선택)</h4>
                  <div style={{ 
                    border: '1px solid #d4af37', 
                    padding: '10px', 
                    borderRadius: '4px',
                    minHeight: '200px',
                    maxHeight: '300px',
                    overflow: 'auto',
                    background: '#2a2a2a'
                  }}>
                    {employees.map(emp => {
                      const isAssigned = selectedAssignment?.cookingEmployees?.includes(emp.id) || false;
                      const isAssignedToDelivery = selectedAssignment?.deliveryEmployees?.includes(emp.id) || false;
                      const isDisabled = isAssignedToDelivery || (!isAssigned && (selectedAssignment?.cookingEmployees?.length || 0) >= 5);
                      return (
                        <div key={emp.id} style={{ 
                          display: 'flex', 
                          justifyContent: 'space-between', 
                          alignItems: 'center',
                          padding: '8px',
                          marginBottom: '5px',
                          background: isAssigned ? '#4CAF50' : isAssignedToDelivery ? '#666' : '#3a3a3a',
                          borderRadius: '4px',
                          opacity: isDisabled && !isAssigned ? 0.5 : 1
                        }}>
                          <span>{emp.name}</span>
                          <button
                            onClick={() => updateDayAssignment(selectedDate, 'cooking', emp.id, !isAssigned)}
                            className={`btn ${isAssigned ? 'btn-danger' : 'btn-success'}`}
                            style={{ padding: '5px 10px', fontSize: '12px' }}
                            disabled={isDisabled}
                          >
                            {isAssigned ? '제거' : '추가'}
                          </button>
                        </div>
                      );
                    })}
                  </div>
                </div>
                <div>
                  <h4>배달 담당 (5명 선택)</h4>
                  <div style={{ 
                    border: '1px solid #d4af37', 
                    padding: '10px', 
                    borderRadius: '4px',
                    minHeight: '200px',
                    maxHeight: '300px',
                    overflow: 'auto',
                    background: '#2a2a2a'
                  }}>
                    {employees.map(emp => {
                      const isAssigned = selectedAssignment?.deliveryEmployees?.includes(emp.id) || false;
                      const isAssignedToCooking = selectedAssignment?.cookingEmployees?.includes(emp.id) || false;
                      const isDisabled = isAssignedToCooking || (!isAssigned && (selectedAssignment?.deliveryEmployees?.length || 0) >= 5);
                      return (
                        <div key={emp.id} style={{ 
                          display: 'flex', 
                          justifyContent: 'space-between', 
                          alignItems: 'center',
                          padding: '8px',
                          marginBottom: '5px',
                          background: isAssigned ? '#4CAF50' : isAssignedToCooking ? '#666' : '#3a3a3a',
                          borderRadius: '4px',
                          opacity: isDisabled && !isAssigned ? 0.5 : 1
                        }}>
                          <span>{emp.name}</span>
                          <button
                            onClick={() => updateDayAssignment(selectedDate, 'delivery', emp.id, !isAssigned)}
                            className={`btn ${isAssigned ? 'btn-danger' : 'btn-success'}`}
                            style={{ padding: '5px 10px', fontSize: '12px' }}
                            disabled={isDisabled}
                          >
                            {isAssigned ? '제거' : '추가'}
                          </button>
                        </div>
                      );
                    })}
                  </div>
                </div>
              </div>
              <div style={{ display: 'flex', gap: '10px', marginTop: '20px' }}>
                <button
                  onClick={handleSaveAssignment}
                  className="btn btn-primary"
                  disabled={loading}
                >
                  저장
                </button>
                <button
                  onClick={() => setSelectedDate(null)}
                  className="btn btn-secondary"
                >
                  취소
                </button>
              </div>
            </div>
          </div>
        )}

        {/* Order Calendar Modal */}
        {selectedDateForOrders && (
          <div style={{
            position: 'fixed',
            top: 0,
            left: 0,
            right: 0,
            bottom: 0,
            background: 'rgba(0,0,0,0.8)',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            zIndex: 1000
          }}>
            <div style={{
              background: '#1a1a1a',
              color: '#fff',
              padding: '30px',
              borderRadius: '12px',
              maxWidth: '800px',
              width: '90%',
              maxHeight: '80vh',
              overflow: 'auto',
              border: '2px solid #d4af37'
            }}>
              <h3>{selectedDateForOrders} 주문 목록</h3>
              {getOrdersForDate(selectedDateForOrders).length === 0 ? (
                <p>이 날짜에 주문이 없습니다.</p>
              ) : (
                <div style={{ marginTop: '20px' }}>
                  {getOrdersForDate(selectedDateForOrders).map(order => (
                    <div key={order.id} style={{
                      background: '#2a2a2a',
                      padding: '15px',
                      borderRadius: '8px',
                      marginBottom: '15px',
                      border: '1px solid #d4af37'
                    }}>
                      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'start', marginBottom: '10px' }}>
                        <div>
                          <h4>주문 #{order.id}</h4>
                          <p>{order.customer_name && `고객: ${order.customer_name}`}</p>
                          <p>{order.dinner_name && `디너: ${order.dinner_name}`}</p>
                          <p>주소: {order.delivery_address}</p>
                          <p>주문 항목: {
                            order.items && order.items.length > 0
                              ? order.items.map((item: any) => 
                                  `${item.name || item.name_en || '항목'} x${item.quantity || 0}`
                                ).join(', ')
                              : '항목 없음'
                          }</p>
                          <p>상태: {
                            order.status === 'delivered' ? '배달 완료' : 
                            order.status === 'cancelled' ? '주문 취소' :
                            order.status === 'cooking' ? '조리 중' :
                            order.status === 'out_for_delivery' ? '배달 중' :
                            order.status === 'ready' ? '준비 완료' : '주문 접수'
                          }</p>
                        </div>
                        {/* 관리자는 주문 상태 변경 불가 - 할당받은 직원만 변경 가능 */}
                        <div style={{ display: 'flex', flexDirection: 'column', gap: '5px' }}>
                          <p style={{ fontSize: '12px', color: '#999', fontStyle: 'italic' }}>
                            관리자는 주문 상태를 변경할 수 없습니다.
                            <br />
                            할당받은 직원만 상태를 변경할 수 있습니다.
                          </p>
                        </div>
                      </div>
                    </div>
                  ))}
                </div>
              )}
              <div style={{ display: 'flex', gap: '10px', marginTop: '20px' }}>
                <button
                  onClick={() => setSelectedDateForOrders(null)}
                  className="btn btn-secondary"
                >
                  닫기
                </button>
              </div>
            </div>
          </div>
        )}
      </div>
    </div>
  );
};

export default AdminScheduleManagement;
