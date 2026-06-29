import React from 'react';
import { Platform, Pressable, StyleSheet, Text, View } from 'react-native';
import { useSipContext } from '@/modules/sip/SipContext';
import { sipUnregister, sipRegister } from '@/modules/sip';

type StatusConfig = {
  dot: string;
  label: string;
  sublabel?: string;
};

const STATUS_CONFIG: Record<string, StatusConfig> = {
  registered: { dot: '#22C55E', label: 'Conectado' },
  registering: { dot: '#F59E0B', label: 'Conectando...' },
  unregistering: { dot: '#F59E0B', label: 'Desconectando...' },
  failed: { dot: '#EF4444', label: 'Error de conexión', sublabel: 'Toca para reintentar' },
  idle: { dot: '#94A3B8', label: 'Desconectado' },
};

export function SipStatusBadge() {
  if (Platform.OS !== 'android') return null;

  const { state } = useSipContext();
  const regState = state.registrationState;
  const cfg = STATUS_CONFIG[regState] ?? STATUS_CONFIG.idle;

  const canRetry = regState === 'failed' || regState === 'idle';

  return (
    <View style={styles.container}>
      {/* Left: dot + labels */}
      <View style={styles.left}>
        <View style={[styles.dot, { backgroundColor: cfg.dot }]} />
        <View>
          <Text style={styles.label}>{cfg.label}</Text>
          {cfg.sublabel ? (
            <Text style={styles.sublabel}>{cfg.sublabel}</Text>
          ) : regState === 'registered' && state.activeCallId ? (
            <Text style={styles.sublabelActive}>En llamada</Text>
          ) : null}
        </View>
      </View>

      {/* Right: call state badge */}
      {state.callState !== 'idle' && (
        <View style={[styles.callBadge, getCallBadgeStyle(state.callState)]}>
          <Text style={styles.callBadgeText}>{getCallStateLabel(state.callState)}</Text>
        </View>
      )}
    </View>
  );
}

function getCallStateLabel(callState: string): string {
  switch (callState) {
    case 'ringing_in':
      return '📞 Entrante';
    case 'ringing_out':
      return '📲 Saliente';
    case 'active':
      return '🔊 En llamada';
    default:
      return callState;
  }
}

function getCallBadgeStyle(callState: string) {
  switch (callState) {
    case 'ringing_in':
      return styles.badgeBlue;
    case 'active':
      return styles.badgeGreen;
    default:
      return styles.badgeSlate;
  }
}

const styles = StyleSheet.create({
  container: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingHorizontal: 16,
    paddingVertical: 12,
    backgroundColor: '#F8FAFC',
    borderRadius: 12,
    marginHorizontal: 16,
    marginBottom: 8,
    ...Platform.select({
      android: { elevation: 1 },
      ios: {
        shadowColor: '#000',
        shadowOffset: { width: 0, height: 1 },
        shadowOpacity: 0.05,
        shadowRadius: 3,
      },
    }),
  },
  left: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 10,
  },
  dot: {
    width: 10,
    height: 10,
    borderRadius: 5,
  },
  label: {
    fontSize: 14,
    fontWeight: '600',
    color: '#0F172A',
  },
  sublabel: {
    fontSize: 12,
    color: '#94A3B8',
    marginTop: 1,
  },
  sublabelActive: {
    fontSize: 12,
    color: '#22C55E',
    marginTop: 1,
    fontWeight: '500',
  },
  callBadge: {
    paddingHorizontal: 10,
    paddingVertical: 4,
    borderRadius: 20,
  },
  callBadgeText: {
    fontSize: 12,
    fontWeight: '600',
    color: '#FFFFFF',
  },
  badgeBlue: { backgroundColor: '#3B82F6' },
  badgeGreen: { backgroundColor: '#22C55E' },
  badgeSlate: { backgroundColor: '#94A3B8' },
});
