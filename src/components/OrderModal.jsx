import React, { useState, useEffect } from 'react';
import { 
  X, 
  ArrowUpRight, 
  ArrowDownLeft, 
  ShieldCheck, 
  CheckCircle2, 
  AlertCircle, 
  Clock3,
  AlertTriangle,
  XCircle,
  Wallet, 
  TrendingUp, 
  Minus, 
  Plus,
  Sparkles
} from 'lucide-react';
import { executeOrder } from '../utils/storage';

const won = (x) => Math.round(x).toLocaleString('ko-KR');

export default function OrderModal({ 
  isOpen, 
  onClose, 
  stock, 
  currentUser, 
  onOrderSuccess,
  initialOrderType = 'BUY',
  initialPrice = null 
}) {
  const [orderType, setOrderType] = useState(initialOrderType); // 'BUY' or 'SELL'
  const [pricingMode, setPricingMode] = useState('LIMIT'); // 'LIMIT' or 'MARKET'
  const [price, setPrice] = useState(stock ? stock.price : 70000);
  const [quantity, setQuantity] = useState(1);
  const [errorMsg, setErrorMsg] = useState('');
  const [isSuccess, setIsSuccess] = useState(false);
  const [lastExecutedOrder, setLastExecutedOrder] = useState(null);

  useEffect(() => {
    if (stock) {
      setPrice(initialPrice || stock.price);
      setQuantity(1);
      setErrorMsg('');
      setIsSuccess(false);
      setOrderType(initialOrderType);
    }
  }, [stock, isOpen, initialOrderType, initialPrice]);

  if (!isOpen || !stock) return null;

  const currentHolding = currentUser?.portfolio?.[stock.code] || { quantity: 0, avgPrice: 0 };
  const effectivePrice = pricingMode === 'MARKET' ? stock.price : Number(price) || stock.price;
  const totalAmount = effectivePrice * (Number(quantity) || 0);

  // 최대 매수 가능 수량
  const maxBuyQty = Math.max(0, Math.floor((currentUser?.balance || 0) / effectivePrice));
  // 최대 매도 가능 수량
  const maxSellQty = currentHolding.quantity || 0;

  // 비율 퀵 버튼 클릭
  const handleQuickPercent = (pct) => {
    setErrorMsg('');
    if (orderType === 'BUY') {
      const targetQty = Math.floor(maxBuyQty * (pct / 100));
      setQuantity(Math.max(1, targetQty));
    } else {
      const targetQty = Math.floor(maxSellQty * (pct / 100));
      setQuantity(Math.max(1, targetQty));
    }
  };

  // 수량 증감
  const adjustQty = (delta) => {
    setQuantity((prev) => Math.max(1, Number(prev || 1) + delta));
  };

  // 가격 증감
  const adjustPrice = (delta) => {
    if (pricingMode === 'MARKET') return;
    setPrice((prev) => Math.max(100, Number(prev || stock.price) + delta));
  };

  // 주문 실행
  const handleExecute = (e) => {
    e.preventDefault();
    setErrorMsg('');

    if (!currentUser) {
      setErrorMsg('주문하려면 먼저 로그인해야 합니다.');
      return;
    }

    if (quantity <= 0) {
      setErrorMsg('수량은 1주 이상이어야 합니다.');
      return;
    }

    if (orderType === 'BUY' && totalAmount > (currentUser.balance || 0)) {
      setErrorMsg(`주문 가능 금액이 부족합니다. (필요: ₩${won(totalAmount)}, 잔고: ₩${won(currentUser.balance)})`);
      return;
    }

    if (orderType === 'SELL' && quantity > maxSellQty) {
      setErrorMsg(`매도 가능 수량이 부족합니다. (보유: ${maxSellQty}주, 요청: ${quantity}주)`);
      return;
    }

    const res = executeOrder({
      userId: currentUser.id,
      stockCode: stock.code,
      stockName: stock.name,
      orderType,
      price: effectivePrice,
      quantity: Number(quantity)
    });

    if (res.success) {
      setLastExecutedOrder(res.order);
      setIsSuccess(true);
      if (onOrderSuccess) {
        onOrderSuccess(res.user, res.message);
      }
    } else {
      setErrorMsg(res.message);
    }
  };

  return (
    <div className="overlay" onClick={onClose} role="dialog" aria-modal="true" aria-labelledby="order-title">
      <div className="order-modal panel" onClick={(e) => e.stopPropagation()}>
        <button className="modal-x" onClick={onClose} aria-label="닫기">
          <X size={20} />
        </button>

        {/* 상단 종목 헤더 */}
        <div className="order-stock-header">
          <div className="order-stock-meta">
            <span
              className="company-logo"
              style={{ background: stock.color + '25', color: stock.color }}
            >
              {stock.name.slice(0, 1)}
            </span>
            <div>
              <h2 id="order-title">{stock.name} <small>{stock.code}</small></h2>
              <div className="order-curr-price">
                <strong>₩{won(stock.price)}</strong>
                <span className={stock.change >= 0 ? 'rise' : 'fall'}>
                  {stock.change >= 0 ? <ArrowUpRight size={13} /> : <ArrowDownLeft size={13} />}
                  {stock.change >= 0 ? '+' : ''}{stock.change.toFixed(2)}%
                </span>
              </div>
            </div>
          </div>

          <div className="order-fds-tag">
            <ShieldCheck size={14} /> FDS 실시간 검증 대상
          </div>
        </div>

        {/* 매수 / 매도 탭 */}
        <div className="order-type-tabs">
          <button
            type="button"
            className={`order-tab-btn buy ${orderType === 'BUY' ? 'active' : ''}`}
            onClick={() => {
              setOrderType('BUY');
              setErrorMsg('');
              setIsSuccess(false);
            }}
          >
            매수 (BUY)
          </button>
          <button
            type="button"
            className={`order-tab-btn sell ${orderType === 'SELL' ? 'active' : ''}`}
            onClick={() => {
              setOrderType('SELL');
              setErrorMsg('');
              setIsSuccess(false);
            }}
          >
            매도 (SELL)
          </button>
        </div>

        {isSuccess && lastExecutedOrder ? (
          /* 주문 처리 결과 화면 (EXECUTED / HOLD / REJECTED / CANCELLED 상태별 명확한 분기) */
        (() => {
          const status = lastExecutedOrder.status || (
            lastExecutedOrder.fdsStatus?.includes('보류') ? 'HOLD' :
            (lastExecutedOrder.fdsStatus?.includes('차단') || lastExecutedOrder.fdsStatus?.includes('거절')) ? 'REJECTED' : 'EXECUTED'
          );
          const orderType = lastExecutedOrder.orderType || lastExecutedOrder.order_type || 'BUY';
          const stockName = lastExecutedOrder.stockName || lastExecutedOrder.stock_name || stock?.name || '';
          const quantity = Number(lastExecutedOrder.quantity || 1);
          const price = Number(lastExecutedOrder.price || 0);
          const totalCost = Number(lastExecutedOrder.totalCost ?? lastExecutedOrder.total_cost ?? (price * quantity));
          const fdsStatus = lastExecutedOrder.fdsStatus || lastExecutedOrder.fds_status || 'FDS 분석 완료';
          const rejectionReason = lastExecutedOrder.rejectionReason || lastExecutedOrder.rejection_reason || '';

          return (
            <div className={`order-result-box ${status.toLowerCase()}`}>
              {status === 'EXECUTED' && (
                <>
                  <div className="order-result-icon executed">
                    <CheckCircle2 size={44} />
                  </div>
                  <h3 className="order-result-title text-green">모의 주문 체결 완료!</h3>
                  <p className="order-result-msg">
                    <strong>{stockName}</strong> {quantity}주{' '}
                    <b className={orderType === 'BUY' ? 'rise' : 'fall'}>
                      {orderType === 'BUY' ? '매수' : '매도'}
                    </b>{' '}
                    체결되었습니다.
                  </p>
                </>
              )}

              {status === 'HOLD' && (
                <>
                  <div className="order-result-icon hold">
                    <Clock3 size={44} />
                  </div>
                  <h3 className="order-result-title text-amber">주문 접수 완료 (FDS 심사 보류)</h3>
                  <p className="order-result-msg">
                    <strong>{stockName}</strong> {quantity}주{' '}
                    <b className={orderType === 'BUY' ? 'rise' : 'fall'}>
                      {orderType === 'BUY' ? '매수' : '매도'}
                    </b>{' '}
                    주문이 <strong>FDS 심사 보류</strong> 상태로 접수되었습니다.
                  </p>
                  <div className="order-status-notice hold">
                    <strong>⚠️ FDS 이상거래 심사 대기 안내</strong>
                    <p>
                      {rejectionReason || '단시간 대량 주문 또는 이상거래 패턴이 감지되어 관리자 승인 대기 중입니다. 보안관제팀 확인 후 즉시 체결됩니다. (접수 30분 미처리 시 자동 취소)'}
                    </p>
                  </div>
                </>
              )}

              {status === 'REJECTED' && (
                <>
                  <div className="order-result-icon rejected">
                    <AlertTriangle size={44} />
                  </div>
                  <h3 className="order-result-title text-red">FDS 위험 주문 차단 (주문 거절)</h3>
                  <p className="order-result-msg">
                    <strong>{stockName}</strong> {quantity}주{' '}
                    <b className={orderType === 'BUY' ? 'rise' : 'fall'}>
                      {orderType === 'BUY' ? '매수' : '매도'}
                    </b>{' '}
                    주문이 <strong>FDS 고위험 차단</strong>되었습니다.
                  </p>
                  <div className="order-status-notice rejected">
                    <strong>⛔ 주문 차단 및 계좌 잠금 안내</strong>
                    <p>
                      {rejectionReason || 'FDS 위험점수 초과(80점 이상)로 주문이 차단되었으며 안전을 위해 계좌가 긴급 잠금되었습니다. 관제팀 소명 후 해제 가능합니다.'}
                    </p>
                  </div>
                </>
              )}

              {status === 'CANCELLED' && (
                <>
                  <div className="order-result-icon cancelled">
                    <XCircle size={44} />
                  </div>
                  <h3 className="order-result-title text-gray">주문 취소 및 만료</h3>
                  <p className="order-result-msg">
                    <strong>{stockName}</strong> {quantity}주 주문이 취소 또는 시간 초과로 만료되었습니다.
                  </p>
                </>
              )}

              <div className="order-summary-table">
                <div>
                  <span>주문 상태</span>
                  <span className={`order-status-tag ${status.toLowerCase()}`}>
                    {status === 'EXECUTED' && '체결 완료 (EXECUTED)'}
                    {status === 'HOLD' && '심사 보류 (HOLD)'}
                    {status === 'REJECTED' && '주문 거절 (REJECTED)'}
                    {status === 'CANCELLED' && '주문 취소 (CANCELLED)'}
                  </span>
                </div>
                <div>
                  <span>{status === 'EXECUTED' ? '체결 단가' : '접수 단가'}</span>
                  <b>₩{won(price)}</b>
                </div>
                <div>
                  <span>{status === 'EXECUTED' ? '체결 수량' : '주문 수량'}</span>
                  <b>{quantity}주</b>
                </div>
                <div>
                  <span>{status === 'EXECUTED' ? '총 체결 금액' : '주문 금액'}</span>
                  <b className="accent-price">₩{won(totalCost)}</b>
                </div>
                <div>
                  <span>FDS 보안 상태</span>
                  <span className="fds-badge-pill">
                    <ShieldCheck size={12} /> {fdsStatus}
                  </span>
                </div>
              </div>

                <div className="modal-actions" style={{ marginTop: '20px' }}>
                  <button
                    type="button"
                    className="secondary-btn"
                    style={{ flex: 1 }}
                    onClick={() => setIsSuccess(false)}
                  >
                    추가 주문하기
                  </button>
                  <button
                    type="button"
                    className="primary-btn"
                    style={{ flex: 1 }}
                    onClick={onClose}
                  >
                    확인
                  </button>
                </div>
              </div>
            );
          })()
        ) : (
          /* 주문 입력 폼 */
          <form onSubmit={handleExecute} className="order-form-body">
            {errorMsg && (
              <div className="form-alert error">
                <AlertCircle size={15} />
                <span>{errorMsg}</span>
              </div>
            )}

            {/* 주문 구분: 지정가 vs 시장가 */}
            <div className="order-mode-row">
              <span className="mode-label">주문 구분</span>
              <div className="mode-selector">
                <button
                  type="button"
                  className={pricingMode === 'LIMIT' ? 'active' : ''}
                  onClick={() => setPricingMode('LIMIT')}
                >
                  지정가
                </button>
                <button
                  type="button"
                  className={pricingMode === 'MARKET' ? 'active' : ''}
                  onClick={() => {
                    setPricingMode('MARKET');
                    setPrice(stock.price);
                  }}
                >
                  시장가
                </button>
              </div>
            </div>

            {/* 주문 단가 */}
            <div className="order-field-group">
              <div className="field-title-row">
                <label htmlFor="order-price">주문 단가 (KRW)</label>
                {pricingMode === 'MARKET' && (
                  <span className="market-note">현재가로 즉시 체결</span>
                )}
              </div>
              <div className="stepper-input-wrap">
                <button
                  type="button"
                  className="step-calc-btn"
                  onClick={() => adjustPrice(-100)}
                  disabled={pricingMode === 'MARKET'}
                >
                  <Minus size={15} />
                </button>
                <input
                  id="order-price"
                  type="number"
                  value={pricingMode === 'MARKET' ? stock.price : price}
                  onChange={(e) => setPrice(Number(e.target.value) || '')}
                  disabled={pricingMode === 'MARKET'}
                  className="order-number-input"
                />
                <button
                  type="button"
                  className="step-calc-btn"
                  onClick={() => adjustPrice(100)}
                  disabled={pricingMode === 'MARKET'}
                >
                  <Plus size={15} />
                </button>
              </div>
              {pricingMode === 'LIMIT' && (
                <div className="quick-ticks">
                  {[-1000, -500, +500, +1000].map((delta) => (
                    <button
                      type="button"
                      key={delta}
                      onClick={() => adjustPrice(delta)}
                    >
                      {delta > 0 ? `+${won(delta)}` : won(delta)}
                    </button>
                  ))}
                  <button
                    type="button"
                    className="reset-price"
                    onClick={() => setPrice(stock.price)}
                  >
                    현재가
                  </button>
                </div>
              )}
            </div>

            {/* 주문 수량 */}
            <div className="order-field-group">
              <div className="field-title-row">
                <label htmlFor="order-qty">주문 수량 (주)</label>
                <span className="qty-avail">
                  {orderType === 'BUY'
                    ? `매수가능: 최대 ${maxBuyQty}주`
                    : `매도가능: ${maxSellQty}주`}
                </span>
              </div>
              <div className="stepper-input-wrap">
                <button
                  type="button"
                  className="step-calc-btn"
                  onClick={() => adjustQty(-1)}
                >
                  <Minus size={15} />
                </button>
                <input
                  id="order-qty"
                  type="number"
                  min="1"
                  value={quantity}
                  onChange={(e) => setQuantity(Math.max(1, Number(e.target.value) || 1))}
                  className="order-number-input"
                />
                <button
                  type="button"
                  className="step-calc-btn"
                  onClick={() => adjustQty(1)}
                >
                  <Plus size={15} />
                </button>
              </div>
              {/* 비율 빠른 선택 */}
              <div className="quick-ticks">
                {orderType === 'BUY'
                  ? [10, 25, 50, 100].map((pct) => (
                      <button
                        type="button"
                        key={pct}
                        onClick={() => handleQuickPercent(pct)}
                        disabled={maxBuyQty === 0}
                      >
                        {pct === 100 ? '최대' : `${pct}%`}
                      </button>
                    ))
                  : [25, 50, 75, 100].map((pct) => (
                      <button
                        type="button"
                        key={pct}
                        onClick={() => handleQuickPercent(pct)}
                        disabled={maxSellQty === 0}
                      >
                        {pct === 100 ? '전량' : `${pct}%`}
                      </button>
                    ))}
              </div>
            </div>

            {/* 금액 요약 및 FDS 검증 박스 */}
            <div className="order-calc-card">
              <div className="calc-row">
                <span>총 주문 금액</span>
                <strong className={orderType === 'BUY' ? 'rise' : 'fall'}>
                  ₩{won(totalAmount)}
                </strong>
              </div>
              <div className="calc-row sub">
                <span>보유 예수금</span>
                <span>₩{won(currentUser?.balance || 0)}</span>
              </div>
              <div className="calc-row sub">
                <span>주문 후 잔액</span>
                <span>
                  ₩{won(
                    orderType === 'BUY'
                      ? Math.max(0, (currentUser?.balance || 0) - totalAmount)
                      : (currentUser?.balance || 0) + totalAmount
                  )}
                </span>
              </div>
              {currentHolding.quantity > 0 && (
                <div className="calc-row sub highlight">
                  <span>현재 보유 현황</span>
                  <span>
                    {currentHolding.quantity}주 (평단 ₩{won(currentHolding.avgPrice)})
                  </span>
                </div>
              )}
            </div>

            {/* 주문 실행 버튼 */}
            <button
              type="submit"
              className={`order-submit-btn ${orderType === 'BUY' ? 'buy' : 'sell'}`}
            >
              ₩{won(totalAmount)}{' '}
              {orderType === 'BUY' ? '매수 주문 실행' : '매도 주문 실행'}
            </button>
          </form>
        )}
      </div>
    </div>
  );
}
