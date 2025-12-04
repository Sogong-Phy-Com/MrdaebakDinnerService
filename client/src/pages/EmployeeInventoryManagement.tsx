import React, { useState, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import axios from 'axios';
import TopLogo from '../components/TopLogo';

const API_URL = process.env.REACT_APP_API_URL || (window.location.protocol === 'https:' ? '/api' : 'http://localhost:5000/api');

interface InventoryItem {
  menu_item_id: number;
  menu_item_name: string;
  menu_item_name_en: string;
  category: string;
  capacity_per_window: number;
  reserved: number;
  remaining: number;
  weekly_reserved?: number;  // 이번주 예약 수량 (consumed=false인 예약만 포함)
  reserved_by_date?: Record<string, number>; // 날짜별 예약 수량 (예: {"12/28": 1, "12/29": 2})
  window_start: string;
  window_end: string;
  notes: string | null;
}

const EmployeeInventoryManagement: React.FC = () => {
  const navigate = useNavigate();
  const [inventory, setInventory] = useState<InventoryItem[]>([]);
  const [inventoryLoading, setInventoryLoading] = useState(false);
  const [error, setError] = useState('');
  const [selectedWeek, setSelectedWeek] = useState<number>(0); // 0 = current week

  useEffect(() => {
    fetchInventory();
  }, [selectedWeek]);

  const fetchInventory = async () => {
    setInventoryLoading(true);
    try {
      const token = localStorage.getItem('token');
      if (!token) {
        setError('로그인이 필요합니다.');
        setInventoryLoading(false);
        return;
      }

      const response = await axios.get(`${API_URL}/inventory`, {
        headers: {
          'Authorization': `Bearer ${token}`
        }
      });
      
      setInventory(response.data);
    } catch (err: any) {
      console.error('[EmployeeInventoryManagement] 재고 목록 조회 실패:', err);
      if (err.response) {
        setError(`재고 목록을 불러오는데 실패했습니다. (상태: ${err.response.status})`);
      } else {
        setError('재고 목록을 불러오는데 실패했습니다.');
      }
    } finally {
      setInventoryLoading(false);
    }
  };


  return (
    <div className="employee-dashboard">
      <TopLogo showBackButton={false} />
      <div className="container">
        <div style={{ marginBottom: '20px' }}>
          <button onClick={() => navigate('/')} className="btn btn-secondary">
            ← 홈으로
          </button>
        </div>

        <h2>재고 관리</h2>
        {error && <div className="error">{error}</div>}
        
        <div style={{ marginBottom: '20px', display: 'flex', gap: '10px', alignItems: 'center' }}>
          <button
            onClick={() => setSelectedWeek(selectedWeek - 1)}
            className="btn btn-secondary"
          >
            이전 주
          </button>
          <span style={{ minWidth: '150px', textAlign: 'center' }}>
            {(() => {
              const today = new Date();
              const weekStart = new Date(today);
              weekStart.setDate(today.getDate() - today.getDay() + (selectedWeek * 7));
              const weekEnd = new Date(weekStart);
              weekEnd.setDate(weekStart.getDate() + 6);
              return `${weekStart.toLocaleDateString('ko-KR')} ~ ${weekEnd.toLocaleDateString('ko-KR')}`;
            })()}
          </span>
          <button
            onClick={() => setSelectedWeek(selectedWeek + 1)}
            className="btn btn-secondary"
          >
            다음 주
          </button>
        </div>
        
        {inventoryLoading ? (
          <div className="loading">로딩 중...</div>
        ) : (
          <div className="inventory-list">
            {inventory.length === 0 ? (
              <div className="no-orders">
                <p>재고 정보가 없습니다.</p>
              </div>
            ) : (
              <table style={{ width: '100%', borderCollapse: 'collapse', marginTop: '20px' }}>
                <thead>
                  <tr style={{ background: '#d4af37', color: '#000' }}>
                    <th style={{ padding: '10px', border: '1px solid #000' }}>메뉴 항목</th>
                    <th style={{ padding: '10px', border: '1px solid #000' }}>카테고리</th>
                    <th style={{ padding: '10px', border: '1px solid #000' }}>현재 보유량</th>
                    <th style={{ padding: '10px', border: '1px solid #000' }}>이 주의 예약 수량</th>
                    <th style={{ padding: '10px', border: '1px solid #000' }}>남은 재고</th>
                    <th style={{ padding: '10px', border: '1px solid #000' }}>보충 수령 가능일</th>
                    <th style={{ padding: '10px', border: '1px solid #000' }}>비고</th>
                  </tr>
                </thead>
                <tbody>
                  {inventory.map((item) => {
                    // 이번주 예약 수량 (weekly_reserved가 있으면 사용, 없으면 reserved 사용)
                    const weeklyReserved = item.weekly_reserved !== undefined ? item.weekly_reserved : (item.reserved || 0);
                    // 남은 재고 = 현재 보유량 - 이번주 예약 수량
                    const availableQuantity = item.capacity_per_window - weeklyReserved;
                    
                    const isDrink = item.category?.toLowerCase() === 'drink';
                    return (
                      <tr key={item.menu_item_id} style={{ 
                        background: availableQuantity < 5 ? '#ffcccc' : 'transparent',
                        borderLeft: isDrink ? '5px solid #4a90e2' : 'none'
                      }}>
                        <td style={{ padding: '10px', border: '1px solid #d4af37' }}>
                          {item.menu_item_name} ({item.menu_item_name_en})
                        </td>
                        <td style={{ padding: '10px', border: '1px solid #d4af37' }}>{item.category}</td>
                        <td style={{ padding: '10px', border: '1px solid #d4af37' }}>{item.capacity_per_window.toLocaleString()}</td>
                        <td style={{ padding: '10px', border: '1px solid #d4af37' }}>
                          {(() => {
                            if (!item.reserved_by_date || Object.keys(item.reserved_by_date).length === 0) {
                              return weeklyReserved.toLocaleString();
                            }
                            // 날짜별 예약 수량 표시 (예: 12/28 `1/3`)
                            const dateEntries = Object.entries(item.reserved_by_date)
                              .filter(([_, count]) => count > 0)
                              .sort(([dateA], [dateB]) => {
                                const [monthA, dayA] = dateA.split('/').map(Number);
                                const [monthB, dayB] = dateB.split('/').map(Number);
                                if (monthA !== monthB) return monthA - monthB;
                                return dayA - dayB;
                              })
                              .map(([date, count]) => `${date} \`${count}/${item.capacity_per_window}\``)
                              .join(', ');
                            return dateEntries || weeklyReserved.toLocaleString();
                          })()}
                        </td>
                        <td style={{ padding: '10px', border: '1px solid #d4af37', fontWeight: availableQuantity < 5 ? 'bold' : 'normal' }}>
                          {availableQuantity.toLocaleString()}
                        </td>
                        <td style={{ padding: '10px', border: '1px solid #d4af37' }}>
                          {isDrink ? '당일 보충' : '월요일, 금요일'}
                        </td>
                        <td style={{ padding: '10px', border: '1px solid #d4af37' }}>
                          {isDrink ? '당일 보충' : (() => {
                            const today = new Date();
                            today.setHours(0, 0, 0, 0);
                            const dayOfWeek = today.getDay(); // 0=일요일, 1=월요일, ..., 6=토요일
                            
                            // 월요일 계산 (1)
                            let daysUntilMonday = (1 - dayOfWeek + 7) % 7;
                            if (daysUntilMonday === 0) daysUntilMonday = 7; // 오늘이 월요일이면 다음 주 월요일
                            const nextMonday = new Date(today);
                            nextMonday.setDate(today.getDate() + daysUntilMonday);
                            
                            // 금요일 계산 (5)
                            let daysUntilFriday = (5 - dayOfWeek + 7) % 7;
                            if (daysUntilFriday === 0) daysUntilFriday = 7; // 오늘이 금요일이면 다음 주 금요일
                            const nextFriday = new Date(today);
                            nextFriday.setDate(today.getDate() + daysUntilFriday);
                            
                            // 가까운 날짜 선택
                            const nextRestockDate = daysUntilMonday <= daysUntilFriday ? nextMonday : nextFriday;
                            const month = nextRestockDate.getMonth() + 1;
                            const date = nextRestockDate.getDate();
                            const dayName = nextRestockDate.getDay() === 1 ? '월요일' : '금요일';
                            
                            return `${month}/${date} ${dayName}`;
                          })()}
                        </td>
                        <td style={{ padding: '10px', border: '1px solid #d4af37' }}>{item.notes || '-'}</td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            )}
          </div>
        )}
      </div>
    </div>
  );
};

export default EmployeeInventoryManagement;

