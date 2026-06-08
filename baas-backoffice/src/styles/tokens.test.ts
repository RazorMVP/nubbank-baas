// @vitest-environment node
import { readFileSync } from 'node:fs';
import { describe, it, expect } from 'vitest';

const css = readFileSync(new URL('./tokens.css', import.meta.url), 'utf8');

describe('design tokens', () => {
  it.each([
    ['--color-brand-primary', '#0078d4'],
    ['--color-brand-accent', '#28a8ea'],
    ['--color-ink', '#1a1a1a'],
    ['--color-surface', '#ffffff'],
    ['--color-surface-ink', '#23262e'],
    ['--color-bg-app', '#f5f7fa'],
    ['--color-tint-blue', '#e8f3fc'],
    ['--color-muted', '#6b7280'],
    ['--color-border', '#e6e9ef'],
    ['--color-success', '#1f9d57'],
    ['--color-warning', '#b7791f'],
    ['--color-danger', '#d64545'],
  ])('defines %s = %s', (name, value) => {
    const re = new RegExp(`${name}:\\s*${value}`, 'i');
    expect(css).toMatch(re);
  });

  it('defines radius tokens', () => {
    expect(css).toMatch(/--radius-card:\s*14px/);
    expect(css).toMatch(/--radius-control:\s*8px/);
    expect(css).toMatch(/--radius-pill:\s*999px/);
  });
});
