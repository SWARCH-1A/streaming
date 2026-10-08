import type { FormEvent } from 'react';
import { useEffect } from 'react';
import { useNavigate, useSearchParams } from 'react-router';

import { Button } from '@/src/components/atoms/Button';
import { Icon } from '@/src/components/atoms/Icon';
import { Input } from '@/src/components/atoms/Input';

import styles from './SearchForm.module.css';

export function SearchForm() {
  const [params] = useSearchParams();
  const navigate = useNavigate();
  useEffect(() => {
    function keydown(event: KeyboardEvent) {
      if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'k') {
        event.preventDefault();
        document.getElementById('global-search')?.focus();
      }
    }
    window.addEventListener('keydown', keydown);
    return () => window.removeEventListener('keydown', keydown);
  }, []);
  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const raw = new FormData(event.currentTarget).get('q');
    const query = typeof raw === 'string' ? raw.trim() : '';
    void navigate(`/search${query ? `?${new URLSearchParams({ q: query }).toString()}` : ''}`);
  }
  return (
    <form className={styles.search} onSubmit={submit} role="search">
      <label htmlFor="global-search" className="sr-only">
        Buscar canales o transmisiones
      </label>
      <Input
        id="global-search"
        name="q"
        key={params.get('q') ?? ''}
        defaultValue={params.get('q') ?? ''}
        placeholder="Buscar…"
        type="search"
      />
      <Button variant="ghost" size="icon" type="submit" aria-label="Buscar">
        <Icon name="search" size={18} />
      </Button>
    </form>
  );
}
