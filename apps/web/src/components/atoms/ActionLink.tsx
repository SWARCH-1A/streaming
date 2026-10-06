import type { ReactNode } from 'react';
import { Link } from 'react-router';

import styles from './Button.module.css';
import type { ButtonSize, ButtonVariant } from './Button.types';

interface ActionLinkProps {
  to: string;
  children: ReactNode;
  variant?: ButtonVariant;
  size?: ButtonSize;
  className?: string | undefined;
  'aria-label'?: string;
}

export function ActionLink({
  to,
  children,
  variant = 'primary',
  size = 'md',
  className = '',
  ...props
}: ActionLinkProps) {
  return (
    <Link
      to={to}
      className={`${styles.button} ${styles[variant]} ${styles[size]} ${className}`}
      {...props}
    >
      {children}
    </Link>
  );
}
