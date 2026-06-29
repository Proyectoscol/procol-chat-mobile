import React, { createContext, useContext } from 'react';
import { useSip } from './useSip';

type SipContextValue = ReturnType<typeof useSip>;

const SipContext = createContext<SipContextValue | null>(null);

export function SipProvider({ children }: { children: React.ReactNode }) {
  const sip = useSip();
  return <SipContext.Provider value={sip}>{children}</SipContext.Provider>;
}

export function useSipContext(): SipContextValue {
  const ctx = useContext(SipContext);
  if (!ctx) throw new Error('useSipContext must be used inside <SipProvider>');
  return ctx;
}
