import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { CONTENT_READY_EVENT } from '../utils/appReady';

/**
 * Global startup splash (WIKI4AI-82: coordinated with page-level loaders).
 *
 * The splash hides as soon as the current route signals `wiki4ai:content-ready`
 * (every top-level route does this in a useLayoutEffect, i.e. before first
 * paint), so it never overlaps a page loader — only one spinner is visible at
 * any time. A 3 s safety cap hides it if no signal ever arrives (e.g. a render
 * error before any route mounts).
 */
export default function LoadingScreen() {
  const { t } = useTranslation();
  const [visible, setVisible] = useState(true);

  useEffect(() => {
    const hide = () => setVisible(false);
    window.addEventListener(CONTENT_READY_EVENT, hide);
    // Safety net: never trap the user behind the splash.
    const timer = setTimeout(hide, 3000);

    return () => {
      window.removeEventListener(CONTENT_READY_EVENT, hide);
      clearTimeout(timer);
    };
  }, []);

  return (
    <div className={`loading-screen ${visible ? '' : 'hidden'}`}>
      <div className="loader-ring" />
      <div className="loading-text">{t('app.initializingExperience')}</div>
    </div>
  );
}
