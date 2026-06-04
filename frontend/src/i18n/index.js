// i18n/index.js — i18next setup. English + German UI, bundled as static resources.
// The active language is mirrored in the localStorage prefs bag (`shiftsmith.prefs`,
// key `lang`) so it survives reloads alongside the other appearance preferences;
// App.jsx is the source of truth for that bag and calls i18n.changeLanguage on edit.
import i18n from 'i18next';
import { initReactI18next } from 'react-i18next';
import en from './locales/en.json';
import de from './locales/de.json';

// The languages offered in Settings. `value` is the i18next/BCP-47 code.
export const LANGUAGES = [
  { value: 'en', label: 'English' },
  { value: 'de', label: 'Deutsch' },
];

const PREF_KEY = 'shiftsmith.prefs';
function savedLang() {
  try {
    const lang = JSON.parse(localStorage.getItem(PREF_KEY) || '{}').lang;
    if (lang && LANGUAGES.some((l) => l.value === lang)) return lang;
  } catch { /* ignore */ }
  return 'en';
}

i18n.use(initReactI18next).init({
  resources: { en: { translation: en }, de: { translation: de } },
  lng: savedLang(),
  fallbackLng: 'en',
  interpolation: { escapeValue: false }, // React already escapes
  react: { useSuspense: false },         // resources are bundled — never suspend
});

// Locale tag for Intl date/number formatting, derived from the active language.
export const dateLocale = () => (i18n.language && i18n.language.startsWith('de') ? 'de-DE' : 'en-US');
// German conventionally uses a 24-hour clock; English the 12-hour clock.
export const is24h = () => !!(i18n.language && i18n.language.startsWith('de'));

export default i18n;
