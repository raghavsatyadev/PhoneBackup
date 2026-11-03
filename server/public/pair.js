// "Allow this phone to connect?" prompt for pairing requests from the Android app.
// The phone shows the same code, so the user can check it is their phone.
// Names come from the network: only ever set them with textContent.
(() => {
  const waiting = new Map(); // id -> request, oldest first
  const baseTitle = document.title;
  let shown = null;
  let backdrop = null;

  function el(tag, attrs = {}, text) {
    const e = document.createElement(tag);
    Object.assign(e, attrs);
    if (text !== undefined) e.textContent = text;
    return e;
  }

  function build() {
    backdrop = el('div', { className: 'modal-backdrop', id: 'pairModal' });
    const dialog = el('div', { className: 'modal-dialog' });
    const header = el('div', { className: 'modal-header' });
    header.append(el('h3', {}, '📱 Allow this phone to connect?'));
    const body = el('div', { className: 'modal-body' });
    body.append(
      el('p', { id: 'pairName', style: 'font-size:15px;font-weight:600;margin-bottom:4px' }),
      el('p', { id: 'pairIp', style: 'font-size:12px;color:var(--text-muted);margin-bottom:14px' }),
      el('p', { style: 'font-size:13px;margin-bottom:6px' }, 'Check that the phone shows this code:'),
      el('div', { id: 'pairCode', style: 'font:700 34px monospace;letter-spacing:6px;text-align:center;padding:12px;border:1px solid var(--border);border-radius:var(--radius);margin-bottom:12px' }),
      el('p', { style: 'font-size:12px;color:var(--text-muted);margin-bottom:16px' },
        'Allowing lets this phone send photos to this PC and view the photos it has received.'),
    );
    const buttons = el('div', { style: 'display:flex;gap:8px;justify-content:flex-end' });
    const deny = el('button', { className: 'btn btn-danger' }, 'Deny');
    const allow = el('button', { className: 'btn btn-primary' }, 'Allow');
    deny.onclick = () => decide(false);
    allow.onclick = () => decide(true);
    buttons.append(deny, allow);
    body.append(buttons);
    dialog.append(header, body);
    backdrop.append(dialog);
    document.body.append(backdrop);
  }

  function render() {
    shown = waiting.values().next().value || null;
    document.title = shown ? `(${waiting.size}) Allow phone? · ${baseTitle}` : baseTitle;
    if (!shown) {
      if (backdrop) backdrop.style.display = 'none';
      return;
    }
    if (!backdrop) build();
    backdrop.querySelector('#pairName').textContent = shown.name;
    backdrop.querySelector('#pairIp').textContent = `From ${shown.ip}`;
    backdrop.querySelector('#pairCode').textContent = `${shown.code.slice(0, 3)} ${shown.code.slice(3)}`;
    backdrop.style.display = 'flex';
  }

  async function decide(allow) {
    if (!shown) return;
    const req = shown;
    waiting.delete(req.id);
    render();
    try {
      const res = await fetch('/api/pair/decide', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ id: req.id, allow }),
      });
      const data = await res.json().catch(() => ({}));
      if (!res.ok) throw new Error(data.error || `HTTP ${res.status}`);
      if (typeof showToast === 'function') {
        showToast(allow ? `${req.name} can now send photos` : `${req.name} was denied`, allow ? 'success' : 'info');
      }
    } catch (e) {
      if (typeof showToast === 'function') showToast(e.message, 'error');
    }
  }

  // Called on page load and on every WebSocket (re)connect, so no request is missed.
  window.loadPairRequests = async () => {
    try {
      const res = await fetch('/api/pair/pending');
      if (!res.ok) return;
      waiting.clear();
      for (const r of await res.json()) waiting.set(r.id, r);
      render();
    } catch {}
  };

  // WebSocket messages: pair_request, pair_allowed, pair_denied, pair_expired.
  window.onPairEvent = (data) => {
    const r = data.pair;
    if (!r) return;
    if (data.type === 'pair_request') waiting.set(r.id, r);
    else waiting.delete(r.id);
    render();
  };

  window.loadPairRequests();
})();
