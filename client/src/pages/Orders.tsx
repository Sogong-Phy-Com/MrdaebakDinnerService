import React, { useState, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import axios from 'axios';
import { useAuth } from '../contexts/AuthContext';
import TopLogo from '../components/TopLogo';
import './Orders.css';

const API_URL = process.env.REACT_APP_API_URL || (window.location.protocol === 'https:' ? '/api' : 'http://localhost:5000/api');

interface OrderItem {
  id: number;
  menu_item_id: number;
  name: string;
  name_en: string;
  price: number;
  quantity: number;
}

interface Order {
  id: number;
  dinner_name: string;
  dinner_name_en: string;
  serving_style: string;
  delivery_time: string;
  delivery_address: string;
  total_price: number;
  status: string;
  payment_status: string;
  created_at: string;
  items: OrderItem[];
  admin_approval_status?: string;
}

interface ChangeRequest {
  id: number;
  status: string;
  original_total_amount: number;
  new_total_amount: number;
  change_fee_amount: number;
  extra_charge_amount: number;
  expected_refund_amount: number;
  requires_additional_payment: boolean;
  requires_refund: boolean;
  requested_at: string;
  approved_at?: string;
  rejected_at?: string;
  reason?: string;
  admin_comment?: string;
}

const Orders: React.FC = () => {
  const { user } = useAuth();
  const navigate = useNavigate();
  const [orders, setOrders] = useState<Order[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [detailOrder, setDetailOrder] = useState<Order | null>(null);
  const [changeModalOrder, setChangeModalOrder] = useState<Order | null>(null);
  const [changeRequests, setChangeRequests] = useState<ChangeRequest[]>([]);
  const [changeRequestLoading, setChangeRequestLoading] = useState(false);
  const [changeRequestError, setChangeRequestError] = useState('');

  useEffect(() => {
    fetchOrders();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [user, navigate]);

  const fetchOrders = async () => {
    try {
      const token = localStorage.getItem('token');
      if (!token) {
        setError('로그인이 필요합니다.');
        setLoading(false);
        navigate('/login');
        return;
      }

      const response = await axios.get(`${API_URL}/orders`, {
        headers: {
          'Authorization': `Bearer ${token}`
        }
      });

      if (!Array.isArray(response.data)) {
        setError('서버 응답 형식이 올바르지 않습니다.');
        setLoading(false);
        return;
      }

      setOrders(response.data);
    } catch (err: any) {
      console.error('주문 목록 조회 실패:', err);
      if (err.response) {
        setError(`주문 목록을 불러오는데 실패했습니다. (상태: ${err.response.status})`);
      } else {
        setError('주문 목록을 불러오는데 실패했습니다.');
      }
    } finally {
      setLoading(false);
    }
  };

  const getStatusLabel = (status: string) => {
    const labels: { [key: string]: string } = {
      pending: '주문 접수',
      cooking: '조리 중',
      ready: '준비 완료',
      out_for_delivery: '배달 중',
      delivered: '배달 완료',
      cancelled: '취소됨'
    };
    return labels[status] || status;
  };

  const getStatusClass = (status: string) => {
    const classes: { [key: string]: string } = {
      pending: 'status-pending',
      cooking: 'status-cooking',
      ready: 'status-ready',
      out_for_delivery: 'status-delivery',
      delivered: 'status-delivered',
      cancelled: 'status-cancelled'
    };
    return classes[status] || '';
  };

  const getStyleLabel = (style: string) => {
    const labels: { [key: string]: string } = {
      simple: '심플',
      grand: '그랜드',
      deluxe: '디럭스'
    };
    return labels[style] || style;
  };

  const getApprovalLabel = (status?: string) => {
    const normalized = (status || '').toUpperCase();
    switch (normalized) {
      case 'APPROVED':
        return '관리자 승인 완료';
      case 'REJECTED':
        return '관리자 반려';
      case 'CANCELLED':
        return '고객 취소';
      default:
        return '관리자 승인 대기';
    }
  };

  const getApprovalClass = (status?: string) => {
    const normalized = (status || '').toUpperCase();
    if (normalized === 'APPROVED') return 'approved';
    if (normalized === 'REJECTED') return 'rejected';
    if (normalized === 'CANCELLED') return 'cancelled';
    return 'pending';
  };

  const formatChangeStatus = (status: string) => {
    switch (status) {
      case 'APPROVED':
        return '승인됨';
      case 'REJECTED':
        return '거절됨';
      case 'PAYMENT_FAILED':
        return '결제 실패';
      case 'REFUND_FAILED':
        return '환불 실패';
      case 'REQUESTED':
      default:
        return '승인 대기';
    }
  };

  const changeStatusClass = (status: string) => {
    switch (status) {
      case 'APPROVED':
        return 'status-approved';
      case 'REJECTED':
        return 'status-rejected';
      case 'PAYMENT_FAILED':
      case 'REFUND_FAILED':
        return 'status-warning';
      default:
        return 'status-pending';
    }
  };

  const startOfDay = (date: Date) => {
    return new Date(date.getFullYear(), date.getMonth(), date.getDate());
  };

  const getChangeWindowInfo = (order: Order) => {
    const now = new Date();
    const deliveryDateTime = new Date(order.delivery_time);
    const hoursUntilDelivery = (deliveryDateTime.getTime() - now.getTime()) / (1000 * 60 * 60);
    
    // 날짜 비교 (당일 여부 확인)
    const today = new Date(now.getFullYear(), now.getMonth(), now.getDate());
    const deliveryDate = new Date(deliveryDateTime.getFullYear(), deliveryDateTime.getMonth(), deliveryDateTime.getDate());
    const isSameDay = today.getTime() === deliveryDate.getTime();

    // 배달 3시간 전 이후에는 변경 불가
    if (hoursUntilDelivery < 3) {
      return { allowed: false, fee: 0, message: '배달 시간 3시간 전 이후에는 변경할 수 없습니다.' };
    }

    // 당일 예약 변경 시 만원 추가금 부과
    if (isSameDay) {
      return { allowed: true, fee: 10000, message: '당일 예약 변경으로 인해 추가금 10,000원이 부과됩니다.' };
    }

    // 전날까지는 무료 수정 가능
    return { allowed: true, fee: 0, message: '전날까지는 재고 내에서 무료로 수정할 수 있습니다.' };
  };

  const canModify = (order: Order) => {
    const approvalStatus = order.admin_approval_status ? order.admin_approval_status.toUpperCase() : '';
    
    // PENDING 또는 APPROVED 상태의 주문만 수정 가능
    if (approvalStatus !== 'APPROVED' && approvalStatus !== 'PENDING') {
      return false;
    }
    
    // 취소되거나 배달 완료된 주문은 수정 불가
    if (order.status === 'cancelled' || order.status === 'delivered') {
      return false;
    }
    
    // APPROVED 상태의 주문은 기존 제약(배달 1일 전까지) 확인
    if (approvalStatus === 'APPROVED') {
      return getChangeWindowInfo(order).allowed;
    }
    
    // PENDING 상태의 주문은 항상 수정 가능 (아직 승인 전이므로)
    return true;
  };

  const canCancel = (order: Order) =>
    order.status !== 'delivered' && order.status !== 'cancelled';

  const handleReorder = (order: Order, e?: React.MouseEvent<HTMLButtonElement>) => {
    if (e) {
      e.stopPropagation();
    }
    navigate('/order', { state: { reorderOrder: order } });
  };

  const pendingApprovalCount = orders.filter(order =>
    order.admin_approval_status &&
    order.admin_approval_status.toUpperCase() !== 'APPROVED' &&
    order.status !== 'cancelled'
  ).length;

  const calculateDaysUntilDelivery = (deliveryTime: string): number => {
    const delivery = new Date(deliveryTime);
    const now = new Date();
    const diffTime = delivery.getTime() - now.getTime();
    const diffDays = Math.ceil(diffTime / (1000 * 60 * 60 * 24));
    return diffDays;
  };

  const calculateCancelFee = (order: Order): number => {
    const daysUntil = calculateDaysUntilDelivery(order.delivery_time);
    if (daysUntil >= 7) {
      return 0; // Free
    }
    return 30000; // 30,000 won fee
  };

  const calculateModifyFee = (order: Order): number => {
    return getChangeWindowInfo(order).fee;
  };

  const handleCancelOrder = async (order: Order) => {
    // 조리 시작 이후에는 전액 환불 불가
    if (order.status === 'cooking' || order.status === 'ready' || order.status === 'out_for_delivery') {
      const message = `조리가 시작된 주문은 취소할 수 없습니다.\n\n조리 시작 이후에는 전액 환불이 불가능하며, 재고는 이미 소진되었습니다.\n\n정말 취소하시겠습니까? (환불 불가)`;
      
      if (!window.confirm(message)) {
        return;
      }
    } else {
      const daysUntil = calculateDaysUntilDelivery(order.delivery_time);
      const fee = calculateCancelFee(order);
      const refundAmount = order.total_price - fee;
      
      let message = '';
      if (fee === 0) {
        message = `주문 취소 시 수수료는 없습니다.\n환불 금액: ${refundAmount.toLocaleString()}원\n(배달일로부터 ${daysUntil}일 전)`;
      } else {
        message = `주문 취소 시 수수료 ${fee.toLocaleString()}원이 발생합니다.\n환불 금액: ${refundAmount.toLocaleString()}원\n(배달일로부터 ${daysUntil}일 전)\n\n취소하시겠습니까?`;
      }
      
      if (!window.confirm(message)) {
        return;
      }
    }

    try {
      const token = localStorage.getItem('token');
      if (!token) {
        setError('로그인이 필요합니다.');
        return;
      }

      await axios.post(`${API_URL}/orders/${order.id}/cancel`, {}, {
        headers: {
          'Authorization': `Bearer ${token}`
        }
      });

      if (order.status === 'cooking' || order.status === 'ready' || order.status === 'out_for_delivery') {
        alert('주문이 취소되었습니다. (조리 시작 이후 취소로 인해 환불은 불가능하며, 재고는 이미 소진되었습니다.)');
      } else {
        alert('주문이 취소되었습니다.');
      }
      await fetchOrders();
    } catch (err: any) {
      const errorMsg = err.response?.data?.error || err.message || '주문 취소에 실패했습니다.';
      alert(errorMsg);
    }
  };

  const handleModifyOrder = (order: Order) => {
    const windowInfo = getChangeWindowInfo(order);
    if (!canModify(order)) {
      alert(windowInfo.message || '현재는 예약 변경을 진행할 수 없습니다.');
      return;
    }
    const fee = windowInfo.fee;
    const message = fee > 0
      ? `${windowInfo.message}\n\n이번 변경에는 추가금 ${fee.toLocaleString()}원이 부과됩니다.\n관리자 승인 시 결제됩니다.\n\n변경 요청을 진행하시겠습니까?`
      : `${windowInfo.message}\n\n관리자 승인 시 최종 확정됩니다.\n\n변경 요청을 진행하시겠습니까?`;
    if (!window.confirm(message)) {
      return;
    }
    navigate(`/order?modify=${order.id}`);
  };

  const openChangeRequestModal = async (order: Order) => {
    setChangeModalOrder(order);
    setChangeRequests([]);
    setChangeRequestError('');
    setChangeRequestLoading(true);
    try {
      const token = localStorage.getItem('token');
      if (!token) {
        throw new Error('로그인이 필요합니다.');
      }
      const response = await axios.get(`${API_URL}/reservations/${order.id}/change-requests`, {
        headers: {
          'Authorization': `Bearer ${token}`
        }
      });
      setChangeRequests(Array.isArray(response.data) ? response.data : []);
    } catch (err: any) {
      const msg = err.response?.data?.error || err.message || '변경 요청을 불러오지 못했습니다.';
      setChangeRequestError(msg);
    } finally {
      setChangeRequestLoading(false);
    }
  };

  const closeChangeModal = () => {
    setChangeModalOrder(null);
    setChangeRequests([]);
    setChangeRequestError('');
  };

  if (loading) {
    return (
      <div className="orders-page">
        <div className="loading">로딩 중...</div>
      </div>
    );
  }

  return (
    <div className="orders-page">
      <TopLogo showBackButton={false} />

      <div className="page-content">
        <div className="container">
          <div style={{ marginBottom: '20px' }}>
            <button onClick={() => navigate('/')} className="btn btn-secondary">
              ← 홈으로
            </button>
          </div>
          {error && (
            <div className="error">
              {error}
            </div>
          )}
          {pendingApprovalCount > 0 && (
            <div className="info-banner warning" style={{ marginBottom: '20px' }}>
              관리자 승인 대기 중인 주문 {pendingApprovalCount}건이 있습니다. 승인 완료 후에만 직원에게 전달됩니다.
            </div>
          )}

          {orders.length === 0 ? (
            <div className="no-orders">
              <div className="no-orders-icon">📦</div>
              <h3>주문 내역이 없습니다</h3>
              <p>첫 주문을 시작해보세요!</p>
              <button onClick={() => navigate('/order')} className="btn btn-primary">
                🛒 주문하기
              </button>
            </div>
          ) : (
            <div className="orders-list">
              {orders.map(order => (
                <div key={order.id} className="order-card-modern" onClick={() => navigate(`/delivery/${order.id}`)}>
                  <div className="order-card-header">
                    <div className="order-card-title">
                      <h3>{order.dinner_name}</h3>
                      <span className="order-date">
                        {new Date(order.created_at).toLocaleDateString('ko-KR')}
                      </span>
                    </div>
                    <div className="order-status-group">
                      <span className={`approval-badge ${getApprovalClass(order.admin_approval_status)}`}>
                        {getApprovalLabel(order.admin_approval_status)}
                      </span>
                      <span className={`status-badge-modern ${getStatusClass(order.status)}`}>
                        {getStatusLabel(order.status)}
                      </span>
                    </div>
                  </div>

                  <div className="order-card-body">
                    <div className="order-info-row">
                      <span className="info-icon">📍</span>
                      <span className="info-text">{order.delivery_address}</span>
                    </div>
                    <div className="order-info-row">
                      <span className="info-icon">⏰</span>
                      <span className="info-text">
                        {new Date(order.delivery_time).toLocaleString('ko-KR')}
                      </span>
                    </div>
                    <div className="order-info-row">
                      <span className="info-icon">🎨</span>
                      <span className="info-text">{getStyleLabel(order.serving_style)} 스타일</span>
                    </div>
                  </div>

                  <div className="order-card-footer">
                    <div className="order-items-preview">
                      {order.items.slice(0, 2).map(item => (
                        <span key={item.id} className="item-tag">
                          {item.name} x{item.quantity}
                        </span>
                      ))}
                      {order.items.length > 2 && (
                        <span className="item-tag">+{order.items.length - 2}개</span>
                      )}
                    </div>
                    <div className="order-total-modern">
                      {order.total_price.toLocaleString()}원
                    </div>
                  </div>

                  <div className="order-action" style={{ display: 'flex', flexWrap: 'wrap', gap: '10px', marginTop: '12px' }}>
                    <button
                      className="btn btn-secondary"
                      style={{ flex: 1, minWidth: '140px' }}
                      disabled={!canCancel(order)}
                      onClick={(e) => {
                        e.stopPropagation();
                        if (canCancel(order)) {
                          handleCancelOrder(order);
                        }
                      }}
                    >
                      주문 취소
                    </button>
                    <button
                      className="btn btn-secondary"
                      style={{ flex: 1, minWidth: '140px', borderStyle: 'dashed' }}
                      onClick={(e) => {
                        e.stopPropagation();
                        setDetailOrder(order);
                      }}
                    >
                      세부내역 참조
                    </button>
                    <button
                      className="btn btn-outline"
                      style={{ flex: 1, minWidth: '140px' }}
                      onClick={(e) => {
                        e.stopPropagation();
                        openChangeRequestModal(order);
                      }}
                    >
                      변경 요청 현황
                    </button>
                    {/* 주문 수정 버튼 - PENDING 또는 APPROVED 상태의 주문에 표시 */}
                    {(order.admin_approval_status === 'PENDING' || order.admin_approval_status === 'APPROVED') && 
                     order.status !== 'cancelled' && 
                     order.status !== 'delivered' && (
                      <button
                        className="btn btn-primary"
                        style={{ 
                          flex: 1, 
                          minWidth: '140px', 
                          fontWeight: 'bold',
                          opacity: canModify(order) ? 1 : 0.6
                        }}
                        disabled={!canModify(order)}
                        title={canModify(order) ? '주문을 수정할 수 있습니다' : (order.admin_approval_status === 'APPROVED' ? '배달 1일 전 이후에는 변경할 수 없습니다' : '주문 수정이 불가능합니다')}
                        onClick={(e) => {
                          e.stopPropagation();
                          if (canModify(order)) {
                            handleModifyOrder(order);
                          } else {
                            const windowInfo = getChangeWindowInfo(order);
                            alert(windowInfo.message || '주문 수정이 불가능합니다.');
                          }
                        }}
                      >
                        ✏️ 주문 수정하기
                      </button>
                    )}
                  </div>
                </div>
              ))}
            </div>
          )}
        </div>
      </div>

      {detailOrder && (
        <div
          style={{
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
          }}
          onClick={() => setDetailOrder(null)}
        >
          <div
            style={{
              background: '#1a1a1a',
              padding: '24px',
              borderRadius: '12px',
              width: '90%',
              maxWidth: '600px',
              border: '1px solid var(--border-color)',
              color: '#fff'
            }}
            onClick={(e) => e.stopPropagation()}
          >
            <h3>주문 #{detailOrder.id} 세부내역</h3>
            <p style={{ marginBottom: '10px' }}>디너: {detailOrder.dinner_name}</p>
            <p style={{ marginBottom: '10px' }}>배달 주소: {detailOrder.delivery_address}</p>
            <p style={{ marginBottom: '10px' }}>배달 시간: {new Date(detailOrder.delivery_time).toLocaleString('ko-KR')}</p>
            <p style={{ marginBottom: '10px' }}>서빙 스타일: {getStyleLabel(detailOrder.serving_style)}</p>
            <p style={{ marginBottom: '10px' }}>총 금액: {detailOrder.total_price.toLocaleString()}원</p>
            <div style={{ borderTop: '1px solid var(--border-color)', paddingTop: '12px', marginTop: '12px' }}>
              <h4>주문 항목</h4>
              <ul>
                {detailOrder.items?.map((item) => (
                  <li key={item.id}>
                    {item.name} x {item.quantity} - {item.price ? (item.price * item.quantity).toLocaleString() : ''}원
                  </li>
                ))}
              </ul>
            </div>
            <div style={{ display: 'flex', justifyContent: 'flex-end', marginTop: '20px' }}>
              <button className="btn btn-secondary" onClick={() => setDetailOrder(null)}>
                닫기
              </button>
            </div>
          </div>
        </div>
      )}

      {changeModalOrder && (
        <div
          style={{
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
          }}
          onClick={closeChangeModal}
        >
          <div
            style={{
              background: '#1a1a1a',
              padding: '24px',
              borderRadius: '12px',
              width: '90%',
              maxWidth: '640px',
              border: '1px solid var(--border-color)',
              color: '#fff',
              maxHeight: '90vh',
              overflowY: 'auto'
            }}
            onClick={(e) => e.stopPropagation()}
          >
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '12px' }}>
              <h3>예약 #{changeModalOrder.id} 변경 요청</h3>
              <button className="btn btn-secondary" onClick={closeChangeModal}>닫기</button>
            </div>
            <p style={{ marginBottom: '12px', color: '#bbb' }}>
              {getApprovalLabel(changeModalOrder.admin_approval_status)} · {getStatusLabel(changeModalOrder.status)}
            </p>
            {changeRequestLoading ? (
              <div>변경 요청을 불러오는 중입니다...</div>
            ) : changeRequestError ? (
              <div className="error">{changeRequestError}</div>
            ) : changeRequests.length === 0 ? (
              <div className="info-banner" style={{ marginTop: '12px' }}>
                등록된 변경 요청이 없습니다.
              </div>
            ) : (
              <div style={{ display: 'flex', flexDirection: 'column', gap: '12px' }}>
                {changeRequests.map((req) => (
                  <div key={req.id} className="change-request-card">
                    <div className="change-request-header">
                      <div>
                        <strong>요청 #{req.id}</strong>
                        <div style={{ fontSize: '12px', color: '#ccc' }}>
                          요청일: {new Date(req.requested_at).toLocaleString('ko-KR')}
                        </div>
                      </div>
                      <span className={`change-status-badge ${changeStatusClass(req.status)}`}>
                        {formatChangeStatus(req.status)}
                      </span>
                    </div>
                    <div className="change-request-body">
                      <div>기존 금액: {req.original_total_amount.toLocaleString()}원</div>
                      <div>새 금액: {req.new_total_amount.toLocaleString()}원</div>
                      {req.change_fee_amount > 0 && (
                        <div>변경 수수료: {req.change_fee_amount.toLocaleString()}원</div>
                      )}
                      {req.requires_additional_payment && (
                        <div className="change-delta charge">
                          추가 결제 예정: {req.extra_charge_amount.toLocaleString()}원
                        </div>
                      )}
                      {req.requires_refund && (
                        <div className="change-delta refund">
                          환불 예정: {req.expected_refund_amount.toLocaleString()}원
                        </div>
                      )}
                      {req.reason && (
                        <div style={{ marginTop: '6px', fontSize: '13px', color: '#bbb' }}>
                          사유: {req.reason}
                        </div>
                      )}
                      {req.admin_comment && (
                        <div style={{ marginTop: '6px', fontSize: '13px', color: '#f7caca' }}>
                          관리자 메모: {req.admin_comment}
                        </div>
                      )}
                      {req.approved_at && (
                        <div style={{ fontSize: '12px', color: '#999', marginTop: '4px' }}>
                          승인일: {new Date(req.approved_at).toLocaleString('ko-KR')}
                        </div>
                      )}
                    </div>
                  </div>
                ))}
              </div>
            )}
            {canModify(changeModalOrder) && (
              <div style={{ marginTop: '16px', display: 'flex', justifyContent: 'flex-end', gap: '8px' }}>
                {/* PENDING 상태의 변경 요청이 있으면 편집 버튼 표시 */}
                {changeRequests.some(req => req.status === 'REQUESTED' || req.status === 'PAYMENT_FAILED' || req.status === 'REFUND_FAILED') ? (
                  <button
                    className="btn btn-primary"
                    onClick={() => {
                      const pendingRequest = changeRequests.find(req => 
                        req.status === 'REQUESTED' || req.status === 'PAYMENT_FAILED' || req.status === 'REFUND_FAILED'
                      );
                      if (pendingRequest) {
                        closeChangeModal();
                        navigate(`/order?modify=${changeModalOrder.id}&editRequest=${pendingRequest.id}`);
                      }
                    }}
                  >
                    ✏️ 변경 요청 편집하기
                  </button>
                ) : (
                  <button
                    className="btn btn-primary"
                    onClick={() => {
                      closeChangeModal();
                      handleModifyOrder(changeModalOrder);
                    }}
                  >
                    새 변경 요청 만들기
                  </button>
                )}
              </div>
            )}
          </div>
        </div>
      )}
    </div>
  );
};

export default Orders;
