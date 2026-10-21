import { useEffect, useState } from 'react';

/**
 * Return a wall-clock snapshot for a visible countdown without ticking its
 * parent component. Hidden documents do not keep a visual timer alive.
 */
export function useCountdownNow(active: boolean): number {
  const [now, setNow] = useState(Date.now);

  useEffect(() => {
    if (!active) return;

    const update = () => setNow(Date.now());
    let timer: number | undefined;
    const stopTimer = () => {
      if (timer !== undefined) {
        window.clearInterval(timer);
        timer = undefined;
      }
    };
    const onVisibilityChange = () => {
      stopTimer();
      if (!document.hidden) {
        update();
        timer = window.setInterval(update, 1000);
      }
    };
    document.addEventListener('visibilitychange', onVisibilityChange);
    // A document that starts hidden gets its timer from the listener; the
    // single cleanup below also clears that timer on unmount.
    if (!document.hidden) timer = window.setInterval(update, 1000);
    return () => {
      stopTimer();
      document.removeEventListener('visibilitychange', onVisibilityChange);
    };
  }, [active]);

  return now;
}

