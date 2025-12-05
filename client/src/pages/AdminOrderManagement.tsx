import React, { useState, useEffect } from 'react';
import axios from 'axios';
import TopLogo from '../components/TopLogo';
import './Orders.css';

const API_URL = process.env.REACT_APP_API_URL || (window.location.protocol === 'https:' ? '/api' : 'http://localhost:5000/api');

const AdminOrderManagement: React.FC = () => {
  const [orders, setOrders] = useState<any[]>([]);
  const [ordersLoading, setOrdersLoading] = useState(false);
  const [ordersError, setOrdersError] = useState('');
  const [processingOrderId, setProcessingOrderId] = useState<number | null>(null);
  const [detailOrder, setDetailOrder] = useState<any | null>(null);

  useEffect(() => {
    fetchOrders();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const pendingOrders = orders.filter((order: any) =>
    (order.admin_approval_status || '').toUpperCase() !== 'APPROVED' && order.status !== 'cancelled'
  );

  const approvedOrders = orders.filter((order: any) =>
    (order.admin_approval_status || '').toUpperCase() === 'APPROVED'
  );

  const renderOrderCard = (order: any, options?: { showApprovalActions?: boolean }) => {
    const showApprovalActions = options?.showApprovalActions ?? false;
    return (
      <div
        key={order.id}
        className="order-card-modern"
        style={{
          marginBottom: '16px',
          cursor: 'default',
          border: '1px solid var(--border-color)',
          borderRadius: '16px',
          padding: '20px',
          background: 'var(--white)',
          boxShadow: '0 2px 12px rgba(0,0,0,0.3)'
        }}
      >
        <div className="order-card-header">
          <div className="order-card-title">
            <h3>주문 #{order.id}</h3>
            <span className="order-date">
              {new Date(order.delivery_time).toLocaleDateString('ko-KR')}
            </span>
          </div>
          <div className="order-status-group">
            <span className={`approval-badge ${getApprovalClass(order.admin_approval_status)}`}>
              {getApprovalLabel(order.admin_approval_status)}
            </span>
            <span className={`status-badge-modern status-${order.status}`}>
              {order.status}
            </span>
          </div>
        </div>
        <div className="order-card-body">
          <div className="order-info-row">
            <span className="info-icon">👤</span>
            <span className="info-text">{order.customer_name} • {order.customer_phone}</span>
          </div>
          <div className="order-info-row">
            <span className="info-icon">📍</span>
            <span className="info-text">{order.delivery_address}</span>
          </div>
          <div className="order-info-row">
            <span className="info-icon">⏰</span>
            <span className="info-text">{new Date(order.delivery_time).toLocaleString('ko-KR')}</span>
          </div>
          {order.loyalty_discount_applied && (
            <div className="order-info-row" style={{ marginTop: '8px', padding: '10px', background: '#2a3a2a', borderRadius: '8px', border: '1px solid #4aaf4a' }}>
              <span className="info-icon">💰</span>
              <span className="info-text" style={{ color: '#4aaf4a' }}>
                단골 할인 적용: {order.discount_amount?.toLocaleString()}원 할인 ({order.discount_percentage}%)
                {order.original_price && (
                  <span style={{ fontSize: '12px', color: '#ccc', marginLeft: '8px' }}>
                    (원가: {order.original_price.toLocaleString()}원)
                  </span>
                )}
              </span>
            </div>
          )}
        </div>
        <div style={{ display: 'flex', gap: '12px', marginTop: '12px', flexWrap: 'wrap' }}>
          <div style={{ flex: 1, minWidth: '180px', padding: '12px', borderRadius: '12px', border: '1px solid var(--border-color)' }}>
            <div style={{ fontSize: '13px', color: 'var(--text-secondary)' }}>조리 업무</div>
            <div style={{ fontWeight: 600, color: getTaskColor(order.status, 'cooking') }}>● 상태</div>
          </div>
          <div style={{ flex: 1, minWidth: '180px', padding: '12px', borderRadius: '12px', border: '1px solid var(--border-color)' }}>
            <div style={{ fontSize: '13px', color: 'var(--text-secondary)' }}>배달 업무</div>
            <div style={{ fontWeight: 600, color: getTaskColor(order.status, 'delivery') }}>● 상태</div>
          </div>
        </div>
        <div style={{ display: 'flex', gap: '10px', marginTop: '16px', flexWrap: 'wrap' }}>
          {showApprovalActions && (
            <>
              <button
                className="btn btn-primary"
                disabled={processingOrderId === order.id}
                onClick={() => approveOrder(order.id)}
              >
                {processingOrderId === order.id ? '승인 중...' : '승인'}
              </button>
              <button
                className="btn btn-secondary"
                disabled={processingOrderId === order.id}
                onClick={() => rejectOrder(order.id)}
              >
                반려
              </button>
            </>
          )}
          <button
            className="btn btn-secondary"
            disabled={processingOrderId === order.id}
            onClick={() => cancelOrder(order.id)}
          >
            주문 취소
          </button>
          <button
            className="btn btn-secondary"
            style={{ borderStyle: 'dashed' }}
            onClick={() => setDetailOrder(order)}
          >
            세부내역 참조
          </button>
        </div>
      </div>
    );
  };

  const getAuthHeaders = () => {
    const token = localStorage.getItem('token');
    if (!token) {
      throw new Error('관리자 로그인이 필요합니다.');
    }
    return {
      Authorization: `Bearer ${token}`
    };
  };

  const fetchOrders = async () => {
    try {
      setOrdersLoading(true);
      setOrdersError('');
      const headers = getAuthHeaders();
      const response = await axios.get(`${API_URL}/employee/orders`, { headers });
      setOrders(response.data);
    } catch (err: any) {
      setOrdersError(err.response?.data?.error || err.message || '주문 목록을 불러오는데 실패했습니다.');
      setOrders([]);
    } finally {
      setOrdersLoading(false);
    }
  };

  const getApprovalClass = (status?: string) => {
    const normalized = (status || '').toUpperCase();
    if (normalized === 'APPROVED') return 'approved';
    if (normalized === 'REJECTED') return 'rejected';
    if (normalized === 'CANCELLED') return 'cancelled';
    return 'pending';
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
        return '승인 대기';
    }
  };

  const getTaskColor = (status: string, task: 'cooking' | 'delivery') => {
    const normalized = (status || '').toLowerCase();
    if (task === 'cooking') {
      if (normalized === 'ready' || normalized === 'out_for_delivery' || normalized === 'delivered') return '#9e9e9e';
      if (normalized === 'cooking') return '#ff9800';
      return '#ff5252';
    }
    // delivery task
    if (normalized === 'delivered') return '#9e9e9e';
    if (normalized === 'out_for_delivery') return '#ff9800';
    if (normalized === 'ready') return 'rgba(255, 82, 82, 0.5)';
    return '#ff5252';
  };

  const approveOrder = async (orderId: number) => {
    try {
      setProcessingOrderId(orderId);
      const headers = getAuthHeaders();
      await axios.post(`${API_URL}/admin/orders/${orderId}/approve`, {}, { headers });
      await fetchOrders();
    } catch (err: any) {
      alert(err.response?.data?.error || err.message || '주문 승인에 실패했습니다.');
    } finally {
      setProcessingOrderId(null);
    }
  };

  const rejectOrder = async (orderId: number) => {
    try {
      const reason = window.prompt('반려 사유를 입력하세요 (선택)', '') || '';
      setProcessingOrderId(orderId);
      const headers = getAuthHeaders();
      await axios.post(`${API_URL}/admin/orders/${orderId}/reject`, { reason }, { headers });
      await fetchOrders();
    } catch (err: any) {
      alert(err.response?.data?.error || err.message || '주문 반려에 실패했습니다.');
    } finally {
      setProcessingOrderId(null);
    }
  };

  const cancelOrder = async (orderId: number) => {
    if (!window.confirm('해당 주문을 취소하시겠습니까?')) {
      return;
    }
    try {
      setProcessingOrderId(orderId);
      const headers = getAuthHeaders();
      await axios.post(`${API_URL}/employee/orders/${orderId}/cancel`, {}, { headers });
      await fetchOrders();
    } catch (err: any) {
      alert(err.response?.data?.error || err.message || '주문 취소에 실패했습니다.');
    } finally {
      setProcessingOrderId(null);
    }
  };


  return (
    <div className="admin-dashboard">
      <TopLogo showBackButton={false} />
      <div className="container">

        <div className="admin-section">
          <h2>주문 관리 및 작업 할당</h2>
          {ordersError && <div className="error">{ordersError}</div>}
          {ordersLoading ? (
            <div className="loading">주문 목록을 불러오는 중...</div>
          ) : (
            <div style={{ display: 'grid', gap: '24px' }}>
              <section style={{ background: 'var(--white)', border: '1px solid var(--border-color)', borderRadius: '16px', padding: '20px' }}>
                <div style={{ marginBottom: '12px' }}>
                  <h3>승인 대기 중 ({pendingOrders.length})</h3>
                  <p>관리자 승인 전까지 직원에게 노출되지 않습니다.</p>
                </div>
                {pendingOrders.length === 0 ? (
                  <p style={{ padding: '12px 0', color: 'var(--text-secondary)' }}>승인 대기 중인 주문이 없습니다.</p>
                ) : (
                  pendingOrders.map(order => renderOrderCard(order, { showApprovalActions: true }))
                )}
              </section>
              <section style={{ background: 'var(--white)', border: '1px solid var(--border-color)', borderRadius: '16px', padding: '20px' }}>
                <div style={{ marginBottom: '12px' }}>
                  <h3>승인 완료 ({approvedOrders.length})</h3>
                  <p>스케줄 할당 및 직원 대시보드에서 확인됩니다.</p>
                </div>
                {approvedOrders.length === 0 ? (
                  <p style={{ padding: '12px 0', color: 'var(--text-secondary)' }}>승인 완료된 주문이 없습니다.</p>
                ) : (
                  approvedOrders.map(order => renderOrderCard(order))
                )}
              </section>
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
            <p style={{ marginBottom: '10px' }}>고객: {detailOrder.customer_name} • {detailOrder.customer_phone}</p>
            <p style={{ marginBottom: '10px' }}>배달 주소: {detailOrder.delivery_address}</p>
            <p style={{ marginBottom: '10px' }}>배달 시간: {new Date(detailOrder.delivery_time).toLocaleString('ko-KR')}</p>
            {detailOrder.loyalty_discount_applied && (
              <div style={{ marginBottom: '10px', padding: '10px', background: '#2a3a2a', borderRadius: '8px', border: '1px solid #4aaf4a' }}>
                <strong style={{ color: '#4aaf4a' }}>단골 할인 적용:</strong>
                <span style={{ marginLeft: '8px', color: '#fff' }}>
                  {detailOrder.discount_amount?.toLocaleString()}원 할인 ({detailOrder.discount_percentage}%)
                  {detailOrder.original_price && (
                    <span style={{ fontSize: '12px', color: '#ccc', marginLeft: '8px' }}>
                      (원가: {detailOrder.original_price.toLocaleString()}원)
                    </span>
                  )}
                </span>
              </div>
            )}
            <div style={{ borderTop: '1px solid var(--border-color)', paddingTop: '12px', marginTop: '12px' }}>
              <h4>주문 항목</h4>
              <ul>
                {detailOrder.items?.map((item: any) => (
                  <li key={item.id}>
                    {item.name} x {item.quantity}
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
    </div>
  );
};

export default AdminOrderManagement;

