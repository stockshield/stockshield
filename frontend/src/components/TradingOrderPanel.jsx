import React, { useState, useEffect } from 'react';
import { 
  ArrowUpRight, 
  ArrowDownLeft, 
  ShieldCheck, 
  CheckCircle2, 
  AlertCircle, 
  Clock3,
  AlertTriangle,
  XCircle,
  Lock,
  Minus, 
  Plus,
  RefreshCw,
} from 'lucide-react';
import { executeOrder } from '../utils/storage';

const won = (x) => Math.round(Number(x || 0)).toLocaleString('ko-KR');

export default function TradingOrderPanel({ 
  stock, 
  currentUser, 
  onOrderSuccess,
  orderType = 'BUY',
  setOrderType,
  orderPrice = null,
  balanceCard = null,
  isMarketOpen = true,
  isHighlighted = false,
}) {
  const [priceMode, setPriceMode] = useState('MARKET'); // 'MARKET' | 'LIMIT'
  const [limitPrice, setLimitPrice] = useState(() => Number(orderPrice || stock?.price || 0));
  const [quantity, setQuantity] = useState(1);
  const [errorMsg, setErrorMsg] = useState('');
  const [errorCode, setErrorCode] = useState('');
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [lastOrderOutcome, setLastOrderOutcome] = useState(null); // { order, status, code, message, replayed }

  // 종목 변경 시 입력 초기화
  useEffect(() => {
    if (stock) {
      setErrorMsg('');
      setErrorCode('');
      setLastOrderOutcome(null);
      setLimitPrice(Number(orderPrice || stock.price || 0));
    }
  }, [stock?.code]);

  useEffect(() => {
    if (orderPrice) {
      setLimitPrice(Number(orderPrice));
    }
  }, [orderPrice]);

  if (!stock) return null;

  const currentHolding = currentUser?.portfolio?.[stock.code] || { quantity: 0, avgPrice: 0 };
  const marketPrice = Number(stock.price) || 0;
  const effectivePrice = priceMode === 'LIMIT' ? (Number(limitPrice) || marketPrice) : marketPrice;
  const totalAmount = effectivePrice * (Number(quantity) || 0);
  const isPriceDelayed = Boolean(stock.priceDelayed);
  const isAccountLocked = Boolean(currentUser?.isLocked);

  // 최대 매수 가능 수량
  const maxBuyQty = effectivePrice > 0
    ? Math.max(0, Math.floor((currentUser?.balance || 0) / effectivePrice))
    : 0;
  // 최대 매도 가능 수량
  const maxSellQty = currentHolding.quantity || 0;

  // 비율 퀵 버튼 클릭
  const handleQuickPercent = (pct) => {
    setErrorMsg('');
    setErrorCode('');
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
    setErrorMsg('');
    setErrorCode('');
    setQuantity((prev) => Math.max(1, Number(prev || 1) + delta));
  };

  // 시장가 주문 실행: POST /api/v1/orders { tckCd, ordDvCd, ordQty, idmpKey }
  const handleExecute = async (e) => {
    e?.preventDefault();
    setErrorMsg('');
    setErrorCode('');

    if (priceMode === 'LIMIT') {
      setErrorMsg('현재 백엔드 서버는 시장가 즉시 체결만 지원합니다. 시장가를 선택해 주문해 주세요.');
      return;
    }

    if (!currentUser) {
      setErrorMsg('주문하려면 먼저 로그인해야 합니다.');
      return;
    }

    const numQty = Number(quantity);
    if (!Number.isInteger(numQty) || numQty <= 0 || numQty > 1000000) {
      setErrorMsg('주문 수량은 1주 이상 1,000,000주 이하의 정수만 입력할 수 있습니다.');
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
        // 422 / 423 저장된 업무 거절 (ACCOUNT_LOCKED, MARKET_CLOSED, PRICE_STALE, INSUFFICIENT_BALANCE, INSUFFICIENT_HOLDING)
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
    <div className={`trading-order-panel panel ${isHighlighted ? 'order-highlight-active' : ''}`}>
      {/* 모의투자 총 보유자산 요약 (주문 박스 내부 상단) */}
      {balanceCard}

      {/* 계좌 잠금 경고 배너 */}
      {isAccountLocked && (
        <div style={{ background: '#3b161c', border: '1px solid #ff4d64', borderRadius: '6px', padding: '8px 10px', marginBottom: '10px', fontSize: '11.5px', color: '#ff8596', display: 'flex', alignItems: 'center', gap: '6px' }}>
          <Lock size={14} style={{ flexShrink: 0 }} />
          <span>계좌 잠금 상태 ({currentUser.acntStatCd}): {currentUser.lockReason || '관리자에게 문의하세요.'}</span>
        </div>
      )}

      {/* 매수 / 매도 탭 전환 */}
      <div className="order-type-tabs">
        <button
          type="button"
          className={`order-tab-btn buy ${orderType === 'BUY' ? 'active' : ''}`}
          onClick={() => {
            if (setOrderType) setOrderType('BUY');
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
            if (setOrderType) setOrderType('SELL');
            setErrorMsg('');
            setErrorCode('');
            setLastOrderOutcome(null);
          }}
        >
          매도 (SELL)
        </button>
      </div>

      {lastOrderOutcome ? (
        /* 주문 처리 결과 화면 (FILLED 체결 / REJECTED 거절 사유별 구분 표시) */
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
                    <CheckCircle2 size={40} />
                  </div>
                  <h3 className="order-result-title text-green">시장가 주문 체결 완료 (FILLED)</h3>
                  <p className="order-result-msg">
                    <strong>{stockName}</strong> {qty.toLocaleString()}주{' '}
                    <b className={ordDvCd === 'BUY' ? 'rise' : 'fall'}>
                      {ordDvCd === 'BUY' ? '매수' : '매도'}
                    </b>{' '}
                    즉시 체결되었습니다.
                  </p>
                </>
              ) : (
                <>
                  <div className="order-result-icon rejected">
                    {rjctCode === 'ACCOUNT_LOCKED' ? <Lock size={40} /> :
                     rjctCode === 'PRICE_STALE' ? <Clock3 size={40} /> :
                     <XCircle size={40} />}
                  </div>
                  <h3 className="order-result-title text-red">
                    {rjctCode === 'ACCOUNT_LOCKED' ? '계좌 잠금으로 주문 거절' :
                     rjctCode === 'PRICE_STALE' ? '시세 지연으로 주문 거절' :
                     rjctCode === 'MARKET_CLOSED' ? '장 운영 시간 외 주문 거절' :
                     rjctCode === 'INSUFFICIENT_BALANCE' ? '예수금 부족 주문 거절' :
                     rjctCode === 'INSUFFICIENT_HOLDING' ? '보유 수량 부족 주문 거절' :
                     '주문 거절 (REJECTED)'}
                  </h3>
                  <p className="order-result-msg">
                    <strong>{stockName}</strong> {qty.toLocaleString()}주{' '}
                    <b className={ordDvCd === 'BUY' ? 'rise' : 'fall'}>
                      {ordDvCd === 'BUY' ? '매수' : '매도'}
                    </b>{' '}
                    주문이 거절되었습니다.
                  </p>
                  <div className="order-status-notice rejected">
                    <strong>거절 사유 ({rjctCode})</strong>
                    <p>{rjctMsg}</p>
                  </div>
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
                    <span>멱등 재응답</span>
                    <span className="fds-badge-pill">동일 주문키 중복 방지됨</span>
                  </div>
                )}
              </div>

              <div className="modal-actions" style={{ marginTop: '16px' }}>
                <button
                  type="button"
                  className="primary-btn"
                  style={{ flex: 1 }}
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
        /* 시장가 주문 입력 폼 */
        <form onSubmit={handleExecute} className="order-form-body">
          {errorMsg && (
            <div className="form-alert error" style={{ flexDirection: 'column', alignItems: 'flex-start', gap: '6px' }}>
              <div style={{ display: 'flex', alignItems: 'center', gap: '6px' }}>
                <AlertCircle size={15} />
                <span>{errorMsg}</span>
              </div>
              {(errorCode === 'NETWORK_ERROR' || errorCode === 'LOCK_TIMEOUT' || errorCode === 'PRICE_UNAVAILABLE' || errorCode === 'SERVICE_UNAVAILABLE') && (
                <button
                  type="button"
                  onClick={handleExecute}
                  disabled={isSubmitting}
                  style={{ background: '#2d3f5e', border: '1px solid #4a6899', color: '#fff', borderRadius: '4px', padding: '4px 10px', fontSize: '11px', cursor: 'pointer', display: 'inline-flex', alignItems: 'center', gap: '4px' }}
                >
                  <RefreshCw size={12} /> 같은 주문 키로 안전하게 재시도
                </button>
              )}
            </div>
          )}

          {/* 주문 구분: 시장가 / 지정가 */}
          <div className="order-mode-row">
            <span className="mode-label">주문 방식</span>
            <div className="mode-selector" style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '6px' }}>
              <button
                type="button"
                className={priceMode === 'MARKET' ? 'active' : ''}
                onClick={() => {
                  setPriceMode('MARKET');
                  setErrorMsg('');
                }}
              >
                시장가
              </button>
              <button
                type="button"
                className={priceMode === 'LIMIT' ? 'active' : ''}
                onClick={() => {
                  setPriceMode('LIMIT');
                  if (!limitPrice && marketPrice > 0) setLimitPrice(marketPrice);
                  setErrorMsg('');
                }}
              >
                지정가
              </button>
            </div>
          </div>

          {/* 주문 단가 (시장가: 읽기 전용, 지정가: 입력 가능) */}
          <div className="order-field-group">
            <div className="field-title-row">
              <label>{priceMode === 'LIMIT' ? '지정 단가 (KRW)' : '현재 기준 단가 (KRW)'}</label>
              <span className="market-note">
                {priceMode === 'LIMIT' ? '서버 미지원 (시장가만 체결)' : '서버 Redis 실시간 시세 기준'}
              </span>
            </div>
            <div className="stepper-input-wrap">
              {priceMode === 'LIMIT' ? (
                <input
                  type="number"
                  min="1"
                  step="100"
                  value={limitPrice || ''}
                  onChange={(e) => setLimitPrice(Math.max(0, Number(e.target.value) || 0))}
                  className="order-number-input"
                />
              ) : (
                <input
                  type="text"
                  value={marketPrice > 0 ? `₩${won(marketPrice)}` : '시세 조회 중'}
                  disabled
                  className="order-number-input"
                />
              )}
            </div>
          </div>

          {/* 주문 수량 */}
          <div className="order-field-group">
            <div className="field-title-row">
              <label htmlFor="order-panel-qty">주문 수량 (주)</label>
              <span className="qty-avail">
                {orderType === 'BUY'
                  ? `매수가능: 최대 ${maxBuyQty.toLocaleString()}주`
                  : `매도가능: ${maxSellQty.toLocaleString()}주`}
              </span>
            </div>
            <div className="stepper-input-wrap">
              <button
                type="button"
                className="step-calc-btn"
                onClick={() => adjustQty(-1)}
                aria-label="수량 1주 감소"
              >
                <Minus size={14} />
              </button>
              <input
                id="order-panel-qty"
                type="number"
                min="1"
                max="1000000"
                value={quantity}
                onChange={(e) => {
                  setErrorMsg('');
                  setErrorCode('');
                  setQuantity(Math.max(1, Number(e.target.value) || 1));
                }}
                className="order-number-input"
              />
              <button
                type="button"
                className="step-calc-btn"
                onClick={() => adjustQty(1)}
                aria-label="수량 1주 증가"
              >
                <Plus size={14} />
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

          {/* 시세 지연 경고 배너 (장 운영 중에만 표시, 주문수량 % 아래) */}
          {isMarketOpen && !isAccountLocked && isPriceDelayed && (
            <div style={{ background: '#332714', border: '1px solid #f1cc62', borderRadius: '6px', padding: '8px 10px', marginBottom: '10px', fontSize: '11.5px', color: '#f1cc62', display: 'flex', alignItems: 'center', gap: '6px' }}>
              <Clock3 size={14} style={{ flexShrink: 0 }} />
              <span>시세가 60초 이상 지연되어 주문 시 거절(PRICE_STALE)될 수 있습니다.</span>
            </div>
          )}

          {/* 금액 계산 요약 카드 */}
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
            <div className="calc-row sub">
              <span>주문 후 예상 잔액</span>
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
                <span>현재 보유</span>
                <span>
                  {currentHolding.quantity.toLocaleString()}주 (평단 ₩{won(currentHolding.avgPrice)})
                </span>
              </div>
            )}
          </div>

          {/* 주문 실행 버튼 */}
          <button
            type="submit"
            disabled={isSubmitting}
            className={`order-submit-btn ${orderType === 'BUY' ? 'buy' : 'sell'}`}
          >
            {isSubmitting
              ? '시장가 주문 처리 중...'
              : `₩${won(totalAmount)} 시장가 ${orderType === 'BUY' ? '매수' : '매도'} 주문`}
          </button>
        </form>
      )}
    </div>
  );
}
