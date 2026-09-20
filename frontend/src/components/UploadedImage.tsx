/**
 * <img> renderer for markdown content (WIKI4AI-64, Cesta A).
 *
 * Uploaded images use relative markdown URLs /images/{projectSlug}/{uuid}.{ext}.
 * A plain <img src> cannot carry the Bearer token and the backend serves these
 * paths ONLY to authenticated users (login-only policy), so this component
 * fetches the image through the authenticated API client and displays it via a
 * blob URL. External http(s) URLs (and any other src) pass through unchanged —
 * no behaviour change for existing documents.
 */

import React, { useEffect, useState } from 'react';
import { isUploadedImageSrc, resolveUploadedImageUrl } from '../services/imageApi';
import './UploadedImage.css';

interface UploadedImageProps extends React.ImgHTMLAttributes<HTMLImageElement> {
  src?: string;
  /** react-markdown passes a `node` prop — destructure it out so it is never spread onto the DOM. */
  node?: unknown;
}

const UploadedImage: React.FC<UploadedImageProps> = ({ src, alt, node: _node, ...rest }) => {
  const isUploaded = isUploadedImageSrc(src);
  const [resolvedUrl, setResolvedUrl] = useState<string | null>(null);
  const [error, setError] = useState(false);

  useEffect(() => {
    if (!isUploaded || !src) return;
    let cancelled = false;
    setResolvedUrl(null);
    setError(false);
    resolveUploadedImageUrl(src)
      .then((url) => {
        if (!cancelled) setResolvedUrl(url);
      })
      .catch(() => {
        if (!cancelled) setError(true);
      });
    return () => {
      cancelled = true;
    };
  }, [src, isUploaded]);

  // External / other URLs — render exactly as before.
  if (!isUploaded) {
    return <img src={src} alt={alt} {...rest} />;
  }

  if (error) {
    return (
      <span className="uploaded-image uploaded-image--error" role="img" aria-label={alt || 'image'}>
        [Image unavailable]
      </span>
    );
  }

  if (!resolvedUrl) {
    return (
      <span className="uploaded-image uploaded-image--loading" role="img" aria-label={alt || 'image'} />
    );
  }

  return <img src={resolvedUrl} alt={alt} {...rest} />;
};

export default UploadedImage;
