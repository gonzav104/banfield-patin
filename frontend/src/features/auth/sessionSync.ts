/**
 * Cross-tab session signals. The session cookie is shared by every tab, so when one tab logs out or signs in, the
 * others must drop what they cached. Messages carry only the event type: no identity, token or personal data.
 */
const CHANNEL = 'bp-session';

export type SessionSignal = { type: 'logout' } | { type: 'login' };

export function broadcastSessionSignal(signal: SessionSignal): void {
  if (typeof BroadcastChannel === 'undefined') return;
  try {
    const channel = new BroadcastChannel(CHANNEL);
    channel.postMessage(signal.type === 'logout' ? { type: 'logout' } : { type: 'login' });
    channel.close();
  } catch {
    // Best effort: each tab still re-checks its session on focus and on the next request.
  }
}

export function subscribeSessionSignals(handler: (signal: SessionSignal) => void): () => void {
  if (typeof BroadcastChannel === 'undefined') return () => undefined;
  const channel = new BroadcastChannel(CHANNEL);
  channel.onmessage = (event: MessageEvent<unknown>) => {
    const type = (event.data as { type?: unknown } | null)?.type;
    if (type === 'logout' || type === 'login') handler({ type });
  };
  return () => channel.close();
}
