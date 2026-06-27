import { ActionCable, Cable } from '@kesha-antonov/react-native-action-cable';
import NetInfo from '@react-native-community/netinfo';

const channelName = 'RoomChannel';
const PRESENCE_INTERVAL = 20000;
const MAX_RECONNECT_ATTEMPTS = 10;
const BASE_RECONNECT_DELAY_MS = 1000;
const MAX_RECONNECT_DELAY_MS = 60000;

export interface ActionCableEvent<T = unknown> {
  event: string;
  data: T;
}

class BaseActionCableConnector {
  protected events: { [key: string]: (data: unknown) => void };
  protected accountId: number;

  private readonly pubSubToken: string;
  private readonly webSocketUrl: string;
  private readonly userId: number;

  private cable: Cable;
  private reconnectAttempts = 0;
  private reconnectTimer: ReturnType<typeof setTimeout> | null = null;
  private presenceTimer: ReturnType<typeof setInterval> | null = null;
  private connected = false;
  private disposed = false;
  private netInfoUnsubscribe: (() => void) | null = null;
  private networkAvailable = true;

  constructor(pubSubToken: string, webSocketUrl: string, accountId: number, userId: number) {
    this.pubSubToken = pubSubToken;
    this.webSocketUrl = webSocketUrl;
    this.accountId = accountId;
    this.userId = userId;
    this.cable = new Cable({});
    this.events = {};

    this.connect();
    this.subscribeToNetworkChanges();
  }

  private connect(): void {
    const consumer = ActionCable.createConsumer(this.webSocketUrl);
    const channel = this.cable.setChannel(
      channelName,
      consumer.subscriptions.create(
        {
          channel: channelName,
          pubsub_token: this.pubSubToken,
          account_id: this.accountId,
          user_id: this.userId,
        },
        {
          updatePresence(): void {
            this.perform('update_presence');
          },
        },
      ),
    );

    channel.on('received', this.onReceived);
    channel.on('connected', this.handleConnected);
    channel.on('disconnect', this.handleDisconnected);
  }

  private subscribeToNetworkChanges(): void {
    this.netInfoUnsubscribe = NetInfo.addEventListener(state => {
      const isNowConnected = !!state.isConnected;
      if (isNowConnected && !this.networkAvailable && !this.connected && !this.disposed) {
        // Network came back while we were disconnected - reset backoff and retry immediately
        this.reconnectAttempts = 0;
        this.scheduleReconnect(0);
      }
      this.networkAvailable = isNowConnected;
    });
  }

  private startPresenceTimer(): void {
    if (this.presenceTimer !== null) return;
    this.presenceTimer = setInterval(() => {
      try {
        this.cable.channel(channelName).perform('update_presence');
      } catch {
        // channel may not be ready yet
      }
    }, PRESENCE_INTERVAL);
  }

  private stopPresenceTimer(): void {
    if (this.presenceTimer !== null) {
      clearInterval(this.presenceTimer);
      this.presenceTimer = null;
    }
  }

  private scheduleReconnect(delayOverride?: number): void {
    if (this.disposed) return;
    if (this.reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
      console.error(
        '[ActionCable] reconexion fallida despues de',
        MAX_RECONNECT_ATTEMPTS,
        'intentos',
      );
      return;
    }
    if (this.reconnectTimer !== null) return;

    const delay =
      delayOverride ??
      Math.min(
        BASE_RECONNECT_DELAY_MS * Math.pow(2, this.reconnectAttempts),
        MAX_RECONNECT_DELAY_MS,
      );

    this.reconnectTimer = setTimeout(() => {
      this.reconnectTimer = null;
      if (!this.disposed && !this.connected) {
        this.reconnectAttempts += 1;
        this.cable = new Cable({});
        this.connect();
      }
    }, delay);
  }

  public reconnectIfNeeded(): void {
    if (!this.connected && !this.disposed) {
      this.reconnectAttempts = 0;
      if (this.reconnectTimer !== null) {
        clearTimeout(this.reconnectTimer);
        this.reconnectTimer = null;
      }
      this.scheduleReconnect(0);
    }
  }

  public disconnect(): void {
    this.disposed = true;
    this.stopPresenceTimer();
    if (this.reconnectTimer !== null) {
      clearTimeout(this.reconnectTimer);
      this.reconnectTimer = null;
    }
    if (this.netInfoUnsubscribe) {
      this.netInfoUnsubscribe();
      this.netInfoUnsubscribe = null;
    }
    try {
      this.cable.channel(channelName).unsubscribe();
    } catch {
      // ignore - channel may already be gone
    }
  }

  protected isAValidEvent = (data: unknown): boolean => {
    const { account_id } = data as { account_id: number };
    return this.accountId === account_id;
  };

  private onReceived = ({ event, data }: ActionCableEvent = { event: '', data: null }): void => {
    if (this.isAValidEvent(data)) {
      if (this.events[event] && typeof this.events[event] === 'function') {
        this.events[event](data);
      }
    }
  };

  private handleConnected = (): void => {
    this.connected = true;
    this.reconnectAttempts = 0;
    this.startPresenceTimer();
  };

  private handleDisconnected = (): void => {
    this.connected = false;
    this.stopPresenceTimer();
    this.scheduleReconnect();
  };
}

export default BaseActionCableConnector;
