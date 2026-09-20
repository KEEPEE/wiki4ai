/**
 * Image upload helpers (WIKI4AI-64) — Cesta A (blob URL interception).
 *
 * Uploaded images are stored on the backend under unguessable UUID names and
 * are served ONLY to authenticated users (hard user decision: login-only, same
 * policy as global search). A browser <img src> cannot send a Bearer header,
 * so markdown stores relative URLs of the form /images/{projectSlug}/{uuid}.{ext}
 * and this module resolves them by fetching the JWT-protected API endpoint
 * (via apiRequest — Bearer token + automatic refresh on 401) and converting
 * the response to an object (blob) URL. No tokens ever appear in image URLs.
 */

import { apiRequest } from './apiClient';

const API_BASE_URL = import.meta.env.VITE_API_URL || '/api/v1';

/** Prefix of markdown URLs that reference uploaded (stored) images. */
export const UPLOAD_IMAGE_PREFIX = '/images/';

/**
 * True when the src references an uploaded image: a same-origin relative path
 * /images/{projectSlug}/{filename} with exactly two non-empty segments after
 * the prefix. External http(s) URLs and any other path are NOT uploaded images
 * (they must keep rendering exactly as before).
 */
export function isUploadedImageSrc(src: string | null | undefined): boolean {
  if (typeof src !== 'string' || !src.startsWith(UPLOAD_IMAGE_PREFIX)) return false;
  const rest = src.slice(UPLOAD_IMAGE_PREFIX.length); // "{projectSlug}/{filename}"
  const segments = rest.split('/');
  if (segments.length !== 2) return false;
  return segments[0].length > 0 && segments[1].length > 0;
}

/**
 * Map from markdown src to resolved object URL. Blob URLs are cheap and shared
 * across components rendering the same image, so they live for the browser
 * session (revoking on one component's unmount would break other viewers of
 * the same image). `clearUploadedImageCache()` revokes them all (tests).
 */
const blobUrlCache = new Map<string, string>();

/** In-flight fetches so concurrent renders of the same image share one request. */
const pendingFetches = new Map<string, Promise<string>>();

/**
 * Resolve an uploaded-image markdown URL to a browser-usable object URL by
 * fetching it through the authenticated API client.
 *
 * @throws Error when the backend responds with a non-OK status (e.g. 401 after
 *          a failed refresh, 404 for a deleted image)
 */
export async function resolveUploadedImageUrl(src: string): Promise<string> {
  const cached = blobUrlCache.get(src);
  if (cached) return cached;

  const inFlight = pendingFetches.get(src);
  if (inFlight) return inFlight;

  const fetchPromise = (async () => {
    // "/images/{projectSlug}/{filename}" -> "${API_BASE_URL}/images/..."
    const response = await apiRequest(`${API_BASE_URL}${src}`);
    if (!response.ok) {
      throw new Error(`Failed to load image (${response.status})`);
    }
    const blob = await response.blob();
    return URL.createObjectURL(blob);
  })()
    .then((url) => {
      blobUrlCache.set(src, url);
      pendingFetches.delete(src);
      return url;
    })
    .catch((error) => {
      pendingFetches.delete(src);
      throw error;
    });

  pendingFetches.set(src, fetchPromise);
  return fetchPromise;
}

/** Test helper: revoke all object URLs and clear the session cache. */
export function clearUploadedImageCache(): void {
  for (const url of blobUrlCache.values()) {
    URL.revokeObjectURL(url);
  }
  blobUrlCache.clear();
  pendingFetches.clear();
}
