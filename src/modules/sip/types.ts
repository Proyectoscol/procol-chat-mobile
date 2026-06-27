export interface SipCredentials {
  sip_extension: string;
  sip_password: string;
  wss_url: string;
  sip_domain: string;
  ice_servers: IceServer[];
}

export interface IceServer {
  urls: string[];
  username?: string;
  credential?: string;
}

export type SipRegistrationState =
  | 'idle'
  | 'registering'
  | 'registered'
  | 'unregistering'
  | 'failed';

export type SipCallState = 'idle' | 'ringing_in' | 'ringing_out' | 'active' | 'ending';

export interface SipState {
  registrationState: SipRegistrationState;
  callState: SipCallState;
  callerId: string | null;
  callerName: string | null;
  activeCallId: string | null;
}

export interface IncomingCallPayload {
  callId: string;
  callerId: string;
  callerName: string;
}

export interface CallEndedPayload {
  callId: string;
  reason: string;
  durationSeconds: number;
}

export interface RegistrationFailedPayload {
  reason: string;
  code: number;
}

export const SIP_EVENTS = {
  REGISTERED: 'sip.registered',
  UNREGISTERED: 'sip.unregistered',
  REGISTRATION_FAILED: 'sip.registrationFailed',
  INCOMING_CALL: 'sip.incomingCall',
  CALL_CONNECTED: 'sip.callConnected',
  CALL_ENDED: 'sip.callEnded',
  CALL_FAILED: 'sip.callFailed',
  CALL_CANCELLED: 'sip.callCancelled',
} as const;
