// Einkauf – gemeinsame Logik (Aufteilen, Datenmodell, Abgleich).
// Muss zur Android-App (Planner.kt / SyncDoc.kt) passen.
(function (root) {
  'use strict';

  // ---------- Aufteilen auf Wochen ----------

  function normalize(s) {
    return String(s || '').replace(/[\s   ​‌‍⁠﻿]+/g, ' ').trim();
  }

  function key(week, name) { return week + '|' + normalize(name).toLowerCase(); }

  // items: [{name, qty, onlyWeeks:[..], have}]
  function merge(items) {
    const out = new Map();
    for (const it of items) {
      const name = normalize(it.name);
      if (!name) continue;
      const k = name.toLowerCase();
      const prev = out.get(k);
      if (!prev) out.set(k, { name, qty: it.qty, onlyWeeks: it.onlyWeeks || [], have: it.have || 0 });
      else out.set(k, {
        name: prev.name,
        qty: prev.qty + it.qty,
        onlyWeeks: (it.onlyWeeks && it.onlyWeeks.length) ? it.onlyWeeks : prev.onlyWeeks,
        have: prev.have + (it.have || 0),
      });
    }
    return [...out.values()];
  }

  // -> [{week, name, qty, showQty, have, covered, key}]
  function split(items, weeks) {
    const load = new Array(weeks).fill(0);
    const out = [];
    const leastLoaded = (cands) => cands.reduce((b, w) => (load[w] < load[b] ? w : b), cands[0]);
    for (const item of merge(items)) {
      const share = new Array(weeks).fill(0);
      let allowed = (item.onlyWeeks || []).map(w => w - 1).filter(w => w >= 0 && w < weeks).sort((a, b) => a - b);
      allowed = [...new Set(allowed)];
      if (!allowed.length) allowed = [...Array(weeks).keys()];
      const n = allowed.length;
      if (item.qty <= 1) share[leastLoaded(allowed)] = 1;
      else {
        const per = Math.floor(item.qty / n), rem = item.qty % n;
        for (const w of allowed) share[w] = per;
        for (let i = 0; i < rem; i++) share[allowed[Math.floor(i * n / rem)]]++;
      }
      let stock = item.have || 0;
      const showQty = item.qty > 1;
      for (let w = 0; w < weeks; w++) {
        if (!share[w]) continue;
        const used = Math.min(stock, share[w]);
        stock -= used;
        const need = share[w] - used;
        const e = need > 0
          ? { week: w + 1, name: item.name, qty: need, showQty, have: used, covered: false }
          : { week: w + 1, name: item.name, qty: share[w], showQty, have: used, covered: true };
        e.key = key(e.week, e.name);
        out.push(e);
        load[w]++;
      }
    }
    return out;
  }

  function label(e, qty) {
    const q = qty == null ? e.qty : qty;
    return e.showQty ? q + ' ' + e.name : e.name;
  }

  // Freie Zeile wie "60 Bananen" oder "Bananen 60"
  function parseLine(raw) {
    let s = normalize(raw).replace(/^[-–—•*·▪◦‣]+\s*/, '').trim();
    if (!s) return null;
    let qty = 1;
    let m = s.match(/^(\d+)(?:\s*[xX×*]\s*|\s+)(\S.*)$/);
    if (m) { qty = parseInt(m[1], 10); s = m[2].trim(); }
    else if ((m = s.match(/^(.+?)\s+(?:(\d+)\s*[xX×*]?|[xX×*]\s*(\d+))$/))) {
      qty = parseInt(m[2] || m[3], 10); s = m[1].trim();
    }
    if (!s) return null;
    return { name: s, qty: Math.min(Math.max(qty || 1, 1), 9999) };
  }

  // ---------- Datenmodell (für den Abgleich) ----------
  // doc = { v:1,
  //   items:   { id: {name, qty, have, weeks:[..], perPack, perDay, days, del, t} },
  //   order:   { ids:[..], t },
  //   checked: { key: {v:bool, t} },
  //   bought:  { key: {n:int, t} },
  //   weeks:   { v:4|5, t } }
  // Jeder Eintrag trägt seinen Änderungszeitpunkt t; beim Zusammenführen gewinnt der neuere.

  function emptyDoc() {
    return { v: 1, items: {}, order: { ids: [], t: 0 }, checked: {}, bought: {}, weeks: { v: 4, t: 0 } };
  }

  function canon(x) {
    if (Array.isArray(x)) return '[' + x.map(canon).join(',') + ']';
    if (x && typeof x === 'object') {
      return '{' + Object.keys(x).sort().filter(k => x[k] !== undefined).map(k => JSON.stringify(k) + ':' + canon(x[k])).join(',') + '}';
    }
    if (typeof x === 'number') return Number.isInteger(x) ? String(x) : String(Math.round(x * 1e6) / 1e6);
    return JSON.stringify(x === undefined ? null : x);
  }

  function newer(a, b) {
    if (!a) return b;
    if (!b) return a;
    if ((a.t || 0) !== (b.t || 0)) return (a.t || 0) > (b.t || 0) ? a : b;
    return canon(a) >= canon(b) ? a : b; // gleichstand: deterministisch
  }

  function mergeMap(a, b) {
    const out = {};
    for (const k of new Set([...Object.keys(a || {}), ...Object.keys(b || {})])) out[k] = newer((a || {})[k], (b || {})[k]);
    return out;
  }

  const KEEP_TOMBSTONES_MS = 60 * 24 * 3600 * 1000;

  function mergeDocs(a, b, nowMs) {
    a = a || emptyDoc(); b = b || emptyDoc();
    const now = nowMs || Date.now();
    const items = mergeMap(a.items, b.items);
    for (const id of Object.keys(items)) {
      if (items[id].del && now - (items[id].t || 0) > KEEP_TOMBSTONES_MS) delete items[id];
    }
    const order = newer(a.order, b.order) || { ids: [], t: 0 };
    const doc = {
      v: 1,
      items,
      order: { ids: normalizeOrder(order.ids, items), t: order.t || 0 },
      checked: mergeMap(a.checked, b.checked),
      bought: mergeMap(a.bought, b.bought),
      weeks: newer(a.weeks, b.weeks) || { v: 4, t: 0 },
    };
    return doc;
  }

  // Reihenfolge: nur lebende Artikel; fehlende hinten anhängen (nach Zeit, dann id)
  function normalizeOrder(ids, items) {
    const seen = new Set();
    const out = [];
    for (const id of ids || []) {
      if (items[id] && !items[id].del && !seen.has(id)) { seen.add(id); out.push(id); }
    }
    const missing = Object.keys(items).filter(id => !items[id].del && !seen.has(id))
      .sort((x, y) => ((items[x].t || 0) - (items[y].t || 0)) || (x < y ? -1 : x > y ? 1 : 0));
    return out.concat(missing);
  }

  function qtyOf(it, weeks) {
    if (it.perPack > 0 && it.perDay > 0) {
      const days = it.days || weeks * 7;
      return Math.max(1, Math.ceil(it.perDay * days / it.perPack - 1e-9));
    }
    return Math.max(1, it.qty || 1);
  }

  function liveItems(doc) {
    return normalizeOrder(doc.order.ids, doc.items).map(id => Object.assign({ id }, doc.items[id]));
  }

  function entries(doc) {
    const weeks = doc.weeks.v === 5 ? 5 : 4;
    const items = liveItems(doc).filter(it => normalize(it.name))
      .map(it => ({ name: it.name, qty: qtyOf(it, weeks), onlyWeeks: it.weeks || [], have: it.have || 0 }));
    return split(items, weeks);
  }

  function isChecked(doc, k) { const c = doc.checked[k]; return !!(c && c.v); }
  function boughtOf(doc, k) { const b = doc.bought[k]; return b ? (b.n || 0) : 0; }
  function isDone(doc, e) { return e.covered || isChecked(doc, e.key) || boughtOf(doc, e.key) >= e.qty; }

  // ---------- Verbindungs-Code ----------
  function encodeConnect(cfg) {
    const json = JSON.stringify({ u: cfg.url, k: cfg.key, c: cfg.code });
    const b64 = (typeof btoa === 'function' ? btoa(unescape(encodeURIComponent(json))) : Buffer.from(json).toString('base64'));
    return 'EK1-' + b64.replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
  }
  function decodeConnect(s) {
    s = String(s || '').trim();
    const m = s.match(/EK1-([A-Za-z0-9_-]+)/);
    if (!m) return null;
    let b64 = m[1].replace(/-/g, '+').replace(/_/g, '/');
    while (b64.length % 4) b64 += '=';
    try {
      const json = typeof atob === 'function' ? decodeURIComponent(escape(atob(b64))) : Buffer.from(b64, 'base64').toString('utf8');
      const o = JSON.parse(json);
      if (!o.u || !o.k || !o.c) return null;
      return { url: String(o.u).replace(/\/+$/, ''), key: o.k, code: o.c };
    } catch (e) { return null; }
  }

  root.EK = {
    normalize, key, split, label, parseLine, emptyDoc, canon, mergeDocs, normalizeOrder,
    qtyOf, liveItems, entries, isChecked, boughtOf, isDone, encodeConnect, decodeConnect,
  };
})(typeof window !== 'undefined' ? window : globalThis);
