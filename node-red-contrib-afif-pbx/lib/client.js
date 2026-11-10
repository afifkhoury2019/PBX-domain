"use strict";

/* Small session client for the AFIF-PBX panel API.
 * The panel uses an HttpOnly `afif_session` cookie; this client performs the
 * form login once and re-authenticates when the 12-hour session expires. */

class PbxError extends Error {
  constructor(message, status, body) {
    super(message);
    this.status = status;
    this.body = body;
  }
}

function joinUrl(base, path) {
  if (!path.startsWith("/")) path = "/" + path;
  return base.replace(/\/+$/, "") + path;
}

class PbxClient {
  constructor(baseUrl, username, password) {
    this.baseUrl = (baseUrl || "http://127.0.0.1:8090").trim().replace(/\/+$/, "");
    this.username = username || "admin";
    this.password = password || "";
    this.cookie = "";
    this.loginPromise = null;
  }

  async login() {
    if (this.loginPromise) return this.loginPromise;
    this.loginPromise = (async () => {
      const res = await fetch(joinUrl(this.baseUrl, "/api/login"), {
        method: "POST",
        headers: { "content-type": "application/json" },
        body: JSON.stringify({ user: this.username, password: this.password }),
      });
      if (!res.ok) {
        const text = await res.text().catch(() => "");
        throw new PbxError("AFIF-PBX login failed (" + res.status + ")", res.status, text);
      }
      const cookies =
        typeof res.headers.getSetCookie === "function"
          ? res.headers.getSetCookie()
          : [res.headers.get("set-cookie")].filter(Boolean);
      for (const value of cookies) {
        const match = /(?:^|;\s*)(afif_session=[^;]+)/.exec(value || "");
        if (match) this.cookie = match[1];
      }
      if (!this.cookie) throw new PbxError("AFIF-PBX login returned no session cookie", res.status, "");
      return this.cookie;
    })();
    try {
      return await this.loginPromise;
    } finally {
      this.loginPromise = null;
    }
  }

  async request(path, options = {}, retried = false) {
    if (!this.cookie) await this.login();
    const headers = { ...(options.headers || {}) };
    headers.cookie = this.cookie;
    if (options.body !== undefined && !headers["content-type"]) {
      headers["content-type"] = "application/json";
    }
    const res = await fetch(joinUrl(this.baseUrl, path), { ...options, headers });
    if (res.status === 401 && !retried) {
      this.cookie = "";
      await this.login();
      return this.request(path, options, true);
    }
    return res;
  }

  async json(path, options = {}) {
    const res = await this.request(path, options);
    if (!res.ok) {
      const text = await res.text().catch(() => "");
      throw new PbxError("AFIF-PBX request failed (" + res.status + ")", res.status, text);
    }
    if (res.status === 204) return null;
    return res.json();
  }

  async post(path, body) {
    return this.json(path, { method: "POST", body: JSON.stringify(body || {}) });
  }
}

module.exports = { PbxClient, PbxError };
