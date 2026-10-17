import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';

export default defineConfig({
  plugins: [react()],
  test: {
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.ts'],
    include: ['src/**/*.test.ts', 'src/**/*.test.tsx'],
    // jsdom component suites are slow on memory-constrained laptops; the default
    // 5s limit turns scheduler contention into false failures.
    testTimeout: 15000,
    restoreMocks: true,
    clearMocks: true,
  },
});
