"use strict";

module.exports = function (RED) {
  function AfifSmsInNode(config) {
    RED.nodes.createNode(this, config);
    const node = this;
    node.config = RED.nodes.getNode(config.config);
    node.poll = Math.max(2, Number(config.poll) || 15) * 1000;
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
        const rows = await node.config.get("/api/sms");
        const list = (Array.isArray(rows) ? rows : []).slice().sort((a, b) => Number(a.id) - Number(b.id));

        let maxId = node.lastId;
        for (const sms of list) if (Number(sms.id) > maxId) maxId = Number(sms.id);

        if (!node.primed && !node.initial) {
          node.lastId = maxId;
          node.primed = true;
          node.status({ fill: "green", shape: "dot", text: "watching (" + maxId + ")" });
          return;
        }

        node.primed = true;
        let count = 0;
        for (const sms of list) {
          if (Number(sms.id) <= node.lastId) continue;
          count++;
          node.send({
            payload: sms.text,
            topic: sms.number,
            to: sms.number,
            from: sms.number,
            direction: sms.direction,
            status: sms.status,
            time: sms.time,
            smsId: sms.id,
            sms,
          });
        }
        node.lastId = maxId;
        node.status({
          fill: "green",
          shape: "dot",
          text: count > 0 ? count + " new SMS" : "watching (" + maxId + ")",
        });
      } catch (err) {
        node.status({ fill: "red", shape: "ring", text: err.status === 401 ? "login failed" : "request failed" });
        node.warn("AFIF-PBX SMS poll failed: " + err.message);
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

  RED.nodes.registerType("afif-sms-in", AfifSmsInNode);
};
