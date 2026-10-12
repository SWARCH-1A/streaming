const viewersFormatter = new Intl.NumberFormat('es', {
  notation: 'compact',
  maximumFractionDigits: 1,
});
export function formatViewers(value: number) {
  return viewersFormatter.format(value);
}
export function codePointLength(value: string) {
  return Array.from(value).length;
}
export function normalizeSearch(value: string) {
  return value.trim().normalize('NFKC').toLocaleLowerCase('es');
}
