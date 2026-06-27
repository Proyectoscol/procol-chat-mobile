import { Platform } from 'react-native';
import { NOTIFICATION_TYPES } from '@/constants';
import { Notification } from '@/types/Notification';

let notifee: typeof import('@notifee/react-native').default | undefined;

if (Platform.OS === 'ios') {
  // eslint-disable-next-line @typescript-eslint/no-require-imports
  notifee = require('@notifee/react-native')
    .default as typeof import('@notifee/react-native').default;
}

export const clearAllDeliveredNotifications = async () => {
  if (Platform.OS === 'ios' && notifee) {
    await notifee.cancelAllNotifications();
  }
};

export const updateBadgeCount = async ({ count = 0 }) => {
  if (Platform.OS === 'ios' && count >= 0 && notifee) {
    await notifee.setBadgeCount(count);
  }
};

export const findConversationLinkFromPush = ({
  notification,
  installationUrl,
}: {
  notification: Notification | null | undefined;
  installationUrl: string;
}) => {
  if (!notification) return;

  const { notificationType } = notification;

  if (NOTIFICATION_TYPES.includes(notificationType)) {
    const { primaryActor, primaryActorId, primaryActorType } = notification;
    let conversationId = null;
    if (primaryActorType === 'Conversation') {
      conversationId = primaryActor.id;
    } else if (primaryActorType === 'Message') {
      conversationId = primaryActor.conversationId;
    }
    if (conversationId) {
      const conversationLink = `${installationUrl}/app/accounts/1/conversations/${conversationId}/${primaryActorId}/${primaryActorType}`;
      return conversationLink;
    }
  }
  return;
};

interface FCMMessage {
  data?: {
    payload?: string;
    notification?: string;
  };
}

export const findNotificationFromFCM = ({ message }: { message: FCMMessage }) => {
  try {
    // FCM HTTP v1
    if (message?.data?.payload) {
      const parsedPayload = JSON.parse(message.data.payload);
      return parsedPayload?.data?.notification ?? null;
    }
    // FCM legacy (deprecated)
    if (message?.data?.notification) {
      return JSON.parse(message.data.notification);
    }
  } catch {
    // malformed payload - return null so callers can skip navigation safely
  }
  return null;
};
