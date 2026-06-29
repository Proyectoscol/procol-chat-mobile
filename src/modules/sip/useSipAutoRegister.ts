import { useEffect, useRef } from 'react';
import { Platform } from 'react-native';
import { useAppSelector } from '@/hooks';
import { selectLoggedIn } from '@/store/auth/authSelectors';
import { apiService } from '@/services/APIService';
import { sipGetState, sipRegister, sipUnregister } from './index';

export function useSipAutoRegister() {
  if (Platform.OS !== 'android') return;

  const isLoggedIn = useAppSelector(selectLoggedIn);
  const registeredRef = useRef(false);

  useEffect(() => {
    if (!isLoggedIn) {
      if (registeredRef.current) {
        registeredRef.current = false;
        sipUnregister().catch(() => {});
      }
      return;
    }

    if (registeredRef.current) return;

    let cancelled = false;
    (async () => {
      try {
        const state = await sipGetState();
        if (cancelled) return;
        if (
          state.registrationState === 'registered' ||
          state.registrationState === 'registering'
        ) {
          registeredRef.current = true;
          return;
        }

        const response = await apiService.get<Record<string, unknown>>('sip/credential');
        if (cancelled) return;
        const creds = response.data;

        registeredRef.current = true;
        await sipRegister(creds as never);
      } catch (err) {
        // eslint-disable-next-line no-console
        console.warn('[SipAutoRegister] Failed to fetch/register:', err);
      }
    })();

    return () => {
      cancelled = true;
    };
  }, [isLoggedIn]);
}
