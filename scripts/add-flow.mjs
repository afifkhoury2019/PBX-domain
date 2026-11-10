#!/usr/bin/env node
/*
 * Add the AFIF-PBX tab to a running Node-RED without replacing the flows file.
 *
 *   NODE_RED_URL=http://127.0.0.1:1880 NODE_RED_TOKEN=... node scripts/add-flow.mjs
 *
 * It uses the Admin API's per-flow endpoint (POST /flow), so existing tabs,
 * including Engineering Tools, are not touched. The flow JSON is validated
 * locally first.
 */
import { readFile } from "node:fs/promises";
import process from "node:process";

const url = (process.env.NODE_RED_URL || "http://127.0.0.1:1880").replace(/\/+$/, "");
const token = process.env.NODE_RED_TOKEN || "";
const file = process.env.FLOW_FILE || "flows/afif-pbx.json";

const nodes = JSON.parse(await readFile(file, "utf8"));
if (!Array.isArray(nodes)) throw new Error("flow file must be an array of nodes");

const tab = nodes.find((n) => n.type === "tab");
if (!tab) throw new Error("flow file has no tab node");
const body = { id: tab.id, label: tab.label, nodes: nodes.filter((n) => n !== tab) };

const headers = { "content-type": "application/json" };
if (token) headers.authorization = "Bearer " + token;

const res = await fetch(url + "/flow", { method: "POST", headers, body: JSON.stringify(body) });
const text = await res.text();
if (!res.ok) {
  console.error("Node-RED refused the flow:", res.status, text);
  process.exit(1);
}
console.log("Added tab:", tab.label, "(" + body.nodes.length + " nodes)");
console.log("Open the editor; the AFIF-PBX tab is deployed on its own.");
