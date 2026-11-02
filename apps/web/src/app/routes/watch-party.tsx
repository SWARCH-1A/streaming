import { WatchPartyRoom } from '@/src/modules/watchparty/entry';

export function meta() {
  return [{ title: 'Sala de visualización conjunta · Vault tv' }];
}

export function headers() {
  return { 'Cache-Control': 'no-store, private' };
}

export default WatchPartyRoom;
