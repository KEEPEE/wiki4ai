/**
 * Custom React hook for debouncing a value.
 * Returns the debounced value after the specified delay.
 */

import { useState, useEffect } from 'react';

/**
 * Debounce a value by the specified delay (in milliseconds).
 * @param value - The value to debounce
 * @param delay - Delay in milliseconds (default: 1000)
 * @returns The debounced value
 */
export function useDebounce<T>(value: T, delay: number = 1000): T {
  const [debouncedValue, setDebouncedValue] = useState<T>(value);

  useEffect(() => {
    const timer = setTimeout(() => {
      setDebouncedValue(value);
    }, delay);

    return () => {
      clearTimeout(timer);
    };
  }, [value, delay]);

  return debouncedValue;
}
