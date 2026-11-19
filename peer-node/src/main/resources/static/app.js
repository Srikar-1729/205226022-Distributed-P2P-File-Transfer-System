// Peer node UI: talks only to its own node (HTTP + Server-Sent Events on localhost).
const $ = (s) => document.querySelector(s);
const SOURCE_COLORS = ['#1a73e8', '#e8710a', '#9334e6', '#d01884', '#12b5cb', '#b06000', '#5f6368', '#1e8e3e'];
let state = null;

async function api(path, body, method) {
  const opts = { method: method || (body === undefined ? 'GET' : 'POST'), headers: {} };
  if (body !== undefined && !(body instanceof Blob)) {
    opts.headers['Content-Type'] = 'application/json';
    opts.body = JSON.stringify(body);
  } else if (body instanceof Blob) {
    opts.headers['Content-Type'] = 'application/octet-stream'; // raw upload, never parsed as a form
    opts.body = body;
  }
  const r = await fetch(path, opts);
  const data = await r.json().catch(() => ({}));
  if (!r.ok) throw new Error(data.error || r.statusText);
  return data;
}

const fmtBytes = (n) => {
  if (n < 1024) return n + ' B';
  const u = ['KB', 'MB', 'GB'];
  let i = -1;
  do { n /= 1024; i++; } while (n >= 1024 && i < u.length - 1);
  return n.toFixed(n < 10 ? 1 : 0) + ' ' + u[i];
};
const esc = (s) => String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
const b64 = (s) => Uint8Array.from(atob(s), (c) => c.charCodeAt(0));

// ---------------------------------------------------------------- live state (SSE)
function connect() {
  const es = new EventSource('/api/events');
  es.onmessage = (e) => { state = JSON.parse(e.data); render(); };
  es.onerror = () => { $('#tracker-pill').textContent = 'node offline'; $('#tracker-pill').className = 'pill bad'; };
}

function render() {
  const n = state.node, s = state.session;
  document.title = n.name + ' · P2P';
  $('#node-name').textContent = n.name;
  $('#node-meta').textContent = `UI :${n.uiPort} · gRPC :${n.grpcPort} · TLS ${n.tls ? 'on' : 'off'}` +
    (n.uploadLimit > 0 ? ` · upload ≤ ${fmtBytes(n.uploadLimit)}/s` : '');
  const pill = $('#tracker-pill');
  if (!s.loggedIn) { pill.textContent = 'not connected'; pill.className = 'pill'; }
  else if (n.trackerOnline) { pill.textContent = 'tracker online'; pill.className = 'pill ok'; }
  else { pill.textContent = 'tracker offline'; pill.className = 'pill bad'; pill.title = n.trackerError || ''; }

  $('#login-view').classList.toggle('hidden', s.loggedIn);
  $('#app-view').classList.toggle('hidden', !s.loggedIn);
  $('#user-box').innerHTML = s.loggedIn
    ? `<b>${esc(s.username)}</b> ${s.guest ? '<span class="muted">(guest)</span>' : ''} <button class="small" id="logout-btn">Log out</button>`
    : '';
  const lo = $('#logout-btn');
  if (lo) lo.onclick = async () => { await api('/api/logout', {}); };
  // guests may only share PUBLIC
  for (const o of $('#visibility').options) o.disabled = s.guest && o.value !== 'PUBLIC';
  if (s.guest) $('#visibility').value = 'PUBLIC';
  $('#guest-note').classList.toggle('hidden', !s.guest);
  $('#allowed-label').classList.toggle('hidden', $('#visibility').value !== 'PRIVATE');
  renderTransfers();
}

// ---------------------------------------------------------------- transfers
const cards = new Map();

function renderTransfers() {
  const box = $('#transfers');
  const seen = new Set();
  for (const f of state.files) {
    seen.add(f.fileId);
    let c = cards.get(f.fileId);
    if (!c) { c = makeCard(f); cards.set(f.fileId, c); box.appendChild(c.el); }
    updateCard(c, f);
  }
  for (const [id, c] of cards) if (!seen.has(id)) { c.el.remove(); cards.delete(id); }
  $('#transfers-empty').classList.toggle('hidden', state.files.length > 0);
}

function makeCard(f) {
  const el = document.createElement('div');
  el.className = 'card transfer';
  el.innerHTML = `
    <div class="top"><span class="name"></span><span class="status"></span></div>
    <div class="bar"><div></div></div>
    <div class="stats"></div>
    <canvas class="map"></canvas>
    <div class="legend"></div>
    <p class="error"></p>
    <div class="actions">
      <button class="small" data-act="pause">Pause</button>
      <button class="small" data-act="resume">Resume</button>
      <a class="save" target="_blank"><button class="small">Save to computer</button></a>
      <button class="small danger" data-act="remove">Remove</button>
    </div>`;
  el.querySelector('.actions').addEventListener('click', async (e) => {
    const act = e.target.dataset.act;
    if (!act) return;
    if (act === 'remove' && !confirm('Remove this download? Unfinished data is deleted.')) return;
    try { await api('/api/' + act, { fileId: f.fileId }); } catch (err) { alert(err.message); }
  });
  return { el, q: (s) => el.querySelector(s) };
}

function updateCard(c, f) {
  const pct = f.chunkCount ? Math.floor((100 * f.have) / f.chunkCount) : 0;
  c.q('.name').textContent = f.name;
  const st = c.q('.status');
  st.textContent = f.status.replace('_', ' ') + (f.kind === 'SHARED' ? ' · your file' : '');
  st.className = 'status ' + f.status;
  c.q('.bar > div').style.width = pct + '%';
  const parts = [`${fmtBytes(f.size)}`, `${f.have}/${f.chunkCount} chunks (${pct}%)`];
  if (f.kind === 'DOWNLOAD' && f.status === 'DOWNLOADING') parts.push(`↓ ${fmtBytes(f.speed)}/s from ${f.activePeers} peer${f.activePeers === 1 ? '' : 's'}`);
  if (f.uploaded > 0) parts.push(`↑ ${fmtBytes(f.uploaded)} served to others`);
  c.q('.stats').innerHTML = parts.map((p) => `<span>${esc(p)}</span>`).join('');
  c.q('.error').textContent = f.error || '';
  const downloading = f.kind === 'DOWNLOAD' && f.status !== 'COMPLETED';
  c.q('[data-act=pause]').classList.toggle('hidden', !downloading || f.status === 'PAUSED' || f.status === 'FAILED');
  c.q('[data-act=resume]').classList.toggle('hidden', !downloading || !(f.status === 'PAUSED' || f.status === 'FAILED'));
  c.q('[data-act=remove]').classList.toggle('hidden', f.kind !== 'DOWNLOAD');
  const save = c.q('.save');
  save.classList.toggle('hidden', !(f.status === 'COMPLETED' || f.status === 'SEEDING'));
  save.href = '/api/file?id=' + encodeURIComponent(f.fileId);
  drawMap(c, f);
}

/** Chunk map: one cell per chunk, coloured by the peer it came from (green = this peer's own file). */
function drawMap(c, f) {
  const canvas = c.q('canvas');
  const have = b64(f.bitfield), src = b64(f.sources);
  const n = f.chunkCount;
  const cell = n <= 64 ? 16 : n <= 256 ? 11 : n <= 1024 ? 7 : 4, gap = 2;
  const width = canvas.clientWidth || 800;
  const cols = Math.max(1, Math.floor((width + gap) / (cell + gap)));
  const rows = Math.ceil(n / cols);
  const dpr = window.devicePixelRatio || 1;
  const h = rows * (cell + gap);
  canvas.style.height = h + 'px';
  canvas.width = width * dpr; canvas.height = h * dpr;
  const ctx = canvas.getContext('2d');
  ctx.scale(dpr, dpr);
  const css = getComputedStyle(document.documentElement);
  const empty = css.getPropertyValue('--empty').trim(), mine = css.getPropertyValue('--green').trim();
  const earlier = '#80868b'; // downloaded before this node restarted: source not recorded
  let anyEarlier = false;
  for (let i = 0; i < n; i++) {
    const got = (have[i >> 3] >> (i & 7)) & 1;
    if (got && src[i] === 0 && f.kind !== 'SHARED') anyEarlier = true;
    ctx.fillStyle = !got ? empty : src[i] !== 0 ? SOURCE_COLORS[(src[i] - 1) % SOURCE_COLORS.length]
      : f.kind === 'SHARED' ? mine : earlier;
    ctx.fillRect((i % cols) * (cell + gap), Math.floor(i / cols) * (cell + gap), cell, cell);
  }
  const legend = f.sourceNames.map((name, k) => `<span><i style="background:${SOURCE_COLORS[k % SOURCE_COLORS.length]}"></i>from ${esc(name)}</span>`);
  if (f.kind === 'SHARED') legend.unshift(`<span><i style="background:${mine}"></i>shared by you</span>`);
  if (anyEarlier) legend.push(`<span><i style="background:${earlier}"></i>downloaded before restart</span>`);
  legend.push(`<span><i style="background:${empty}"></i>missing</span>`);
  c.q('.legend').innerHTML = legend.join('');
}

// ---------------------------------------------------------------- login
let loginAction = 'login';
$('#login-form').addEventListener('click', (e) => { if (e.target.dataset.action) loginAction = e.target.dataset.action; });
$('#login-form').addEventListener('submit', async (e) => {
  e.preventDefault();
  const fd = new FormData(e.target);
  $('#login-error').textContent = '';
  try { await api('/api/' + loginAction, { username: fd.get('username'), password: fd.get('password') }); }
  catch (err) { $('#login-error').textContent = err.message; }
});
$('#guest-btn').onclick = async () => {
  try { await api('/api/guest', {}); } catch (err) { $('#login-error').textContent = err.message; }
};

// ---------------------------------------------------------------- tabs
document.querySelectorAll('.tabs button').forEach((b) => b.addEventListener('click', () => {
  document.querySelectorAll('.tabs button').forEach((x) => x.classList.toggle('active', x === b));
  document.querySelectorAll('.tab').forEach((t) => t.classList.toggle('hidden', t.id !== 'tab-' + b.dataset.tab));
  if (b.dataset.tab === 'search') search('');
  if (b.dataset.tab === 'mine') loadMine();
}));

// ---------------------------------------------------------------- search
const visBadge = (v) => `<span class="vis ${v}">${v}</span>`;

async function search(q) {
  $('#search-error').textContent = '';
  try {
    const { files } = await api('/api/search?q=' + encodeURIComponent(q));
    $('#search-results').innerHTML = files.map((f) => `
      <tr><td>${esc(f.name)}</td><td>${fmtBytes(f.size)}</td><td>${esc(f.owner)}${f.ownedByMe ? ' (you)' : ''}</td>
      <td>${visBadge(f.visibility)}</td>
      <td>${f.localStatus ? `<span class="muted">${f.localStatus.toLowerCase()}</span>`
        : `<button class="small primary" data-dl="${esc(f.fileId)}">Download</button>`}</td></tr>`).join('')
      || '<tr><td colspan="5" class="muted">No files you can access match.</td></tr>';
  } catch (err) { $('#search-error').textContent = err.message; }
}
$('#search-form').addEventListener('submit', (e) => { e.preventDefault(); search(new FormData(e.target).get('q')); });
$('#search-results').addEventListener('click', async (e) => {
  const id = e.target.dataset.dl;
  if (!id) return;
  e.target.disabled = true;
  try {
    await api('/api/download', { fileId: id });
    document.querySelector('.tabs [data-tab=transfers]').click();
  } catch (err) { $('#search-error').textContent = err.message; e.target.disabled = false; }
});

// ---------------------------------------------------------------- share
$('#visibility').addEventListener('change', () => $('#allowed-label').classList.toggle('hidden', $('#visibility').value !== 'PRIVATE'));
$('#share-form').addEventListener('submit', async (e) => {
  e.preventDefault();
  const fd = new FormData(e.target);
  const file = fd.get('file');
  $('#share-error').textContent = '';
  $('#share-status').textContent = `Uploading ${fmtBytes(file.size)} to this peer, hashing chunks…`;
  $('#share-btn').disabled = true;
  try {
    const params = new URLSearchParams({ name: file.name, visibility: fd.get('visibility'), allowedUsers: fd.get('allowedUsers') || '' });
    const info = await api('/api/share?' + params, file);
    $('#share-status').textContent = `Shared "${info.name}" (${info.chunkCount} chunks) as ${info.visibility}. You are now seeding it.`;
    e.target.reset();
    $('#allowed-label').classList.add('hidden');
  } catch (err) {
    $('#share-status').textContent = '';
    $('#share-error').textContent = err.message;
  } finally { $('#share-btn').disabled = false; }
});

// ---------------------------------------------------------------- my files
async function loadMine() {
  $('#mine-error').textContent = '';
  try {
    const { files } = await api('/api/my-files');
    const guest = state && state.session.guest;
    $('#mine-results').innerHTML = files.map((f) => `
      <tr data-id="${esc(f.fileId)}"><td>${esc(f.name)}</td><td>${fmtBytes(f.size)}</td>
      <td><select class="vis-select" ${guest ? 'disabled' : ''}>
        ${['PUBLIC', 'REGISTERED', 'PRIVATE'].map((v) => `<option ${v === f.visibility ? 'selected' : ''}>${v}</option>`).join('')}
      </select></td>
      <td><input class="users" value="${esc(f.allowedUsers.join(', '))}" placeholder="usernames" ${f.visibility !== 'PRIVATE' ? 'disabled' : ''}></td>
      <td><button class="small" data-act="save" ${guest ? 'disabled' : ''}>Save</button>
          <button class="small danger" data-act="delete">Delete</button></td></tr>`).join('')
      || '<tr><td colspan="5" class="muted">You have not shared anything yet.</td></tr>';
  } catch (err) { $('#mine-error').textContent = err.message; }
}
$('#mine-results').addEventListener('change', (e) => {
  if (e.target.classList.contains('vis-select')) e.target.closest('tr').querySelector('.users').disabled = e.target.value !== 'PRIVATE';
});
$('#mine-results').addEventListener('click', async (e) => {
  const tr = e.target.closest('tr'), act = e.target.dataset.act;
  if (!act || !tr) return;
  try {
    if (act === 'save') {
      await api('/api/access', { fileId: tr.dataset.id, visibility: tr.querySelector('.vis-select').value, allowedUsers: tr.querySelector('.users').value });
    } else if (confirm('Delete from the network? Nobody will be able to download it any more.')) {
      await api('/api/delete', { fileId: tr.dataset.id });
    }
    loadMine();
  } catch (err) { $('#mine-error').textContent = err.message; }
});

connect();
