import React, { useState, useEffect } from 'react';
import { 
  X, 
  ArrowUpRight, 
  ArrowDownLeft, 
  ShieldCheck, 
  CheckCircle2, 
  AlertCircle, 
  Clock3,
  XCircle,
  Lock,
  Minus, 
  Plus,
  RefreshCw,
} from 'lucide-react';
import { executeOrder } from '../utils/storage';

const won = (x) => Math.round(Number(x || 0)).toLocaleString('ko-KR');

export default function OrderModal({ 
  isOpen, 
  onClose, 
  stock, 
  currentUser, 
  onOrderSuccess,
  initialOrderType = 'BUY',
}) {
  const [orderType, setOrderType] = useState(initialOrderType);
  const [quantity, setQuantity] = useState(1);
  const [errorMsg, setErrorMsg] = useState('');
  const [errorCode, setErrorCode] = useState('');
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [lastOrderOutcome, setLastOrderOutcome] = useState(null);

  useEffect(() => {
    if (stock && isOpen) {
      setQuantity(1);
      setErrorMsg('');
      setErrorCode('');
      setLastOrderOutcome(null);
      setOrderType(initialOrderType);
    }
  }, [stock?.code, isOpen, initialOrderType]);

  if (!isOpen || !stock) return null;

  const currentHolding = currentUser?.portfolio?.[stock.code] || { quantity: 0, avgPrice: 0 };
  const effectivePrice = Number(stock.price) || 0;
  const totalAmount = effectivePrice * (Number(quantity) || 0);

  const maxBuyQty = effectivePrice > 0
    ? Math.max(0, Math.floor((currentUser?.balance || 0) / effectivePrice))
    : 0;
  const maxSellQty = currentHolding.quantity || 0;

  const handleQuickPercent = (pct) => {
    setErrorMsg('');
    setErrorCode('');
    if (orderType === 'BUY') {
      setQuantity(Math.max(1, Math.floor(maxBuyQty * (pct / 100))));
    } else {
      setQuantity(Math.max(1, Math.floor(maxSellQty * (pct / 100))));
    }
  };

  const adjustQty = (delta) => {
    setErrorMsg('');
    setErrorCode('');
    setQuantity((prev) => Math.max(1, Number(prev || 1) + delta));
  };

  const handleExecute = async (e) => {
    e?.preventDefault();
    setErrorMsg('');
    setErrorCode('');

    if (!currentUser) {
      setErrorMsg('주문하려면 먼저 로그인해야 합니다.');
      return;
    }

    const numQty = Number(quantity);
    if (!Number.isInteger(numQty) || numQty <= 0 || numQty > 1000000) {
      setErrorMsg('주문 수량은 1주 이상 1,000,000주 이하의 정수만 가능합니다.');
      return;
    }

    setIsSubmitting(true);
    try {
      const res = await executeOrder({
        tckCd: stock.code,
        stockName: stock.name,
        ordDvCd: orderType,
        ordQty: numQty,
      });

      if (res.success && res.order) {
        setLastOrderOutcome({
          order: res.order,
          status: 'FILLED',
          code: null,
          message: res.message,
          replayed: res.replayed,
        });
        if (onOrderSuccess) {
          onOrderSuccess(res.user, res.message);
        }
      } else if (res.isRejected && res.order) {
        setLastOrderOutcome({
          order: res.order,
          status: 'REJECTED',
          code: res.code,
          message: res.message,
          replayed: res.replayed,
        });
        if (onOrderSuccess && res.user) {
          onOrderSuccess(res.user, `[주문 거절] ${res.message}`);
        }
      } else {
        setErrorCode(res.code || '');
        setErrorMsg(res.message || '주문 처리에 실패했습니다.');
      }
    } catch (err) {
      setErrorCode('NETWORK_ERROR');
      setErrorMsg('주문 전송 중 통신 오류가 발생했습니다: ' + err.message);
    } finally {
      setIsSubmitting(false);
    }
  };

  return (
    <div className="overlay" onClick={onClose} role="dialog" aria-modal="true" aria-labelledby="order-title">
      <div className="order-modal panel" onClick={(e) => e.stopPropagation()}>
        <button className="modal-x" onClick={onClose} aria-label="닫기">
          <X size={20} />
        </button>

        <div className="order-stock-header">
          <div className="order-stock-meta">
            <span
              className="company-logo"
              style={{ background: (stock.color || '#6c85f7') + '25', color: stock.color || '#6c85f7' }}
            >
              {stock.name.slice(0, 1)}
            </span>
            <div>
              <h2 id="order-title">{stock.name} <small>{stock.code}</small></h2>
              <div className="order-curr-price">
                <strong>{effectivePrice > 0 ? `₩${won(effectivePrice)}` : '시세 대기 중'}</strong>
                {stock.change != null && (
                  <span className={stock.change >= 0 ? 'rise' : 'fall'}>
                    {stock.change >= 0 ? <ArrowUpRight size={13} /> : <ArrowDownLeft size={13} />}
                    {stock.change >= 0 ? '+' : ''}{Number(stock.change).toFixed(2)}%
                  </span>
                )}
              </div>
            </div>
          </div>

          <div className="order-fds-tag">
            <ShieldCheck size={14} /> 시장가 즉시 체결
          </div>
        </div>

        <div className="order-type-tabs">
          <button
            type="button"
            className={`order-tab-btn buy ${orderType === 'BUY' ? 'active' : ''}`}
            onClick={() => {
              setOrderType('BUY');
              setErrorMsg('');
              setErrorCode('');
              setLastOrderOutcome(null);
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
              setErrorCode('');
              setLastOrderOutcome(null);
            }}
          >
            매도 (SELL)
          </button>
        </div>

        {lastOrderOutcome ? (
          (() => {
            const { order, status, code, message, replayed } = lastOrderOutcome;
            const isFilled = status === 'FILLED' || order.ordStatCd === 'FILLED' || order.status === 'EXECUTED';
            const ordDvCd = order.ordDvCd || order.orderType || 'BUY';
            const stockName = order.tckNm || order.stockName || stock?.name || '';
            const qty = Number(order.ordQty ?? order.quantity ?? 1);
            const prc = Number(order.ordPrc ?? order.price ?? effectivePrice);
            const trdAmt = Number(order.trdAmt ?? order.totalCost ?? prc * qty);
            const rjctCode = code || order.rjctRsnCd || 'REJECTED';
            const rjctMsg = message || order.rjctRsnMsg || order.rejectionReason || '';

            return (
              <div className={`order-result-box ${isFilled ? 'executed' : 'rejected'}`}>
                {isFilled ? (
                  <>
                    <div className="order-result-icon executed">
                      <CheckCircle2 size={44} />
                    </div>
                    <h3 className="order-result-title text-green">시장가 주문 체결 완료!</h3>
                    <p className="order-result-msg">
                      <strong>{stockName}</strong> {qty.toLocaleString()}주{' '}
                      <b className={ordDvCd === 'BUY' ? 'rise' : 'fall'}>
                        {ordDvCd === 'BUY' ? '매수' : '매도'}
                      </b>{' '}
                      체결되었습니다.
                    </p>
                  </>
                ) : (
                  <>
                    <div className="order-result-icon rejected">
                      {rjctCode === 'ACCOUNT_LOCKED' ? <Lock size={44} /> :
                       rjctCode === 'PRICE_STALE' ? <Clock3 size={44} /> :
                       <XCircle size={44} />}
                    </div>
                    <h3 className="order-result-title text-red">주문 거절 ({rjctCode})</h3>
                    <p className="order-result-msg">{rjctMsg}</p>
                  </>
                )}

                <div className="order-summary-table">
                  <div>
                    <span>주문 번호</span>
                    <b>{order.ordNo || order.id}</b>
                  </div>
                  <div>
                    <span>주문 상태</span>
                    <span className={`order-status-tag ${isFilled ? 'executed' : 'rejected'}`}>
                      {isFilled ? '체결 완료 (FILLED)' : `주문 거절 (${rjctCode})`}
                    </span>
                  </div>
                  <div>
                    <span>{isFilled ? '체결 단가' : '기준 단가'}</span>
                    <b>{prc > 0 ? `₩${won(prc)}` : '-'}</b>
                  </div>
                  <div>
                    <span>주문 수량</span>
                    <b>{qty.toLocaleString()}주</b>
                  </div>
                  <div>
                    <span>{isFilled ? '총 체결 금액' : '주문 금액'}</span>
                    <b className="accent-price">{trdAmt > 0 ? `₩${won(trdAmt)}` : '-'}</b>
                  </div>
                  {replayed && (
                    <div>
                      <span>멱등 처리</span>
                      <span className="fds-badge-pill">동일 주문키 중복 방지됨</span>
                    </div>
                  )}
                </div>

                <div className="modal-actions" style={{ marginTop: '16px' }}>
                  <button
                    type="button"
                    className="secondary-btn"
                    onClick={onClose}
                  >
                    닫기
                  </button>
                  <button
                    type="button"
                    className="primary-btn"
                    onClick={() => {
                      setLastOrderOutcome(null);
                      setQuantity(1);
                    }}
                  >
                    <RefreshCw size={14} /> 추가 주문하기
                  </button>
                </div>
              </div>
            );
          })()
        ) : (
          <form onSubmit={handleExecute} className="order-form-body">
            {errorMsg && (
              <div className="form-alert error">
                <AlertCircle size={15} />
                <span>{errorMsg}</span>
              </div>
            )}

            <div className="order-mode-row">
              <span className="mode-label">주문 방식</span>
              <div className="mode-selector">
                <button type="button" className="active" style={{ width: '100%' }}>
                  시장가 (서버 현재가 즉시 체결)
                </button>
              </div>
            </div>

            <div className="order-field-group">
              <div className="field-title-row">
                <label htmlFor="modal-order-qty">주문 수량 (주)</label>
                <span className="qty-avail">
                  {orderType === 'BUY'
                    ? `매수가능: 최대 ${maxBuyQty.toLocaleString()}주`
                    : `매도가능: ${maxSellQty.toLocaleString()}주`}
                </span>
              </div>
              <div className="stepper-input-wrap">
                <button type="button" className="step-calc-btn" onClick={() => adjustQty(-1)}>
                  <Minus size={14} />
                </button>
                <input
                  id="modal-order-qty"
                  type="number"
                  min="1"
                  max="1000000"
                  value={quantity}
                  onChange={(e) => setQuantity(Math.max(1, Number(e.target.value) || 1))}
                  className="order-number-input"
                />
                <button type="button" className="step-calc-btn" onClick={() => adjustQty(1)}>
                  <Plus size={14} />
                </button>
              </div>
              <div className="quick-ticks">
                {[25, 50, 75, 100].map((pct) => (
                  <button
                    type="button"
                    key={pct}
                    onClick={() => handleQuickPercent(pct)}
                    disabled={orderType === 'BUY' ? maxBuyQty === 0 : maxSellQty === 0}
                  >
                    {pct === 100 ? '최대' : `${pct}%`}
                  </button>
                ))}
              </div>
            </div>

            <div className="order-calc-card">
              <div className="calc-row">
                <span>예상 주문 금액</span>
                <strong className={orderType === 'BUY' ? 'rise' : 'fall'}>
                  ₩{won(totalAmount)}
                </strong>
              </div>
              <div className="calc-row sub">
                <span>보유 예수금</span>
                <span>₩{won(currentUser?.balance || 0)}</span>
              </div>
            </div>

            <button
              type="submit"
              disabled={isSubmitting}
              className={`order-submit-btn ${orderType === 'BUY' ? 'buy' : 'sell'}`}
            >
              {isSubmitting
                ? '시장가 주문 처리 중...'
                : `₩${won(totalAmount)} 시장가 ${orderType === 'BUY' ? '매수' : '매도'} 실행`}
            </button>
          </form>
        )}
      </div>
    </div>
  );
}
