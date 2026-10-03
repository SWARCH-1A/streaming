import type { FormEvent } from 'react';
import { useState } from 'react';
import { Link, useNavigate } from 'react-router';

import background from '@/public/images/studio-1.jpg';

import { Button } from '@/src/components/atoms/Button';
import { Icon } from '@/src/components/atoms/Icon';
import { Input } from '@/src/components/atoms/Input';
import { FormField } from '@/src/components/molecules/FormField';
import { Notice } from '@/src/components/molecules/Notice';
import { Panel } from '@/src/components/molecules/Panel';
import { useSession } from '@/src/shell/session/useSession';

import styles from './AuthPage.module.css';
import type { RegistrationValues } from './validation';
import { validateRegistration } from './validation';

export function AuthPage({ mode }: { mode: 'login' | 'register' }) {
  const register = mode === 'register';
  const [values, setValues] = useState({ email: '', handle: '', password: '' });
  const [errors, setErrors] = useState<Partial<Record<keyof RegistrationValues, string>>>({});
  const [done, setDone] = useState(false);
  const [visible, setVisible] = useState(false);
  const { signIn } = useSession();
  const navigate = useNavigate();
  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const nextErrors = register
      ? validateRegistration(values)
      : {
          ...(!values.handle.trim() ? { handle: 'Escribe tu handle de demostración.' } : {}),
          ...(!values.password ? { password: 'Escribe una contraseña de demostración.' } : {}),
        };
    setErrors(nextErrors);
    if (Object.keys(nextErrors).length) {
      document.getElementById(Object.keys(nextErrors)[0] ?? '')?.focus();
      return;
    }
    if (register) {
      setValues((previous) => ({ ...previous, password: '' }));
      setDone(true);
    } else {
      signIn(values.handle.trim().toLowerCase());
      setValues((previous) => ({ ...previous, password: '' }));
      void navigate('/studio');
    }
  }
  function change(field: keyof RegistrationValues, value: string) {
    setValues((previous) => ({ ...previous, [field]: value }));
    setErrors((previous) => ({ ...previous, [field]: undefined }));
  }
  return (
    <div className={styles.layout}>
      <div className={styles.visual}>
        <img src={background} alt="" />
        <div>
          <Icon name="broadcast" size={48} />
          <h2>
            Tu comunidad.
            <br />
            Tu escenario.
          </h2>
          <p>Comparte lo que te apasiona, en directo.</p>
        </div>
      </div>
      <Panel className={styles.form}>
        <p className="eyebrow">Identidad & acceso</p>
        <h1>{register ? 'Crea tu cuenta' : 'Bienvenido de nuevo'}</h1>
        <p className="muted">
          {register
            ? 'El primer paso hacia tu próximo directo.'
            : 'Entra y vuelve a conectar con tu comunidad.'}
        </p>
        <Notice>
          Modo de demostración. Usa datos ficticios; no se enviarán ni guardarán credenciales.
        </Notice>
        {done ? (
          <div className="stack">
            <Notice tone="success">
              Formulario validado. Continúa con una sesión de demostración.
            </Notice>
            <Link to="/login">Ir a iniciar sesión →</Link>
          </div>
        ) : (
          <form className="stack" onSubmit={submit} noValidate>
            {register ? (
              <FormField id="email" label="Correo electrónico" error={errors.email}>
                <Input
                  id="email"
                  type="email"
                  autoComplete="email"
                  value={values.email}
                  onChange={(event) => change('email', event.target.value)}
                  aria-invalid={!!errors.email}
                  aria-describedby={errors.email ? 'email-error' : undefined}
                  required
                />
              </FormField>
            ) : null}
            <FormField
              id="handle"
              label={register ? 'Handle' : 'Handle de demostración'}
              error={errors.handle}
              hint={register ? 'De 4 a 25 letras, números o guiones bajos.' : undefined}
            >
              <Input
                id="handle"
                autoComplete="username"
                value={values.handle}
                onChange={(event) => change('handle', event.target.value)}
                aria-invalid={!!errors.handle}
                aria-describedby={
                  errors.handle ? 'handle-error' : register ? 'handle-hint' : undefined
                }
                required
              />
            </FormField>
            <FormField
              id="password"
              label="Contraseña"
              error={errors.password}
              hint={register ? 'De 12 a 128 caracteres, sin reglas de composición.' : undefined}
            >
              <div className={styles.password}>
                <Input
                  id="password"
                  type={visible ? 'text' : 'password'}
                  autoComplete={register ? 'new-password' : 'current-password'}
                  value={values.password}
                  onChange={(event) => change('password', event.target.value)}
                  aria-invalid={!!errors.password}
                  aria-describedby={
                    errors.password ? 'password-error' : register ? 'password-hint' : undefined
                  }
                  required
                />
                <Button
                  size="icon"
                  variant="ghost"
                  aria-label={visible ? 'Ocultar contraseña' : 'Mostrar contraseña'}
                  aria-pressed={visible}
                  onClick={() => setVisible((previous) => !previous)}
                >
                  <Icon name="eye" />
                </Button>
              </div>
            </FormField>
            <Button type="submit" size="lg">
              {register ? 'Crear cuenta de demostración' : 'Iniciar sesión de demostración'}
              <Icon name="arrow" />
            </Button>
          </form>
        )}
        <p className={styles.alternate}>
          {register ? '¿Ya tienes una cuenta?' : '¿Primera vez aquí?'}{' '}
          <Link to={register ? '/login' : '/register'}>
            {register ? 'Iniciar sesión' : 'Crear cuenta'}
          </Link>
        </p>
      </Panel>
    </div>
  );
}
