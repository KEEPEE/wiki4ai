import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';

export default function LoadingScreen() {
  const { t } = useTranslation();
  const [visible, setVisible] = useState(true);

  useEffect(() => {
    // Hide loading screen after initial render (1000ms delay)
    const timer = setTimeout(() => {
      setVisible(false);
    }, 1000);

    return () => clearTimeout(timer);
  }, []);

  return (
    <div className={`loading-screen ${visible ? '' : 'hidden'}`}>
      <div className="loader-ring" />
      <div className="loading-text">{t('app.initializingExperience')}</div>
    </div>
  );
}
