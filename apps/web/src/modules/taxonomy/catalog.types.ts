import type { IconName } from '@/src/components/atoms/Icon';

export interface CatalogValue {
  id: string;
  name: string;
}
export interface Category extends CatalogValue {
  icon: IconName;
}
export interface Catalog {
  categories: readonly Category[];
  tags: readonly CatalogValue[];
}
