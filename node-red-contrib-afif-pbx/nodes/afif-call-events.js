"use strict";

function eventName(call) {
  const d = String(call.disposition || "").toUpperCase();
  if (d === "NO ANSWER") return "missed";
  if (d === "ANSWERED") return "answered";
  if (d === "VOICEMAIL") return "voicemail";
  if (d === "BUSY") return "busy";
  if (d === "FAILED") return "failed";
  return d ? d.toLowerCase() : "call";
}

module.exports = function (RED) {
  function AfifCallEventsNode(config) {
    RED.nodes.createNode(this, config);
    const node = this;
    node.config = RED.nodes.getNode(config.config);
    node.poll = Math.max(2, Number(config.poll) || 10) * 1000;
    node.initial = config.initial === true || config.initial === "true";
    node.lastId = 0;
    node.primed = false;
    node.busy = false;

    if (!node.config) {
      node.status({ fill: "red", shape: "ring", text: "no AFIF-PBX config" });
      return;
    }

    node.status({ fill: "grey", shape: "ring", text: "waiting" });

    async function tick() {
      if (node.busy) return;
      node.busy = true;
      try {
        const rows = await node.config.get("/api/calls?limit=100");
        const list = (Array.isArray(rows) ? rows : []).slice().sort((a, b) => Number(a.id) - Number(b.id));
        let maxId = node.lastId;
        for (const call of list) if (Number(call.id) > maxId) maxId = Number(call.id);

        if (!node.primed && !node.initial) {
          node.lastId = maxId;
          node.primed = true;
          node.status({ fill: "green", shape: "dot", text: "watching (" + maxId + ")" });
          return;
        }

        node.primed = true;
        let count = 0;
        for (const call of list) {
          if (Number(call.id) <= node.lastId) continue;
          count++;
          node.send({
            payload: call,
            call,
            event: eventName(call),
            direction: call.direction,
            from: call.fromNumber,
            fromName: call.fromName,
            to: call.toNumber,
            disposition: call.disposition,
            durationSeconds: call.durationSeconds,
            callId: call.id,
          });
        }
        node.lastId = maxId;
        node.status({
          fill: "green",
          shape: "dot",
          text: count > 0 ? count + " new call event" : "watching (" + maxId + ")",
        });
      } catch (err) {
        node.status({ fill: "red", shape: "ring", text: err.status === 401 ? "login failed" : "request failed" });
        node.warn("AFIF-PBX call poll failed: " + err.message);
      } finally {
        node.busy = false;
      }
    }

    node.timer = setInterval(tick, node.poll);
    setTimeout(tick, 500);
    node.on("close", function () {
      clearInterval(node.timer);
    });
  }

  RED.nodes.registerType("afif-call-events", AfifCallEventsNode);
};
