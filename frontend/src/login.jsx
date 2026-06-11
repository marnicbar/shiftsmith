// login.jsx — full-screen sign-in shown until the user has a valid session.
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import * as api from './lib/api.js';

export function Login({ onSuccess }) {
  const { t } = useTranslation();
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [remember, setRemember] = useState(true);
  const [error, setError] = useState(null);
  const [busy, setBusy] = useState(false);

  async function submit(e) {
    e.preventDefault();
    if (busy) return;
    setBusy(true);
    setError(null);
    try {
      const res = await api.login(username.trim(), password, remember);
      if (res.ok) onSuccess(res.username, res.mustChangePassword);
      else setError(t('auth.invalid'));
    } catch {
      setError(t('auth.failed'));
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="login-screen">
      <form className="card login-card" onSubmit={submit}>
        <div className="login-brand"><span className="logo">S</span><span className="brand-name"><b>Shift</b>Smith</span></div>
        <h1 className="login-title">{t('auth.title')}</h1>
        <p className="login-sub">{t('auth.subtitle')}</p>

        <div className="field">
          <label htmlFor="login-user">{t('auth.username')}</label>
          <input id="login-user" className="input" autoFocus autoComplete="username"
            value={username} onChange={(e) => setUsername(e.target.value)} />
        </div>
        <div className="field">
          <label htmlFor="login-pass">{t('auth.password')}</label>
          <input id="login-pass" className="input" type="password" autoComplete="current-password"
            value={password} onChange={(e) => setPassword(e.target.value)} />
        </div>

        <label className="login-remember">
          <input type="checkbox" checked={remember} onChange={(e) => setRemember(e.target.checked)} />
          {t('auth.remember')}
        </label>

        {error && <div className="login-error">{error}</div>}

        <button type="submit" className="btn primary login-submit" disabled={busy || !username.trim() || !password}>
          {busy ? t('auth.signingIn') : t('auth.signIn')}
        </button>
      </form>
    </div>
  );
}

// Shown after sign-in when the account still uses a seeded, publicly-known
// password: the backend gates every other endpoint behind 403 until it is
// rotated, so we force the change here before the app can load.
export function ForcePasswordChange({ username, onDone }) {
  const { t } = useTranslation();
  const [cur, setCur] = useState('');
  const [next, setNext] = useState('');
  const [confirm, setConfirm] = useState('');
  const [error, setError] = useState(null);
  const [busy, setBusy] = useState(false);

  async function submit(e) {
    e.preventDefault();
    if (busy) return;
    setError(null);
    if (next.length < 6) { setError(t('auth.tooShort')); return; }
    if (next !== confirm) { setError(t('auth.mismatch')); return; }
    setBusy(true);
    try {
      await api.changePassword(cur, next);
      onDone();
    } catch (err) {
      const wrongCurrent = String(err.message || '').includes('403');
      setError(wrongCurrent ? t('auth.currentIncorrect') : t('auth.changeFailed'));
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="login-screen">
      <form className="card login-card" onSubmit={submit}>
        <div className="login-brand"><span className="logo">S</span><span className="brand-name"><b>Shift</b>Smith</span></div>
        <h1 className="login-title">{t('auth.forceTitle')}</h1>
        <p className="login-sub">{t('auth.forceSubtitle')}</p>

        <div className="field">
          <label htmlFor="force-cur">{t('account.currentPassword')}</label>
          <input id="force-cur" className="input" type="password" autoComplete="current-password"
            value={cur} onChange={(e) => setCur(e.target.value)} />
        </div>
        <div className="field">
          <label htmlFor="force-new">{t('account.newPassword')}</label>
          <input id="force-new" className="input" type="password" autoFocus autoComplete="new-password"
            value={next} onChange={(e) => setNext(e.target.value)} />
        </div>
        <div className="field">
          <label htmlFor="force-confirm">{t('account.confirmPassword')}</label>
          <input id="force-confirm" className="input" type="password" autoComplete="new-password"
            value={confirm} onChange={(e) => setConfirm(e.target.value)} />
        </div>

        {error && <div className="login-error">{error}</div>}

        <button type="submit" className="btn primary login-submit" disabled={busy || !cur || !next || !confirm}>
          {busy ? t('auth.signingIn') : t('account.updatePassword')}
        </button>
      </form>
    </div>
  );
}
