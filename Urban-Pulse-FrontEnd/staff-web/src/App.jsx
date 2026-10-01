import React, { useState, useEffect } from 'react';
import { loginWithToken } from './services/apiService';
import { auth, RecaptchaVerifier, signInWithPhoneNumber } from './config/firebase';
import AdminView from './components/AdminView';
import SupervisorView from './components/SupervisorView';
import WorkerView from './components/WorkerView';
import './index.css';

export default function App() {
  const [phoneNumber, setPhoneNumber] = useState('');
  const [otp, setOtp] = useState('');
  const [step, setStep] = useState('MOBILE_INPUT'); // 'MOBILE_INPUT' | 'OTP_INPUT'
  const [confirmationResult, setConfirmationResult] = useState(null);
  const [idToken, setIdToken] = useState(null);
  const [backendUser, setBackendUser] = useState(null);
  const [loading, setLoading] = useState(false);
  const [errorMessage, setErrorMessage] = useState(null);

  // Safely clear old verifier and recreate pristine container DOM element
  const clearRecaptcha = () => {
    if (window.recaptchaVerifier) {
      try {
        window.recaptchaVerifier.clear();
      } catch (e) {
        console.warn('Error clearing recaptchaVerifier:', e);
      }
      window.recaptchaVerifier = null;
    }
    const wrapper = document.getElementById('recaptcha-wrapper');
    if (wrapper) {
      wrapper.innerHTML = '<div id="recaptcha-container"></div>';
    }
  };

  useEffect(() => {
    // Ensure clean state on mount & unmount
    clearRecaptcha();
    return () => {
      clearRecaptcha();
    };
  }, []);

  const getFreshRecaptchaVerifier = () => {
    clearRecaptcha();

    const container = document.getElementById('recaptcha-container');
    if (!container) {
      throw new Error('reCAPTCHA container element not found in DOM. Please reload.');
    }

    window.recaptchaVerifier = new RecaptchaVerifier(auth, container, {
      size: 'invisible',
      callback: () => {
        // Recaptcha solved automatically
      },
      'expired-callback': () => {
        setErrorMessage('reCAPTCHA expired. Please try sending OTP again.');
        clearRecaptcha();
      }
    });

    return window.recaptchaVerifier;
  };

  const formatFullMobileNumber = (raw) => {
    const cleaned = raw.replace(/\D/g, '');
    if (raw.trim().startsWith('+')) {
      return '+' + cleaned;
    }
    if (cleaned.length === 10) {
      return '+91' + cleaned;
    }
    return '+' + cleaned;
  };

  const handleSendOtp = async (e) => {
    e?.preventDefault();
    if (loading) return; // Prevent duplicate clicks

    const formattedPhone = formatFullMobileNumber(phoneNumber);

    if (!phoneNumber.trim() || formattedPhone.length < 12) {
      setErrorMessage('Please enter a valid 10-digit mobile number.');
      return;
    }

    setLoading(true);
    setErrorMessage(null);

    try {
      const verifier = getFreshRecaptchaVerifier();
      const confirmation = await signInWithPhoneNumber(auth, formattedPhone, verifier);
      setConfirmationResult(confirmation);
      setStep('OTP_INPUT');
    } catch (err) {
      console.error('Firebase Send OTP Error:', err);
      let userMsg = err?.message || 'Failed to send OTP. Please check mobile number and try again.';
      if (userMsg.includes('reCAPTCHA has already been rendered')) {
        userMsg = 'reCAPTCHA verification state refreshed. Please click Send OTP again.';
      }
      setErrorMessage(userMsg);
      clearRecaptcha();
    } finally {
      setLoading(false);
    }
  };

  const handleVerifyOtp = async (e) => {
    e?.preventDefault();
    if (!otp.trim() || otp.trim().length !== 6) {
      setErrorMessage('Please enter a valid 6-digit OTP code.');
      return;
    }

    if (!confirmationResult) {
      setErrorMessage('Session expired. Please request a new OTP.');
      setStep('MOBILE_INPUT');
      return;
    }

    setLoading(true);
    setErrorMessage(null);

    try {
      // 1. Verify OTP with Firebase
      const userCredential = await confirmationResult.confirm(otp.trim());
      // 2. Obtain Firebase ID Token automatically
      const firebaseIdToken = await userCredential.user.getIdToken();

      // 3. Authenticate with Spring Boot backend (POST /api/auth/login)
      const res = await loginWithToken(firebaseIdToken);

      if (res && res.authenticated) {
        setIdToken(firebaseIdToken);
        setBackendUser(res);
      } else {
        throw new Error(res?.message || 'Authentication rejected by backend.');
      }
    } catch (err) {
      console.error('OTP Verification / Backend Login Error:', err);
      setErrorMessage(err.message || 'Invalid OTP code or authentication failed.');
    } finally {
      setLoading(false);
    }
  };

  const handleResendOtp = async () => {
    if (loading) return;
    setOtp('');
    setErrorMessage(null);
    await handleSendOtp();
  };

  const handleResetMobile = () => {
    clearRecaptcha();
    setStep('MOBILE_INPUT');
    setOtp('');
    setConfirmationResult(null);
    setErrorMessage(null);
  };

  const handleLogout = () => {
    auth.signOut().catch(() => {});
    clearRecaptcha();
    setIdToken(null);
    setBackendUser(null);
    setPhoneNumber('');
    setOtp('');
    setStep('MOBILE_INPUT');
    setConfirmationResult(null);
    setErrorMessage(null);
  };

  return (
    <div style={{ minHeight: '100vh', display: 'flex', flexDirection: 'column' }}>
      {/* Invisible Recaptcha Container with Parent Wrapper */}
      <div id="recaptcha-wrapper">
        <div id="recaptcha-container"></div>
      </div>

      {/* Navbar Header */}
      <header style={{
        backgroundColor: '#0F172A',
        borderBottom: '1px solid #1E293B',
        padding: '16px 24px',
        display: 'flex',
        justifyContent: 'space-between',
        alignItems: 'center'
      }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: '12px' }}>
          <h1 className="gradient-title" style={{ fontSize: '22px', fontWeight: '800' }}>UrbanPulse Staff</h1>
          <span style={{
            fontSize: '11px',
            backgroundColor: 'rgba(2, 132, 199, 0.2)',
            color: '#38BDF8',
            border: '1px solid rgba(56, 189, 248, 0.4)',
            padding: '2px 8px',
            borderRadius: '12px',
            fontWeight: '600'
          }}>
            Civic Operations Staff Portal
          </span>
        </div>

        {backendUser && (
          <div style={{ display: 'flex', alignItems: 'center', gap: '16px' }}>
            <div style={{ textAlign: 'right', fontSize: '13px' }}>
              <div style={{ color: '#F8FAFC', fontWeight: '600' }}>{backendUser.mobileNumber}</div>
              <div style={{ color: '#38BDF8', fontWeight: '700', fontSize: '11px' }}>
                Role: {backendUser.role}
              </div>
            </div>
            <button onClick={handleLogout} className="btn-primary" style={{ backgroundColor: '#334155', padding: '6px 12px', fontSize: '13px' }}>
              Logout
            </button>
          </div>
        )}
      </header>

      {/* Main Container */}
      <main style={{ flex: 1, backgroundColor: '#0B1120' }}>
        {!backendUser ? (
          <div style={{ display: 'flex', justifyContent: 'center', alignItems: 'center', minHeight: 'calc(100vh - 100px)', padding: '20px' }}>
            <div className="glass-card" style={{ width: '100%', maxWidth: '440px', padding: '32px' }}>
              <div style={{ textAlign: 'center', marginBottom: '24px' }}>
                <h2 style={{ fontSize: '24px', fontWeight: '700', color: '#F8FAFC' }}>UrbanPulse Staff Portal</h2>
                <p style={{ fontSize: '13px', color: '#94A3B8', marginTop: '6px' }}>
                  Secure Firebase Phone OTP Authentication
                </p>
              </div>

              {step === 'MOBILE_INPUT' ? (
                <form onSubmit={handleSendOtp} style={{ display: 'flex', flexDirection: 'column', gap: '16px' }}>
                  <div>
                    <label style={{ fontSize: '13px', fontWeight: '500', color: '#CBD5E1', display: 'block', marginBottom: '6px' }}>
                      Mobile Number:
                    </label>
                    <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
                      <span style={{
                        backgroundColor: '#1E293B',
                        border: '1px solid #334155',
                        color: '#94A3B8',
                        padding: '10px 14px',
                        borderRadius: '6px',
                        fontSize: '14px',
                        fontWeight: '600'
                      }}>
                        +91
                      </span>
                      <input
                        type="tel"
                        className="input-field"
                        style={{ flex: 1, fontSize: '15px', letterSpacing: '1px' }}
                        placeholder="Enter 10-digit mobile number..."
                        value={phoneNumber}
                        onChange={(e) => setPhoneNumber(e.target.value)}
                        maxLength={10}
                        required
                      />
                    </div>
                  </div>

                  {errorMessage && (
                    <div style={{
                      padding: '10px 14px',
                      borderRadius: '6px',
                      backgroundColor: 'rgba(239, 68, 68, 0.2)',
                      border: '1px solid rgba(239, 68, 68, 0.4)',
                      color: '#F87171',
                      fontSize: '13px'
                    }}>
                      {errorMessage}
                    </div>
                  )}

                  <button type="submit" className="btn-primary" disabled={loading} style={{ height: '44px', fontSize: '15px' }}>
                    {loading ? 'Sending OTP...' : 'SEND OTP'}
                  </button>
                </form>
              ) : (
                <form onSubmit={handleVerifyOtp} style={{ display: 'flex', flexDirection: 'column', gap: '16px' }}>
                  <div style={{
                    backgroundColor: '#1E293B',
                    padding: '10px 14px',
                    borderRadius: '6px',
                    display: 'flex',
                    justifyContent: 'space-between',
                    alignItems: 'center',
                    fontSize: '13px'
                  }}>
                    <span style={{ color: '#94A3B8' }}>
                      OTP sent to <strong style={{ color: '#F8FAFC' }}>+91 {phoneNumber}</strong>
                    </span>
                    <div style={{ display: 'flex', gap: '10px' }}>
                      <button
                        type="button"
                        onClick={handleResendOtp}
                        disabled={loading}
                        style={{ background: 'none', border: 'none', color: '#38BDF8', cursor: 'pointer', fontSize: '12px', textDecoration: 'underline' }}
                      >
                        {loading ? 'Resending...' : 'Resend OTP'}
                      </button>
                      <button
                        type="button"
                        onClick={handleResetMobile}
                        disabled={loading}
                        style={{ background: 'none', border: 'none', color: '#94A3B8', cursor: 'pointer', fontSize: '12px', textDecoration: 'underline' }}
                      >
                        Change
                      </button>
                    </div>
                  </div>

                  <div>
                    <label style={{ fontSize: '13px', fontWeight: '500', color: '#CBD5E1', display: 'block', marginBottom: '6px' }}>
                      Enter OTP:
                    </label>
                    <input
                      type="text"
                      className="input-field"
                      style={{ width: '100%', fontSize: '18px', textAlign: 'center', letterSpacing: '6px', fontWeight: '700' }}
                      placeholder="______"
                      value={otp}
                      onChange={(e) => setOtp(e.target.value)}
                      maxLength={6}
                      autoFocus
                      required
                    />
                  </div>

                  {errorMessage && (
                    <div style={{
                      padding: '10px 14px',
                      borderRadius: '6px',
                      backgroundColor: 'rgba(239, 68, 68, 0.2)',
                      border: '1px solid rgba(239, 68, 68, 0.4)',
                      color: '#F87171',
                      fontSize: '13px'
                    }}>
                      {errorMessage}
                    </div>
                  )}

                  <button type="submit" className="btn-primary" disabled={loading} style={{ height: '44px', fontSize: '15px' }}>
                    {loading ? 'Verifying with Backend...' : 'VERIFY OTP'}
                  </button>
                </form>
              )}
            </div>
          </div>
        ) : (
          <div>
            {backendUser.role === 'ADMIN' && <AdminView idToken={idToken} user={backendUser} />}
            {backendUser.role === 'SUPERVISOR' && <SupervisorView idToken={idToken} user={backendUser} />}
            {backendUser.role === 'WORKER' && <WorkerView idToken={idToken} user={backendUser} />}
            {backendUser.role === 'CITIZEN' && (
              <div style={{ padding: '60px', textAlign: 'center', color: '#F87171' }}>
                Access Denied: Logged in user is registered as CITIZEN in PostgreSQL. Staff Web Portal is restricted to ADMIN, SUPERVISOR, and WORKER roles.
              </div>
            )}
          </div>
        )}
      </main>
    </div>
  );
}
