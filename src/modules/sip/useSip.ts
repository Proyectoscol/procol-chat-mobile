import { useEffect, useRef, useState } from 'react';
import {
  sipGetState,
  onSipRegistered,
  onSipUnregistered,
  onSipRegistrationFailed,
  onSipIncomingCall,
  onSipCallConnected,
  onSipCallEnded,
  onSipCallFailed,
  onSipCallCancelled,
} from './index';
import type { SipState, IncomingCallPayload } from './types';

const INITIAL_STATE: SipState = {
  registrationState: 'idle',
  callState: 'idle',
  callerId: null,
  callerName: null,
  activeCallId: null,
};

/**
 * React hook that subscribes to all SIP events and exposes current state.
 *
 * Mount once near the root of the authenticated part of the app (e.g. inside
 * the tabs navigator after login) so the state is available app-wide.
 *
 * Usage:
 *   const { state, incomingCall } = useSip();
 *   if (state.callState === 'ringing_in') { ... show incoming call UI ... }
 */
export function useSip() {
  const [state, setState] = useState<SipState>(INITIAL_STATE);
  const [incomingCall, setIncomingCall] = useState<IncomingCallPayload | null>(null);

  const isMounted = useRef(true);

  useEffect(() => {
    isMounted.current = true;

    // Sync initial state from native layer (handles app reload / fast refresh)
    sipGetState().then(s => {
      if (isMounted.current) setState(s);
    });

    const subs = [
      onSipRegistered(({ extension }) => {
        if (!isMounted.current) return;
        setState(prev => ({ ...prev, registrationState: 'registered' }));
      }),

      onSipUnregistered(() => {
        if (!isMounted.current) return;
        setState(INITIAL_STATE);
        setIncomingCall(null);
      }),

      onSipRegistrationFailed(({ reason, code }) => {
        if (!isMounted.current) return;
        setState(prev => ({ ...prev, registrationState: 'failed' }));
      }),

      onSipIncomingCall(payload => {
        if (!isMounted.current) return;
        setIncomingCall(payload);
        setState(prev => ({
          ...prev,
          callState: 'ringing_in',
          callerId: payload.callerId,
          callerName: payload.callerName,
          activeCallId: payload.callId,
        }));
      }),

      onSipCallConnected(({ callId }) => {
        if (!isMounted.current) return;
        setState(prev => ({ ...prev, callState: 'active', activeCallId: callId }));
      }),

      onSipCallEnded(({ callId, durationSeconds }) => {
        if (!isMounted.current) return;
        setState(prev => ({
          ...prev,
          callState: 'idle',
          callerId: null,
          callerName: null,
          activeCallId: null,
        }));
        setIncomingCall(null);
      }),

      onSipCallFailed(() => {
        if (!isMounted.current) return;
        setState(prev => ({
          ...prev,
          callState: 'idle',
          callerId: null,
          callerName: null,
          activeCallId: null,
        }));
        setIncomingCall(null);
      }),

      onSipCallCancelled(() => {
        if (!isMounted.current) return;
        setState(prev => ({
          ...prev,
          callState: 'idle',
          callerId: null,
          callerName: null,
          activeCallId: null,
        }));
        setIncomingCall(null);
      }),
    ];

    return () => {
      isMounted.current = false;
      subs.forEach(sub => sub?.remove());
    };
  }, []);

  return { state, incomingCall };
}
