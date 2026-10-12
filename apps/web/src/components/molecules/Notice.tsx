import type { ReactNode } from 'react';

import { Icon } from '@/src/components/atoms/Icon';

import styles from './Notice.module.css';

interface NoticeProps {
  children: ReactNode;
  tone?: 'info' | 'success' | 'warning' | 'error';
}
export function Notice({ children, tone = 'info' }: NoticeProps) {
  return (
    <div
      className={`${styles.notice} ${styles[tone]}`}
      role={tone === 'error' ? 'alert' : 'status'}
    >
      <Icon
        name={
          tone === 'error' || tone === 'warning' ? 'alert' : tone === 'success' ? 'check' : 'info'
        }
      />
      <div>{children}</div>
    </div>
  );
}
