import js from '@eslint/js';
import prettier from 'eslint-config-prettier';
import jsxA11y from 'eslint-plugin-jsx-a11y';
import reactHooks from 'eslint-plugin-react-hooks';
import reactRefresh from 'eslint-plugin-react-refresh';
import simpleImportSort from 'eslint-plugin-simple-import-sort';
import globals from 'globals';
import type { ConfigWithExtends } from 'typescript-eslint';
import tseslint from 'typescript-eslint';

const modules = ['accounts', 'channels', 'chat', 'discovery', 'streaming', 'taxonomy'];

export default tseslint.config(
  {
    ignores: [
      'dist/**',
      'coverage/**',
      'node_modules/**',
      'playwright-report/**',
      'test-results/**',
    ],
  },
  js.configs.recommended,
  ...tseslint.configs.recommendedTypeChecked,
  {
    files: ['**/*.{ts,tsx}'],
    languageOptions: {
      globals: { ...globals.browser, ...globals.node },
      parserOptions: { projectService: true, tsconfigRootDir: import.meta.dirname },
    },
    plugins: {
      'jsx-a11y': jsxA11y,
      'react-hooks': reactHooks,
      'react-refresh': reactRefresh,
      'simple-import-sort': simpleImportSort,
    },
    rules: {
      ...jsxA11y.configs.recommended.rules,
      ...reactHooks.configs.recommended.rules,
      'react-refresh/only-export-components': ['error', { allowConstantExport: true }],
      'jsx-a11y/no-noninteractive-tabindex': ['error', { roles: ['log'] }],
      '@typescript-eslint/consistent-type-imports': ['error', { prefer: 'type-imports' }],
      '@typescript-eslint/no-floating-promises': 'error',
      'simple-import-sort/imports': [
        'error',
        {
          groups: [
            ['^node:'],
            ['^react', '^@?\\w'],
            ['^@/public/'],
            ['^@/src/'],
            ['^\\.'],
            ['^\\u0000'],
          ],
        },
      ],
      'simple-import-sort/exports': 'error',
      'no-restricted-imports': [
        'error',
        {
          patterns: [
            {
              regex: '^(\\.\\./|\\./[^/]+/)',
              message:
                'Solo ./Archivo en la misma carpeta; usa @/src/ o @/public/ para otras rutas.',
            },
            {
              regex: '^@/src/modules/[^/]+/(?!entry$|entry\\.)',
              message: 'Consume módulos mediante su entry; sus internals son privados.',
            },
          ],
        },
      ],
    },
  },
  ...modules.map((module): ConfigWithExtends => ({
    files: [`src/modules/${module}/**/*.{ts,tsx}`],
    rules: {
      'no-restricted-imports': [
        'error',
        {
          patterns: [
            { regex: '^(\\.\\./|\\./[^/]+/)', message: 'Usa ./Archivo o el alias @/.' },
            ...modules
              .filter((other) => other !== module)
              .map((other) => ({
                regex: `^@/src/modules/${other}/(?!entry$|entry\\.)`,
                message: 'No importes internals de otro módulo.',
              })),
          ],
        },
      ],
    },
  })),
  {
    files: ['src/**/*.test.{ts,tsx}', 'src/test/**', 'e2e/**'],
    rules: {
      'react-refresh/only-export-components': 'off',
      'no-restricted-imports': [
        'error',
        { patterns: [{ regex: '^(\\.\\./|\\./[^/]+/)', message: 'Usa ./Archivo o @/.' }] },
      ],
    },
  },
  { files: ['src/modules/*/entry.tsx'], rules: { 'react-refresh/only-export-components': 'off' } },
  {
    files: ['src/**/*.tsx'],
    ignores: ['src/components/atoms/**'],
    rules: {
      'no-restricted-syntax': [
        'error',
        {
          selector: "JSXOpeningElement[name.name='button']",
          message: 'Usa el Button compartido con variantes.',
        },
        { selector: "JSXOpeningElement[name.name='input']", message: 'Usa Input compartido.' },
        { selector: "JSXOpeningElement[name.name='select']", message: 'Usa Select compartido.' },
        {
          selector: "JSXOpeningElement[name.name='textarea']",
          message: 'Usa Textarea compartido.',
        },
      ],
    },
  },
  prettier,
);
