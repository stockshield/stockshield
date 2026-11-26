import React, { useState, useEffect } from 'react';
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
  Lock, 
  KeyRound,
  Coins, 
  Sparkles, 
  ArrowRight, 
  ArrowLeft,
  Clock
} from 'lucide-react';
import {
  checkUserIdAvailable,
  sendSignupEmailCode,
  registerUser,
  authenticateUser,
} from '../utils/storage';

const SEED_PACKAGES = [
  { code: 'PKG_10M', amount: 10000000, label: '1,000만원', badge: '스타터팩 (추천)' },
  { code: 'PKG_30M', amount: 30000000, label: '3,000만원', badge: '스탠다드' },
  { code: 'PKG_50M', amount: 50000000, label: '5,000만원', badge: 'VIP 마스터' },
];

export default function SignUpModal({ isOpen, onClose, onRegisterSuccess }) {
  const [step, setStep] = useState(1); // 1: 계정 및 이메일 인증, 2: 비밀번호·출금비밀번호·패키지, 3: 완료

  // Step 1 상태 (아이디, 이름, 이메일, 이메일 인증코드)
  const [id, setId] = useState('');
  const [name, setName] = useState('');
  const [email, setEmail] = useState('');
  const [idChecked, setIdChecked] = useState(null); // null, 'checking', 'available', 'taken', 'invalid'
  const [idMessage, setIdMessage] = useState('');
  const [authId, setAuthId] = useState('');
  const [authCode, setAuthCode] = useState('');
  const [codeSentEmail, setCodeSentEmail] = useState('');
  const [ttlSec, setTtlSec] = useState(0); // 5분(300초) 카운트다운
  const [resendCooldown, setResendCooldown] = useState(0); // 60초 재발송 제한
  const [isSendingCode, setIsSendingCode] = useState(false);
  const [step1Error, setStep1Error] = useState('');

  // Step 2 상태 (로그인 비밀번호, 출금 비밀번호 6자리, 초기 자금 패키지 코드)
  const [password, setPassword] = useState('');
  const [confirmPassword, setConfirmPassword] = useState('');
  const [showPassword, setShowPassword] = useState(false);
  const [showConfirmPassword, setShowConfirmPassword] = useState(false);
  const [wdrPwd, setWdrPwd] = useState('');
  const [confirmWdrPwd, setConfirmWdrPwd] = useState('');
  const [showWdrPwd, setShowWdrPwd] = useState(false);
  const initPkgCd = 'PKG_10M';
  const [terms, setTerms] = useState({
    service: true,
    privacy: true,
    fds: true,
    marketing: false,
  });
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [step2Error, setStep2Error] = useState('');

  // 가입 완료된 결과 ({ mbrNo, acntNo, initDpsAmt, dpsBal })
  const [registeredInfo, setRegisteredInfo] = useState(null);
  const [isLoggingIn, setIsLoggingIn] = useState(false);

  // 이메일 인증 타이머 (5분 만료 & 60초 재발송 제한)
  useEffect(() => {
    if (!isOpen || (!ttlSec && !resendCooldown)) return;
    const timer = setInterval(() => {
      setTtlSec((prev) => Math.max(0, prev - 1));
      setResendCooldown((prev) => Math.max(0, prev - 1));
    }, 1000);
    return () => clearInterval(timer);
  }, [isOpen, Boolean(ttlSec || resendCooldown)]);

  if (!isOpen) return null;

  const formatTimer = (sec) => {
    const m = String(Math.floor(sec / 60)).padStart(2, '0');
    const s = String(sec % 60).padStart(2, '0');
    return `${m}:${s}`;
  };

  // 아이디 중복 확인: GET /api/v1/members/check-id?lgnId=...
  const handleCheckId = async () => {
    const trimmed = id.trim();
    if (!/^[a-z][a-z0-9]{3,15}$/.test(trimmed)) {
      setIdChecked('invalid');
      setIdMessage('영문 소문자로 시작하는 영문 소문자·숫자 4~16자여야 합니다.');
      return;
    }
    setIdChecked('checking');
    setIdMessage('');
    const res = await checkUserIdAvailable(trimmed);
    setIdChecked(res.available ? 'available' : 'taken');
    setIdMessage(res.message || '');
  };

  // 가입 이메일 인증코드 발송: POST /api/v1/members/email-code { emlAddr }
  const handleSendEmailCode = async () => {
    setStep1Error('');
    const trimmedEmail = email.trim();
    const emailRegex = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
    if (!emailRegex.test(trimmedEmail) || trimmedEmail.length > 100) {
      setStep1Error('올바른 이메일 주소를 입력해주세요 (100자 이하).');
      return;
    }
    if (resendCooldown > 0 && codeSentEmail === trimmedEmail) {
      return;
    }

    setIsSendingCode(true);
    try {
      const res = await sendSignupEmailCode(trimmedEmail);
      if (res.success) {
        setAuthId(res.authId);
        setTtlSec(res.ttlSec || 300);
        setResendCooldown(60);
        setCodeSentEmail(trimmedEmail);
        setAuthCode('');
      } else {
        setStep1Error(res.message || '인증 코드 발송에 실패했습니다.');
      }
    } finally {
      setIsSendingCode(false);
    }
  };

  // 비밀번호 강도 계산 (8~72자, 공백 없는 ASCII, 영문+숫자+특수문자 모두 포함)
  const getPasswordStrength = () => {
    if (!password) return { score: 0, label: '비밀번호를 입력하세요', color: '#6e7f94' };
    const missing = [];
    if (password.length < 8) missing.push('8자 이상');
    else if (password.length > 72) missing.push('72자 이하');
    if (/\s/.test(password)) missing.push('공백 제외');
    if (!/[a-zA-Z]/.test(password)) missing.push('영문');
    if (!/[0-9]/.test(password)) missing.push('숫자');
    if (!/[^a-zA-Z0-9\s]/.test(password)) missing.push('특수문자');

    if (missing.length === 0) {
      return { score: 3, label: '안전 (보안 규정 충족: 8자 이상+영문+숫자+특수문자)', color: '#37c48e' };
    }
    if (missing.length === 1) {
      return { score: 2, label: `보통 (${missing[0]} 필요)`, color: '#f1cc62' };
    }
    return { score: 1, label: `취약 (${missing.join('·')} 필요)`, color: '#ed707b' };
  };

  const pwStrength = getPasswordStrength();
  const passwordsMatch = password.length > 0 && password === confirmPassword;
  const wdrPwdsMatch = wdrPwd.length === 6 && wdrPwd === confirmWdrPwd;

  const allRequiredTermsChecked = terms.service && terms.privacy && terms.fds;
  const allTermsChecked = allRequiredTermsChecked && terms.marketing;

  const handleToggleAllTerms = () => {
    const nextVal = !allTermsChecked;
    setTerms({
      service: nextVal,
      privacy: nextVal,
      fds: nextVal,
      marketing: nextVal,
    });
  };

  // Step 1 -> Step 2 검증
  const handleNextStep = async () => {
    setStep1Error('');
    const trimmedId = id.trim();
    if (!/^[a-z][a-z0-9]{3,15}$/.test(trimmedId)) {
      setStep1Error('아이디는 영문 소문자로 시작하는 영문 소문자·숫자 4~16자여야 합니다.');
      return;
    }
    if (idChecked !== 'available') {
      const res = await checkUserIdAvailable(trimmedId);
      if (!res.available) {
        setStep1Error(res.message || '아이디 중복 확인을 통과해야 합니다.');
        setIdChecked('taken');
        return;
      }
      setIdChecked('available');
    }
    if (!name.trim() || name.trim().length > 50) {
      setStep1Error('이름을 1~50자 이내로 입력해주세요.');
      return;
    }
    const emailRegex = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
    if (!emailRegex.test(email.trim())) {
      setStep1Error('올바른 이메일 형식을 입력해주세요.');
      return;
    }
    if (!authId || codeSentEmail !== email.trim()) {
      setStep1Error('이메일 인증 코드를 먼저 발송받아 주세요.');
      return;
    }
    if (ttlSec <= 0) {
      setStep1Error('인증 코드 유효 시간(5분)이 만료되었습니다. 인증 코드를 다시 발송받아 주세요.');
      return;
    }
    if (!/^\d{6}$/.test(authCode.trim())) {
      setStep1Error('이메일로 받은 6자리 숫자 인증 코드를 입력해주세요.');
      return;
    }
    setStep(2);
  };

  // Step 2 최종 회원가입 제출: POST /api/v1/members
  const handleSubmitRegistration = async (e) => {
    e.preventDefault();
    setStep2Error('');

    const PASSWORD_REGEX = /^(?=.*[A-Za-z])(?=.*\d)(?=.*[^A-Za-z0-9\s])[\x21-\x7E]{8,72}$/;
    if (!PASSWORD_REGEX.test(password)) {
      setStep2Error('비밀번호는 8~72자(공백 제외)이며 영문, 숫자, 특수문자를 각각 1개 이상 포함해야 합니다.');
      return;
    }
    if (password !== confirmPassword) {
      setStep2Error('비밀번호와 비밀번호 확인이 일치하지 않습니다.');
      return;
    }
    if (!/^\d{6}$/.test(wdrPwd)) {
      setStep2Error('출금 비밀번호는 숫자 6자리로 입력해주세요.');
      return;
    }
    if (wdrPwd !== confirmWdrPwd) {
      setStep2Error('출금 비밀번호와 확인 번호가 일치하지 않습니다.');
      return;
    }
    if (!terms.service || !terms.privacy || !terms.fds) {
      setStep2Error('필수 이용약관 및 FDS 모니터링 동의 항목에 체크해주세요.');
      return;
    }

    setIsSubmitting(true);
    try {
      const res = await registerUser({
        lgnId: id.trim(),
        lgnPwd: password,
        mbrNm: name.trim(),
        emlAddr: email.trim(),
        authId,
        authCode: authCode.trim(),
        wdrPwd,
        initPkgCd,
      });

      if (res.success) {
        setRegisteredInfo({
          ...res.registered,
          lgnId: id.trim(),
          mbrNm: name.trim(),
          emlAddr: email.trim(),
          initPkgCd,
        });
        setStep(3);
      } else {
        if (res.code === 'AUTH_CODE_MISMATCH' || res.code === 'AUTH_CODE_EXPIRED') {
          const remainMsg = res.remainCnt != null ? ` (남은 시도: ${res.remainCnt}회)` : '';
          setStep1Error(`${res.message}${remainMsg}`);
          setStep(1);
        } else if (res.code === 'LGN_ID_DUPLICATED') {
          setIdChecked('taken');
          setStep1Error(res.message);
          setStep(1);
        } else {
          setStep2Error(res.message || '회원가입에 실패했습니다.');
        }
      }
    } finally {
      setIsSubmitting(false);
    }
  };

  // 가입 완료 후 자동 로그인 (가입한 동일 기기 X-Device-Id로 첫 신뢰 접속 KNOWN 로그인)
  const handleFinishAndLogin = async () => {
    if (!registeredInfo) {
      onClose();
      return;
    }
    setIsLoggingIn(true);
    try {
      const loginRes = await authenticateUser(id.trim(), password);
      if (loginRes.success && loginRes.user) {
        onRegisterSuccess(loginRes.user, registeredInfo);
      } else {
        onRegisterSuccess(null, registeredInfo);
      }
      onClose();
    } finally {
      setIsLoggingIn(false);
    }
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
        </div>

        {/* 단계 표시기 (Stepper) */}
        {step < 3 && (
          <div className="stepper-bar">
            <div className={`step-item ${step === 1 ? 'active' : 'completed'}`}>
              <div className="step-num">{step > 1 ? <Check size={14} /> : '1'}</div>
              <span>계정 & 이메일 인증</span>
            </div>
            <div className={`step-connector ${step > 1 ? 'completed' : ''}`} />
            <div className={`step-item ${step === 2 ? 'active' : ''}`}>
              <div className="step-num">2</div>
              <span>비밀번호 & 약관 동의</span>
            </div>
          </div>
        )}

        {/* STEP 1: 기본 계정 정보 + 이메일 인증 코드 */}
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
                아이디 (영문 소문자로 시작, 소문자/숫자 4~16자) <span className="req">*</span>
              </label>
              <div className="input-with-button">
                <div className="form-control">
                  <User size={16} className="input-icon" />
                  <input
                    id="reg-id"
                    value={id}
                    onChange={(e) => {
                      setId(e.target.value.toLowerCase().replace(/[^a-z0-9]/g, ''));
                      setIdChecked(null);
                      setIdMessage('');
                    }}
                    placeholder="예: investor01"
                    maxLength={16}
                    autoComplete="username"
                  />
                </div>
                <button
                  type="button"
                  className="check-id-btn"
                  onClick={handleCheckId}
                  disabled={id.length < 4 || idChecked === 'checking'}
                >
                  {idChecked === 'checking' ? '확인 중...' : '중복 확인'}
                </button>
              </div>
              {idChecked === 'available' && (
                <p className="field-hint success">
                  <CheckCircle2 size={13} /> {idMessage || '사용 가능한 아이디입니다.'}
                </p>
              )}
              {idChecked === 'taken' && (
                <p className="field-hint error">
                  <AlertCircle size={13} /> {idMessage || '이미 사용 중인 아이디입니다.'}
                </p>
              )}
              {idChecked === 'invalid' && (
                <p className="field-hint error">
                  <AlertCircle size={13} /> {idMessage || '영문 소문자로 시작하는 4~16자 소문자/숫자만 가능합니다.'}
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
                  placeholder="대시보드에 표시될 성명 (최대 50자)"
                  maxLength={50}
                />
              </div>
            </div>

            <div className="form-group">
              <label htmlFor="reg-email">
                이메일 주소 <span className="req">*</span>
              </label>
              <div className="input-with-button">
                <div className="form-control">
                  <Mail size={16} className="input-icon" />
                  <input
                    id="reg-email"
                    type="email"
                    value={email}
                    onChange={(e) => setEmail(e.target.value)}
                    placeholder="인증코드 및 FDS 보안 알림 수신용"
                    autoComplete="email"
                    maxLength={100}
                  />
                </div>
                <button
                  type="button"
                  className="check-id-btn"
                  onClick={handleSendEmailCode}
                  disabled={isSendingCode || !email.trim() || (resendCooldown > 0 && codeSentEmail === email.trim())}
                >
                  {isSendingCode
                    ? '발송 중...'
                    : resendCooldown > 0 && codeSentEmail === email.trim()
                    ? `재발송 (${resendCooldown}초)`
                    : authId && codeSentEmail === email.trim()
                    ? '코드 재발송'
                    : '인증코드 발송'}
                </button>
              </div>
            </div>

            {/* 가입 이메일 인증 코드 입력 (5분 만료 · 60초 재발송 제한 표시) */}
            <div className="form-group">
              <div className="label-with-hint" style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                <label htmlFor="reg-auth-code">
                  이메일 인증 코드 (숫자 6자리) <span className="req">*</span>
                </label>
                {authId && (
                  <span style={{ fontSize: '11.5px', color: ttlSec <= 60 ? '#ed707b' : '#4cd49e', display: 'inline-flex', alignItems: 'center', gap: '4px', fontWeight: 600 }}>
                    <Clock size={12} />
                    {ttlSec > 0 ? `유효시간 ${formatTimer(ttlSec)}` : '만료됨 (재발송 필요)'}
                  </span>
                )}
              </div>
              <div className="form-control">
                <KeyRound size={16} className="input-icon" />
                <input
                  id="reg-auth-code"
                  type="text"
                  inputMode="numeric"
                  maxLength={6}
                  value={authCode}
                  onChange={(e) => setAuthCode(e.target.value.replace(/[^0-9]/g, '').slice(0, 6))}
                  placeholder={authId ? '이메일로 수신한 6자리 숫자 입력' : '먼저 [인증코드 발송]을 눌러주세요'}
                  disabled={!authId}
                />
              </div>
              {authId && (
                <p className="field-hint" style={{ color: '#8fa0b5' }}>
                  • 인증 코드는 5분간 유효하며, 60초 간격으로 재발송할 수 있습니다.
                </p>
              )}
            </div>

            <div className="modal-actions">
              <button type="button" className="secondary-btn" onClick={onClose}>
                취소
              </button>
              <button type="button" className="primary-btn" onClick={handleNextStep}>
                다음 단계 (보안 & 초기자금) <ArrowRight size={16} />
              </button>
            </div>
          </div>
        )}

        {/* STEP 2: 로그인 비밀번호 & 출금 비밀번호 & 초기 자금 선택 (PKG_10M, PKG_30M, PKG_50M) */}
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
                  로그인 비밀번호 <span className="req">*</span>
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
                  placeholder="8~72자 영문, 숫자, 특수문자 필수"
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
              <div className="strength-bar-track">
                <div
                  className="strength-bar-fill"
                  style={{
                    width: `${(pwStrength.score / 3) * 100}%`,
                    backgroundColor: pwStrength.color,
                  }}
                />
              </div>
            </div>

            <div className="form-group">
              <label htmlFor="reg-pw-confirm">
                로그인 비밀번호 확인 <span className="req">*</span>
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

            {/* 출금 비밀번호 (숫자 6자리) */}
            <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '10px' }}>
              <div className="form-group">
                <label htmlFor="reg-wdr-pw">
                  출금 비밀번호 (숫자 6자리) <span className="req">*</span>
                </label>
                <div className="form-control password">
                  <KeyRound size={16} className="input-icon" />
                  <input
                    id="reg-wdr-pw"
                    type={showWdrPwd ? 'text' : 'password'}
                    inputMode="numeric"
                    maxLength={6}
                    value={wdrPwd}
                    onChange={(e) => setWdrPwd(e.target.value.replace(/[^0-9]/g, '').slice(0, 6))}
                    placeholder="숫자 6자리"
                    autoComplete="off"
                  />
                  <button
                    type="button"
                    aria-label={showWdrPwd ? '출금 비밀번호 가리기' : '출금 비밀번호 보기'}
                    onClick={() => setShowWdrPwd(!showWdrPwd)}
                  >
                    {showWdrPwd ? <EyeOff size={15} /> : <Eye size={15} />}
                  </button>
                </div>
              </div>

              <div className="form-group">
                <label htmlFor="reg-wdr-pw-confirm">
                  출금 비밀번호 확인 <span className="req">*</span>
                </label>
                <div className="form-control password">
                  <KeyRound size={16} className="input-icon" />
                  <input
                    id="reg-wdr-pw-confirm"
                    type={showWdrPwd ? 'text' : 'password'}
                    inputMode="numeric"
                    maxLength={6}
                    value={confirmWdrPwd}
                    onChange={(e) => setConfirmWdrPwd(e.target.value.replace(/[^0-9]/g, '').slice(0, 6))}
                    placeholder="숫자 6자리 재입력"
                    autoComplete="off"
                  />
                </div>
                {confirmWdrPwd.length > 0 && (
                  <p className={`field-hint ${wdrPwdsMatch ? 'success' : 'error'}`}>
                    {wdrPwdsMatch ? '일치합니다.' : '6자리 숫자가 일치하지 않습니다.'}
                  </p>
                )}
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
              </div>
            </div>

            <div className="modal-actions">
              <button
                type="button"
                className="secondary-btn"
                onClick={() => setStep(1)}
                disabled={isSubmitting}
              >
                <ArrowLeft size={16} /> 이전
              </button>
              <button
                type="submit"
                className="primary-btn"
                disabled={isSubmitting}
              >
                {isSubmitting ? '가입 처리 중...' : '회원가입 완료'} <Check size={16} />
              </button>
            </div>
          </form>
        )}

        {/* STEP 3: 회원가입 완료 축하 화면 */}
        {step === 3 && registeredInfo && (
          <div className="signup-success-view">
            <div className="success-badge-wrap">
              <div className="success-circle">
                <CheckCircle2 size={48} />
              </div>
            </div>
            <h3>환영합니다, {registeredInfo.mbrNm}님!</h3>
            <p className="success-desc">
              회원가입과 계좌 개설이 완료되었습니다.
              <br />
              현재 접속 기기가 첫 신뢰 기기로 안전하게 등록되었습니다.
            </p>

            <div className="account-summary-card">
              <div className="summary-row">
                <span>계정 아이디</span>
                <strong>{registeredInfo.lgnId}</strong>
              </div>
              <div className="summary-row">
                <span>개설 계좌번호</span>
                <strong>{registeredInfo.acntNo}</strong>
              </div>
              <div className="summary-row">
                <span>인증된 이메일</span>
                <span className="muted-text">{registeredInfo.emlAddr}</span>
              </div>
            </div>

            <div className="success-note">
              <Sparkles size={16} />
              <span>
                FDS가 신규 IP·기기 접속 및 고액 출금을 실시간으로 보호합니다.
              </span>
            </div>

            <button
              type="button"
              className="primary-btn complete-btn"
              onClick={handleFinishAndLogin}
              disabled={isLoggingIn}
            >
              {isLoggingIn ? '로그인 중...' : '로그인하고 시작하기'} <ArrowRight size={18} />
            </button>
          </div>
        )}
      </div>
    </div>
  );
}
