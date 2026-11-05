import type { InputHTMLAttributes } from 'react';

/** Native checkbox: keeps keyboard, focus and screen-reader semantics; the label wraps it. */
export function Checkbox(props: Omit<InputHTMLAttributes<HTMLInputElement>, 'type'>) {
  return (
    <input type="checkbox" style={{ accentColor: 'var(--color-primary, #7c5cff)' }} {...props} />
  );
}
