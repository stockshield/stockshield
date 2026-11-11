import React, { useState } from 'react';
import { 
  ShieldCheck, 
  X, 
  Check, 
  CheckCircle2, 
  AlertCircle, 
  Eye, 
  EyeOff, 
  User, 
  Mail, 
  Phone, 
  Lock, 
  Coins, 
  Sparkles, 
  ArrowRight, 
  ArrowLeft 
} from 'lucide-react';
import { checkUserIdAvailable, registerUser } from '../utils/storage';

export default function SignUpModal({ isOpen, onClose, onRegisterSuccess }) {
  const [step, setStep] = useState(1); // 1: 계정 정보, 2: 보안 및 투자 설정, 3: 완료

  // Step 1 상태
  const [id, setId] = useState('');
  const [name, setName] = useState('');
  const [email, setEmail] = useState('');
  const [phone, setPhone] = useState('');
  const [idChecked, setIdChecked] = useState(null); // null, 'available', 'taken', 'invalid'
  const [step1Error, setStep1Error] = useState('');

  // Step 2 상태
  const [password, setPassword] = useState('');
  const [confirmPassword, setConfirmPassword] = useState('');
  const [showPassword, setShowPassword] = useState(false);
  const [showConfirmPassword, setShowConfirmPassword] = useState(false);
  const [seedAmount, setSeedAmount] = useState(10000000);
  const [terms, setTerms] = useState({
    service: true,
    privacy: true,
    fds: true,
    marketing: false
  });
  const [step2Error, setStep2Error] = useState('');

  // 가입 완료된 사용자 정보
  const [registeredUser, setRegisteredUser] = useState(null);

  if (!isOpen) return null;

  // 휴대폰 자동 하이픈
  const handlePhoneChange = (e) => {
    const raw = e.target.value.replace(/[^0-9]/g, '');
    let formatted = raw;
    if (raw.length > 3 && raw.length <= 7) {
      formatted = `${raw.slice(0, 3)}-${raw.slice(3)}`;
    } else if (raw.length > 7) {
      formatted = `${raw.slice(0, 3)}-${raw.slice(3, 7)}-${raw.slice(7, 11)}`;
    }
    setPhone(formatted);
  };

  // 아이디 중복 확인 (서버 실시간 검증)
  const handleCheckId = async () => {
    const trimmed = id.trim();
    if (trimmed.length < 4 || !/^[a-zA-Z0-9_]+$/.test(trimmed)) {
      setIdChecked('invalid');
      return;
    }
    setIdChecked('checking');
    const res = await checkUserIdAvailable(trimmed);
    setIdChecked(res.available ? 'available' : 'taken');
  };

  // 비밀번호 강도 계산 (0: 취약, 1: 보통, 2: 안전)
  const getPasswordStrength = () => {
    if (!password) return { score: 0, label: '비밀번호를 입력하세요', color: '#6e7f94' };
    let score = 0;
    if (password.length >= 8) score++;
    if (/[a-zA-Z]/.test(password) && /[0-9]/.test(password)) score++;
    if (/[^a-zA-Z0-9]/.test(password)) score++;

    if (score <= 1) return { score: 1, label: '취약 (8자 이상, 영문/숫자 조합 필요)', color: '#ed707b' };
    if (score === 2) return { score: 2, label: '보통 (특수문자 추가 권장)', color: '#f1cc62' };
    return { score: 3, label: '매우 안전 (보안 규정 충족: 8자 이상+영문+숫자+특수문자)', color: '#37c48e' };
  };

  const pwStrength = getPasswordStrength();
  const passwordsMatch = password.length > 0 && password === confirmPassword;

  // 전체 약관 동의 토글
  const allRequiredTermsChecked = terms.service && terms.privacy && terms.fds;
  const allTermsChecked = allRequiredTermsChecked && terms.marketing;

  const handleToggleAllTerms = () => {
    const nextVal = !allTermsChecked;
    setTerms({
      service: nextVal,
      privacy: nextVal,
      fds: nextVal,
      marketing: nextVal
    });
  };

  // Step 1 다음 단계 이동 검증
  const handleNextStep = async () => {
    setStep1Error('');
    if (!id.trim() || id.trim().length < 4) {
      setStep1Error('아이디를 4자 이상 입력해주세요.');
      return;
    }
    if (idChecked !== 'available') {
      const res = await checkUserIdAvailable(id.trim());
      if (!res.available) {
        setStep1Error(res.message || '아이디 중복 확인을 통과해야 합니다.');
        setIdChecked('taken');
        return;
      }
      setIdChecked('available');
    }
    if (!name.trim()) {
      setStep1Error('이름 또는 닉네임을 입력해주세요.');
      return;
    }
    const emailRegex = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
    if (!emailRegex.test(email.trim())) {
      setStep1Error('올바른 이메일 형식을 입력해주세요 (예: user@example.com).');
      return;
    }
    setStep(2);
  };

  // Step 2 최종 제출 (BCrypt & 8자 이상 영문/숫자/특수문자 검증)
  const handleSubmitRegistration = async (e) => {
    e.preventDefault();
    setStep2Error('');

    if (!password || !password.trim()) {
      setStep2Error('비밀번호를 입력해주세요.');
      return;
    }

    // 명세 USR_AUTH_001 시트 행 7: 8자 이상, 영문·숫자·특수문자 조합 강제
    const PASSWORD_REGEX = /^(?=.*[A-Za-z])(?=.*\d)(?=.*[!@#$%^&*()_+\-=\[\]{};':"\\|,.<>\/?]).{8,}$/;
    if (!PASSWORD_REGEX.test(password)) {
      setStep2Error('비밀번호는 8자 이상이며, 영문, 숫자, 특수문자를 각각 1개 이상 반드시 포함해야 합니다.');
      return;
    }

    if (!confirmPassword || !confirmPassword.trim()) {
      setStep2Error('비밀번호 확인을 입력해주세요.');
      return;
    }
    if (password !== confirmPassword) {
      setStep2Error('비밀번호와 비밀번호 확인이 일치하지 않습니다.');
      return;
    }
    if (!terms.service || !terms.privacy || !terms.fds) {
      setStep2Error('필수 이용약관 및 FDS 모니터링 동의 항목에 체크해주세요.');
      return;
    }

    try {
      const res = await registerUser({
        id: id.trim(),
        password,
        name: name.trim(),
        email: email.trim(),
        phone: phone.trim(),
        seedAmount: 10000000 // 기능 ID 9: 1,000만 원 표준 개설
      });

      if (res.success) {
        setRegisteredUser(res.user);
        setStep(3);
      } else {
        setStep2Error(res.message || '회원가입에 실패했습니다.');
      }
    } catch (err) {
      console.error('Registration error:', err);
      setStep2Error('회원 저장 중 오류가 발생했습니다: ' + err.message);
    }
  };

  const handleFinishAndLogin = () => {
    if (registeredUser) {
      onRegisterSuccess(registeredUser);
    }
    onClose();
  };

  return (
    <div className="overlay" onClick={onClose} role="dialog" aria-modal="true" aria-labelledby="signup-title">
      <div className="signup-modal panel" onClick={(e) => e.stopPropagation()}>
        <button className="modal-x" onClick={onClose} aria-label="닫기">
          <X size={20} />
        </button>

        {/* 상단 브랜딩 헤더 */}
        <div className="signup-header">
          <div className="brand-icon large">
            <ShieldCheck size={28} />
          </div>
          <h2 id="signup-title">StockShield 회원가입</h2>
          <p className="signup-subtitle">
            실시간 FDS 이상거래 탐지 및 안전한 모의투자 플랫폼
          </p>
        </div>

        {/* 단계 표시기 (Stepper) */}
        {step < 3 && (
          <div className="stepper-bar">
            <div className={`step-item ${step === 1 ? 'active' : 'completed'}`}>
              <div className="step-num">{step > 1 ? <Check size={14} /> : '1'}</div>
              <span>계정 정보</span>
            </div>
            <div className={`step-connector ${step > 1 ? 'completed' : ''}`} />
            <div className={`step-item ${step === 2 ? 'active' : ''}`}>
              <div className="step-num">2</div>
              <span>보안 & 모의투자</span>
            </div>
          </div>
        )}

        {/* STEP 1: 기본 계정 정보 */}
        {step === 1 && (
          <div className="signup-step-body">
            {step1Error && (
              <div className="form-alert error">
                <AlertCircle size={16} />
                <span>{step1Error}</span>
              </div>
            )}

            <div className="form-group">
              <label htmlFor="reg-id">
                아이디 (4~16자 영문/숫자) <span className="req">*</span>
              </label>
              <div className="input-with-button">
                <div className="form-control">
                  <User size={16} className="input-icon" />
                  <input
                    id="reg-id"
                    value={id}
                    onChange={(e) => {
                      setId(e.target.value.toLowerCase().replace(/[^a-z0-9_]/g, ''));
                      setIdChecked(null);
                    }}
                    placeholder="아이디를 입력하세요"
                    maxLength={16}
                    autoComplete="username"
                  />
                </div>
                <button
                  type="button"
                  className="check-id-btn"
                  onClick={handleCheckId}
                  disabled={id.length < 4}
                >
                  중복 확인
                </button>
              </div>
              {idChecked === 'available' && (
                <p className="field-hint success">
                  <CheckCircle2 size={13} /> 사용 가능한 아이디입니다.
                </p>
              )}
              {idChecked === 'taken' && (
                <p className="field-hint error">
                  <AlertCircle size={13} /> 이미 사용 중인 아이디입니다.
                </p>
              )}
              {idChecked === 'invalid' && (
                <p className="field-hint error">
                  <AlertCircle size={13} /> 4자 이상의 영문/숫자만 사용 가능합니다.
                </p>
              )}
            </div>

            <div className="form-group">
              <label htmlFor="reg-name">
                이름 / 닉네임 <span className="req">*</span>
              </label>
              <div className="form-control">
                <Sparkles size={16} className="input-icon" />
                <input
                  id="reg-name"
                  value={name}
                  onChange={(e) => setName(e.target.value)}
                  placeholder="대시보드에 표시될 호칭 (예: 김투자)"
                  maxLength={12}
                />
              </div>
            </div>

            <div className="form-group">
              <label htmlFor="reg-email">
                이메일 주소 <span className="req">*</span>
              </label>
              <div className="form-control">
                <Mail size={16} className="input-icon" />
                <input
                  id="reg-email"
                  type="email"
                  value={email}
                  onChange={(e) => setEmail(e.target.value)}
                  placeholder="FDS 보안 알림 수신용 (예: user@stockshield.io)"
                  autoComplete="email"
                />
              </div>
            </div>

            <div className="form-group">
              <label htmlFor="reg-phone">
                휴대폰 번호 (선택)
              </label>
              <div className="form-control">
                <Phone size={16} className="input-icon" />
                <input
                  id="reg-phone"
                  value={phone}
                  onChange={handlePhoneChange}
                  placeholder="010-0000-0000 (FDS 2차 인증용)"
                  maxLength={13}
                />
              </div>
            </div>

            <div className="modal-actions">
              <button type="button" className="secondary-btn" onClick={onClose}>
                취소
              </button>
              <button type="button" className="primary-btn" onClick={handleNextStep}>
                다음 단계 (보안 설정) <ArrowRight size={16} />
              </button>
            </div>
          </div>
        )}

        {/* STEP 2: 비밀번호 & 보안 / 투자 설정 */}
        {step === 2 && (
          <form className="signup-step-body" onSubmit={handleSubmitRegistration}>
            {step2Error && (
              <div className="form-alert error">
                <AlertCircle size={16} />
                <span>{step2Error}</span>
              </div>
            )}

            <div className="form-group">
              <div className="label-with-hint">
                <label htmlFor="reg-pw">
                  비밀번호 <span className="req">*</span>
                </label>
                {password && (
                  <span className="pw-strength-label" style={{ color: pwStrength.color }}>
                    {pwStrength.label}
                  </span>
                )}
              </div>
              <div className="form-control password">
                <Lock size={16} className="input-icon" />
                <input
                  id="reg-pw"
                  type={showPassword ? 'text' : 'password'}
                  value={password}
                  onChange={(e) => setPassword(e.target.value)}
                  placeholder="8자 이상 영문, 숫자, 특수문자 권장"
                  autoComplete="new-password"
                />
                <button
                  type="button"
                  aria-label={showPassword ? '비밀번호 가리기' : '비밀번호 보기'}
                  onClick={() => setShowPassword(!showPassword)}
                >
                  {showPassword ? <EyeOff size={16} /> : <Eye size={16} />}
                </button>
              </div>
              {/* 비밀번호 강도 바 */}
              <div className="strength-bar-track">
                <div
                  className="strength-bar-fill"
                  style={{
                    width: `${(pwStrength.score / 3) * 100}%`,
                    backgroundColor: pwStrength.color
                  }}
                />
              </div>
            </div>

            <div className="form-group">
              <label htmlFor="reg-pw-confirm">
                비밀번호 확인 <span className="req">*</span>
              </label>
              <div className="form-control password">
                <Lock size={16} className="input-icon" />
                <input
                  id="reg-pw-confirm"
                  type={showConfirmPassword ? 'text' : 'password'}
                  value={confirmPassword}
                  onChange={(e) => setConfirmPassword(e.target.value)}
                  placeholder="동일한 비밀번호를 재입력하세요"
                  autoComplete="new-password"
                />
                <button
                  type="button"
                  aria-label={showConfirmPassword ? '비밀번호 확인 가리기' : '비밀번호 확인 보기'}
                  onClick={() => setShowConfirmPassword(!showConfirmPassword)}
                >
                  {showConfirmPassword ? <EyeOff size={16} /> : <Eye size={16} />}
                </button>
              </div>
              {confirmPassword.length > 0 && (
                <p className={`field-hint ${passwordsMatch ? 'success' : 'error'}`}>
                  {passwordsMatch ? (
                    <>
                      <CheckCircle2 size={13} /> 비밀번호가 일치합니다.
                    </>
                  ) : (
                    <>
                      <AlertCircle size={13} /> 비밀번호가 일치하지 않습니다.
                    </>
                  )}
                </p>
              )}
            </div>

            {/* 모의투자 초기 지원금 선택 */}
            <div className="form-group">
              <label>
                <Coins size={14} className="inline-icon" /> 초기 모의투자 지원금 선택
              </label>
              <div className="seed-selector">
                {[
                  { amount: 10000000, label: '1,000만원', badge: '스타터팩 (추천)' },
                  { amount: 30000000, label: '3,000만원', badge: '스탠다드' },
                  { amount: 50000000, label: '5,000만원', badge: 'VIP 마스터' }
                ].map((item) => (
                  <button
                    type="button"
                    key={item.amount}
                    className={`seed-chip ${seedAmount === item.amount ? 'selected' : ''}`}
                    onClick={() => setSeedAmount(item.amount)}
                  >
                    <strong>₩{item.label}</strong>
                    <small>{item.badge}</small>
                  </button>
                ))}
              </div>
            </div>

            {/* 약관 및 FDS 동의 */}
            <div className="terms-section">
              <div className="terms-all">
                <label className="checkbox-label">
                  <input
                    type="checkbox"
                    checked={allTermsChecked}
                    onChange={handleToggleAllTerms}
                  />
                  <b>전체 약관에 동의합니다.</b>
                </label>
              </div>
              <div className="terms-list">
                <label className="checkbox-label">
                  <input
                    type="checkbox"
                    checked={terms.service}
                    onChange={(e) => setTerms({ ...terms, service: e.target.checked })}
                  />
                  <span>[필수] StockShield 서비스 이용약관 동의</span>
                </label>
                <label className="checkbox-label">
                  <input
                    type="checkbox"
                    checked={terms.privacy}
                    onChange={(e) => setTerms({ ...terms, privacy: e.target.checked })}
                  />
                  <span>[필수] 개인정보 수집 및 이용 동의</span>
                </label>
                <label className="checkbox-label highlight">
                  <input
                    type="checkbox"
                    checked={terms.fds}
                    onChange={(e) => setTerms({ ...terms, fds: e.target.checked })}
                  />
                  <span>[필수] FDS(이상거래탐지시스템) 실시간 보안 관제 동의</span>
                </label>
                <label className="checkbox-label">
                  <input
                    type="checkbox"
                    checked={terms.marketing}
                    onChange={(e) => setTerms({ ...terms, marketing: e.target.checked })}
                  />
                  <span>[선택] 실시간 시장 브리핑 및 보안 리포트 알림 수신</span>
                </label>
              </div>
            </div>

            <div className="modal-actions">
              <button
                type="button"
                className="secondary-btn"
                onClick={() => setStep(1)}
              >
                <ArrowLeft size={16} /> 이전
              </button>
              <button
                type="submit"
                className="primary-btn"
              >
                회원가입 완료 <Check size={16} />
              </button>
            </div>
          </form>
        )}

        {/* STEP 3: 회원가입 완료 축하 화면 */}
        {step === 3 && registeredUser && (
          <div className="signup-success-view">
            <div className="success-badge-wrap">
              <div className="success-circle">
                <CheckCircle2 size={48} />
              </div>
            </div>
            <h3>환영합니다, {registeredUser.name}님!</h3>
            <p className="success-desc">
              StockShield 정회원 등록이 안전하게 완료되었습니다.
              <br />
              지급된 모의투자 예수금으로 주식 거래를 체험해보세요.
            </p>

            <div className="account-summary-card">
              <div className="summary-row">
                <span>계정 아이디</span>
                <strong>{registeredUser.id}</strong>
              </div>
              <div className="summary-row">
                <span>보안 등급</span>
                <span className="fds-badge-pill">
                  <ShieldCheck size={13} /> {registeredUser.fdsLevel}
                </span>
              </div>
              <div className="summary-row">
                <span>모의투자 초기 예수금</span>
                <strong className="seed-val">₩{registeredUser.balance.toLocaleString('ko-KR')}</strong>
              </div>
              <div className="summary-row">
                <span>알림 수신 이메일</span>
                <span className="muted-text">{registeredUser.email}</span>
              </div>
            </div>

            <div className="success-note">
              <Sparkles size={16} />
              <span>
                FDS 이상거래 탐지 엔진이 24시간 거래 패턴을 모니터링하여 안전한 모의투자를 보장합니다.
              </span>
            </div>

            <button
              type="button"
              className="primary-btn complete-btn"
              onClick={handleFinishAndLogin}
            >
              로그인하고 모의투자 시작하기 <ArrowRight size={18} />
            </button>
          </div>
        )}
      </div>
    </div>
  );
}
