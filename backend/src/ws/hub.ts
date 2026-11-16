import type { WebSocket } from "ws";

export interface WsEnvelopePayload {
  id: string;
  senderNexId: string;
  senderDeviceId: string;
  senderSignalDeviceId: number;
  recipientDeviceId: string;
  recipientSignalDeviceId: number;
  groupId: string | null;
  clientId: string;
  cipherType: number;
  ciphertext: string;
  createdAt: string;
  expiresAt: string;
  ephemeral: boolean;
}

interface Connection {
  socket: WebSocket;
  userId: string;
  deviceId: string;
  alive: boolean;
}

export class WsHub {
  private readonly byDevice = new Map<string, Set<Connection>>();
  private readonly byUser = new Map<string, Set<Connection>>();

  add(socket: WebSocket, userId: string, deviceId: string): Connection {
    const connection: Connection = { socket, userId, deviceId, alive: true };
    let devices = this.byDevice.get(deviceId);
    if (!devices) {
      devices = new Set();
      this.byDevice.set(deviceId, devices);
    }
    devices.add(connection);
    let users = this.byUser.get(userId);
    if (!users) {
      users = new Set();
      this.byUser.set(userId, users);
    }
    users.add(connection);
    socket.on("pong", () => {
      connection.alive = true;
    });
    socket.on("close", () => this.remove(connection));
    socket.on("error", () => this.remove(connection));
    return connection;
  }

  remove(connection: Connection): void {
    this.byDevice.get(connection.deviceId)?.delete(connection);
    this.byUser.get(connection.userId)?.delete(connection);
    if (this.byDevice.get(connection.deviceId)?.size === 0) {
      this.byDevice.delete(connection.deviceId);
    }
    if (this.byUser.get(connection.userId)?.size === 0) {
      this.byUser.delete(connection.userId);
    }
  }

  isOnline(deviceId: string): boolean {
    const connections = this.byDevice.get(deviceId);
    return !!connections && [...connections].some((c) => c.socket.readyState === c.socket.OPEN);
  }

  sendToDevice(deviceId: string, message: unknown): number {
    return this.sendToConnections(this.byDevice.get(deviceId), message);
  }

  sendToUser(userId: string, message: unknown): number {
    return this.sendToConnections(this.byUser.get(userId), message);
  }

  closeDevice(deviceId: string, code = 4001, reason = "session revoked"): void {
    for (const connection of this.byDevice.get(deviceId) ?? []) {
      try {
        connection.socket.close(code, reason);
      } catch {
        // The socket is already gone.
      }
    }
  }

  closeAll(): void {
    for (const connections of this.byDevice.values()) {
      for (const connection of connections) {
        try {
          connection.socket.close(1001, "server shutting down");
        } catch {
          // The socket is already gone.
        }
      }
    }
  }

  private sendToConnections(connections: Set<Connection> | undefined, message: unknown): number {
    if (!connections) return 0;
    const text = JSON.stringify(message);
    let delivered = 0;
    for (const connection of connections) {
      if (connection.socket.readyState === connection.socket.OPEN) {
        connection.socket.send(text);
        delivered += 1;
      }
    }
    return delivered;
  }
}
