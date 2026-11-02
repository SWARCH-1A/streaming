// The access code is shown once by Core. It lives only in this tab's memory (never in storage, URL
// or history) until the owner hides it or the page reloads.
const codes = new Map<string, string>();

export function revealCode(partyId: string, code: string) {
  codes.set(partyId, code);
}
export function peekCode(partyId: string): string | null {
  return codes.get(partyId) ?? null;
}
export function hideCode(partyId: string) {
  codes.delete(partyId);
}
