// Vitest setup: register jest-dom matchers (toBeInTheDocument, toHaveTextContent, …)
// and clean up the DOM between tests.
import '@testing-library/jest-dom/vitest';
import { afterEach } from 'vitest';
import { cleanup } from '@testing-library/react';
// Initialize i18next (defaults to English) so components using useTranslation
// render their text in tests without needing an explicit provider.
import '../i18n/index.js';

afterEach(() => {
  cleanup();
});
