import { Link } from 'react-router';

import { Icon } from '@/src/components/atoms/Icon';

import styles from './Brand.module.css';

export function Brand() {
  return (
    <Link to="/" className={styles.brand} aria-label="STREAMING, inicio">
      <span>
        <Icon name="broadcast" size={24} />
      </span>
      STREAMING
      <i aria-hidden="true" />
    </Link>
  );
}
