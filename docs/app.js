// Einkauf – Web-App (iPhone & Browser). Gleiche Funktionen wie die Android-App.
(function () {
  'use strict';
  const EK = window.EK;

  // ---------- Speicher ----------
  const LS = { doc: 'ek_doc', cfg: 'ek_cfg', ui: 'ek_ui' };
  function load(k) { try { const s = localStorage.getItem(k); return s ? JSON.parse(s) : null; } catch (e) { return null; } }
  function store(k, v) { try { localStorage.setItem(k, JSON.stringify(v)); } catch (e) { /* voll/gesperrt */ } }

  let doc = load(LS.doc) || EK.emptyDoc();
  doc = EK.mergeDocs(doc, EK.emptyDoc());
  let cfg = load(LS.cfg);
  let ui = Object.assign({ tab: 0 }, load(LS.ui) || {});
  let pendingNew = { qty: '', have: '', weeks: [] };
  let lastType = 0;

  const now = () => Date.now();
  const weeks = () => (doc.weeks.v === 5 ? 5 : 4);
  const uid = () => (crypto.randomUUID ? crypto.randomUUID() : 'i' + now().toString(36) + Math.random().toString(36).slice(2, 10));

  // Verbindungs-Code aus dem Link (#EK1-…)
  (function readHash() {
    const c = EK.decodeConnect(decodeURIComponent(location.hash || ''));
    if (c) {
      cfg = c; store(LS.cfg, cfg);
      history.replaceState(null, '', location.pathname + location.search);
      setTimeout(() => toast('Connected to your shared list'), 300);
    }
  })();

  // ---------- Änderungen (jede mit Zeitstempel für den Abgleich) ----------
  function save() { store(LS.doc, doc); scheduleSync(700); }

  function touchItem(id, patch) {
    const prev = doc.items[id];
    const isNew = !prev || prev.del;
    const base = isNew ? { name: '', qty: 1, have: 0, weeks: [] } : prev;
    const next = Object.assign({}, base, patch, { t: now() });
    delete next.del;
    for (const k of Object.keys(next)) if (next[k] === undefined) delete next[k];
    doc.items[id] = next;
    if (isNew) {
      const ids = EK.normalizeOrder(doc.order.ids, doc.items).filter(x => x !== id);
      doc.order = { ids: ids.concat(id), t: now() };
    }
    save();
  }
  function deleteItem(id) {
    doc.items[id] = { del: true, t: now() };
    doc.order = { ids: doc.order.ids.filter(x => x !== id), t: doc.order.t };
    save();
  }
  function setChecked(k, v) { doc.checked[k] = { v: !!v, t: now() }; }
  function setBought(k, n) { doc.bought[k] = { n: n, t: now() }; }
  function setOrder(ids) { doc.order = { ids: ids, t: now() }; save(); }
  function itemsNamed(name) {
    const n = EK.normalize(name).toLowerCase();
    return EK.liveItems(doc).filter(it => EK.normalize(it.name).toLowerCase() === n);
  }

  // ---------- kleine DOM-Hilfen ----------
  function h(tag, attrs, ...kids) {
    const el = document.createElement(tag);
    for (const [k, v] of Object.entries(attrs || {})) {
      if (v == null || v === false) continue;
      if (k === 'class') el.className = v;
      else if (k.startsWith('on')) el.addEventListener(k.slice(2), v);
      else if (k === 'text') el.textContent = v;
      else if (k in el && k !== 'list') el[k] = v;
      else el.setAttribute(k, v);
    }
    for (const kid of kids.flat()) if (kid != null && kid !== false) el.append(kid.nodeType ? kid : document.createTextNode(String(kid)));
    return el;
  }
  const $main = document.getElementById('main');
  const $tabs = document.getElementById('tabs');

  function toast(msg) {
    const t = h('div', { class: 'toast', text: msg });
    document.body.append(t);
    setTimeout(() => t.remove(), 2400);
  }

  // ---------- Dialoge ----------
  let openSheet = null;
  function sheet(build) {
    closeSheet();
    const box = h('div', { class: 'sheet', role: 'dialog' });
    const scrim = h('div', { class: 'scrim', onclick: e => { if (e.target === scrim) closeSheet(); } }, box);
    build(box, closeSheet);
    document.body.append(scrim);
    openSheet = scrim;
  }
  function closeSheet() { if (openSheet) { openSheet.remove(); openSheet = null; } }

  function menu(title, options) {
    sheet((box, close) => {
      box.append(h('h2', { text: title }));
      for (const [label, fn] of options) box.append(h('button', { class: 'opt', text: label, onclick: () => { close(); fn(); } }));
      box.append(h('div', { class: 'btns' }, h('button', { text: 'Cancel', onclick: close })));
    });
  }

  function numberDialog(title, message, initial, onOk) {
    sheet((box, close) => {
      const inp = h('input', { class: 'fld', type: 'text', inputMode: 'numeric', value: initial > 0 ? String(initial) : '', enterKeyHint: 'done' });
      const ok = () => { close(); onOk(parseInt(inp.value, 10) || 0); render(); };
      inp.addEventListener('keydown', e => { if (e.key === 'Enter') ok(); });
      box.append(h('h2', { text: title }), h('p', { text: message }), inp,
        h('div', { class: 'btns' }, h('button', { text: 'Cancel', onclick: close }), h('button', { class: 'pri', text: 'OK', onclick: ok })));
      setTimeout(() => { inp.focus(); inp.select(); }, 50);
    });
  }

  function weeksDialog(title, current, onOk) {
    sheet((box, close) => {
      box.append(h('h2', { text: title + ': which weeks?' }));
      const boxes = [];
      for (let w = 1; w <= weeks(); w++) {
        const cb = h('input', { type: 'checkbox', checked: current.includes(w) });
        boxes.push(cb);
        box.append(h('label', { class: 'check' }, cb, 'Week ' + w));
      }
      const done = sel => { close(); onOk(sel); render(); };
      box.append(h('div', { class: 'btns' },
        h('button', { text: 'All', onclick: () => done([]) }),
        h('span', { class: 'spacer' }),
        h('button', { text: 'Cancel', onclick: close }),
        h('button', { class: 'pri', text: 'OK', onclick: () => {
          const sel = boxes.map((b, i) => (b.checked ? i + 1 : 0)).filter(Boolean);
          done(sel.length === weeks() ? [] : sel);
        } })));
    });
  }

  function weeksLabel(sel) {
    const s = (sel || []).filter(w => w >= 1 && w <= weeks()).sort((a, b) => a - b);
    if (!s.length || s.length === weeks()) return 'auto';
    if (s.length === 1) return 'W' + s[0];
    if (s.length === 2) return 'W' + s[0] + '+' + s[1];
    return s.join('+');
  }

  const fmt = d => (Number.isInteger(d) ? String(d) : String(Math.round(d * 10) / 10));
  const num = s => { const v = parseFloat(String(s).replace(',', '.')); return isFinite(v) ? v : null; };

  function calcDialog(it) {
    sheet((box, close) => {
      box.append(h('h2', { text: (it.name ? it.name + ': ' : '') + 'Pack calculator' }));
      const field = (label, value, ph) => {
        const inp = h('input', { class: 'fld', type: 'text', inputMode: 'decimal', value, placeholder: ph });
        box.append(h('label', { class: 'lbl', text: label }), inp);
        return inp;
      };
      const pack = field('Contents of one pack (g, ml, pieces …)', it.perPack ? fmt(it.perPack) : '', 'e.g. 500');
      const day = field('Used per day (same unit)', it.perDay ? fmt(it.perDay) : '', 'e.g. 300');
      const days = field('For how many days', String(it.days || weeks() * 7), String(weeks() * 7));
      const result = h('div', { class: 'result' });
      box.append(result);
      const update = () => {
        const p = num(pack.value), d = num(day.value), t = num(days.value);
        if (p > 0 && d > 0 && t > 0) {
          const total = d * t;
          result.textContent = '= ' + Math.ceil(total / p - 1e-9) + ' packs  (' + fmt(total) + ' total)';
        } else result.textContent = 'Enter pack size and daily use';
      };
      [pack, day, days].forEach(i => i.addEventListener('input', update));
      update();
      const apply = () => {
        const p = num(pack.value), d = num(day.value), t = Math.round(num(days.value) || 0);
        if (!(p > 0 && d > 0)) return;
        const patch = { perPack: p, perDay: d, days: (t > 0 && t !== weeks() * 7) ? t : undefined };
        patch.qty = EK.qtyOf(Object.assign({}, it, patch), weeks());
        touchItem(it.id, patch);
        close(); render();
      };
      const btns = h('div', { class: 'btns' });
      if (it.perPack && it.perDay) btns.append(h('button', { class: 'warn', text: 'Turn off calculator', onclick: () => {
        touchItem(it.id, { perPack: undefined, perDay: undefined, days: undefined }); close(); render();
      } }));
      btns.append(h('span', { class: 'spacer' }), h('button', { text: 'Cancel', onclick: close }), h('button', { class: 'pri', text: 'Apply', onclick: apply }));
      box.append(btns);
      setTimeout(() => pack.focus(), 50);
    });
  }

  // ---------- Ziehen zum Sortieren / Antippen ----------
  function attachDrag(handle, el, getSiblings, onDrop, onTap) {
    handle.addEventListener('pointerdown', ev => {
      ev.preventDefault();
      const list = getSiblings();
      const from = list.indexOf(el);
      if (from < 0) return;
      try { handle.setPointerCapture(ev.pointerId); } catch (e) { /* ok */ }
      if (document.activeElement && document.activeElement.blur) document.activeElement.blur();
      const tops = list.map(x => x.offsetTop), hs = list.map(x => x.offsetHeight);
      const hgt = el.offsetHeight, startY = ev.clientY, startScroll = window.scrollY;
      let target = from, moved = false;
      el.classList.add('dragging'); handle.classList.add('active');
      const move = e => {
        const dy = e.clientY - startY + (window.scrollY - startScroll);
        if (Math.abs(dy) > 6) moved = true;
        if (!moved) return;
        el.style.transform = 'translateY(' + dy + 'px)';
        const center = tops[from] + dy + hgt / 2;
        let t = from;
        for (let j = from + 1; j < list.length; j++) if (center > tops[j] + hs[j] / 2) t = j;
        for (let j = from - 1; j >= 0; j--) if (center < tops[j] + hs[j] / 2) t = j;
        target = t;
        list.forEach((o, j) => {
          if (o === el) return;
          let s = 0;
          if (j > from && j <= t) s = -hgt; else if (j >= t && j < from) s = hgt;
          o.style.transition = 'transform .12s';
          o.style.transform = s ? 'translateY(' + s + 'px)' : '';
        });
        if (e.clientY < 90) window.scrollBy(0, -10);
        else if (e.clientY > window.innerHeight - 90) window.scrollBy(0, 10);
      };
      const up = () => {
        handle.removeEventListener('pointermove', move);
        handle.removeEventListener('pointerup', up);
        handle.removeEventListener('pointercancel', up);
        list.forEach(o => { o.style.transform = ''; o.style.transition = ''; });
        el.classList.remove('dragging'); handle.classList.remove('active');
        if (!moved) { if (onTap) onTap(); return; }
        if (target !== from) onDrop(from, target);
      };
      handle.addEventListener('pointermove', move);
      handle.addEventListener('pointerup', up);
      handle.addEventListener('pointercancel', up);
    });
  }

  function pressable(el, onTap, onLong) {
    let timer = null, sx = 0, sy = 0, long = false;
    const cancel = () => { if (timer) { clearTimeout(timer); timer = null; } };
    el.addEventListener('pointerdown', e => {
      if (e.target.closest('.handle')) return;
      long = false; sx = e.clientX; sy = e.clientY;
      timer = setTimeout(() => { timer = null; long = true; if (navigator.vibrate) navigator.vibrate(15); onLong(); }, 500);
    });
    el.addEventListener('pointermove', e => { if (timer && (Math.abs(e.clientX - sx) > 10 || Math.abs(e.clientY - sy) > 10)) cancel(); });
    el.addEventListener('pointerup', cancel);
    el.addEventListener('pointercancel', cancel);
    el.addEventListener('contextmenu', e => e.preventDefault());
    el.addEventListener('click', e => {
      if (e.target.closest('.handle')) return;
      if (long) { long = false; return; }
      onTap();
    });
  }

  // ---------- Aufbau ----------
  function render() {
    // Fokus merken, damit ein Neuaufbau beim Tippen nicht stört
    const a = document.activeElement;
    let focus = null;
    if (a && a.dataset && a.dataset.f) {
      focus = { id: a.dataset.id, f: a.dataset.f, s: a.selectionStart, e: a.selectionEnd };
    }
    renderTabs();
    $main.replaceChildren(ui.tab === 0 ? monthView() : weekView(ui.tab));
    if (focus) {
      const el = $main.querySelector('[data-id="' + focus.id + '"][data-f="' + focus.f + '"]');
      if (el) { el.focus({ preventScroll: true }); try { el.setSelectionRange(focus.s, focus.e); } catch (e) { /* ok */ } }
    }
  }

  function renderTabs() {
    const all = EK.entries(doc);
    const W = weeks();
    if (ui.tab > W) ui.tab = W;
    $tabs.replaceChildren();
    for (let i = 0; i <= W; i++) {
      let label, sub;
      if (i === 0) { label = 'Month'; sub = EK.liveItems(doc).filter(it => EK.normalize(it.name)).length; }
      else {
        const wk = all.filter(e => e.week === i);
        const open = wk.filter(e => !EK.isDone(doc, e)).length;
        label = 'W' + i; sub = wk.length && !open ? '✓' : open;
      }
      $tabs.append(h('button', {
        class: 'tab' + (i === 0 ? ' month' : '') + (i === ui.tab ? ' on' : ''),
        onclick: () => { ui.tab = i; store(LS.ui, ui); render(); window.scrollTo(0, 0); },
      }, label, h('small', { text: String(sub) })));
    }
  }

  function syncLine() {
    const dot = h('span', { class: 'dot ' + (syncState === 'ok' ? 'ok' : syncState === 'busy' ? 'busy' : syncState === 'err' ? 'err' : '') });
    let text;
    if (!cfg) text = 'Only on this device';
    else if (syncState === 'err') text = 'Offline – will sync later';
    else if (syncState === 'busy' && !lastSync) text = 'Connecting …';
    else text = 'Synced';
    return h('div', { class: 'sync', id: 'syncline' }, dot, h('span', { text }),
      h('button', { class: 'linkbtn', text: cfg ? 'Sync' : 'Set up sync', onclick: settingsDialog }));
  }

  // ----- Monat -----
  function monthView() {
    const W = weeks();
    const root = h('div');
    root.append(h('h1', { text: 'What do we need this month?' }), syncLine());
    root.append(h('div', { class: 'muted' },
      'Enter quantity and item – the quantity is split across the weeks. “have” = already at home. ',
      'Tap “Week” = only certain weeks. Tap ≡ = menu (pack calculator, delete), drag ≡ = reorder.'));
    root.append(h('div', { class: 'cols' }, h('span'), h('span', { text: 'Qty' }), h('span', { class: 'l', text: 'Item' }), h('span', { text: 'Have' }), h('span', { text: 'Week' })));

    const list = h('div', { id: 'rows' });
    const items = EK.liveItems(doc);
    for (const it of items) list.append(rowEl(it, list));
    list.append(newRowEl(list));
    root.append(list);

    root.append(h('div', { class: 'stats', id: 'stats', text: statsText() }));
    const seg = n => h('button', { class: 'seg' + (W === n ? ' on' : ''), text: n + ' weeks', onclick: () => {
      doc.weeks = { v: n, t: now() }; save(); render();
    } });
    root.append(h('div', { class: 'line' }, h('span', { class: 'grow', text: 'Split across' }), seg(4), seg(5)));
    root.append(h('button', { class: 'outline', text: 'New month – clear everything', onclick: () => {
      sheet((box, close) => {
        box.append(h('h2', { text: 'Start a new month?' }),
          h('p', { text: 'The month list and all check marks will be deleted – on the other phone too.' }),
          h('div', { class: 'btns' }, h('button', { text: 'Cancel', onclick: close }), h('button', { class: 'pri', text: 'Clear', onclick: () => {
            const t = now();
            for (const id of Object.keys(doc.items)) if (!doc.items[id].del) doc.items[id] = { del: true, t };
            for (const k of Object.keys(doc.checked)) if (doc.checked[k].v) doc.checked[k] = { v: false, t };
            for (const k of Object.keys(doc.bought)) if (doc.bought[k].n) doc.bought[k] = { n: 0, t };
            doc.order = { ids: [], t };
            save(); close(); render();
          } })));
      });
    } }));
    return root;
  }

  function statsText() {
    const n = EK.liveItems(doc).filter(it => EK.normalize(it.name)).length;
    if (!n) return 'Nothing added yet.';
    const all = EK.entries(doc);
    const per = [];
    for (let w = 1; w <= weeks(); w++) per.push('W' + w + ': ' + all.filter(e => e.week === w && !e.covered).length);
    return n + (n === 1 ? ' item' : ' items') + '  →  ' + per.join('  ·  ');
  }
  function refreshCounts() {
    renderTabs();
    const s = document.getElementById('stats');
    if (s) s.textContent = statsText();
  }
  function typed() { lastType = now(); refreshCounts(); }

  function focusNext(el) {
    const next = el.nextElementSibling;
    const q = next && next.querySelector('[data-f="qty"]');
    if (q) q.focus();
  }

  function rowEl(it, list) {
    const W = weeks();
    const calc = it.perPack > 0 && it.perDay > 0;
    const el = h('div', { class: 'item' });
    const handle = h('div', { class: 'handle', text: '≡', 'aria-label': 'Menu, or drag to reorder' });
    const qty = h('input', {
      class: 'num' + (calc ? ' calc' : ''), type: 'text', inputMode: 'numeric', enterKeyHint: 'next',
      value: String(EK.qtyOf(it, W)), 'data-id': it.id, 'data-f': 'qty', 'aria-label': 'Quantity',
      oninput: () => {
        const n = Math.max(1, parseInt(qty.value, 10) || 1);
        const cur = doc.items[it.id];
        const patch = { qty: n };
        if (cur.perPack && cur.perDay && n !== EK.qtyOf(cur, weeks())) {
          Object.assign(patch, { perPack: undefined, perDay: undefined, days: undefined });
          qty.classList.remove('calc');
          const note = el.querySelector('.calcnote'); if (note) note.remove();
        }
        touchItem(it.id, patch); typed();
      },
      onkeydown: e => { if (e.key === 'Enter') { e.preventDefault(); name.focus(); } },
    });
    const name = h('input', {
      type: 'text', value: it.name || '', placeholder: 'Item…', enterKeyHint: 'next', autocapitalize: 'sentences',
      'data-id': it.id, 'data-f': 'name', 'aria-label': 'Item',
      oninput: () => { touchItem(it.id, { name: name.value }); typed(); },
      onchange: () => { if (!EK.normalize(name.value)) { deleteItem(it.id); render(); } },
      onkeydown: e => { if (e.key === 'Enter') { e.preventDefault(); focusNext(el); } },
    });
    const have = h('input', {
      class: 'num', type: 'text', inputMode: 'numeric', enterKeyHint: 'next', placeholder: '–',
      value: it.have > 0 ? String(it.have) : '', 'data-id': it.id, 'data-f': 'have', 'aria-label': 'Already have',
      oninput: () => { touchItem(it.id, { have: Math.max(0, parseInt(have.value, 10) || 0) }); typed(); },
      onkeydown: e => { if (e.key === 'Enter') { e.preventDefault(); focusNext(el); } },
    });
    const wl = weeksLabel(it.weeks);
    const chip = h('button', { class: 'chip' + (wl !== 'auto' ? ' set' : '') + (wl.length > 5 ? ' small' : ''), text: wl, 'aria-label': 'Weeks',
      onclick: () => weeksDialog(it.name || 'Weeks', it.weeks || [], sel => touchItem(it.id, { weeks: sel })) });
    el.append(h('div', { class: 'row' }, handle, qty, name, have, chip));
    if (calc) {
      el.append(h('div', { class: 'calcnote', text:
        fmt(it.perDay) + ' per day × ' + (it.days || W * 7) + ' days ÷ ' + fmt(it.perPack) + ' per pack = ' + EK.qtyOf(it, W) + ' packs',
        onclick: () => calcDialog(doc.items[it.id] ? Object.assign({ id: it.id }, doc.items[it.id]) : it) }));
    }
    attachDrag(handle, el, () => [...list.querySelectorAll('.item[data-live]')], (from, to) => {
      const ids = EK.liveItems(doc).map(x => x.id);
      const [m] = ids.splice(from, 1);
      ids.splice(to, 0, m);
      setOrder(ids); render();
    }, () => {
      const cur = Object.assign({ id: it.id }, doc.items[it.id]);
      menu(cur.name || 'Row', [
        ['Pack calculator …', () => calcDialog(cur)],
        ['Choose weeks …', () => weeksDialog(cur.name || 'Weeks', cur.weeks || [], sel => touchItem(it.id, { weeks: sel }))],
        ['Delete row', () => { deleteItem(it.id); render(); }],
      ]);
    });
    el.dataset.live = '1';
    return el;
  }

  // leere Zeile am Ende: wird zum Artikel, sobald ein Name drinsteht
  function newRowEl(list) {
    const el = h('div', { class: 'item' });
    const qty = h('input', { class: 'num', type: 'text', inputMode: 'numeric', enterKeyHint: 'next', placeholder: '1',
      value: pendingNew.qty, 'data-id': 'new', 'data-f': 'qty', 'aria-label': 'Quantity',
      oninput: () => { pendingNew.qty = qty.value; },
      onkeydown: e => { if (e.key === 'Enter') { e.preventDefault(); name.focus(); } } });
    const name = h('input', { type: 'text', placeholder: 'Item…', enterKeyHint: 'next', autocapitalize: 'sentences',
      'data-id': 'new', 'data-f': 'name', 'aria-label': 'Item',
      oninput: () => {
        if (!EK.normalize(name.value)) return;
        const id = uid();
        touchItem(id, {
          name: name.value,
          qty: Math.max(1, parseInt(pendingNew.qty, 10) || 1),
          have: Math.max(0, parseInt(pendingNew.have, 10) || 0),
          weeks: pendingNew.weeks,
        });
        pendingNew = { qty: '', have: '', weeks: [] };
        lastType = now();
        // Zeile an Ort und Stelle "umwandeln", damit der Fokus bleibt
        const caret = name.selectionStart;
        render();
        const n = $main.querySelector('[data-id="' + id + '"][data-f="name"]');
        if (n) { n.focus({ preventScroll: true }); try { n.setSelectionRange(caret, caret); } catch (e) { /* ok */ } }
      },
      onkeydown: e => { if (e.key === 'Enter') e.preventDefault(); } });
    const have = h('input', { class: 'num', type: 'text', inputMode: 'numeric', placeholder: '–', value: pendingNew.have,
      'data-id': 'new', 'data-f': 'have', 'aria-label': 'Already have', oninput: () => { pendingNew.have = have.value; } });
    const wl = weeksLabel(pendingNew.weeks);
    const chip = h('button', { class: 'chip' + (wl !== 'auto' ? ' set' : ''), text: wl, 'aria-label': 'Weeks',
      onclick: () => weeksDialog('New item', pendingNew.weeks, sel => { pendingNew.weeks = sel; }) });
    el.append(h('div', { class: 'row' }, h('div', { class: 'handle' }), qty, name, have, chip));
    return el;
  }

  // ----- Woche -----
  function weekView(week) {
    const root = h('div');
    const wk = EK.entries(doc).filter(e => e.week === week);
    const open = wk.filter(e => !EK.isDone(doc, e));
    const done = wk.filter(e => EK.isDone(doc, e));
    root.append(h('h1', { text: 'Week ' + week }), syncLine());
    root.append(h('div', { class: 'muted', text: wk.length ? done.length + ' of ' + wk.length + ' done' : 'Nothing for this week yet.' }));
    if (wk.length) {
      const pct = Math.round(done.length / wk.length * 100);
      root.append(h('div', { class: 'bar' }, h('i', { class: pct === 100 ? 'full' : '', style: 'width:' + pct + '%' })));
    }
    const openBox = h('div');
    for (const e of open) {
      const el = entryEl(e, false);
      const handle = h('div', { class: 'handle', text: '≡', 'aria-label': 'Drag to reorder' });
      el.append(handle);
      attachDrag(handle, el, () => [...openBox.children], (from, to) => {
        moveNear(open[from].name, open[to].name, to > from); render();
      }, () => actions(e));
      openBox.append(el);
    }
    root.append(openBox);
    if (open.length && done.length) root.append(h('div', { class: 'sep' }));
    for (const e of done) root.append(entryEl(e, true));
    if (wk.length) root.append(h('div', { class: 'hint', style: 'margin-top:14px',
      text: 'Tap = check off. Long-press or tap ≡ = partly bought, already at home, weeks. Drag ≡ = reorder.' }));

    const inp = h('input', { type: 'text', placeholder: 'Add for this week, e.g. 3 avocados', enterKeyHint: 'done', autocapitalize: 'sentences' });
    const add = () => {
      const p = EK.parseLine(inp.value);
      if (!p) return;
      touchItem(uid(), { name: p.name, qty: p.qty, have: 0, weeks: [week] });
      render();
    };
    inp.addEventListener('keydown', e => { if (e.key === 'Enter') { e.preventDefault(); add(); } });
    root.append(h('div', { class: 'add' }, inp, h('button', { text: '+', 'aria-label': 'Add', onclick: add })));
    return root;
  }

  function entryEl(e, isDone) {
    const got = EK.boughtOf(doc, e.key);
    const partial = !isDone && got > 0;
    const notes = [];
    if (partial) notes.push('still open · ' + got + ' of ' + e.qty + ' bought');
    if (e.covered) notes.push('all at home already'); else if (e.have > 0) notes.push(e.have + ' already at home');
    const el = h('div', { class: 'entry' + (isDone ? ' done' : '') },
      h('div', { class: 'box' + (isDone ? ' done' : partial ? ' part' : ''), text: isDone ? '✓' : partial ? '½' : '' }),
      h('div', { class: 'etext' },
        h('div', { class: 'ename', text: EK.label(e, partial ? e.qty - got : e.qty) }),
        notes.length ? h('div', { class: 'enote' + (partial ? ' part' : ''), text: notes.join('  ·  ') }) : null));
    pressable(el, () => {
      if (e.covered) return;
      if (isDone) { setChecked(e.key, false); if (got) setBought(e.key, 0); }
      else { setChecked(e.key, true); if (got) setBought(e.key, 0); }
      save(); render();
    }, () => actions(e));
    return el;
  }

  function actions(e) {
    const opts = [];
    if (e.qty > 1 && !e.covered) opts.push(['Only partly bought …', () => numberDialog(
      e.name + ': how many did you buy?', 'Out of ' + e.qty + ' for this week. The rest stays open.', EK.boughtOf(doc, e.key), n => {
        setChecked(e.key, false);
        if (n <= 0) setBought(e.key, 0);
        else if (n >= e.qty) { setBought(e.key, 0); setChecked(e.key, true); }
        else setBought(e.key, n);
        save();
      })]);
    const named = itemsNamed(e.name);
    const haveNow = named.reduce((s, it) => s + (it.have || 0), 0);
    opts.push(['Already at home …', () => numberDialog(
      e.name + ': how many are already at home?', 'Taken off the earliest weeks first. 0 = none at home.', haveNow, n => {
        named.forEach((it, i) => touchItem(it.id, { have: i === 0 ? n : 0 }));
      })]);
    const cur = (named.find(it => it.weeks && it.weeks.length) || {}).weeks || [];
    opts.push(['Choose weeks …', () => weeksDialog(e.name, cur, sel => named.forEach(it => touchItem(it.id, { weeks: sel })))]);
    opts.push(['Spread over all weeks', () => { named.forEach(it => touchItem(it.id, { weeks: [] })); render(); }]);
    menu(e.name, opts);
  }

  // Alle Zeilen von Artikel a direkt vor/hinter Artikel b schieben
  function moveNear(a, b, after) {
    const na = EK.normalize(a).toLowerCase(), nb = EK.normalize(b).toLowerCase();
    const live = EK.liveItems(doc);
    const moving = live.filter(it => EK.normalize(it.name).toLowerCase() === na).map(it => it.id);
    const rest = live.filter(it => EK.normalize(it.name).toLowerCase() !== na).map(it => it.id);
    const tIdx = rest.map((id, i) => (EK.normalize(doc.items[id].name).toLowerCase() === nb ? i : -1)).filter(i => i >= 0);
    if (!moving.length) return;
    let at = rest.length;
    if (tIdx.length) at = after ? tIdx[tIdx.length - 1] + 1 : tIdx[0];
    rest.splice(at, 0, ...moving);
    setOrder(rest);
  }

  // ---------- Abgleich (Supabase) ----------
  let syncing = false, again = false, syncState = cfg ? 'busy' : 'off', lastSync = 0, syncTimer = null;

  function setSync(s) {
    syncState = s;
    const old = document.getElementById('syncline');
    if (old) old.replaceWith(syncLine());
  }
  function scheduleSync(ms) { if (!cfg) return; clearTimeout(syncTimer); syncTimer = setTimeout(sync, ms); }

  async function rpc(fn, body) {
    const headers = { apikey: cfg.key, 'Content-Type': 'application/json' };
    if (/^eyJ/.test(cfg.key)) headers.Authorization = 'Bearer ' + cfg.key;
    const ctrl = new AbortController();
    const to = setTimeout(() => ctrl.abort(), 12000);
    try {
      const r = await fetch(cfg.url + '/rest/v1/rpc/' + fn, { method: 'POST', headers, body: JSON.stringify(body), signal: ctrl.signal });
      if (!r.ok) throw new Error('Server ' + r.status + ': ' + (await r.text()).slice(0, 200));
      return r.json();
    } finally { clearTimeout(to); }
  }

  function isEditing() {
    const a = document.activeElement;
    return a && a.tagName === 'INPUT' && $main.contains(a);
  }

  // Server-Stand einarbeiten; lokale Änderungen, die währenddessen passiert sind, bleiben erhalten
  function applyDoc(m) {
    const next = EK.mergeDocs(doc, m);
    if (EK.canon(next) === EK.canon(doc)) return;
    doc = next;
    store(LS.doc, doc);
    if (!openSheet) render(); else refreshCounts();
  }

  async function sync() {
    if (!cfg) return;
    if (syncing) { again = true; return; }
    if (isEditing() && now() - lastType < 2500) { scheduleSync(2500); return; }
    syncing = true;
    if (!lastSync) setSync('busy');
    try {
      const got = await rpc('get_list', { p_code: cfg.code });
      let server = got && got[0] ? got[0].out_data : null;
      let rev = got && got[0] ? got[0].out_rev : 0;
      for (let i = 0; i < 4; i++) {
        const merged = EK.mergeDocs(doc, server);
        applyDoc(merged);
        if (server && EK.canon(merged) === EK.canon(EK.mergeDocs(server, server))) break;
        const res = await rpc('put_list', { p_code: cfg.code, p_data: merged, p_base_rev: rev });
        const r0 = res && res[0];
        if (!r0 || r0.out_ok) break;
        server = r0.out_data; rev = r0.out_rev;
      }
      lastSync = now();
      setSync('ok');
    } catch (e) {
      console.warn('Sync failed', e);
      setSync('err');
    }
    syncing = false;
    if (again) { again = false; scheduleSync(300); }
  }

  function settingsDialog() {
    sheet((box, close) => {
      box.append(h('h2', { text: 'Sync between your phones' }));
      if (!cfg) {
        box.append(h('p', { text: 'Paste the connection code (starts with EK1-). After that, both phones show the same list.' }));
        const ta = h('textarea', { placeholder: 'EK1-…', autocapitalize: 'off', autocorrect: 'off', spellcheck: false });
        const msg = h('p');
        box.append(ta, msg, h('div', { class: 'btns' }, h('button', { text: 'Cancel', onclick: close }), h('button', { class: 'pri', text: 'Connect', onclick: () => {
          const c = EK.decodeConnect(ta.value);
          if (!c) { msg.textContent = 'That is not a valid connection code. It starts with EK1- and must be copied completely.'; return; }
          cfg = c; store(LS.cfg, cfg); close(); lastSync = 0; setSync('busy'); sync(); render();
        } })));
      } else {
        const code = EK.encodeConnect(cfg);
        box.append(h('p', { text: 'Connected. Changes sync every few seconds. Use this code to connect another device:' }),
          h('div', { class: 'code', text: code }),
          h('div', { class: 'btns' },
            h('button', { class: 'warn', text: 'Disconnect', onclick: () => {
              cfg = null; try { localStorage.removeItem(LS.cfg); } catch (e) { /* ok */ }
              syncState = 'off'; close(); render();
            } }),
            h('span', { class: 'spacer' }),
            h('button', { text: 'Copy code', onclick: () => {
              if (navigator.clipboard) navigator.clipboard.writeText(code).then(() => toast('Copied'), () => toast('Please select and copy the code'));
            } }),
            h('button', { class: 'pri', text: 'Done', onclick: close })));
      }
      const standalone = window.navigator.standalone || matchMedia('(display-mode: standalone)').matches;
      if (!standalone) box.append(h('p', { class: 'hint', style: 'margin-top:14px',
        text: 'iPhone tip: in Safari tap “Share” → “Add to Home Screen”. Then open the app from the Home Screen and paste the code there. The Home Screen app has its own storage.' }));
    });
  }

  // ---------- Start ----------
  render();
  if (cfg) sync();
  setInterval(() => { if (document.visibilityState === 'visible') sync(); }, 5000);
  document.addEventListener('visibilitychange', () => { if (document.visibilityState === 'visible') sync(); });
  window.addEventListener('online', () => sync());
  if ('serviceWorker' in navigator) navigator.serviceWorker.register('sw.js').catch(() => {});
})();
