import { Link } from 'react-router';

import logo from '@/public/brand/vault-tv.png';

import styles from './Brand.module.css';

export function Brand() {
  return (
    <Link to="/" className={styles.brand} aria-label="Vault tv, inicio">
      <span className={styles.mark} aria-hidden="true">
        <img src={logo} alt="" width={1600} height={581} />
      </span>
    </Link>
  );
}
