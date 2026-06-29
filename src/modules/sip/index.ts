import { NativeEventEmitter, NativeModules, Platform } from 'react-native';
import type {
  SipCredentials,
  SipState,
  IncomingCallPayload,
  CallEndedPayload,
  RegistrationFailedPayload,
} from './types';
export * from './types';

const { SipModule } = NativeModules;

if (Platform.OS === 'android' && !SipModule) {
  // This fires on Android if the native module is not linked.
  // Check that SipPackage is registered in MainApplication.kt and that
  // the pjsua2.aar is in android/app/libs/ before building.
  console.warn('[SipNative] SipModule not found - run `pnpm sip:setup` and rebuild the app');
}

const emitter = SipModule ? new NativeEventEmitter(SipModule) : null;

// ── Core API ──────────────────────────────────────────────────────────────────

/**
 * Register the SIP UA against Asterisk.
 * Pass the raw JSON response from GET /api/v1/accounts/:id/sip/credential.
 */
export function sipRegister(credentials: SipCredentials): Promise<void> {
  if (Platform.OS !== 'android') return Promise.resolve();
  return SipModule.register(JSON.stringify(credentials));
}

/** Unregister and stop the Foreground Service. */
export function sipUnregister(): Promise<void> {
  if (Platform.OS !== 'android') return Promise.resolve();
  return SipModule.unregister();
}

/** Answer the current incoming call. */
export function sipAcceptCall(): Promise<void> {
  if (Platform.OS !== 'android') return Promise.resolve();
  return SipModule.acceptCall();
}

/** Reject the current incoming call. */
export function sipRejectCall(): Promise<void> {
  if (Platform.OS !== 'android') return Promise.resolve();
  return SipModule.rejectCall();
}

/** Hang up the active call. */
export function sipHangup(): Promise<void> {
  if (Platform.OS !== 'android') return Promise.resolve();
  return SipModule.hangup();
}

/**
 * Initiate an outbound call.
 * @param target - Extension number or full E.164 phone number (e.g. "9001" or "+573001234567")
 */
export function sipStartCall(target: string): Promise<void> {
  if (Platform.OS !== 'android') return Promise.resolve();
  return SipModule.startCall(target);
}

/** Mute or unmute the microphone during a call. */
export function sipSetMuted(muted: boolean): Promise<void> {
  if (Platform.OS !== 'android') return Promise.resolve();
  return SipModule.setMuted(muted);
}

/** Get the FCM push token for this device (used to register with backend). */
export function sipGetFcmToken(): Promise<string | null> {
  if (Platform.OS !== 'android') return Promise.resolve(null);
  return SipModule.getFcmToken();
}

/** Route audio to speaker (true) or earpiece (false). */
export function sipSetSpeaker(enabled: boolean): Promise<void> {
  if (Platform.OS !== 'android') return Promise.resolve();
  return SipModule.setSpeaker(enabled);
}

/** Read the current SIP state synchronously from the native layer. */
export function sipGetState(): Promise<SipState> {
  if (Platform.OS !== 'android') {
    return Promise.resolve({
      registrationState: 'idle',
      callState: 'idle',
      callerId: null,
      callerName: null,
      activeCallId: null,
    });
  }
  return SipModule.getState();
}

// ── Event subscriptions ───────────────────────────────────────────────────────

export function onSipRegistered(cb: (payload: { extension: string }) => void) {
  return emitter?.addListener('sip.registered', cb);
}

export function onSipUnregistered(cb: (payload: { extension: string }) => void) {
  return emitter?.addListener('sip.unregistered', cb);
}

export function onSipRegistrationFailed(cb: (payload: RegistrationFailedPayload) => void) {
  return emitter?.addListener('sip.registrationFailed', cb);
}

export function onSipIncomingCall(cb: (payload: IncomingCallPayload) => void) {
  return emitter?.addListener('sip.incomingCall', cb);
}

export function onSipCallConnected(cb: (payload: { callId: string }) => void) {
  return emitter?.addListener('sip.callConnected', cb);
}

export function onSipCallEnded(cb: (payload: CallEndedPayload) => void) {
  return emitter?.addListener('sip.callEnded', cb);
}

export function onSipCallFailed(cb: (payload: { callId: string; reason: string }) => void) {
  return emitter?.addListener('sip.callFailed', cb);
}

export function onSipCallCancelled(cb: (payload: { callId: string }) => void) {
  return emitter?.addListener('sip.callCancelled', cb);
}
