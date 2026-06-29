import React, { useEffect, useRef, useState, useCallback } from 'react';
import {
  Animated,
  Pressable,
  StyleSheet,
  Text,
  View,
  Platform,
  ActivityIndicator,
} from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { useSipContext } from '@/modules/sip/SipContext';
import { sipAcceptCall, sipRejectCall, sipHangup, sipSetMuted } from '@/modules/sip';

const formatDuration = (seconds: number): string => {
  const m = Math.floor(seconds / 60).toString().padStart(2, '0');
  const s = (seconds % 60).toString().padStart(2, '0');
  return `${m}:${s}`;
};

export function SipCallOverlay() {
  const { state, incomingCall } = useSipContext();
  const insets = useSafeAreaInsets();

  const [durationSecs, setDurationSecs] = useState(0);
  const [isMuted, setIsMuted] = useState(false);
  // Prevents double-tap: locks buttons from first tap until state transitions
  const [isConnecting, setIsConnecting] = useState(false);

  // Fade-in so buttons are at their final position immediately (not sliding in)
  const opacityAnim = useRef(new Animated.Value(0)).current;
  const pulseAnim = useRef(new Animated.Value(1)).current;
  const pulseLoop = useRef<Animated.CompositeAnimation | null>(null);

  const isVisible = state.callState === 'ringing_in' || state.callState === 'active';

  // Fade in fast, fade out slightly slower
  useEffect(() => {
    Animated.timing(opacityAnim, {
      toValue: isVisible ? 1 : 0,
      duration: isVisible ? 120 : 200,
      useNativeDriver: true,
    }).start();
  }, [isVisible, opacityAnim]);

  // Pulse ring animation
  useEffect(() => {
    if (state.callState === 'ringing_in') {
      pulseLoop.current = Animated.loop(
        Animated.sequence([
          Animated.timing(pulseAnim, { toValue: 1.15, duration: 600, useNativeDriver: true }),
          Animated.timing(pulseAnim, { toValue: 1, duration: 600, useNativeDriver: true }),
        ])
      );
      pulseLoop.current.start();
    } else {
      pulseLoop.current?.stop();
      pulseAnim.setValue(1);
    }
    return () => pulseLoop.current?.stop();
  }, [state.callState, pulseAnim]);

  // Duration counter
  useEffect(() => {
    if (state.callState !== 'active') {
      setDurationSecs(0);
      return;
    }
    const interval = setInterval(() => setDurationSecs(s => s + 1), 1000);
    return () => clearInterval(interval);
  }, [state.callState]);

  // Reset connecting lock when call state settles
  useEffect(() => {
    if (state.callState === 'active' || state.callState === 'idle') {
      setIsConnecting(false);
    }
  }, [state.callState]);

  // Reset mute when idle
  useEffect(() => {
    if (state.callState === 'idle') setIsMuted(false);
  }, [state.callState]);

  const handleAccept = useCallback(() => {
    if (isConnecting) return;
    setIsConnecting(true);
    sipAcceptCall().catch(() => setIsConnecting(false));
  }, [isConnecting]);

  const handleReject = useCallback(() => {
    if (isConnecting) return;
    setIsConnecting(true);
    sipRejectCall().catch(() => setIsConnecting(false));
  }, [isConnecting]);

  const handleHangup = useCallback(() => {
    sipHangup().catch(() => {});
  }, []);

  const handleToggleMute = useCallback(() => {
    const next = !isMuted;
    setIsMuted(next);
    sipSetMuted(next).catch(() => {});
  }, [isMuted]);

  const callerName =
    incomingCall?.callerName || state.callerName || state.callerId || 'Llamada entrante';
  const callerId = incomingCall?.callerId || state.callerId || '';

  const isRinging = state.callState === 'ringing_in';
  const isActive = state.callState === 'active';

  return (
    <Animated.View
      pointerEvents={isVisible ? 'auto' : 'none'}
      style={[styles.container, { top: insets.top + 8, opacity: opacityAnim }]}
    >
      {/* Header: status */}
      <View style={styles.header}>
        <View style={[styles.statusPill, isActive ? styles.pillActive : styles.pillRinging]}>
          <View style={[styles.statusDot, isActive ? styles.dotActive : styles.dotRinging]} />
          <Text style={styles.statusText}>
            {isActive ? `En llamada · ${formatDuration(durationSecs)}` : 'Llamada entrante'}
          </Text>
        </View>
      </View>

      {/* Caller */}
      <View style={styles.callerRow}>
        <Animated.View style={[styles.avatarWrap, { transform: [{ scale: isRinging ? pulseAnim : 1 }] }]}>
          <View style={[styles.avatarCircle, isActive && styles.avatarActive]}>
            <Text style={styles.avatarInitial}>{callerName.charAt(0).toUpperCase()}</Text>
          </View>
        </Animated.View>
        <View style={styles.callerInfo}>
          <Text style={styles.callerName} numberOfLines={1}>{callerName}</Text>
          {callerId ? (
            <Text style={styles.callerId} numberOfLines={1}>{callerId}</Text>
          ) : null}
          {isConnecting && (
            <Text style={styles.connectingText}>Conectando...</Text>
          )}
        </View>
      </View>

      {/* Buttons */}
      <View style={styles.actionsRow}>
        {isActive ? (
          <>
            <CallButton
              label={isMuted ? 'Activar mic' : 'Silenciar'}
              icon={isMuted ? '🔇' : '🎤'}
              color={isMuted ? '#F59E0B' : '#64748B'}
              onPress={handleToggleMute}
            />
            <CallButton
              label="Colgar"
              icon="📵"
              color="#EF4444"
              onPress={handleHangup}
            />
          </>
        ) : (
          <>
            <CallButton
              label="Rechazar"
              icon="📵"
              color="#EF4444"
              onPress={handleReject}
              disabled={isConnecting}
            />
            <CallButton
              label={isConnecting ? 'Conectando' : 'Contestar'}
              icon={isConnecting ? null : '📞'}
              color="#22C55E"
              onPress={handleAccept}
              disabled={isConnecting}
              loading={isConnecting}
            />
          </>
        )}
      </View>
    </Animated.View>
  );
}

// ── Reusable button ──────────────────────────────────────────────────────────

type CallButtonProps = {
  label: string;
  icon?: string | null;
  color: string;
  onPress: () => void;
  disabled?: boolean;
  loading?: boolean;
};

function CallButton({ label, icon, color, onPress, disabled, loading }: CallButtonProps) {
  const scale = useRef(new Animated.Value(1)).current;

  const onPressIn = () =>
    Animated.spring(scale, { toValue: 0.93, useNativeDriver: true, speed: 50 }).start();
  const onPressOut = () =>
    Animated.spring(scale, { toValue: 1, useNativeDriver: true, speed: 50 }).start();

  return (
    <Animated.View style={[styles.btnWrap, { transform: [{ scale }] }]}>
      <Pressable
        style={[styles.btn, { backgroundColor: disabled ? '#CBD5E1' : color }]}
        onPress={disabled ? undefined : onPress}
        onPressIn={disabled ? undefined : onPressIn}
        onPressOut={disabled ? undefined : onPressOut}
        android_ripple={disabled ? undefined : { color: 'rgba(255,255,255,0.25)' }}
      >
        {loading ? (
          <ActivityIndicator color="#FFFFFF" size="small" />
        ) : (
          <>
            {icon ? <Text style={styles.btnIcon}>{icon}</Text> : null}
            <Text style={styles.btnLabel}>{label}</Text>
          </>
        )}
      </Pressable>
    </Animated.View>
  );
}

// ── Styles ───────────────────────────────────────────────────────────────────

const styles = StyleSheet.create({
  container: {
    position: 'absolute',
    left: 12,
    right: 12,
    backgroundColor: '#FFFFFF',
    borderRadius: 24,
    padding: 16,
    gap: 14,
    zIndex: 9999,
    ...Platform.select({
      android: { elevation: 16 },
      ios: {
        shadowColor: '#000',
        shadowOffset: { width: 0, height: 6 },
        shadowOpacity: 0.18,
        shadowRadius: 16,
      },
    }),
  },
  header: {
    alignItems: 'flex-start',
  },
  statusPill: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 6,
    paddingHorizontal: 10,
    paddingVertical: 4,
    borderRadius: 20,
  },
  pillActive: { backgroundColor: '#DCFCE7' },
  pillRinging: { backgroundColor: '#DBEAFE' },
  statusDot: {
    width: 7,
    height: 7,
    borderRadius: 4,
  },
  dotActive: { backgroundColor: '#16A34A' },
  dotRinging: { backgroundColor: '#2563EB' },
  statusText: {
    fontSize: 12,
    fontWeight: '600',
    color: '#374151',
  },
  callerRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 14,
    paddingHorizontal: 4,
  },
  avatarWrap: {},
  avatarCircle: {
    width: 52,
    height: 52,
    borderRadius: 26,
    backgroundColor: '#2563EB',
    alignItems: 'center',
    justifyContent: 'center',
  },
  avatarActive: {
    backgroundColor: '#16A34A',
  },
  avatarInitial: {
    color: '#FFFFFF',
    fontSize: 22,
    fontWeight: '700',
  },
  callerInfo: {
    flex: 1,
  },
  callerName: {
    fontSize: 17,
    fontWeight: '700',
    color: '#0F172A',
    marginBottom: 2,
  },
  callerId: {
    fontSize: 13,
    color: '#64748B',
  },
  connectingText: {
    fontSize: 12,
    color: '#2563EB',
    fontWeight: '500',
    marginTop: 2,
  },
  actionsRow: {
    flexDirection: 'row',
    gap: 10,
    paddingTop: 4,
  },
  btnWrap: {
    flex: 1,
  },
  btn: {
    flexDirection: 'row',
    paddingVertical: 14,
    borderRadius: 14,
    alignItems: 'center',
    justifyContent: 'center',
    gap: 6,
  },
  btnIcon: {
    fontSize: 16,
  },
  btnLabel: {
    color: '#FFFFFF',
    fontWeight: '700',
    fontSize: 15,
  },
});
