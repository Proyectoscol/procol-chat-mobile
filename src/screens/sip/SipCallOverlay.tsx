import React, { useEffect, useRef, useState } from 'react';
import {
  Animated,
  Modal,
  Pressable,
  StyleSheet,
  Text,
  View,
  Platform,
} from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { useSipContext } from '@/modules/sip/SipContext';
import { sipAcceptCall, sipRejectCall, sipHangup, sipSetMuted } from '@/modules/sip';

const formatDuration = (seconds: number): string => {
  const m = Math.floor(seconds / 60)
    .toString()
    .padStart(2, '0');
  const s = (seconds % 60).toString().padStart(2, '0');
  return `${m}:${s}`;
};

export function SipCallOverlay() {
  const { state, incomingCall } = useSipContext();
  const insets = useSafeAreaInsets();

  const [durationSecs, setDurationSecs] = useState(0);
  const [isMuted, setIsMuted] = useState(false);

  const slideAnim = useRef(new Animated.Value(200)).current;
  const isVisible = state.callState === 'ringing_in' || state.callState === 'active';

  // Slide up / down animation
  useEffect(() => {
    Animated.spring(slideAnim, {
      toValue: isVisible ? 0 : 200,
      useNativeDriver: true,
      damping: 18,
      stiffness: 200,
    }).start();
  }, [isVisible, slideAnim]);

  // Duration counter for active calls
  useEffect(() => {
    if (state.callState !== 'active') {
      setDurationSecs(0);
      return;
    }
    const interval = setInterval(() => setDurationSecs(s => s + 1), 1000);
    return () => clearInterval(interval);
  }, [state.callState]);

  // Reset mute when call ends
  useEffect(() => {
    if (state.callState === 'idle') setIsMuted(false);
  }, [state.callState]);

  const handleAccept = () => sipAcceptCall().catch(() => {});
  const handleReject = () => sipRejectCall().catch(() => {});
  const handleHangup = () => sipHangup().catch(() => {});
  const handleToggleMute = () => {
    const next = !isMuted;
    setIsMuted(next);
    sipSetMuted(next).catch(() => {});
  };

  const callerName =
    incomingCall?.callerName || state.callerName || state.callerId || 'Llamada SIP';
  const callerId = incomingCall?.callerId || state.callerId || '';

  if (!isVisible) return null;

  return (
    <Animated.View
      style={[
        styles.container,
        { bottom: insets.bottom + 16, transform: [{ translateY: slideAnim }] },
      ]}>
      {/* Status bar */}
      <View style={styles.statusRow}>
        <View
          style={[
            styles.statusDot,
            state.callState === 'active' ? styles.dotActive : styles.dotRinging,
          ]}
        />
        <Text style={styles.statusText}>
          {state.callState === 'active'
            ? `En llamada  ${formatDuration(durationSecs)}`
            : 'Llamada entrante'}
        </Text>
      </View>

      {/* Caller info */}
      <View style={styles.callerRow}>
        <View style={styles.avatarCircle}>
          <Text style={styles.avatarInitial}>
            {callerName.charAt(0).toUpperCase()}
          </Text>
        </View>
        <View style={styles.callerInfo}>
          <Text style={styles.callerName} numberOfLines={1}>
            {callerName}
          </Text>
          {callerId ? (
            <Text style={styles.callerId} numberOfLines={1}>
              {callerId}
            </Text>
          ) : null}
        </View>
      </View>

      {/* Action buttons */}
      <View style={styles.actionsRow}>
        {state.callState === 'active' ? (
          <>
            <Pressable
              style={[styles.actionBtn, isMuted ? styles.btnAmber : styles.btnSlate]}
              onPress={handleToggleMute}>
              <Text style={styles.btnLabel}>{isMuted ? '🔇 Silenciado' : '🎤 Silenciar'}</Text>
            </Pressable>
            <Pressable style={[styles.actionBtn, styles.btnRed]} onPress={handleHangup}>
              <Text style={styles.btnLabel}>Colgar</Text>
            </Pressable>
          </>
        ) : (
          <>
            <Pressable style={[styles.actionBtn, styles.btnRed]} onPress={handleReject}>
              <Text style={styles.btnLabel}>Rechazar</Text>
            </Pressable>
            <Pressable style={[styles.actionBtn, styles.btnGreen]} onPress={handleAccept}>
              <Text style={styles.btnLabel}>Contestar</Text>
            </Pressable>
          </>
        )}
      </View>
    </Animated.View>
  );
}

const styles = StyleSheet.create({
  container: {
    position: 'absolute',
    left: 16,
    right: 16,
    backgroundColor: '#FFFFFF',
    borderRadius: 20,
    padding: 16,
    gap: 12,
    ...Platform.select({
      android: { elevation: 12 },
      ios: {
        shadowColor: '#000',
        shadowOffset: { width: 0, height: 4 },
        shadowOpacity: 0.15,
        shadowRadius: 12,
      },
    }),
    // Ensure it renders above tab bar and navigation
    zIndex: 9999,
  },
  statusRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 6,
  },
  statusDot: {
    width: 8,
    height: 8,
    borderRadius: 4,
  },
  dotActive: { backgroundColor: '#22C55E' },
  dotRinging: { backgroundColor: '#3B82F6' },
  statusText: {
    fontSize: 12,
    fontWeight: '600',
    color: '#64748B',
    letterSpacing: 0.2,
  },
  callerRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 12,
  },
  avatarCircle: {
    width: 44,
    height: 44,
    borderRadius: 22,
    backgroundColor: '#3B82F6',
    alignItems: 'center',
    justifyContent: 'center',
  },
  avatarInitial: {
    color: '#FFFFFF',
    fontSize: 18,
    fontWeight: '700',
  },
  callerInfo: {
    flex: 1,
  },
  callerName: {
    fontSize: 15,
    fontWeight: '600',
    color: '#0F172A',
    marginBottom: 2,
  },
  callerId: {
    fontSize: 13,
    color: '#64748B',
  },
  actionsRow: {
    flexDirection: 'row',
    gap: 10,
  },
  actionBtn: {
    flex: 1,
    paddingVertical: 12,
    borderRadius: 12,
    alignItems: 'center',
  },
  btnLabel: {
    color: '#FFFFFF',
    fontWeight: '600',
    fontSize: 14,
  },
  btnGreen: { backgroundColor: '#22C55E' },
  btnRed: { backgroundColor: '#EF4444' },
  btnAmber: { backgroundColor: '#F59E0B' },
  btnSlate: { backgroundColor: '#94A3B8' },
});
