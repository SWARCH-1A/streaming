import { Button } from '@/src/components/atoms/Button';
import { Icon } from '@/src/components/atoms/Icon';
import type { Catalog } from '@/src/modules/taxonomy/entry';

import styles from './CategoryRibbon.module.css';

interface CategoryRibbonProps {
  catalog: Catalog;
  selected: string;
  onSelect: (id: string) => void;
}
export function CategoryRibbon({ catalog, selected, onSelect }: CategoryRibbonProps) {
  return (
    <div className={styles.ribbon}>
      {catalog.categories.map((category) => (
        <Button
          key={category.id}
          variant="secondary"
          className={`${styles.category} ${selected === category.id ? styles.selected : ''}`}
          aria-pressed={selected === category.id}
          onClick={() => onSelect(selected === category.id ? '' : category.id)}
        >
          <span className={styles.icon}>
            <Icon name={category.icon} size={26} />
          </span>
          <span>{category.name}</span>
        </Button>
      ))}
    </div>
  );
}
