// i18n smoke test: the resource files stay in sync (same keys EN/DE) and switching
// language re-renders a component's text. Resets the language afterwards so the rest
// of the suite keeps its English default.
import { describe, it, expect, afterEach, vi } from 'vitest';
import { render, screen, act } from '@testing-library/react';
import i18n, { dateLocale, is24h, LANGUAGES } from './i18n/index.js';
import en from './i18n/locales/en.json';
import de from './i18n/locales/de.json';
import { Dashboard } from './dashboard.jsx';

// Recursively collect every leaf key path, ignoring plural suffixes so EN/DE can
// each carry their own _one/_other variants without being flagged.
function leafKeys(obj, prefix = '') {
  const out = [];
  for (const [k, v] of Object.entries(obj)) {
    const key = (prefix ? `${prefix}.` : '') + k.replace(/_(one|other|zero|two|few|many)$/, '');
    if (v && typeof v === 'object' && !Array.isArray(v)) out.push(...leafKeys(v, key));
    else out.push(key);
  }
  return [...new Set(out)];
}

afterEach(async () => {
  await act(async () => { await i18n.changeLanguage('en'); });
});

describe('i18n resources', () => {
  it('offers English and German', () => {
    expect(LANGUAGES.map((l) => l.value)).toEqual(['en', 'de']);
  });

  it('has matching key sets in English and German', () => {
    expect(leafKeys(de).sort()).toEqual(leafKeys(en).sort());
  });
});

describe('language switching', () => {
  it('renders German text after switching, English by default', async () => {
    const props = { employees: [], positions: [], sched: { total: 10, staffed: 8, unassigned: 2 }, onGo: vi.fn() };

    const { rerender } = render(<Dashboard {...props} />);
    expect(screen.getByText('Coverage')).toBeInTheDocument();

    await act(async () => { await i18n.changeLanguage('de'); });
    rerender(<Dashboard {...props} />);
    expect(screen.getByText('Abdeckung')).toBeInTheDocument();
    expect(screen.queryByText('Coverage')).not.toBeInTheDocument();
  });

  it('derives the Intl locale and clock format from the language', async () => {
    await i18n.changeLanguage('en');
    expect(dateLocale()).toBe('en-US');
    expect(is24h()).toBe(false);
    await i18n.changeLanguage('de');
    expect(dateLocale()).toBe('de-DE');
    expect(is24h()).toBe(true);
  });
});
