// 경량 STOMP 1.2 over WebSocket 클라이언트 (Spring SimpleBroker /ws 연동)
// - 엔드포인트: VITE_WS_URL (기본 /ws)
// - 프레임 포맷: COMMAND\nheader:value\n\nbody\0
// - 자동 재연결(지수 백오프) 및 재연결 시 onReconnect 콜백 호출로 최신 REST 데이터 재조회 지원

import { WS_BASE, getAuthTokens } from './storage';

const NULL_CHAR = '\x00';

export function resolveWsUrl() {
  const base = WS_BASE || '/ws';
  if (base.startsWith('ws://') || base.startsWith('wss://')) {
    return base;
  }
  if (base.startsWith('http://') || base.startsWith('https://')) {
    return base.replace(/^http/, 'ws');
  }
  if (typeof window === 'undefined') {
    return 'ws://localhost:8080/ws';
  }
  const proto = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
  const path = base.startsWith('/') ? base : `/${base}`;
  return `${proto}//${window.location.host}${path}`;
}

function serializeFrame(command, headers = {}, body = '') {
  const lines = [command];
  Object.entries(headers).forEach(([k, v]) => {
    if (v != null && v !== '') {
      lines.push(`${k}:${v}`);
    }
  });
  return `${lines.join('\n')}\n\n${body}${NULL_CHAR}`;
}

function parseFrames(rawData) {
  if (!rawData || typeof rawData !== 'string') return [];
  // Heartbeat LF
  if (rawData === '\n' || rawData === '\r\n') {
    return [{ command: 'HEARTBEAT', headers: {}, body: '' }];
  }

  const chunks = rawData.split(NULL_CHAR);
  const frames = [];

  for (let i = 0; i < chunks.length; i++) {
    const chunk = chunks[i].replace(/^\n+|\r\n/g, (m) => (m === '\r\n' ? '\n' : ''));
    if (!chunk.trim()) continue;

    const dividerIdx = chunk.indexOf('\n\n');
    const headerPart = dividerIdx !== -1 ? chunk.slice(0, dividerIdx) : chunk;
    const bodyPart = dividerIdx !== -1 ? chunk.slice(dividerIdx + 2) : '';

    const lines = headerPart.split('\n');
    const command = (lines[0] || '').trim();
    if (!command) continue;

    const headers = {};
    for (let j = 1; j < lines.length; j++) {
      const line = lines[j];
      const colonIdx = line.indexOf(':');
      if (colonIdx !== -1) {
        const key = line.slice(0, colonIdx).trim();
        const val = line.slice(colonIdx + 1).trim();
        if (!(key in headers)) {
          headers[key] = val;
        }
      }
    }

    frames.push({ command, headers, body: bodyPart });
  }

  return frames;
}

export function createStompManager({
  withAuth = false,
  onConnect,
  onReconnect,
  onDisconnect,
  onError,
} = {}) {
  let ws = null;
  let connected = false;
  let disposed = false;
  let hasConnectedOnce = false;
  let reconnectTimer = null;
  let reconnectAttempt = 0;
  let subSeq = 0;

  // Map<subId, { destination, callback }>
  const subscriptions = new Map();

  function sendRaw(data) {
    if (ws && ws.readyState === WebSocket.OPEN) {
      try {
        ws.send(data);
      } catch (e) {
        // ignore send errors
      }
    }
  }

  function subscribeOnWire(subId, destination) {
    if (!connected) return;
    sendRaw(
      serializeFrame('SUBSCRIBE', {
        id: subId,
        destination,
      })
    );
  }

  function unsubscribeOnWire(subId) {
    if (!connected) return;
    sendRaw(
      serializeFrame('UNSUBSCRIBE', {
        id: subId,
      })
    );
  }

  function scheduleReconnect() {
    if (disposed || reconnectTimer) return;
    const delay = Math.min(10000, 1000 * Math.pow(1.6, reconnectAttempt));
    reconnectAttempt += 1;
    reconnectTimer = setTimeout(() => {
      reconnectTimer = null;
      connect();
    }, delay);
  }

  function connect() {
    if (disposed) return;
    if (ws && (ws.readyState === WebSocket.OPEN || ws.readyState === WebSocket.CONNECTING)) {
      return;
    }

    const url = resolveWsUrl();
    try {
      ws = new WebSocket(url);
    } catch (e) {
      scheduleReconnect();
      return;
    }

    ws.onopen = () => {
      if (disposed) {
        ws.close();
        return;
      }
      const connectHeaders = {
        'accept-version': '1.2,1.1,1.0',
        'heart-beat': '10000,10000',
      };
      if (withAuth) {
        const tokens = getAuthTokens();
        if (tokens?.accessToken) {
          connectHeaders['Authorization'] = `Bearer ${tokens.accessToken}`;
        }
      }
      sendRaw(serializeFrame('CONNECT', connectHeaders));
    };

    ws.onmessage = (event) => {
      if (disposed) return;
      const frames = parseFrames(event.data);
      frames.forEach((frame) => {
        if (frame.command === 'CONNECTED') {
          const wasReconnect = hasConnectedOnce;
          connected = true;
          hasConnectedOnce = true;
          reconnectAttempt = 0;

          // 등록된 모든 채널 다시 구독
          subscriptions.forEach((sub, subId) => {
            subscribeOnWire(subId, sub.destination);
          });

          if (wasReconnect && typeof onReconnect === 'function') {
            onReconnect();
          } else if (typeof onConnect === 'function') {
            onConnect();
          }
        } else if (frame.command === 'MESSAGE') {
          const subId = frame.headers['subscription'];
          const dest = frame.headers['destination'];
          let parsedBody = frame.body;
          if (frame.body) {
            try {
              parsedBody = JSON.parse(frame.body);
            } catch (e) {
              parsedBody = frame.body;
            }
          }
          if (subId && subscriptions.has(subId)) {
            subscriptions.get(subId).callback(parsedBody, frame);
          } else if (dest) {
            subscriptions.forEach((sub) => {
              if (sub.destination === dest) {
                sub.callback(parsedBody, frame);
              }
            });
          }
        } else if (frame.command === 'ERROR') {
          if (typeof onError === 'function') {
            onError(frame.headers['message'] || frame.body || 'STOMP 오류');
          }
        }
      });
    };

    ws.onerror = () => {
      // onclose에서 재연결 처리
    };

    ws.onclose = () => {
      const wasConnected = connected;
      connected = false;
      ws = null;
      if (wasConnected && typeof onDisconnect === 'function') {
        onDisconnect();
      }
      if (!disposed) {
        scheduleReconnect();
      }
    };
  }

  function subscribe(destination, callback) {
    subSeq += 1;
    const subId = `sub-${subSeq}`;
    subscriptions.set(subId, { destination, callback });
    if (connected) {
      subscribeOnWire(subId, destination);
    }
    return () => {
      if (subscriptions.has(subId)) {
        subscriptions.delete(subId);
        unsubscribeOnWire(subId);
      }
    };
  }

  function disconnect() {
    disposed = true;
    if (reconnectTimer) {
      clearTimeout(reconnectTimer);
      reconnectTimer = null;
    }
    if (ws) {
      if (connected && ws.readyState === WebSocket.OPEN) {
        sendRaw(serializeFrame('DISCONNECT'));
      }
      try {
        ws.close();
      } catch (e) {
        // ignore
      }
      ws = null;
    }
    connected = false;
    subscriptions.clear();
  }

  connect();

  return {
    subscribe,
    disconnect,
    isConnected: () => connected,
  };
}
