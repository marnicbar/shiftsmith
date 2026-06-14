// error-boundary.jsx — last line of defence so a render-time exception shows a
// recoverable fallback instead of white-screening the whole SPA (issue #40).
import React from 'react';
import i18n from './i18n/index.js';

export class ErrorBoundary extends React.Component {
  constructor(props) {
    super(props);
    this.state = { failed: false };
  }

  static getDerivedStateFromError() {
    return { failed: true };
  }

  componentDidCatch(error, info) {
    // Keep a trace for support; the UI just offers a reload.
    console.error('Unhandled render error:', error, info);
  }

  render() {
    if (!this.state.failed) return this.props.children;
    // Resolve strings via the i18n instance directly (no hook in a class), with
    // English defaults so the fallback works even if i18n itself failed to load.
    const t = (key, def) => i18n.t(key, { defaultValue: def });
    return (
      <div className="error-boundary" role="alert">
        <h1>{t('app.errorTitle', 'Something went wrong')}</h1>
        <p>{t('app.errorBody', 'The app hit an unexpected error. Reloading usually fixes it.')}</p>
        <button type="button" className="btn primary" onClick={() => window.location.reload()}>
          {t('app.errorReload', 'Reload')}
        </button>
      </div>
    );
  }
}
