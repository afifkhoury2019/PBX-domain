"use strict";

const { PbxClient } = require("../lib/client");

module.exports = function (RED) {
  function AfifPbxConfigNode(config) {
    RED.nodes.createNode(this, config);
    const node = this;
    node.name = config.name || "AFIF-PBX";
    node.url = (config.url || "http://127.0.0.1:8090").trim();
    node.username = (config.username || "admin").trim();
    node.password = (node.credentials && node.credentials.password) || "";
    node.client = new PbxClient(node.url, node.username, node.password);

    node.status({ fill: "grey", shape: "ring", text: "not connected" });

    node.ensureLogin = async function () {
      try {
        await node.client.login();
        node.status({ fill: "green", shape: "dot", text: node.url.replace(/^https?:\/\//, "") });
        return true;
      } catch (err) {
        node.status({ fill: "red", shape: "ring", text: "login failed" });
        throw err;
      }
    };

    node.get = function (path, options) {
      return node.client.json(path, options);
    };

    node.post = function (path, body) {
      return node.client.post(path, body);
    };
  }

  RED.nodes.registerType("afif-pbx-config", AfifPbxConfigNode, {
    credentials: {
      password: { type: "password" },
    },
  });
};
