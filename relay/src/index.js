/**
 * DragonNES relay: two phones join the same room code (host + guest) over WebSocket and every
 * binary message from one is forwarded to the other. Text frames are relay events:
 *   to the newcomer: "peer-present" (the other side is already here), "no-host", "room-full", "room-taken"
 *   to the other side: "peer-joined", "peer-left"
 * The relay never looks inside game messages.
 */
const CODE = /^[A-Z0-9]{6}$/;

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    const m = url.pathname.match(/^\/room\/([A-Za-z0-9]{6})$/);
    if (!m) {
      return new Response("DragonNES relay is running 🐉", { headers: { "content-type": "text/plain; charset=utf-8" } });
    }
    if (request.headers.get("Upgrade") !== "websocket") {
      return new Response("Expected a WebSocket", { status: 426 });
    }
    const code = m[1].toUpperCase();
    if (!CODE.test(code)) return new Response("Bad room code", { status: 400 });
    const room = env.ROOMS.get(env.ROOMS.idFromName(code));
    return room.fetch(request);
  },
};

export class Room {
  constructor(state, env) {
    this.state = state;
  }

  async fetch(request) {
    const url = new URL(request.url);
    const role = url.searchParams.get("role") === "host" ? "host" : "guest";
    const other = role === "host" ? "guest" : "host";

    const pair = new WebSocketPair();
    const [client, server] = Object.values(pair);

    const mine = this.state.getWebSockets(role);
    const theirs = this.state.getWebSockets(other);
    let reject = null;
    if (role === "host" && mine.length > 0) reject = "room-taken";
    else if (role === "guest" && mine.length > 0) reject = "room-full";
    else if (role === "guest" && theirs.length === 0) reject = "no-host";

    if (reject) {
      // Not part of the room: a plain (non-hibernating) socket that only says why, then closes.
      server.accept();
      server.send(reject);
      server.close(4000, reject);
      return new Response(null, { status: 101, webSocket: client });
    }

    this.state.acceptWebSocket(server, [role]);
    if (theirs.length > 0) {
      server.send("peer-present");
      for (const ws of theirs) safeSend(ws, "peer-joined");
    }
    return new Response(null, { status: 101, webSocket: client });
  }

  webSocketMessage(ws, message) {
    const role = this.state.getTags(ws)[0];
    const other = role === "host" ? "guest" : "host";
    for (const peer of this.state.getWebSockets(other)) safeSend(peer, message);
  }

  webSocketClose(ws, code, reason) {
    this.leave(ws);
    try { ws.close(code, reason); } catch (_) {}
  }

  webSocketError(ws) {
    this.leave(ws);
  }

  leave(ws) {
    const role = this.state.getTags(ws)[0];
    const other = role === "host" ? "guest" : "host";
    for (const peer of this.state.getWebSockets(other)) safeSend(peer, "peer-left");
  }
}

function safeSend(ws, data) {
  try { ws.send(data); } catch (_) {}
}
