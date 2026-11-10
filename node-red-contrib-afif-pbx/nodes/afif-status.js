"use strict";

module.exports = function (RED) {
  function AfifStatusNode(config) {
    RED.nodes.createNode(this, config);
    const node = this;
    node.config = RED.nodes.getNode(config.config);
    node.poll = Math.max(2, Number(config.poll) || 30) * 1000;
    node.onlyChanges = config.onlyChanges === true || config.onlyChanges === "true";
    node.last = "";
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
        const status = await node.config.get("/api/status");
        const serialized = JSON.stringify(status);
        if (node.onlyChanges && serialized === node.last) {
          node.status({ fill: "green", shape: "dot", text: "unchanged" });
          return;
        }
        node.last = serialized;
        const modem = status && status.modem ? status.modem : {};
        const gsm = modem.registered ? "GSM registered" : modem.signal != null ? "GSM signal " + modem.signal : "GSM idle";
        node.status({ fill: modem.registered ? "green" : "yellow", shape: "dot", text: gsm });
        node.send({
          payload: status,
          status,
          modem: status ? status.modem : null,
          extensions: status ? status.extensions : [],
          activeCalls: status ? status.activeCalls : [],
        });
      } catch (err) {
        node.status({ fill: "red", shape: "ring", text: err.status === 401 ? "login failed" : "request failed" });
        node.warn("AFIF-PBX status poll failed: " + err.message);
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

  RED.nodes.registerType("afif-status", AfifStatusNode);
};
