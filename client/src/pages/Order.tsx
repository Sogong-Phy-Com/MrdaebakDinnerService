import React, { useState, useEffect, useRef } from 'react';
import { useNavigate, useSearchParams, useLocation } from 'react-router-dom';
import axios from 'axios';
import { useAuth } from '../contexts/AuthContext';
import TopLogo from '../components/TopLogo';
import './Order.css';

const API_URL = process.env.REACT_APP_API_URL || (window.location.protocol === 'https:' ? '/api' : 'http://localhost:5000/api');

interface Dinner {
  id: number;
  name: string;
  name_en: string;
  base_price: number;
  description: string;
  menu_items: MenuItem[];
}

interface MenuItem {
  id: number;
  name: string;
  name_en: string;
  price: number;
  category: string;
  quantity?: number;
}

interface ServingStyle {
  name: string;
  name_ko: string;
  price_multiplier: number;
  description: string;
}

interface PreviousOrder {
  id: number;
  dinner_name: string;
  dinner_type_id: number;
  serving_style: string;
  delivery_time: string;
  delivery_address: string;
  total_price: number;
  status: string;
  items: { menu_item_id: number; quantity: number; name?: string }[];
}

const Order: React.FC = () => {
  const { user } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();
  const [searchParams] = useSearchParams();
  const modifyOrderId = searchParams.get('modify');
  const editRequestId = searchParams.get('editRequest');
  const [isModifying, setIsModifying] = useState(false);
  const [dinners, setDinners] = useState<Dinner[]>([]);
  const [menuItems, setMenuItems] = useState<MenuItem[]>([]);
  const [servingStyles, setServingStyles] = useState<ServingStyle[]>([]);
  const [selectedDinner, setSelectedDinner] = useState<number | null>(null);
  const [selectedStyle, setSelectedStyle] = useState<string>('simple');
  const [selectedYear, setSelectedYear] = useState<number>(new Date().getFullYear());
  const [selectedMonth, setSelectedMonth] = useState<number>(new Date().getMonth() + 1);
  const [selectedDay, setSelectedDay] = useState<number>(new Date().getDate());
  const [selectedTime, setSelectedTime] = useState<string>('');
  const [deliveryTime, setDeliveryTime] = useState('');
  const [useMyAddress, setUseMyAddress] = useState<boolean>(true);
  const [deliveryAddress, setDeliveryAddress] = useState(user?.address || '');
  const [customAddress, setCustomAddress] = useState('');
  const [orderItems, setOrderItems] = useState<{ menu_item_id: number; quantity: number }[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [isSubmitting, setIsSubmitting] = useState(false);
  const orderSubmissionRef = useRef<string | null>(null);
  const [inventoryAvailable, setInventoryAvailable] = useState(true);
  const [availableTimeSlots, setAvailableTimeSlots] = useState<string[]>([]);
  const [showOrderConfirmation, setShowOrderConfirmation] = useState(false);
  const [orderPassword, setOrderPassword] = useState('');
  const [userCardInfo, setUserCardInfo] = useState<any>(null);
  const [pendingOrderData, setPendingOrderData] = useState<any>(null);
  const [previousOrders, setPreviousOrders] = useState<PreviousOrder[]>([]);
  const [orderAlert, setOrderAlert] = useState('');
  const [agreeCardUse, setAgreeCardUse] = useState(false);
  const [agreePolicy, setAgreePolicy] = useState(false);
  const [prefillingOrder, setPrefillingOrder] = useState(false);
  const [changeReason, setChangeReason] = useState('');

  useEffect(() => {
    fetchDinners();
    fetchMenuItems();
    fetchServingStyles();
    if (modifyOrderId) {
      if (editRequestId) {
        fetchChangeRequestForEditing(Number(modifyOrderId), Number(editRequestId));
      } else {
        fetchOrderForModification(Number(modifyOrderId));
      }
    }
    fetchUserCardInfo();
    fetchPreviousOrders();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [modifyOrderId, editRequestId]);

useEffect(() => {
  if (!isModifying) {
    setChangeReason('');
  }
}, [isModifying]);

  const fetchUserCardInfo = async () => {
    try {
      const token = localStorage.getItem('token');
      if (!token) return;
      const response = await axios.get(`${API_URL}/auth/me`, {
        headers: { 'Authorization': `Bearer ${token}` }
      });
      setUserCardInfo(response.data);
    } catch (err) {
      console.error('카드 정보 조회 실패:', err);
    }
  };

  const fetchPreviousOrders = async () => {
    try {
      const token = localStorage.getItem('token');
      if (!token) return;
      const response = await axios.get(`${API_URL}/orders`, {
        headers: { 'Authorization': `Bearer ${token}` }
      });
      if (Array.isArray(response.data)) {
        // 모든 주문을 가져와서 배달 완료 주문을 정확히 카운트
        setPreviousOrders(response.data);
        console.log('전체 주문 수:', response.data.length);
        console.log('주문 상태 분포:', response.data.reduce((acc: any, order: any) => {
          const status = order.status?.toLowerCase() || 'unknown';
          acc[status] = (acc[status] || 0) + 1;
          return acc;
        }, {}));
      }
    } catch (err) {
      console.error('이전 주문 조회 실패:', err);
    }
  };

  const applyReorderData = (order: PreviousOrder) => {
    setPrefillingOrder(true);
    setIsModifying(false);
    setSelectedDinner(order.dinner_type_id);
    setSelectedStyle(order.serving_style);
    setDeliveryAddress(order.delivery_address);
    setUseMyAddress(order.delivery_address === user?.address);
    if (order.items && order.items.length > 0) {
      setOrderItems(order.items.map(item => ({
        menu_item_id: item.menu_item_id,
        quantity: item.quantity
      })));
    }
    setOrderAlert(`주문 #${order.id} 정보를 불러왔습니다. 새로운 날짜와 시간을 선택한 뒤 주문을 확정해주세요.`);
  };

  useEffect(() => {
    if (!selectedDinner) {
      return;
    }
    if (prefillingOrder) {
      setPrefillingOrder(false);
      return;
    }
    const dinner = dinners.find(d => d.id === selectedDinner);
    if (dinner) {
      // 디너의 기본 포함 항목들을 기본 수량으로 설정
      const items = dinner.menu_items.map(item => ({
        menu_item_id: item.id,
        quantity: item.quantity || 1 // DinnerMenuItem의 quantity 사용 (기본값 1)
      }));
      setOrderItems(items);
    }
  }, [selectedDinner, dinners, prefillingOrder]);

  useEffect(() => {
    const state: any = location.state;
    if (state && state.reorderOrder) {
      applyReorderData(state.reorderOrder);
      navigate(location.pathname + location.search, { replace: true, state: {} });
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [location]);

  // Generate years (current year to next year)
  const years = Array.from({ length: 2 }, (_, i) => new Date().getFullYear() + i);
  
  // Generate months (1-12)
  const months = Array.from({ length: 12 }, (_, i) => i + 1);
  
  // Generate days based on selected year and month
  const getDaysInMonth = (year: number, month: number): number => {
    return new Date(year, month, 0).getDate();
  };
  
  const days = Array.from({ length: getDaysInMonth(selectedYear, selectedMonth) }, (_, i) => i + 1);
  
  // Get selected date string
  const selectedDate = `${selectedYear}-${selectedMonth.toString().padStart(2, '0')}-${selectedDay.toString().padStart(2, '0')}`;
  
  // Validate selected date (must be today or future)
  const today = new Date();
  today.setHours(0, 0, 0, 0);
  const selectedDateObj = new Date(selectedYear, selectedMonth - 1, selectedDay);
  selectedDateObj.setHours(0, 0, 0, 0);
  const isDateValid = selectedDateObj >= today;

  // Generate time slots (5 PM - 9 PM, 30 min intervals) with validation
  useEffect(() => {
    if (isDateValid) {
      const slots: string[] = [];
      const now = new Date();
      const isToday = selectedDateObj.getTime() === today.getTime();
      
      for (let hour = 17; hour <= 21; hour++) {
        for (let minute = 0; minute < 60; minute += 30) {
          if (hour === 21 && minute > 0) break; // Stop at 9:00 PM
          const timeStr = `${hour.toString().padStart(2, '0')}:${minute.toString().padStart(2, '0')}`;
          
          // 시간 유효성 검사: 지난 시간이거나 3시간 미만 남은 경우 제외
          if (isToday) {
            const deliveryDateTime = new Date(selectedYear, selectedMonth - 1, selectedDay, hour, minute);
            const hoursUntilDelivery = (deliveryDateTime.getTime() - now.getTime()) / (1000 * 60 * 60);
            
            if (deliveryDateTime <= now || hoursUntilDelivery < 3) {
              continue; // 이 시간대는 제외
            }
          }
          
          slots.push(timeStr);
        }
      }
      setAvailableTimeSlots(slots);
    } else {
      setAvailableTimeSlots([]);
      setSelectedTime('');
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [isDateValid, selectedYear, selectedMonth, selectedDay]);

  // Update deliveryTime when date and time are selected
  useEffect(() => {
    if (isDateValid && selectedTime) {
      // 로컬 시간대를 사용하여 날짜/시간 문자열 생성 (UTC 변환 없이)
      const dateTime = `${selectedDate}T${selectedTime}:00`;
      setDeliveryTime(dateTime);
    } else {
      setDeliveryTime('');
    }
  }, [selectedDate, selectedTime, isDateValid]);

  // Update delivery address based on selection
  useEffect(() => {
    if (useMyAddress) {
      setDeliveryAddress(user?.address || '');
    } else {
      setDeliveryAddress(customAddress);
    }
  }, [useMyAddress, user?.address, customAddress]);

  // Check inventory when delivery time is set
  useEffect(() => {
    const checkInventory = async () => {
      if (!selectedDinner || !deliveryTime || orderItems.length === 0) {
        setInventoryAvailable(true);
        return;
      }

      try {
        // 3일 이하 예약은 현재 보유량 초과 불가, 3일 이상은 초과 가능
        const deliveryDate = new Date(deliveryTime);
        const today = new Date();
        today.setHours(0, 0, 0, 0);
        deliveryDate.setHours(0, 0, 0, 0);
        const daysUntilDelivery = Math.ceil((deliveryDate.getTime() - today.getTime()) / (1000 * 60 * 60 * 24));
        const isWithin3Days = daysUntilDelivery <= 3;
        
        const menuItemIds = orderItems.map(item => item.menu_item_id).join(',');
        const response = await axios.get(`${API_URL}/inventory/check-availability`, {
          params: {
            menuItemIds: menuItemIds,
            deliveryTime: deliveryTime
          }
        });

        // 3일 이하 예약인 경우 현재 보유량 초과 불가 검증
        if (isWithin3Days) {
          // 재고 정보를 가져와서 현재 보유량 확인
          try {
            const inventoryResponse = await axios.get(`${API_URL}/inventory`);
            const allAvailable = orderItems.every(item => {
              const inventoryItem = inventoryResponse.data.find((inv: any) => inv.menu_item_id === item.menu_item_id);
              if (!inventoryItem) return false;
              
              const currentStock = inventoryItem.capacity_per_window || 0;
              const weeklyReserved = inventoryItem.weekly_reserved || 0;
              const availableStock = currentStock - weeklyReserved;
              
              // 3일 이하 예약은 현재 보유량을 초과할 수 없음
              return availableStock >= item.quantity && response.data[item.menu_item_id] === true;
            });
            setInventoryAvailable(allAvailable);
          } catch (invErr) {
            console.error('Inventory fetch failed:', invErr);
            setInventoryAvailable(false);
          }
        } else {
          // 3일 이상 예약은 초과 가능하므로 기본 검증만 수행
          const allAvailable = orderItems.every(item => response.data[item.menu_item_id] === true);
          setInventoryAvailable(allAvailable);
        }
      } catch (err) {
        console.error('Inventory check failed:', err);
        setInventoryAvailable(false);
      }
    };

    checkInventory();
  }, [selectedDinner, deliveryTime, orderItems]);

  const fetchDinners = async () => {
    try {
      const token = localStorage.getItem('token');
      const response = await axios.get(`${API_URL}/menu/dinners`, {
        headers: token ? { 'Authorization': `Bearer ${token}` } : {}
      });
      setDinners(response.data);
    } catch (err) {
      console.error('디너 목록 조회 실패:', err);
    }
  };

  const fetchMenuItems = async () => {
    try {
      const token = localStorage.getItem('token');
      const response = await axios.get(`${API_URL}/menu/items`, {
        headers: token ? { 'Authorization': `Bearer ${token}` } : {}
      });
      setMenuItems(response.data);
    } catch (err) {
      console.error('메뉴 항목 조회 실패:', err);
    }
  };

  const fetchServingStyles = async () => {
    try {
      const token = localStorage.getItem('token');
      const response = await axios.get(`${API_URL}/menu/serving-styles`, {
        headers: token ? { 'Authorization': `Bearer ${token}` } : {}
      });
      setServingStyles(response.data);
    } catch (err) {
      console.error('서빙 스타일 조회 실패:', err);
    }
  };

  const fetchOrderForModification = async (orderId: number) => {
    try {
      setLoading(true);
      const token = localStorage.getItem('token');
      if (!token) {
        setError('로그인이 필요합니다.');
        return;
      }

      const response = await axios.get(`${API_URL}/orders`, {
        headers: { 'Authorization': `Bearer ${token}` }
      });

      const order = response.data.find((o: any) => o.id === orderId);
      if (!order) {
        setError('주문을 찾을 수 없습니다.');
        return;
      }

      setIsModifying(true);
      setSelectedDinner(order.dinner_type_id);
      setSelectedStyle(order.serving_style);
      
      // Parse delivery time
      const deliveryDateTime = new Date(order.delivery_time);
      setSelectedYear(deliveryDateTime.getFullYear());
      setSelectedMonth(deliveryDateTime.getMonth() + 1);
      setSelectedDay(deliveryDateTime.getDate());
      setSelectedTime(`${deliveryDateTime.getHours().toString().padStart(2, '0')}:${deliveryDateTime.getMinutes().toString().padStart(2, '0')}`);
      
      // Set address
      if (user && order.delivery_address === user.address) {
        setUseMyAddress(true);
        setDeliveryAddress(user.address);
      } else {
        setUseMyAddress(false);
        setCustomAddress(order.delivery_address);
        setDeliveryAddress(order.delivery_address);
      }

      // Set order items
      if (order.items && Array.isArray(order.items)) {
        setOrderItems(order.items.map((item: any) => ({
          menu_item_id: item.menu_item_id || item.id,
          quantity: item.quantity || 1
        })));
      }
      setChangeReason('');
    } catch (err: any) {
      console.error('주문 조회 실패:', err);
      setError('주문 정보를 불러오는데 실패했습니다.');
    } finally {
      setLoading(false);
    }
  };

  const fetchChangeRequestForEditing = async (orderId: number, changeRequestId: number) => {
    try {
      setLoading(true);
      const token = localStorage.getItem('token');
      if (!token) {
        setError('로그인이 필요합니다.');
        return;
      }

      // 주문 정보 먼저 가져오기
      const orderResponse = await axios.get(`${API_URL}/orders`, {
        headers: { 'Authorization': `Bearer ${token}` }
      });

      const order = orderResponse.data.find((o: any) => o.id === orderId);
      if (!order) {
        setError('주문을 찾을 수 없습니다.');
        return;
      }

      // 변경 요청 정보 가져오기
      const changeRequestResponse = await axios.get(`${API_URL}/change-requests/${changeRequestId}`, {
        headers: { 'Authorization': `Bearer ${token}` }
      });

      const changeRequest = changeRequestResponse.data;
      if (!changeRequest) {
        setError('변경 요청을 찾을 수 없습니다.');
        return;
      }

      // PENDING 상태인지 확인
      if (changeRequest.status !== 'REQUESTED' && changeRequest.status !== 'PAYMENT_FAILED' && changeRequest.status !== 'REFUND_FAILED') {
        setError('이미 처리된 변경 요청은 수정할 수 없습니다.');
        return;
      }

      setIsModifying(true);
      
      // 변경 요청의 새 값으로 폼 채우기
      setSelectedDinner(changeRequest.new_dinner_type_id);
      setSelectedStyle(changeRequest.new_serving_style);
      
      // Parse delivery time
      const deliveryDateTime = new Date(changeRequest.new_delivery_time);
      setSelectedYear(deliveryDateTime.getFullYear());
      setSelectedMonth(deliveryDateTime.getMonth() + 1);
      setSelectedDay(deliveryDateTime.getDate());
      setSelectedTime(`${deliveryDateTime.getHours().toString().padStart(2, '0')}:${deliveryDateTime.getMinutes().toString().padStart(2, '0')}`);
      
      // Set address
      if (user && changeRequest.new_delivery_address === user.address) {
        setUseMyAddress(true);
        setDeliveryAddress(user.address);
      } else {
        setUseMyAddress(false);
        setCustomAddress(changeRequest.new_delivery_address);
        setDeliveryAddress(changeRequest.new_delivery_address);
      }

      // Set order items from change request
      if (changeRequest.items && Array.isArray(changeRequest.items)) {
        setOrderItems(changeRequest.items.map((item: any) => ({
          menu_item_id: item.menu_item_id,
          quantity: item.quantity || 1
        })));
      }
      
      // 변경 사유 설정
      setChangeReason(changeRequest.reason || '');
    } catch (err: any) {
      console.error('변경 요청 조회 실패:', err);
      setError('변경 요청 정보를 불러오는데 실패했습니다.');
    } finally {
      setLoading(false);
    }
  };

  const updateItemQuantity = (menuItemId: number, delta: number) => {
    setOrderItems(prev => {
      const existing = prev.find(item => item.menu_item_id === menuItemId);
      
      if (existing) {
        const newQuantity = existing.quantity + delta;
        
        // 0 이하가 되면 제거 (하지만 가격은 줄어들지 않음 - calculateTotal에서 처리)
        if (newQuantity <= 0) {
          return prev.filter(item => item.menu_item_id !== menuItemId);
        }
        
        return prev.map(item =>
          item.menu_item_id === menuItemId
            ? { ...item, quantity: newQuantity }
            : item
        );
      } else if (delta > 0) {
        return [...prev, { menu_item_id: menuItemId, quantity: 1 }];
      }
      return prev;
    });
  };

  const calculateTotal = () => {
    if (!selectedDinner) return 0;
    
    const dinner = dinners.find(d => d.id === selectedDinner);
    if (!dinner) return 0;

    const style = servingStyles.find(s => s.name === selectedStyle);
    const styleMultiplier = style?.price_multiplier || 1;

    // 기본 가격 (이미 기본 포함 항목들의 가격이 포함됨) - 항상 유지됨
    const basePrice = dinner.base_price * styleMultiplier;
    
    // 기본 포함 항목들의 기본 수량과 가격 계산
    const defaultItemsPrice = dinner.menu_items.reduce((sum, defaultItem) => {
      const menuItem = menuItems.find(m => m.id === defaultItem.id);
      const defaultQuantity = defaultItem.quantity || 1;
      return sum + (menuItem?.price || 0) * defaultQuantity;
    }, 0);
    
    // 현재 주문 항목들의 총 가격
    const currentItemsPrice = orderItems.reduce((sum, item) => {
      const menuItem = menuItems.find(m => m.id === item.menu_item_id);
      return sum + (menuItem?.price || 0) * item.quantity;
    }, 0);
    
    // 추가 수량만 계산 (현재 가격 - 기본 포함 가격)
    // 음수가 되더라도 기본 가격은 줄어들지 않음
    const additionalItemsPrice = Math.max(0, currentItemsPrice - defaultItemsPrice);
    
    // 기본 가격 + 추가 항목 가격 (기본 가격은 항상 유지)
    let subtotal = basePrice + additionalItemsPrice;
    
    // 할인 적용 여부 확인
    if (user) {
      const allConsentsGiven = Boolean(user.consentName) && Boolean(user.consentAddress) && Boolean(user.consentPhone);
      const deliveredOrders = previousOrders.filter(order => {
        const status = order.status?.toLowerCase() || '';
        const isDelivered = status === 'delivered';
        return isDelivered;
      }).length;
      const loyaltyEligible = Boolean(user.loyaltyConsent) && allConsentsGiven && deliveredOrders >= 4;
      
      // 디버깅: previousOrders의 status 값 확인
      console.log('previousOrders 전체:', previousOrders.length);
      console.log('previousOrders status 상세:', previousOrders.map(o => ({ 
        id: o.id, 
        status: o.status, 
        statusLower: o.status?.toLowerCase(),
        isDelivered: o.status?.toLowerCase() === 'delivered'
      })));
      console.log('배달 완료 주문 수:', deliveredOrders);
      
      // 디버깅 로그
      console.log('=== calculateTotal 할인 조건 ===');
      console.log('loyaltyConsent:', user.loyaltyConsent, typeof user.loyaltyConsent);
      console.log('consentName:', user.consentName, typeof user.consentName);
      console.log('consentAddress:', user.consentAddress, typeof user.consentAddress);
      console.log('consentPhone:', user.consentPhone, typeof user.consentPhone);
      console.log('allConsentsGiven:', allConsentsGiven);
      console.log('deliveredOrders:', deliveredOrders);
      console.log('previousOrders.length:', previousOrders.length);
      console.log('loyaltyEligible:', loyaltyEligible);
      console.log('originalSubtotal:', subtotal);
      
      // 할인 적용
      if (loyaltyEligible) {
        const originalSubtotal = subtotal;
        subtotal = Math.round(subtotal * 0.9);
        console.log('✅ 할인 적용됨!');
        console.log('originalSubtotal:', originalSubtotal);
        console.log('discountedSubtotal:', subtotal);
        console.log('discountAmount:', originalSubtotal - subtotal);
      } else {
        console.log('❌ 할인 미적용 - 조건 불충족');
        console.log('조건 확인:', {
          loyaltyConsent: user.loyaltyConsent,
          allConsentsGiven,
          deliveredOrders,
          requiredDeliveredOrders: 4
        });
      }
      console.log('================================');
    }
    
    // 주문 수정 시 당일 변경 수수료 계산
    let modificationFee = 0;
    if (isModifying && deliveryTime) {
      const now = new Date();
      const deliveryDateTime = new Date(deliveryTime);
      const today = new Date(now.getFullYear(), now.getMonth(), now.getDate());
      const deliveryDate = new Date(deliveryDateTime.getFullYear(), deliveryDateTime.getMonth(), deliveryDateTime.getDate());
      const isSameDay = today.getTime() === deliveryDate.getTime();
      
      if (isSameDay) {
        modificationFee = 10000; // 당일 변경 시 만원 추가
      }
    }

    const finalTotal = subtotal + modificationFee;
    console.log('최종 총액 계산:', { 
      subtotal: subtotal, 
      modificationFee: modificationFee, 
      finalTotal: finalTotal,
      user: user ? '있음' : '없음',
      previousOrdersCount: previousOrders.length
    });
    return finalTotal;
  };

  const calculateModificationFee = () => {
    if (!isModifying || !deliveryTime) return 0;
    
    const now = new Date();
    const deliveryDateTime = new Date(deliveryTime);
    const today = new Date(now.getFullYear(), now.getMonth(), now.getDate());
    const deliveryDate = new Date(deliveryDateTime.getFullYear(), deliveryDateTime.getMonth(), deliveryDateTime.getDate());
    const isSameDay = today.getTime() === deliveryDate.getTime();
    
    return isSameDay ? 10000 : 0;
  };

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    e.stopPropagation(); // 이벤트 전파 중단
    
    console.log('[주문 생성] handleSubmit 호출됨');
    console.log('[주문 생성] 현재 제출 ID:', orderSubmissionRef.current);
    console.log('[주문 생성] isSubmitting:', isSubmitting);
    console.log('[주문 생성] loading:', loading);
    
    // 중복 제출 방지 - 즉시 체크
    if (loading || isSubmitting || orderSubmissionRef.current) {
      console.log('[주문 생성] 이미 제출 중입니다. 중복 제출 방지 (즉시 반환)');
      e.preventDefault();
      e.stopPropagation();
      return;
    }
    
    setError('');

    if (!selectedDinner) {
      setError('디너를 선택해주세요.');
      return;
    }

    if (!selectedDate) {
      setError('배달 날짜를 선택해주세요.');
      return;
    }

    if (!selectedTime) {
      setError('배달 시간을 선택해주세요.');
      return;
    }

    if (!deliveryTime) {
      setError('배달 시간을 설정해주세요.');
      return;
    }

    if (!deliveryAddress) {
      setError('배달 주소를 입력해주세요.');
      return;
    }

    if (orderItems.length === 0) {
      setError('주문 항목을 선택해주세요.');
      return;
    }

    // 배달 시간 유효성 검사: 지난 시간이거나 3시간 이하 남은 경우 주문 불가
    const now = new Date();
    const deliveryDateTime = new Date(deliveryTime);
    const hoursUntilDelivery = (deliveryDateTime.getTime() - now.getTime()) / (1000 * 60 * 60);
    
    if (deliveryDateTime <= now) {
      setError('이미 지난 시간은 선택할 수 없습니다.');
      return;
    }
    
    if (hoursUntilDelivery < 3) {
      setError('배달 시간은 최소 3시간 전에 주문해야 합니다.');
      return;
    }

    // 카드 정보 확인
    if (!userCardInfo?.hasCard) {
      alert('주문을 하려면 카드 정보가 필요합니다. 내 정보에서 카드 정보를 등록해주세요.');
      navigate('/profile');
      return;
    }

    // 주문 수정 시에는 변경 사유가 필수가 아님 (직접 주문 수정 방식으로 변경됨)
    // if (isModifying && (!changeReason || changeReason.trim().length < 5)) {
    //   setError('예약 변경 사유를 5자 이상 입력해주세요.');
    //   return;
    // }

    // 중복 제출 방지 - 두 번째 체크 (이미 위에서 체크했지만 추가 보호)
    if (loading || isSubmitting || orderSubmissionRef.current) {
      console.log('[주문 생성] 이미 제출 중입니다. 중복 제출 방지 (두 번째 체크)');
      e.preventDefault();
      e.stopPropagation();
      return;
    }

    // 주문 확인 영수증 표시
    const orderData = {
      dinner_type_id: selectedDinner,
      serving_style: selectedStyle,
      delivery_time: deliveryTime,
      delivery_address: deliveryAddress,
      items: orderItems,
      payment_method: 'card'
    };
    setPendingOrderData(orderData);
    setShowOrderConfirmation(true);
    return;
  };

  const handleConfirmOrder = async () => {
    // 중복 제출 방지 - 즉시 체크
    if (loading || isSubmitting || orderSubmissionRef.current) {
      console.log('[주문 생성] handleConfirmOrder - 이미 제출 중입니다. 중복 제출 방지');
      return;
    }

    if (!orderPassword) {
      alert('비밀번호를 입력해주세요.');
      return;
    }

    if (!pendingOrderData) {
      alert('주문 정보가 없습니다.');
      return;
    }

    // 주문 수정 시에는 변경 사유가 필수가 아님 (직접 주문 수정 방식으로 변경됨)
    // if (isModifying && (!changeReason || changeReason.trim().length < 5)) {
    //   alert('예약 변경 사유를 5자 이상 입력해주세요.');
    //   return;
    // }

    if (!agreeCardUse || !agreePolicy) {
      alert('카드 결제 동의 및 정책 동의에 체크해주세요.');
      return;
    }

    // 고유한 제출 ID 생성
    const submissionId = `${Date.now()}-${Math.random().toString(36).substr(2, 9)}`;
    
    // 제출 ID 설정 전에 다시 한 번 체크
    if (orderSubmissionRef.current) {
      console.log('[주문 생성] handleConfirmOrder - 다른 제출이 이미 진행 중입니다.');
      return;
    }
    
    orderSubmissionRef.current = submissionId;
    
    setIsSubmitting(true);
    setLoading(true);
    setError(''); // 에러 초기화

    try {
      const token = localStorage.getItem('token');
      if (!token) {
        setError('로그인이 필요합니다.');
        setLoading(false);
        navigate('/login');
        return;
      }

      // 비밀번호 확인
      try {
        await axios.post(`${API_URL}/auth/verify-password`, {
          password: orderPassword
        }, {
          headers: { 'Authorization': `Bearer ${token}` }
        });
      } catch (err: any) {
        if (err.response?.status === 401) {
          alert('비밀번호가 올바르지 않습니다.');
          setLoading(false);
          setIsSubmitting(false);
          return;
        }
        throw err;
      }

      if (isModifying && modifyOrderId) {
        // 제출 ID 확인 (다른 제출이 이미 진행 중이면 중단)
        if (orderSubmissionRef.current !== submissionId) {
          console.log('[주문 수정] 다른 제출이 이미 진행 중입니다. 중단합니다.');
          setLoading(false);
          setIsSubmitting(false);
          return;
        }

        const modifyPayload = {
          dinner_type_id: pendingOrderData.dinner_type_id,
          serving_style: pendingOrderData.serving_style,
          delivery_time: pendingOrderData.delivery_time,
          delivery_address: pendingOrderData.delivery_address,
          items: pendingOrderData.items
        };

        try {
          const response = await axios.post(`${API_URL}/orders/${modifyOrderId}/modify`, modifyPayload, {
            headers: {
              'Authorization': `Bearer ${token}`,
              'X-Request-ID': submissionId
            }
          });
          console.log('[주문 수정] 성공:', response.data);
          
          // 수수료 정보 확인
          const now = new Date();
          const newDeliveryDateTime = new Date(pendingOrderData.delivery_time);
          const today = new Date(now.getFullYear(), now.getMonth(), now.getDate());
          const deliveryDate = new Date(newDeliveryDateTime.getFullYear(), newDeliveryDateTime.getMonth(), newDeliveryDateTime.getDate());
          const isSameDay = today.getTime() === deliveryDate.getTime();
          const modificationFee = isSameDay ? 10000 : 0;
          
          let message = `주문이 수정되었습니다.\n\n`;
          if (modificationFee > 0) {
            message += `당일 예약 변경으로 인해 추가금 ${modificationFee.toLocaleString()}원이 부과되었습니다.\n`;
          }
          message += `기존 주문은 취소되었고, 새 주문이 생성되었습니다.\n주문 ID: ${response.data.order_id}\n총 금액: ${response.data.new_order_total_price?.toLocaleString() || '확인 중'}원\n\n관리자 승인을 기다려주세요.`;
          alert(message);
        } catch (err: any) {
          const errorMsg = err.response?.data?.error || err.message || '주문 수정에 실패했습니다.';
          alert(errorMsg);
        } finally {
          setLoading(false);
          setIsSubmitting(false);
          setShowOrderConfirmation(false);
          setOrderPassword('');
          setPendingOrderData(null);
          setAgreeCardUse(false);
          setAgreePolicy(false);
          setChangeReason('');
          orderSubmissionRef.current = null;
          navigate('/orders');
        }
        return;
      } else {
        // Create new order - 한 번만 호출되도록 보장
        console.log('[주문 생성] 주문 생성 요청 시작 - 제출 ID:', orderSubmissionRef.current);
        
        // 제출 ID 확인 (다른 제출이 이미 진행 중이면 중단)
        if (orderSubmissionRef.current !== submissionId) {
          console.log('[주문 생성] 다른 제출이 이미 진행 중입니다. 중단합니다.');
          setLoading(false);
          setIsSubmitting(false);
          return;
        }
        
        // 주문 데이터에 고유 ID 추가
        const orderData = {
          ...pendingOrderData,
          _request_id: submissionId // 중복 방지를 위한 고유 ID
        };
        
        let response;
        try {
          response = await axios.post(`${API_URL}/orders`, orderData, {
            headers: {
              'Authorization': `Bearer ${token}`,
              'X-Request-ID': submissionId // 헤더에도 추가
            }
          });
        } catch (err: any) {
          // 429 에러 처리 (50초 제한)
          if (err.response?.status === 429) {
            const errorMsg = err.response?.data?.error || '같은 계정으로 50초 이내에는 하나의 주문만 가능합니다.';
            alert(errorMsg);
            setLoading(false);
            setIsSubmitting(false);
            setShowOrderConfirmation(false);
            setOrderPassword('');
            orderSubmissionRef.current = null;
            return;
          }
          // 409 에러 처리 (중복 주문)
          if (err.response?.status === 409) {
            const errorMsg = err.response?.data?.error || '동일한 주문이 이미 처리 중입니다.';
            alert(errorMsg);
            setLoading(false);
            setIsSubmitting(false);
            setShowOrderConfirmation(false);
            setOrderPassword('');
            orderSubmissionRef.current = null;
            return;
          }
          throw err;
        }

        console.log('[주문 생성] 성공:', response.data);
        
        // 제출 ID 확인 (다른 제출이 이미 완료되었으면 중단)
        if (orderSubmissionRef.current !== submissionId) {
          console.log('[주문 생성] 다른 제출이 이미 완료되었습니다. 중단합니다.');
          setLoading(false);
          setIsSubmitting(false);
          return;
        }
        
        // 응답 형식에 따라 orderId 추출
        const orderId = response.data.order_id || response.data.id || response.data.order?.id || response.data.order_id;
        
        // 주문 확인 모달 닫기
        setShowOrderConfirmation(false);
        setOrderPassword('');
        setPendingOrderData(null);
        setAgreeCardUse(false);
        setAgreePolicy(false);
        
        // 할인 정보 확인 및 표시
        const loyaltyDiscountApplied = response.data.loyalty_discount_applied;
        let successMessage = '주문이 접수되었습니다. 관리자 승인 후 직원에게 전달됩니다.';
        
        if (loyaltyDiscountApplied) {
          const originalPrice = response.data.original_price;
          const discountAmount = response.data.discount_amount;
          successMessage = `주문이 접수되었습니다.\n\n🎉 10% 할인 혜택이 적용되었습니다!\n원래 가격: ${originalPrice?.toLocaleString()}원\n할인 금액: ${discountAmount?.toLocaleString()}원\n최종 가격: ${response.data.total_price?.toLocaleString()}원\n\n관리자 승인 후 직원에게 전달됩니다.`;
        }
        
        // 주문 생성 성공 후 즉시 리다이렉트하여 추가 호출 방지
        if (orderId) {
          // 제출 ID를 null로 설정하여 추가 제출 완전 차단
          orderSubmissionRef.current = null;
          setIsSubmitting(false);
          setLoading(false);
          alert(successMessage);
          navigate(`/delivery/${orderId}`, { replace: true });
        } else {
          // orderId가 없어도 주문은 성공했을 수 있으므로 주문 목록으로 이동
          console.warn('[주문 생성] orderId를 찾을 수 없지만 주문은 성공했습니다:', response.data);
          orderSubmissionRef.current = null;
          setIsSubmitting(false);
          setLoading(false);
          alert(successMessage);
          navigate('/orders', { replace: true });
        }
      }
    } catch (err: any) {
      console.error('[주문 생성] 실패');
      console.error('[주문 생성] 에러:', err);
      
      // 429 또는 409 에러는 이미 처리됨
      if (err.response?.status === 429 || err.response?.status === 409) {
        return;
      }
      
      // 에러 발생 시 제출 ID 초기화
      orderSubmissionRef.current = null;
      setLoading(false);
      setIsSubmitting(false);
      setShowOrderConfirmation(false);
      
      if (err.response) {
        const status = err.response.status;
        const errorData = err.response.data;
        console.error('[주문 생성] HTTP 상태 코드:', status);
        console.error('[주문 생성] 응답 데이터:', errorData);
        
        if (status === 403) {
          const userStr = localStorage.getItem('user');
          const user = userStr ? JSON.parse(userStr) : null;
          setError(`[권한 없음] 주문 권한이 없습니다. (상태: 403)\n현재 역할: ${user?.role || '알 수 없음'}\n상세: ${JSON.stringify(errorData)}`);
        } else if (status === 401) {
          setError(`[인증 실패] 로그인이 필요합니다. (상태: 401)\n상세: ${JSON.stringify(errorData)}`);
        } else if (status === 400) {
          const errorMessage = errorData.error || errorData.message || JSON.stringify(errorData);
          // 중복 주문 메시지인 경우 조용히 처리 (주문이 이미 생성되었으므로)
          if (errorMessage.includes('동일한 주문이') || errorMessage.includes('이미 처리 중') || errorMessage.includes('최근에 생성되었습니다')) {
            // 중복 주문 에러는 조용히 처리하고 주문 목록으로 이동 (오류 메시지 표시 안 함)
            console.log('[주문 생성] 중복 주문 감지, 주문 목록으로 이동');
            navigate('/orders', { replace: true });
            return;
          }
          const validationErrors = errorData.errors || errorData;
          if (Array.isArray(validationErrors)) {
            setError(`[입력 오류]\n${validationErrors.map((e: any) => e.message || e).join('\n')}`);
          } else if (typeof validationErrors === 'object') {
            setError(`[입력 오류]\n${JSON.stringify(validationErrors, null, 2)}`);
          } else {
            setError(`[입력 오류] ${errorData.message || errorData}`);
          }
        } else {
          setError(`[주문 생성 실패] 서버 오류가 발생했습니다. (상태: ${status})\n상세: ${JSON.stringify(errorData)}`);
        }
      } else {
        setError('[주문 생성 실패] 네트워크 오류가 발생했습니다.\n서버에 연결할 수 없습니다.');
      }
      setShowOrderConfirmation(false);
      setOrderPassword('');
      setPendingOrderData(null);
    }
  };

  const selectedDinnerData = dinners.find(d => d.id === selectedDinner);
  const isChampagneDinner = selectedDinnerData?.name.includes('샴페인');

  return (
    <div className="order-page">
      <TopLogo showBackButton={true} />

        <div className="container">
        <div style={{ display: 'flex', alignItems: 'center', gap: '12px', marginBottom: '20px' }}>
          <button 
            onClick={() => navigate(-1)} 
            className="btn btn-secondary"
            style={{ padding: '8px 16px' }}
          >
            ← 뒤로가기
          </button>
          <h2 style={{ margin: 0 }}>{isModifying ? '예약 변경 요청' : '주문하기'}</h2>
        </div>
        {isModifying && (
          <div className="info-banner warning" style={{ marginBottom: '16px' }}>
            <strong>주문 수정 안내</strong><br />
            • 전날까지는 재고 내에서 무료로 수정 가능합니다.<br />
            • 당일 예약 변경 시 추가금 10,000원이 부과됩니다.<br />
            • 배달 시간 3시간 전 이후에는 변경이 불가합니다.
          </div>
        )}

        <form onSubmit={(e) => {
          e.preventDefault();
          e.stopPropagation();
          handleSubmit(e);
        }} className="order-form">
          <div className="form-group">
            <label>디너 선택</label>
            <div className="dinner-grid">
              {dinners.map(dinner => (
                <div
                  key={dinner.id}
                  className={`dinner-card ${selectedDinner === dinner.id ? 'selected' : ''}`}
                  onClick={() => setSelectedDinner(dinner.id)}
                >
                  <h3>{dinner.name}</h3>
                  <p>{dinner.description}</p>
                  <div className="price">{dinner.base_price.toLocaleString()}원</div>
                </div>
              ))}
            </div>
          </div>

          {selectedDinner && (
            <>
              <div className="form-group">
                <label>서빙 스타일</label>
                <div className="style-grid">
                  {servingStyles.map(style => {
                    const disabled = isChampagneDinner && style.name === 'simple';
                    return (
                      <label
                        key={style.name}
                        className={`style-option ${disabled ? 'disabled' : ''} ${selectedStyle === style.name ? 'selected' : ''}`}
                        title={style.description}
                      >
                        <input
                          type="radio"
                          name="style"
                          value={style.name}
                          checked={selectedStyle === style.name}
                          onChange={(e) => setSelectedStyle(e.target.value)}
                          disabled={disabled}
                        />
                        <div className="style-name">{style.name_ko}</div>
                        <div className="style-price">
                          {style.price_multiplier > 1 ? `+${((style.price_multiplier - 1) * 100).toFixed(0)}%` : '기본'}
                        </div>
                        {selectedStyle === style.name && (
                          <div style={{ 
                            fontSize: '11px', 
                            color: '#ccc', 
                            marginTop: '8px',
                            textAlign: 'center',
                            lineHeight: '1.4',
                            padding: '0 5px'
                          }}>
                            {style.description}
                          </div>
                        )}
                      </label>
                    );
                  })}
                </div>
              </div>

              <div className="form-group">
                <label>주문 항목</label>
                <div className="info-banner warning" style={{ marginBottom: '15px', fontSize: '13px' }}>
                  ⚠️ 메뉴 구성은 0으로 줄일 수 있지만, 기본 가격은 줄어들지 않습니다.
                  <br />
                  기본 가격에는 이미 기본 포함 항목들의 가격이 포함되어 있습니다.
                </div>
                <div className="order-items-section">
                  {selectedDinnerData?.menu_items.map(item => {
                    const orderItem = orderItems.find(oi => oi.menu_item_id === item.id);
                    const quantity = orderItem?.quantity || 0;
                    const defaultQuantity = item.quantity || 1; // 기본 포함 수량
                    return (
                      <div key={item.id} className="order-item">
                        <span>
                          {item.name} - {item.price.toLocaleString()}원
                          <span style={{ fontSize: '12px', color: '#999', marginLeft: '8px' }}>
                            (기본 {defaultQuantity}개 포함)
                          </span>
                        </span>
                        <div className="quantity-controls">
                          <button
                            type="button"
                            onClick={() => updateItemQuantity(item.id, -1)}
                            className="btn btn-secondary"
                          >
                            -
                          </button>
                          <span className="quantity">{quantity}</span>
                          <button
                            type="button"
                            onClick={() => updateItemQuantity(item.id, 1)}
                            className="btn btn-secondary"
                          >
                            +
                          </button>
                        </div>
                      </div>
                    );
                  })}
                </div>
              </div>

              <div className="form-group">
                <label>배달 날짜</label>
                <div style={{ 
                  display: 'flex', 
                  gap: '15px', 
                  alignItems: 'center',
                  flexWrap: 'wrap'
                }}>
                  <div style={{ display: 'flex', flexDirection: 'column', gap: '5px' }}>
                    <label style={{ fontSize: '12px', color: '#666' }}>년도</label>
                    <select
                      value={selectedYear}
                      onChange={(e) => {
                        setSelectedYear(Number(e.target.value));
                        const maxDay = getDaysInMonth(Number(e.target.value), selectedMonth);
                        if (selectedDay > maxDay) setSelectedDay(maxDay);
                      }}
                      style={{ padding: '10px', borderRadius: '8px', border: '1px solid #d4af37', minWidth: '100px' }}
                    >
                      {years.map(year => (
                        <option key={year} value={year}>{year}년</option>
                      ))}
                    </select>
                  </div>
                  <div style={{ display: 'flex', flexDirection: 'column', gap: '5px' }}>
                    <label style={{ fontSize: '12px', color: '#666' }}>월</label>
                    <select
                      value={selectedMonth}
                      onChange={(e) => {
                        setSelectedMonth(Number(e.target.value));
                        const maxDay = getDaysInMonth(selectedYear, Number(e.target.value));
                        if (selectedDay > maxDay) setSelectedDay(maxDay);
                      }}
                      style={{ padding: '10px', borderRadius: '8px', border: '1px solid #d4af37', minWidth: '100px' }}
                    >
                      {months.map(month => (
                        <option key={month} value={month}>{month}월</option>
                      ))}
                    </select>
                  </div>
                  <div style={{ display: 'flex', flexDirection: 'column', gap: '5px' }}>
                    <label style={{ fontSize: '12px', color: '#666' }}>일</label>
                    <select
                      value={selectedDay}
                      onChange={(e) => setSelectedDay(Number(e.target.value))}
                      style={{ padding: '10px', borderRadius: '8px', border: '1px solid #d4af37', minWidth: '100px' }}
                    >
                      {days.map(day => (
                        <option key={day} value={day}>{day}일</option>
                      ))}
                    </select>
                  </div>
                </div>
                {!isDateValid && (
                  <div style={{ color: '#ff4444', fontSize: '12px', marginTop: '5px' }}>
                    과거 날짜는 선택할 수 없습니다.
                  </div>
                )}
              </div>

              {isDateValid && (
                <div className="form-group">
                  <label>배달 시간 (5 PM - 9 PM, 30분 단위)</label>
                  <div className="time-slots-grid" style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(100px, 1fr))', gap: '10px', marginTop: '10px' }}>
                    {availableTimeSlots.map(time => {
                      const now = new Date();
                      const isToday = selectedDateObj.getTime() === today.getTime();
                      const [hours, minutes] = time.split(':').map(Number);
                      const deliveryDateTime = new Date(selectedYear, selectedMonth - 1, selectedDay, hours, minutes);
                      const hoursUntilDelivery = (deliveryDateTime.getTime() - now.getTime()) / (1000 * 60 * 60);
                      const isTimeDisabled = isToday && (deliveryDateTime <= now || hoursUntilDelivery < 3);
                      
                      return (
                        <button
                          key={time}
                          type="button"
                          onClick={() => !isTimeDisabled && setSelectedTime(time)}
                          className={`btn ${selectedTime === time ? 'btn-primary' : 'btn-secondary'} ${isTimeDisabled ? 'disabled' : ''}`}
                          style={{ 
                            padding: '10px',
                            opacity: isTimeDisabled ? 0.5 : 1,
                            cursor: isTimeDisabled ? 'not-allowed' : 'pointer'
                          }}
                          disabled={isTimeDisabled}
                        >
                          {time}
                        </button>
                      );
                    })}
                  </div>
                  {availableTimeSlots.length === 0 && isDateValid && (
                    <div style={{ color: '#ff4444', fontSize: '12px', marginTop: '5px' }}>
                      선택 가능한 시간대가 없습니다. (최소 3시간 전에 주문해야 합니다)
                    </div>
                  )}
                </div>
              )}

              <div className="form-group">
                <label>배달 주소</label>
                <div style={{ display: 'flex', gap: '15px', marginBottom: '15px' }}>
                  <div
                    onClick={() => setUseMyAddress(true)}
                    style={{
                      flex: 1,
                      padding: '20px',
                      border: `2px solid ${useMyAddress ? '#FFD700' : '#d4af37'}`,
                      borderRadius: '8px',
                      backgroundColor: useMyAddress ? 'rgba(255, 215, 0, 0.1)' : 'transparent',
                      cursor: 'pointer',
                      textAlign: 'center',
                      transition: 'all 0.3s',
                      fontWeight: useMyAddress ? 'bold' : 'normal'
                    }}
                  >
                    내 주소로 배달
                  </div>
                  <div
                    onClick={() => setUseMyAddress(false)}
                    style={{
                      flex: 1,
                      padding: '20px',
                      border: `2px solid ${!useMyAddress ? '#FFD700' : '#d4af37'}`,
                      borderRadius: '8px',
                      backgroundColor: !useMyAddress ? 'rgba(255, 215, 0, 0.1)' : 'transparent',
                      cursor: 'pointer',
                      textAlign: 'center',
                      transition: 'all 0.3s',
                      fontWeight: !useMyAddress ? 'bold' : 'normal'
                    }}
                  >
                    다른 주소로 배달
                  </div>
                </div>
                {useMyAddress ? (
                  <div style={{ 
                    padding: '15px', 
                    backgroundColor: '#1a1a1a', 
                    borderRadius: '8px', 
                    minHeight: '60px', 
                    display: 'flex', 
                    alignItems: 'center',
                    border: '1px solid #d4af37',
                    color: '#FFD700'
                  }}>
                    <strong>{deliveryAddress || '주소가 설정되지 않았습니다.'}</strong>
                  </div>
                ) : (
                  <textarea
                    value={customAddress}
                    onChange={(e) => setCustomAddress(e.target.value)}
                    required={!useMyAddress}
                    rows={3}
                    placeholder="배달 주소를 입력하세요"
                    style={{ width: '100%', padding: '15px', borderRadius: '8px', border: '1px solid #d4af37' }}
                  />
                )}
              </div>

              {/* 할인 혜택 안내 문구 - 배달 주소 아래, 총 가격 위 */}
              <div className="form-group" style={{ marginBottom: '20px' }}>
                {(() => {
                  if (!user) {
                    return (
                      <div style={{ 
                        padding: '12px', 
                        background: '#2a2a2a', 
                        borderRadius: '8px', 
                        border: '1px solid #666',
                        fontSize: '14px',
                        textAlign: 'center',
                        color: '#999'
                      }}>
                        <span>로그인이 필요합니다.</span>
                      </div>
                    );
                  }
                  
                  const allConsentsGiven = Boolean(user.consentName) && Boolean(user.consentAddress) && Boolean(user.consentPhone);
                  const deliveredOrders = previousOrders.filter(order => {
                  const status = order.status?.toLowerCase() || '';
                  return status === 'delivered';
                }).length;
                  const loyaltyEligible = Boolean(user.loyaltyConsent) && allConsentsGiven && deliveredOrders >= 4;
                  
                  if (loyaltyEligible) {
                    return (
                      <div style={{ 
                        padding: '12px', 
                        background: '#2a3a2a', 
                        borderRadius: '8px', 
                        border: '2px solid #4aaf4a',
                        fontSize: '15px',
                        fontWeight: '600',
                        textAlign: 'center'
                      }}>
                        <span style={{ color: '#4aaf4a' }}>
                          단골 고객입니다. 10% 할인 혜택 적용
                        </span>
                      </div>
                    );
                  } else {
                    return (
                      <div style={{ 
                        padding: '12px', 
                        background: '#2a2a2a', 
                        borderRadius: '8px', 
                        border: '1px solid #666',
                        fontSize: '14px',
                        textAlign: 'center',
                        color: '#999'
                      }}>
                        <span style={{ color: '#999' }}>
                          단골 고객 10% 할인 혜택: 단골 할인 동의 및 모든 개인정보 동의 완료, 배달 완료 4회 이상 필요
                        </span>
                      </div>
                    );
                  }
                })()}
              </div>

          {isModifying && (
            <div className="form-group">
              <label>예약 변경 사유</label>
              <textarea
                value={changeReason}
                onChange={(e) => setChangeReason(e.target.value)}
                rows={3}
                placeholder="변경을 원하는 이유를 작성해주세요 (예: 인원 증가, 메뉴 조정 등)"
                style={{ width: '100%', padding: '15px', borderRadius: '8px', border: '1px solid #d4af37' }}
              />
              <div style={{ fontSize: '12px', color: '#ccc', marginTop: '6px' }}>
                요청 사유는 관리자 검토용으로 전달됩니다. 5자 이상 입력해주세요.
              </div>
            </div>
          )}

              <div className="total-price">
                <h3>총 가격</h3>
                {(() => {
                  const total = calculateTotal();
                  console.log('총 가격 표시:', total);
                  return <div className="amount">{total.toLocaleString()}원</div>;
                })()}
              </div>

              {error && <div className="error">{error}</div>}
              
              {!inventoryAvailable && (
                <div className="error" style={{ marginBottom: '10px' }}>
                  재고가 부족하여 주문할 수 없습니다.
                </div>
              )}

              {(() => {
                const now = new Date();
                const deliveryDateTime = deliveryTime ? new Date(deliveryTime) : null;
                const hoursUntilDelivery = deliveryDateTime ? (deliveryDateTime.getTime() - now.getTime()) / (1000 * 60 * 60) : Infinity;
                const isTimeValid = deliveryDateTime && deliveryDateTime > now && hoursUntilDelivery >= 3;
                
                return (
                  <button 
                    type="submit" 
                    className="btn btn-primary submit-button" 
                    disabled={loading || !inventoryAvailable || !isTimeValid}
                  >
                    {loading ? (isModifying ? '수정 중...' : '주문 처리 중...') : (isModifying ? '주문 수정하기' : '주문하기')}
                  </button>
                );
              })()}
            </>
          )}
        </form>
      </div>
      <div className="info-banner approval">
        모든 주문은 관리자 승인 후 직원에게 전달됩니다. 변경 및 취소 요청 또한 관리자 승인 후 확정됩니다.
      </div>
      <div className="info-banner card">
        결제는 등록된 카드로만 가능하며, 주문 확정 시 비밀번호와 결제 동의가 필요합니다.
      </div>
      {orderAlert && (
        <div className="info-banner success">
          {orderAlert}
        </div>
      )}

      {previousOrders.length > 0 && (
        <div className="previous-orders-panel">
          <h3>이전 주문 빠른 선택</h3>
          <div className="previous-orders-grid">
            {previousOrders.slice(0, 3).map(order => (
              <div key={order.id} className="previous-order-card">
                <div className="previous-order-header">
                  <div>
                    <strong>#{order.id}</strong> {order.dinner_name}
                  </div>
                  <span>{new Date(order.delivery_time).toLocaleDateString('ko-KR')}</span>
                </div>
                <div className="previous-order-body">
                  <p>{order.delivery_address}</p>
                  <div className="previous-order-items">
                    {order.items.slice(0, 2).map(item => (
                      <span key={`${order.id}-${item.menu_item_id}`} className="item-tag">
                        {item.name || `항목 ${item.menu_item_id}`} x{item.quantity}
                      </span>
                    ))}
                    {order.items.length > 2 && (
                      <span className="item-tag">+{order.items.length - 2}개</span>
                    )}
                  </div>
                </div>
                <div className="previous-order-actions">
                  <button className="btn btn-outline" onClick={() => applyReorderData(order)}>
                    불러오기
                  </button>
                </div>
              </div>
            ))}
          </div>
        </div>
      )}

      {/* 주문 확인 모달 */}
      {showOrderConfirmation && pendingOrderData && (
        <div className="modal-overlay" style={{
          position: 'fixed',
          top: 0,
          left: 0,
          right: 0,
          bottom: 0,
          background: 'rgba(0, 0, 0, 0.8)',
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'center',
          zIndex: 10000
        }} onClick={() => {
          setShowOrderConfirmation(false);
          setOrderPassword('');
          setPendingOrderData(null);
          setAgreeCardUse(false);
          setAgreePolicy(false);
        }}>
          <div style={{
            background: '#1a1a1a',
            border: '2px solid #d4af37',
            borderRadius: '8px',
            padding: '30px',
            maxWidth: '600px',
            width: '90%',
            maxHeight: '90vh',
            overflowY: 'auto',
            color: '#fff'
          }} onClick={(e) => e.stopPropagation()}>
            <h2 style={{ color: '#d4af37', marginBottom: '20px' }}>주문 확인</h2>
            
            {/* 영수증 */}
            <div style={{
              background: '#2a2a2a',
              padding: '20px',
              borderRadius: '8px',
              marginBottom: '20px',
              border: '1px solid #d4af37'
            }}>
              <h3 style={{ color: '#d4af37', marginBottom: '15px' }}>주문 내역</h3>
              <div style={{ marginBottom: '10px' }}>
                <strong>디너:</strong> {dinners.find(d => d.id === pendingOrderData.dinner_type_id)?.name || '-'}
              </div>
              <div style={{ marginBottom: '10px' }}>
                <strong>서빙 스타일:</strong> {servingStyles.find(s => s.name === pendingOrderData.serving_style)?.name_ko || pendingOrderData.serving_style}
              </div>
              <div style={{ marginBottom: '10px' }}>
                <strong>배달 시간:</strong> {new Date(pendingOrderData.delivery_time).toLocaleString('ko-KR')}
              </div>
              <div style={{ marginBottom: '10px' }}>
                <strong>배달 주소:</strong> {pendingOrderData.delivery_address}
              </div>
              <div style={{ marginBottom: '15px', paddingTop: '15px', borderTop: '1px solid #d4af37' }}>
                <strong>주문 항목:</strong>
                <div style={{ marginTop: '10px', marginLeft: '20px' }}>
                  {pendingOrderData.items.map((item: any, idx: number) => {
                    const menuItem = menuItems.find(m => m.id === item.menu_item_id);
                    return (
                      <div key={idx} style={{ marginBottom: '5px' }}>
                        {menuItem?.name || `항목 ${item.menu_item_id}`} x {item.quantity}
                      </div>
                    );
                  })}
                </div>
              </div>
              {isModifying && (
                <div style={{ marginBottom: '15px' }}>
                  <strong>변경 사유:</strong>
                  <div style={{ marginTop: '8px', whiteSpace: 'pre-wrap' }}>
                    {changeReason || '사유가 입력되지 않았습니다.'}
                  </div>
                  {calculateModificationFee() > 0 && (
                    <div style={{ marginTop: '10px', padding: '10px', background: '#3a2a1a', borderRadius: '4px', border: '1px solid #d4af37' }}>
                      <strong style={{ color: '#d4af37' }}>변경 수수료:</strong>
                      <span style={{ marginLeft: '8px', color: '#fff' }}>
                        {calculateModificationFee().toLocaleString()}원 추가 (당일 예약 변경)
                      </span>
                    </div>
                  )}
                  {calculateModificationFee() === 0 && (
                    <div style={{ marginTop: '10px', padding: '10px', background: '#2a3a2a', borderRadius: '4px', border: '1px solid #4aaf4a' }}>
                      <span style={{ color: '#4aaf4a' }}>
                        전날까지는 재고 내에서 무료로 수정 가능합니다.
                      </span>
                    </div>
                  )}
                </div>
              )}
              {(() => {
                if (!user) {
                  return null;
                }
                
                const allConsentsGiven = Boolean(user.consentName) && Boolean(user.consentAddress) && Boolean(user.consentPhone);
                const deliveredOrders = previousOrders.filter(order => {
                  const status = order.status?.toLowerCase() || '';
                  return status === 'delivered';
                }).length;
                const loyaltyEligible = Boolean(user.loyaltyConsent) && allConsentsGiven && deliveredOrders >= 4;
                const style = servingStyles.find(s => s.name === selectedStyle);
                const styleMultiplier = style?.price_multiplier || 1;
                const dinner = dinners.find(d => d.id === selectedDinner);
                const basePrice = dinner ? dinner.base_price * styleMultiplier : 0;
                const defaultItemsPrice = dinner ? dinner.menu_items.reduce((sum, defaultItem) => {
                  const menuItem = menuItems.find(m => m.id === defaultItem.id);
                  const defaultQuantity = defaultItem.quantity || 1;
                  return sum + (menuItem?.price || 0) * defaultQuantity;
                }, 0) : 0;
                const currentItemsPrice = orderItems.reduce((sum, item) => {
                  const menuItem = menuItems.find(m => m.id === item.menu_item_id);
                  return sum + (menuItem?.price || 0) * item.quantity;
                }, 0);
                const additionalItemsPrice = Math.max(0, currentItemsPrice - defaultItemsPrice);
                const originalSubtotal = basePrice + additionalItemsPrice;
                const discountAmount = loyaltyEligible ? Math.round(originalSubtotal * 0.1) : 0;
                
                return (
                  <>
                    {loyaltyEligible && (
                      <div style={{ marginBottom: '10px', padding: '10px', background: '#2a3a2a', borderRadius: '4px', border: '1px solid #4aaf4a' }}>
                        <span style={{ color: '#4aaf4a' }}>
                          🎉 10% 할인 혜택이 적용됩니다!
                        </span>
                      </div>
                    )}
                    <div style={{
                      paddingTop: '15px',
                      borderTop: '2px solid #d4af37',
                      fontSize: '18px',
                      fontWeight: 'bold',
                      color: '#d4af37'
                    }}>
                      총 금액: {calculateTotal().toLocaleString()}원
                      {loyaltyEligible && (
                        <div style={{ fontSize: '14px', color: '#4aaf4a', marginTop: '5px', fontWeight: 'normal' }}>
                          (원래 가격: {originalSubtotal.toLocaleString()}원 - 할인: {discountAmount.toLocaleString()}원)
                        </div>
                      )}
                      {calculateModificationFee() > 0 && (
                        <div style={{ fontSize: '14px', color: '#ffaa00', marginTop: '5px', fontWeight: 'normal' }}>
                          (기본 금액 + 변경 수수료 {calculateModificationFee().toLocaleString()}원 포함)
                        </div>
                      )}
                    </div>
                  </>
                );
              })()}
            </div>

          <div className="card-info-block" style={{ marginBottom: '20px' }}>
            <h3 style={{ color: '#d4af37', marginBottom: '10px' }}>카드 결제 정보</h3>
            <p style={{ marginBottom: '8px' }}>
              카드 번호: {userCardInfo?.cardNumber || '등록된 카드가 없습니다'}
            </p>
            <div className="consent-check">
              <label>
                <input
                  type="checkbox"
                  checked={agreeCardUse}
                  onChange={(e) => setAgreeCardUse(e.target.checked)}
                />
                등록된 카드로 결제하는 것에 동의합니다.
              </label>
              <label>
                <input
                  type="checkbox"
                  checked={agreePolicy}
                  onChange={(e) => setAgreePolicy(e.target.checked)}
                />
                주문 변경 및 취소는 관리자 승인 후 확정됨을 이해했습니다.
              </label>
            </div>
          </div>

            <div style={{ marginBottom: '20px' }}>
              <label style={{ display: 'block', marginBottom: '10px', color: '#FFD700' }}>
                주문 확정을 위해 비밀번호를 입력해주세요
              </label>
              <input
                type="password"
                value={orderPassword}
                onChange={(e) => setOrderPassword(e.target.value)}
                placeholder="비밀번호를 입력하세요"
                style={{
                  width: '100%',
                  padding: '10px',
                  background: '#2a2a2a',
                  border: '1px solid #d4af37',
                  borderRadius: '4px',
                  color: '#fff',
                  fontSize: '16px'
                }}
                autoFocus
              />
            </div>

            <div style={{ display: 'flex', gap: '12px' }}>
              <button
                className="btn btn-secondary"
                onClick={() => {
                  setShowOrderConfirmation(false);
                  setOrderPassword('');
                  setPendingOrderData(null);
                  setAgreeCardUse(false);
                  setAgreePolicy(false);
                }}
                style={{ flex: 1 }}
              >
                취소
              </button>
              <button
                className="btn btn-primary"
                onClick={handleConfirmOrder}
                disabled={!orderPassword || loading || !agreeCardUse || !agreePolicy}
                style={{ flex: 1 }}
              >
                {loading ? '주문 처리 중...' : '주문 확정'}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
};

export default Order;
