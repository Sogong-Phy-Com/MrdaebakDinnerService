import React, { useState, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import axios from 'axios';
import { useAuth } from '../contexts/AuthContext';
import TopLogo from '../components/TopLogo';
import './Profile.css';

const API_URL = process.env.REACT_APP_API_URL || (window.location.protocol === 'https:' ? '/api' : 'http://localhost:5000/api');

interface OrderStats {
  totalOrders: number;
  deliveredOrders: number;
  pendingOrders: number;
}

interface ReservedOrder {
  id: number;
  dinner_name: string;
  delivery_time: string;
  delivery_address: string;
  total_price: number;
  status: string;
  admin_approval_status?: string;
}

const Profile: React.FC = () => {
  const { user, logout, updateUser } = useAuth();
  const navigate = useNavigate();
  const [activeTab, setActiveTab] = useState<'info' | 'orders' | 'settings'>('info');
  const [stats, setStats] = useState<OrderStats>({ totalOrders: 0, deliveredOrders: 0, pendingOrders: 0 });
  const [reservedOrders, setReservedOrders] = useState<ReservedOrder[]>([]);
  const [orders, setOrders] = useState<any[]>([]);
  const [ordersLoading, setOrdersLoading] = useState(false);
  const [ordersError, setOrdersError] = useState('');
  
  // 비밀번호 변경 모달
  const [showPasswordModal, setShowPasswordModal] = useState(false);
  const [currentPassword, setCurrentPassword] = useState('');
  const [newPassword, setNewPassword] = useState('');
  const [confirmPassword, setConfirmPassword] = useState('');
  const [passwordError, setPasswordError] = useState('');
  
  // 기본 정보 수정
  const [showEditProfile, setShowEditProfile] = useState(false);
  const [editPassword, setEditPassword] = useState('');
  const [editName, setEditName] = useState(user?.name || '');
  const [editPhone, setEditPhone] = useState(user?.phone || '');
  
  // 카드 정보
  const [showCardModal, setShowCardModal] = useState(false);
  const [cardPassword, setCardPassword] = useState('');
  const [cardNumber, setCardNumber] = useState('');
  const [cardExpiry, setCardExpiry] = useState('');
  const [cardCvv, setCardCvv] = useState('');
  const [cardHolderName, setCardHolderName] = useState('');
  const [userCardInfo, setUserCardInfo] = useState<any>(null);

  useEffect(() => {
    if (activeTab === 'info' && user?.role === 'customer') {
      fetchStats();
      fetchUserCardInfo();
    } else if (activeTab === 'orders') {
      fetchAllOrders();
    }
    if (user) {
      setEditName(user.name || '');
      setEditPhone(user.phone || '');
    }
  }, [activeTab, user?.role, user]);

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

  const fetchStats = async () => {
    try {
      const token = localStorage.getItem('token');
      if (!token) return;

      const response = await axios.get(`${API_URL}/orders/stats`, {
        headers: { 'Authorization': `Bearer ${token}` }
      });
      setStats(response.data);
    } catch (err) {
      console.error('통계 조회 실패:', err);
    }
  };

  const fetchReservedOrders = async () => {
    try {
      const token = localStorage.getItem('token');
      if (!token) return;

      const response = await axios.get(`${API_URL}/orders`, {
        headers: { 'Authorization': `Bearer ${token}` }
      });
      
      // 예약 주문 = 배달 시간이 미래인 주문
      const now = new Date();
      const reserved = response.data.filter((order: any) => {
        const deliveryTime = new Date(order.delivery_time);
        return deliveryTime > now && order.status !== 'delivered' && order.status !== 'cancelled';
      });
      setReservedOrders(reserved);
    } catch (err) {
      console.error('예약 주문 조회 실패:', err);
    }
  };

  const fetchAllOrders = async () => {
    setOrdersLoading(true);
    setOrdersError('');
    try {
      const token = localStorage.getItem('token');
      if (!token) {
        setOrdersError('로그인이 필요합니다.');
        setOrdersLoading(false);
        return;
      }

      const response = await axios.get(`${API_URL}/orders`, {
        headers: { 'Authorization': `Bearer ${token}` }
      });

      if (!Array.isArray(response.data)) {
        setOrdersError('서버 응답 형식이 올바르지 않습니다.');
        setOrdersLoading(false);
        return;
      }

      // Filter only reserved orders (future delivery time)
      const now = new Date();
      const reservedOrders = response.data.filter((order: any) => {
        const deliveryTime = new Date(order.delivery_time);
        return deliveryTime > now && order.status !== 'delivered' && order.status !== 'cancelled';
      });
      
      // dinner_name이 없으면 추가
      const ordersWithDinnerName = await Promise.all(reservedOrders.map(async (order: any) => {
        if (order.dinner_name) {
          return order;
        }
        try {
          const dinnerResponse = await axios.get(`${API_URL}/menu/dinners`, {
            headers: { 'Authorization': `Bearer ${token}` }
          });
          const dinner = dinnerResponse.data.find((d: any) => d.id === order.dinner_type_id);
          return {
            ...order,
            dinner_name: dinner?.name || '알 수 없음'
          };
        } catch {
          return {
            ...order,
            dinner_name: '알 수 없음'
          };
        }
      }));

      setOrders(ordersWithDinnerName);
    } catch (err: any) {
      console.error('주문 목록 조회 실패:', err);
      if (err.response) {
        setOrdersError(`주문 목록을 불러오는데 실패했습니다. (상태: ${err.response.status})`);
      } else {
        setOrdersError('주문 목록을 불러오는데 실패했습니다.');
      }
    } finally {
      setOrdersLoading(false);
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

  const handlePasswordChange = async () => {
    setPasswordError('');
    
    if (!currentPassword || !newPassword || !confirmPassword) {
      setPasswordError('모든 필드를 입력해주세요.');
      return;
    }

    if (newPassword !== confirmPassword) {
      setPasswordError('새 비밀번호가 일치하지 않습니다.');
      return;
    }

    if (newPassword.length < 6) {
      setPasswordError('비밀번호는 최소 6자 이상이어야 합니다.');
      return;
    }

    try {
      const token = localStorage.getItem('token');
      await axios.post(`${API_URL}/auth/change-password`, {
        currentPassword,
        newPassword
      }, {
        headers: { 'Authorization': `Bearer ${token}` }
      });

      alert('비밀번호가 변경되었습니다.');
      setShowPasswordModal(false);
      setCurrentPassword('');
      setNewPassword('');
      setConfirmPassword('');
    } catch (err: any) {
      setPasswordError(err.response?.data?.error || '비밀번호 변경에 실패했습니다.');
    }
  };


  const handleUpdateProfile = async () => {
    if (!editPassword) {
      alert('비밀번호를 입력해주세요.');
      return;
    }

    try {
      const token = localStorage.getItem('token');
      // Verify password first
      await axios.post(`${API_URL}/auth/verify-password`, {
        password: editPassword
      }, {
        headers: { 'Authorization': `Bearer ${token}` }
      });

      // Update profile
      await axios.put(`${API_URL}/auth/update-profile`, {
        name: editName,
        phone: editPhone
      }, {
        headers: { 'Authorization': `Bearer ${token}` }
      });

      if (user) {
        updateUser({ ...user, name: editName, phone: editPhone });
      }
      alert('기본 정보가 수정되었습니다.');
      setShowEditProfile(false);
      setEditPassword('');
    } catch (err: any) {
      if (err.response?.status === 401) {
        alert('비밀번호가 올바르지 않습니다.');
      } else {
        alert('기본 정보 수정에 실패했습니다.');
      }
    }
  };

  const showCustomerService = () => {
    alert('고객센터\n\n전화: 1588-0000\n이메일: support@mrdabak.com\n운영시간: 평일 09:00 - 18:00');
  };

  const showTerms = () => {
    alert('이용약관\n\n제1조 (목적)\n본 약관은 미스터 대박이 제공하는 서비스의 이용과 관련하여 회사와 이용자 간의 권리, 의무 및 책임사항을 규정함을 목적으로 합니다.\n\n제2조 (정의)\n1. "서비스"란 회사가 제공하는 디너 배달 서비스를 의미합니다.\n2. "이용자"란 본 약관에 동의하고 서비스를 이용하는 회원 및 비회원을 의미합니다.\n\n제3조 (약관의 효력 및 변경)\n1. 본 약관은 서비스 화면에 게시하거나 기타의 방법으로 이용자에게 공지함으로써 효력이 발생합니다.\n2. 회사는 필요한 경우 관련 법령을 위배하지 않는 범위에서 본 약관을 변경할 수 있습니다.');
  };

  const handleReorder = (order: ReservedOrder, e?: React.MouseEvent<HTMLButtonElement>) => {
    if (e) {
      e.stopPropagation();
    }
    navigate('/order', { state: { reorderOrder: order } });
  };

  return (
    <div className="profile-page">
      <TopLogo showBackButton={false} />

      <div className="page-content">
        <div className="container">
          <div style={{ marginBottom: '20px' }}>
            <button onClick={() => navigate('/')} className="btn btn-secondary">
              ← 홈으로
            </button>
          </div>
          {/* 프로필 헤더 */}
          <div className="profile-header">
            <div className="profile-avatar">
              <span className="avatar-icon">👤</span>
            </div>
            <div className="profile-info">
              <h2>{user?.name || '사용자'}</h2>
              <p className="profile-email">{user?.email}</p>
              <span className="profile-badge">
                {user?.role === 'admin' ? '관리자 계정' : 
                 user?.role === 'employee' ? '직원 계정' : 
                 '고객 계정'}
              </span>
            </div>
          </div>

          {/* 탭 메뉴 */}
          <div className="profile-tabs">
            <button
              className={`tab-button ${activeTab === 'info' ? 'active' : ''}`}
              onClick={() => setActiveTab('info')}
            >
              내 정보
            </button>
            {user?.role === 'customer' && (
              <button
                className={`tab-button ${activeTab === 'orders' ? 'active' : ''}`}
                onClick={() => setActiveTab('orders')}
              >
                주문 내역
              </button>
            )}
            {user?.role === 'customer' && (
              <button
                className={`tab-button ${activeTab === 'settings' ? 'active' : ''}`}
                onClick={() => setActiveTab('settings')}
              >
                설정
              </button>
            )}
          </div>

          {/* 탭 컨텐츠 */}
          <div className="tab-content">
            {activeTab === 'info' && (
              <div className="info-section">
                {user?.role === 'customer' ? (
                  <>
                    <div className="card">
                      <h3 className="card-title">기본 정보</h3>
                      <div className="info-item">
                        <span className="info-label">이름</span>
                        <span className="info-value">{user?.name || '-'}</span>
                      </div>
                      <div className="info-item">
                        <span className="info-label">이메일</span>
                        <span className="info-value">{user?.email || '-'}</span>
                      </div>
                      <div className="info-item">
                        <span className="info-label">전화번호</span>
                        <span className="info-value">{user?.phone || '-'}</span>
                      </div>
                      <div className="info-item">
                        <span className="info-label">주소</span>
                        <span className="info-value">{user?.address || '-'}</span>
                      </div>
                      <div style={{ marginTop: '20px', paddingTop: '20px', borderTop: '1px solid #d4af37' }}>
                        <button
                          className="btn btn-primary"
                          style={{ width: '100%' }}
                          onClick={() => setShowEditProfile(true)}
                        >
                          내 정보 변경
                        </button>
                      </div>
                    </div>

                    <div className="card">
                      <h3 className="card-title">카드 정보</h3>
                      {userCardInfo?.hasCard ? (
                        <div className="info-item">
                          <span className="info-label">카드 번호</span>
                          <span className="info-value">{userCardInfo.cardNumber || '등록됨'}</span>
                        </div>
                      ) : (
                        <div className="info-item">
                          <span className="info-label">카드 정보</span>
                          <span className="info-value" style={{ color: '#ff4444' }}>등록되지 않음</span>
                        </div>
                      )}
                      <div style={{ marginTop: '20px', paddingTop: '20px', borderTop: '1px solid #d4af37' }}>
                        <button
                          className="btn btn-primary"
                          style={{ width: '100%' }}
                          onClick={() => {
                            // 모달 열 때 상태 초기화
                            setCardPassword('');
                            setCardNumber('');
                            setCardExpiry('');
                            setCardCvv('');
                            setCardHolderName('');
                            setShowCardModal(true);
                          }}
                        >
                          {userCardInfo?.hasCard ? '카드 정보 변경' : '카드 정보 등록'}
                        </button>
                      </div>
                    </div>

                    <div className="card">
                      <h3 className="card-title">주문 통계</h3>
                      <div className="stats-grid">
                        <div className="stat-item">
                          <div className="stat-value">{stats.totalOrders}</div>
                          <div className="stat-label">총 주문</div>
                        </div>
                        <div className="stat-item">
                          <div className="stat-value">{stats.deliveredOrders}</div>
                          <div className="stat-label">배달 완료</div>
                        </div>
                        <div className="stat-item">
                          <div className="stat-value">{stats.pendingOrders}</div>
                          <div className="stat-label">대기 중</div>
                        </div>
                      </div>
                    </div>
                    <div className="card">
                      <h3 className="card-title">개인정보 동의 현황</h3>
                      <ul className="consent-list">
                        <li>
                          <span>이름 사용</span>
                          <label style={{ display: 'flex', alignItems: 'center', gap: '8px', cursor: 'pointer' }}>
                            <input
                              type="checkbox"
                              checked={user?.consentName || false}
                              onChange={async (e) => {
                                try {
                                  const token = localStorage.getItem('token');
                                  const response = await axios.patch(`${API_URL}/auth/me/consent`, 
                                    { consentName: e.target.checked },
                                    { headers: { 'Authorization': `Bearer ${token}` } }
                                  );
                                  if (response.data) {
                                    updateUser({ ...user, consentName: response.data.consentName });
                                    alert('개인정보 동의 현황이 업데이트되었습니다.');
                                  }
                                } catch (err: any) {
                                  alert(err.response?.data?.error || '업데이트에 실패했습니다.');
                                }
                              }}
                            />
                            <strong>{user?.consentName ? '동의' : '비동의'}</strong>
                          </label>
                        </li>
                        <li>
                          <span>주소 저장</span>
                          <label style={{ display: 'flex', alignItems: 'center', gap: '8px', cursor: 'pointer' }}>
                            <input
                              type="checkbox"
                              checked={user?.consentAddress || false}
                              onChange={async (e) => {
                                try {
                                  const token = localStorage.getItem('token');
                                  const response = await axios.patch(`${API_URL}/auth/me/consent`, 
                                    { consentAddress: e.target.checked },
                                    { headers: { 'Authorization': `Bearer ${token}` } }
                                  );
                                  if (response.data) {
                                    updateUser({ ...user, consentAddress: response.data.consentAddress });
                                    alert('개인정보 동의 현황이 업데이트되었습니다.');
                                  }
                                } catch (err: any) {
                                  alert(err.response?.data?.error || '업데이트에 실패했습니다.');
                                }
                              }}
                            />
                            <strong>{user?.consentAddress ? '동의' : '비동의'}</strong>
                          </label>
                        </li>
                        <li>
                          <span>연락처 알림</span>
                          <label style={{ display: 'flex', alignItems: 'center', gap: '8px', cursor: 'pointer' }}>
                            <input
                              type="checkbox"
                              checked={user?.consentPhone || false}
                              onChange={async (e) => {
                                try {
                                  const token = localStorage.getItem('token');
                                  const response = await axios.patch(`${API_URL}/auth/me/consent`, 
                                    { consentPhone: e.target.checked },
                                    { headers: { 'Authorization': `Bearer ${token}` } }
                                  );
                                  if (response.data) {
                                    updateUser({ ...user, consentPhone: response.data.consentPhone });
                                    alert('개인정보 동의 현황이 업데이트되었습니다.');
                                  }
                                } catch (err: any) {
                                  alert(err.response?.data?.error || '업데이트에 실패했습니다.');
                                }
                              }}
                            />
                            <strong>{user?.consentPhone ? '동의' : '비동의'}</strong>
                          </label>
                        </li>
                        <li>
                          <span>단골 할인 안내</span>
                          <label style={{ display: 'flex', alignItems: 'center', gap: '8px', cursor: 'pointer' }}>
                            <input
                              type="checkbox"
                              checked={user?.loyaltyConsent || false}
                              onChange={async (e) => {
                                try {
                                  const token = localStorage.getItem('token');
                                  const response = await axios.patch(`${API_URL}/auth/me/consent`, 
                                    { loyaltyConsent: e.target.checked },
                                    { headers: { 'Authorization': `Bearer ${token}` } }
                                  );
                                  if (response.data) {
                                    updateUser({ ...user, loyaltyConsent: response.data.loyaltyConsent });
                                    alert('개인정보 동의 현황이 업데이트되었습니다.');
                                  }
                                } catch (err: any) {
                                  alert(err.response?.data?.error || '업데이트에 실패했습니다.');
                                }
                              }}
                            />
                            <strong>{user?.loyaltyConsent ? '동의' : '비동의'}</strong>
                          </label>
                        </li>
                      </ul>
                      <div className={`loyalty-message ${user?.loyaltyConsent ? 'success' : 'muted'}`}>
                        {user?.loyaltyConsent
                          ? '단골 할인 안내 동의 완료! 배달 완료 5회 이상부터 10% 할인 혜택이 적용됩니다.'
                          : '단골 할인 혜택을 받으려면 "단골 할인 안내 동의" 설정이 필요합니다.'}
                      </div>
                    </div>
                  </>
                ) : (
                  <div className="card">
                    <h3 className="card-title">기본 정보</h3>
                    <div className="info-item">
                      <span className="info-label">이름</span>
                      <span className="info-value">{user?.name || '-'}</span>
                    </div>
                    <div className="info-item">
                      <span className="info-label">이메일</span>
                      <span className="info-value">{user?.email || '-'}</span>
                    </div>
                    <div className="info-item">
                      <span className="info-label">전화번호</span>
                      <span className="info-value">{user?.phone || '-'}</span>
                    </div>
                  </div>
                )}
              </div>
            )}

            {activeTab === 'orders' && user?.role === 'customer' && (
              <div className="orders-section">
                {ordersLoading ? (
                  <div className="loading">로딩 중...</div>
                ) : ordersError ? (
                  <div className="error">{ordersError}</div>
                ) : orders.length === 0 ? (
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
                            <span className={`status-badge-modern status-${order.status}`}>
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
                        </div>

                        <div className="order-card-footer">
                          <div className="order-total-modern">
                            {order.total_price.toLocaleString()}원
                          </div>
                        </div>

                        <div className="order-action" style={{ display: 'flex', gap: '10px', flexWrap: 'wrap', marginTop: '12px' }}>
                          <button
                            className="btn btn-primary"
                            style={{ flex: 1, minWidth: '140px' }}
                            onClick={(e) => {
                              e.stopPropagation();
                              navigate(`/delivery/${order.id}`);
                            }}
                          >
                            주문 상세
                          </button>
                          <button
                            className="btn btn-outline"
                            style={{ flex: 1, minWidth: '140px' }}
                            onClick={(e) => handleReorder(order, e)}
                          >
                            재주문
                          </button>
                        </div>
                      </div>
                    ))}
                  </div>
                )}
              </div>
            )}

            {activeTab === 'settings' && (
              <div className="settings-section">
                <div className="card">
                  <h3 className="card-title">계정 설정</h3>
                  <button
                    className="btn btn-secondary"
                    style={{ width: '100%', marginBottom: '12px' }}
                    onClick={() => setShowPasswordModal(true)}
                  >
                    비밀번호 변경
                  </button>
                </div>

                <div className="card">
                  <h3 className="card-title">기타</h3>
                  <button
                    className="btn btn-secondary"
                    style={{ width: '100%', marginBottom: '12px' }}
                    onClick={showCustomerService}
                  >
                    고객센터
                  </button>
                  <button
                    className="btn btn-secondary"
                    style={{ width: '100%', marginBottom: '12px' }}
                    onClick={showTerms}
                  >
                    이용약관
                  </button>
                  <button
                    className="btn btn-secondary"
                    style={{ width: '100%', color: 'var(--error)' }}
                    onClick={logout}
                  >
                    로그아웃
                  </button>
                </div>
              </div>
            )}
          </div>
        </div>
      </div>

      {/* 비밀번호 변경 모달 */}
      {showPasswordModal && (
        <div className="modal-overlay" onClick={() => setShowPasswordModal(false)}>
          <div className="modal-content" onClick={(e) => e.stopPropagation()}>
            <h3>비밀번호 변경</h3>
            <div className="form-group">
              <label>현재 비밀번호</label>
              <input
                type="password"
                value={currentPassword}
                onChange={(e) => setCurrentPassword(e.target.value)}
              />
            </div>
            <div className="form-group">
              <label>새 비밀번호</label>
              <input
                type="password"
                value={newPassword}
                onChange={(e) => setNewPassword(e.target.value)}
              />
            </div>
            <div className="form-group">
              <label>새 비밀번호 확인</label>
              <input
                type="password"
                value={confirmPassword}
                onChange={(e) => setConfirmPassword(e.target.value)}
              />
            </div>
            {passwordError && <div className="error">{passwordError}</div>}
            <div style={{ display: 'flex', gap: '12px', marginTop: '20px' }}>
              <button className="btn btn-secondary" onClick={() => setShowPasswordModal(false)}>
                취소
              </button>
              <button className="btn btn-primary" onClick={handlePasswordChange}>
                변경
              </button>
            </div>
          </div>
        </div>
      )}

      {/* 내 정보 변경 모달 */}
      {showEditProfile && (
        <div className="modal-overlay" onClick={() => setShowEditProfile(false)}>
          <div className="modal-content" onClick={(e) => e.stopPropagation()}>
            <h3>내 정보 변경</h3>
            <p style={{ color: '#FFD700', marginBottom: '20px' }}>정보를 변경하려면 비밀번호를 입력해주세요.</p>
            <div className="form-group">
              <label>비밀번호 확인</label>
              <input
                type="password"
                value={editPassword}
                onChange={(e) => setEditPassword(e.target.value)}
                placeholder="비밀번호를 입력하세요"
              />
            </div>
            <div className="form-group">
              <label>이름</label>
              <input
                type="text"
                value={editName}
                onChange={(e) => setEditName(e.target.value)}
                disabled={!editPassword}
              />
            </div>
            <div className="form-group">
              <label>전화번호</label>
              <input
                type="text"
                value={editPhone}
                onChange={(e) => setEditPhone(e.target.value)}
                disabled={!editPassword}
              />
            </div>
            <div style={{ display: 'flex', gap: '12px', marginTop: '20px' }}>
              <button className="btn btn-secondary" onClick={() => {
                setShowEditProfile(false);
                setEditPassword('');
                setEditName(user?.name || '');
                setEditPhone(user?.phone || '');
              }}>
                취소
              </button>
              <button className="btn btn-primary" onClick={handleUpdateProfile} disabled={!editPassword}>
                변경
              </button>
            </div>
          </div>
        </div>
      )}

      {/* 카드 정보 입력 모달 */}
      {showCardModal && (
        <div className="modal-overlay" onClick={() => {
          setShowCardModal(false);
          setCardPassword('');
          setCardNumber('');
          setCardExpiry('');
          setCardCvv('');
          setCardHolderName('');
        }}>
          <div className="modal-content" onClick={(e) => e.stopPropagation()} style={{ maxWidth: '500px' }}>
            <h3>카드 정보 {userCardInfo?.hasCard ? '변경' : '등록'}</h3>
            <p style={{ color: '#FFD700', marginBottom: '20px' }}>카드 정보를 {userCardInfo?.hasCard ? '변경' : '등록'}하려면 비밀번호를 입력해주세요.</p>
            <div className="form-group">
              <label>비밀번호 확인</label>
              <input
                type="password"
                value={cardPassword}
                onChange={(e) => setCardPassword(e.target.value)}
                placeholder="비밀번호를 입력하세요"
                autoFocus
                onKeyDown={(e) => {
                  if (e.key === 'Enter' && cardPassword) {
                    // Enter 키로 다음 필드로 이동하지 않고, 비밀번호 입력 후 자동으로 활성화
                    const nextInput = e.currentTarget.parentElement?.nextElementSibling?.querySelector('input');
                    if (nextInput && cardPassword) {
                      nextInput.focus();
                    }
                  }
                }}
              />
            </div>
            <div className="form-group">
              <label>카드 번호</label>
              <input
                type="text"
                value={cardNumber}
                onChange={(e) => {
                  const value = e.target.value.replace(/\D/g, '').slice(0, 16);
                  setCardNumber(value);
                }}
                placeholder="1234 5678 9012 3456"
                disabled={!cardPassword}
                maxLength={16}
                onKeyDown={(e) => {
                  if (e.key === 'Enter' && cardPassword && cardNumber.length >= 16) {
                    const nextInput = e.currentTarget.parentElement?.nextElementSibling?.querySelector('input');
                    if (nextInput) {
                      nextInput.focus();
                    }
                  }
                }}
              />
            </div>
            <div style={{ display: 'flex', gap: '12px' }}>
              <div className="form-group" style={{ flex: 1 }}>
                <label>만료일 (MM/YY)</label>
                <input
                  type="text"
                  value={cardExpiry}
                  onChange={(e) => {
                    let value = e.target.value.replace(/\D/g, '');
                    if (value.length >= 2) {
                      value = value.slice(0, 2) + '/' + value.slice(2, 4);
                    }
                    setCardExpiry(value);
                  }}
                  placeholder="MM/YY"
                  disabled={!cardPassword}
                  maxLength={5}
                />
              </div>
              <div className="form-group" style={{ flex: 1 }}>
                <label>CVV</label>
                <input
                  type="text"
                  value={cardCvv}
                  onChange={(e) => {
                    const value = e.target.value.replace(/\D/g, '').slice(0, 3);
                    setCardCvv(value);
                  }}
                  placeholder="123"
                  disabled={!cardPassword}
                  maxLength={3}
                />
              </div>
            </div>
            <div className="form-group">
              <label>카드 소유자 이름</label>
              <input
                type="text"
                value={cardHolderName}
                onChange={(e) => setCardHolderName(e.target.value)}
                placeholder="홍길동"
                disabled={!cardPassword}
              />
            </div>
            <div style={{ display: 'flex', gap: '12px', marginTop: '20px' }}>
              <button className="btn btn-secondary" onClick={() => {
                setShowCardModal(false);
                setCardPassword('');
                setCardNumber('');
                setCardExpiry('');
                setCardCvv('');
                setCardHolderName('');
              }}>
                취소
              </button>
              <button className="btn btn-primary" onClick={async () => {
                if (!cardPassword) {
                  alert('비밀번호를 입력해주세요.');
                  return;
                }
                if (!cardNumber || cardNumber.length < 16) {
                  alert('카드 번호를 올바르게 입력해주세요. (16자리)');
                  return;
                }
                if (!cardExpiry || cardExpiry.length < 5) {
                  alert('만료일을 올바르게 입력해주세요. (MM/YY 형식)');
                  return;
                }
                if (!cardCvv || cardCvv.length < 3) {
                  alert('CVV를 올바르게 입력해주세요. (3자리)');
                  return;
                }
                if (!cardHolderName || cardHolderName.trim() === '') {
                  alert('카드 소유자 이름을 입력해주세요.');
                  return;
                }

                try {
                  const token = localStorage.getItem('token');
                  if (!token) {
                    alert('로그인이 필요합니다.');
                    return;
                  }

                  // Verify password first
                  await axios.post(`${API_URL}/auth/verify-password`, {
                    password: cardPassword
                  }, {
                    headers: { 'Authorization': `Bearer ${token}` }
                  });

                  // Update card information
                  await axios.put(`${API_URL}/auth/update-card`, {
                    cardNumber: cardNumber.trim(),
                    cardExpiry: cardExpiry.trim(),
                    cardCvv: cardCvv.trim(),
                    cardHolderName: cardHolderName.trim()
                  }, {
                    headers: { 'Authorization': `Bearer ${token}` }
                  });

                  alert('카드 정보가 저장되었습니다.');
                  setShowCardModal(false);
                  setCardPassword('');
                  setCardNumber('');
                  setCardExpiry('');
                  setCardCvv('');
                  setCardHolderName('');
                  await fetchUserCardInfo();
                  if (user) {
                    updateUser({ ...user, hasCard: true });
                  }
                } catch (err: any) {
                  if (err.response?.status === 401) {
                    alert('비밀번호가 올바르지 않습니다.');
                    setCardPassword('');
                  } else {
                    const errorMsg = err.response?.data?.error || '카드 정보 저장에 실패했습니다.';
                    alert(errorMsg);
                  }
                }
              }} disabled={!cardPassword || !cardNumber || !cardExpiry || !cardCvv || !cardHolderName}>
                {userCardInfo?.hasCard ? '변경' : '등록'}
              </button>
            </div>
          </div>
        </div>
      )}


    </div>
  );
};

export default Profile;
