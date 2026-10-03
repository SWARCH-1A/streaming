import type { ReactNode } from 'react';

import type { IconName } from '@/src/components/atoms/Icon';
import { Icon } from '@/src/components/atoms/Icon';
import { Panel } from '@/src/components/molecules/Panel';

import styles from './EmptyState.module.css';

interface EmptyStateProps {
  title: string;
  description: string;
  icon?: IconName;
  action?: ReactNode;
}
export function EmptyState({ title, description, icon = 'search', action }: EmptyStateProps) {
  return (
    <Panel className={styles.empty}>
      <Icon name={icon} size={36} />
      <h3>{title}</h3>
      <p>{description}</p>
      {action}
    </Panel>
  );
}
