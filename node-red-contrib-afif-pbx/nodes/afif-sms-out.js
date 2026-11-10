"use strict";

module.exports = function (RED) {
  function AfifSmsOutNode(config) {
    RED.nodes.createNode(this, config);
    const node = this;
    node.config = RED.nodes.getNode(config.config);
    node.status({ fill: "grey", shape: "ring", text: "ready" });

    if (!node.config) {
      node.status({ fill: "red", shape: "ring", text: "no AFIF-PBX config" });
      return;
    }

    node.on("input", async function (msg, send, done) {
      const to = String(msg.to || msg.number || msg.topic || "").trim();
      const text = typeof msg.payload === "string" ? msg.payload : String(msg.payload == null ? "" : msg.payload);
      if (!to || !text) {
        node.status({ fill: "red", shape: "ring", text: "missing number/text" });
        node.error("AFIF-PBX SMS out needs msg.to (number) and msg.payload (text)", msg);
        if (done) done();
        return;
      }
      node.status({ fill: "blue", shape: "dot", text: "sending…" });
      try {
        await node.config.post("/api/sms", { number: to, text });
        msg.to = to;
        msg.number = to;
        msg.sent = true;
        msg.pbx = { ok: true, number: to };
        node.status({ fill: "green", shape: "dot", text: "sent to " + to });
        send(msg);
        if (done) done();
      } catch (err) {
        node.status({ fill: "red", shape: "ring", text: "send failed" });
        msg.error = { message: err.message, status: err.status, body: err.body };
        node.error("AFIF-PBX SMS send failed: " + err.message, msg);
        if (done) done();
      }
    });

    node.on("close", function () {
      node.status({});
    });
  }

  RED.nodes.registerType("afif-sms-out", AfifSmsOutNode);
};
