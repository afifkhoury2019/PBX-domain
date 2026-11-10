"use strict";

const test = require("node:test");
const assert = require("node:assert");
const http = require("node:http");
const { PbxClient } = require("../lib/client");

function mockPbx() {
  return new Promise((resolve) => {
    const sms = [{ id: 1, time: "2026-01-01T00:00:00Z", direction: "IN", number: "+9627", text: "hello", status: "received" }];
    const server = http.createServer((req, res) => {
      let body = "";
      req.on("data", (c) => (body += c));
      req.on("end", () => {
        if (req.url === "/api/login" && req.method === "POST") {
          const parsed = JSON.parse(body || "{}");
          if (parsed.user !== "admin" || parsed.password !== "secret") {
            res.writeHead(401).end();
            return;
          }
          res.writeHead(200, { "set-cookie": "afif_session=abc123; Path=/; HttpOnly" }).end("{}");
          return;
        }
        if (!/afif_session=abc123/.test(req.headers.cookie || "")) {
          res.writeHead(401).end();
          return;
        }
        if (req.url === "/api/sms" && req.method === "GET") {
          res.writeHead(200, { "content-type": "application/json" }).end(JSON.stringify(sms));
          return;
        }
        if (req.url === "/api/sms" && req.method === "POST") {
          const parsed = JSON.parse(body || "{}");
          sms.push({ id: sms.length + 1, direction: "OUT", number: parsed.number, text: parsed.text, status: "sent" });
          res.writeHead(200, { "content-type": "application/json" }).end("{}");
          return;
        }
        res.writeHead(404).end();
      });
    });
    server.listen(0, "127.0.0.1", () => resolve({ server, port: server.address().port, sms }));
  });
}

test("logs in, reads SMS and sends SMS", async () => {
  const { server, port, sms } = await mockPbx();
  try {
    const client = new PbxClient("http://127.0.0.1:" + port, "admin", "secret");
    const rows = await client.json("/api/sms");
    assert.equal(rows.length, 1);
    assert.equal(rows[0].text, "hello");
    await client.post("/api/sms", { number: "+9627900", text: "test" });
    assert.equal(sms.length, 2);
    assert.equal(sms[1].number, "+9627900");
  } finally {
    server.close();
  }
});

test("renews an expired session once", async () => {
  const { server, port } = await mockPbx();
  try {
    const client = new PbxClient("http://127.0.0.1:" + port, "admin", "secret");
    await client.login();
    client.cookie = "afif_session=expired";
    const rows = await client.json("/api/sms");
    assert.equal(rows.length, 1);
  } finally {
    server.close();
  }
});
