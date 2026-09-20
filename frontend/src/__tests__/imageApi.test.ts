/**
 * Unit tests for the image upload helpers (WIKI4AI-64, Cesta A).
 *
 * isUploadedImageSrc decides which markdown <img> sources are intercepted;
 * resolveUploadedImageUrl fetches them through the authenticated API client
 * and converts the response to a blob URL. The apiClient module is mocked —
 * only the image logic under test is real.
 */

import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';

const { mockApiRequest } = vi.hoisted(() => ({ mockApiRequest: vi.fn() }));

vi.mock('../services/apiClient', () => ({
  apiRequest: (url: string, options?: unknown) => mockApiRequest(url, options),
}));

import {
  isUploadedImageSrc,
  resolveUploadedImageUrl,
  clearUploadedImageCache,
  UPLOAD_IMAGE_PREFIX,
} from '../services/imageApi';

describe('isUploadedImageSrc', () => {
  it('accepts /images/{projectSlug}/{filename}', () => {
    expect(isUploadedImageSrc('/images/my-project/123e4567-e89b-42d3-a456-426614174000.png')).toBe(true);
    expect(isUploadedImageSrc('/images/a/b.jpg')).toBe(true);
  });

  it('rejects external URLs even when they contain /images/', () => {
    expect(isUploadedImageSrc('https://example.com/images/foo/bar.png')).toBe(false);
    expect(isUploadedImageSrc('http://example.com/images/foo/bar.png')).toBe(false);
  });

  it('rejects wrong segment counts and empty segments', () => {
    expect(isUploadedImageSrc('/images/onlyproject')).toBe(false); // 1 segment
    expect(isUploadedImageSrc('/images/a/b/c.png')).toBe(false); // 3 segments
    expect(isUploadedImageSrc('/images//bar.png')).toBe(false); // empty slug
    expect(isUploadedImageSrc('/images/foo/')).toBe(false); // empty filename
  });

  it('rejects non-image paths, data URLs and null/undefined', () => {
    expect(isUploadedImageSrc('/uploads/foo/bar.png')).toBe(false);
    expect(isUploadedImageSrc('/api/v1/images/foo/bar.png')).toBe(false);
    expect(isUploadedImageSrc('data:image/png;base64,AAA=')).toBe(false);
    expect(isUploadedImageSrc(undefined)).toBe(false);
    expect(isUploadedImageSrc(null)).toBe(false);
  });

  it('exposes the documented prefix constant', () => {
    expect(UPLOAD_IMAGE_PREFIX).toBe('/images/');
  });
});

describe('resolveUploadedImageUrl', () => {
  let objectUrlCounter = 0;

  beforeEach(() => {
    mockApiRequest.mockReset();
    clearUploadedImageCache();
    URL.createObjectURL = vi.fn(() => `blob:unit-${++objectUrlCounter}`);
    URL.revokeObjectURL = vi.fn();
  });

  afterEach(() => {
    clearUploadedImageCache();
  });

  it('fetches through the authenticated API endpoint and returns a blob URL', async () => {
    const src = '/images/my-project/123e4567-e89b-42d3-a456-426614174000.png';
    mockApiRequest.mockResolvedValue({
      ok: true,
      status: 200,
      blob: async () => new Blob([new Uint8Array([1, 2, 3])], { type: 'image/png' }),
    });

    const url = await resolveUploadedImageUrl(src);

    expect(url.startsWith('blob:')).toBe(true);
    expect(mockApiRequest).toHaveBeenCalledTimes(1);
    expect(String(mockApiRequest.mock.calls[0][0])).toContain(`/api/v1${src}`);
  });

  it('caches the blob URL — a second call does not refetch', async () => {
    const src = '/images/my-project/aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee.png';
    mockApiRequest.mockResolvedValue({
      ok: true,
      status: 200,
      blob: async () => new Blob(),
    });

    const first = await resolveUploadedImageUrl(src);
    const second = await resolveUploadedImageUrl(src);

    expect(first).toBe(second);
    expect(mockApiRequest).toHaveBeenCalledTimes(1);
  });

  it('shares one in-flight request across concurrent callers', async () => {
    const src = '/images/my-project/aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee.png';
    let resolveResponse: (r: unknown) => void;
    mockApiRequest.mockReturnValue(
      new Promise((resolve) => {
        resolveResponse = resolve;
      }),
    );

    const p1 = resolveUploadedImageUrl(src);
    const p2 = resolveUploadedImageUrl(src);
    resolveResponse!({ ok: true, status: 200, blob: async () => new Blob() });

    await Promise.all([p1, p2]);
    expect(mockApiRequest).toHaveBeenCalledTimes(1);
  });

  it('throws when the backend responds non-OK and does not cache the failure', async () => {
    const src = '/images/my-project/aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee.png';
    mockApiRequest.mockResolvedValue({ ok: false, status: 401, blob: async () => new Blob() });

    await expect(resolveUploadedImageUrl(src)).rejects.toThrow(/401/);

    // A retry after a (hypothetical) refresh must re-fetch.
    mockApiRequest.mockResolvedValue({ ok: true, status: 200, blob: async () => new Blob() });
    await expect(resolveUploadedImageUrl(src)).resolves.toMatch(/^blob:/);
    expect(mockApiRequest).toHaveBeenCalledTimes(2);
  });

  it('clearUploadedImageCache revokes all object URLs', async () => {
    mockApiRequest.mockResolvedValue({ ok: true, status: 200, blob: async () => new Blob() });
    await resolveUploadedImageUrl('/images/a/aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee.png');
    await resolveUploadedImageUrl('/images/b/aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee.png');

    clearUploadedImageCache();
    expect(URL.revokeObjectURL).toHaveBeenCalledTimes(2);
  });
});
