/**
 * Content-ready handshake between the global LoadingScreen splash and the
 * page-level loaders (WIKI4AI-82).
 *
 * The splash previously stayed up for a hardcoded 1000 ms, so on fresh loads of
 * data-driven pages (search, graph, viewer) it overlapped with the page's own
 * loader — two spinners at once. Now every top-level route signals
 * `wiki4ai:content-ready` as soon as its shell commits to the DOM (via
 * useLayoutEffect, i.e. before the browser paints), and the splash fades out in
 * that same frame. The page-level loader then becomes the single visible
 * spinner while data is actually being fetched.
 */

export const CONTENT_READY_EVENT = 'wiki4ai:content-ready';

/** Signal that the current route's shell has rendered (call from useLayoutEffect). */
export function signalContentReady(): void {
  if (typeof window !== 'undefined') {
    window.dispatchEvent(new Event(CONTENT_READY_EVENT));
  }
}
