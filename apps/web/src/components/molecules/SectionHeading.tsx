import type { ReactNode } from 'react';

import styles from './SectionHeading.module.css';

interface SectionHeadingProps {
  title: string;
  eyebrow?: string;
  action?: ReactNode;
}
export function SectionHeading({ title, eyebrow, action }: SectionHeadingProps) {
  return (
    <div className={styles.heading}>
      <div>
        {eyebrow ? <p className="eyebrow">{eyebrow}</p> : null}
        <h2>{title}</h2>
      </div>
      {action}
    </div>
  );
}
