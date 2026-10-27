// Included in generated app.js. Tokens stay in memory, never in URLs or browser storage.
var activeCode = '';
var browserToken = '';
var pairAttemptCode = '';
var pairAttemptToken = '';
var sessionTimer = null;
async function api(action, payload, token) {
  var headers = { 'Content-Type': 'application/json' };
  if (token) headers.Authorization = 'Bearer ' + token;
  var timeout = null;
  var signal;
  if (typeof AbortSignal !== 'undefined' && typeof AbortSignal.timeout === 'function') {
    signal = AbortSignal.timeout(35000);
  } else {
    var controller = new AbortController();
    signal = controller.signal;
    timeout = setTimeout(function() { controller.abort(); }, 35000);
  }
  var r;
  var value;
  try {
    r = await fetch('/api/' + action, {
      method: 'POST', headers: headers, body: JSON.stringify(payload),
      cache: 'no-store', credentials: 'omit', redirect: 'error',
      signal: signal
    });
    value = await r.json().catch(function() { return {}; });
    if (!value || typeof value !== 'object') value = {};
  } finally { if (timeout !== null) clearTimeout(timeout); }
  if (!r.ok) {
    if (r.status === 401 || r.status === 404) disconnect();
    throw new Error(value.error || 'The TV could not complete this action.');
  }
  return value;
}
function disconnect() {
  activeCode = ''; browserToken = ''; pairAttemptCode = ''; pairAttemptToken = '';
  clearTimeout(sessionTimer);
  meta = { sources: [], categories: [] };
  channels = []; currentCat = null; currentSource = null;
  document.getElementById('catList').replaceChildren();
  document.getElementById('chanList').replaceChildren();
  document.getElementById('srcBar').replaceChildren();
  document.getElementById('srcBar').hidden = true;
  document.getElementById('chanHead').textContent = '';
  document.getElementById('chanView').hidden = true;
  document.getElementById('catView').hidden = false;
  document.querySelectorAll('#manager input').forEach(function(input) { input.value = ''; });
  document.querySelectorAll('#manager select').forEach(function(select) { select.selectedIndex = 0; });
  document.getElementById('recPass').placeholder = '';
  document.querySelectorAll('#manager details').forEach(function(details) { details.open = false; });
  syncProvFields();
  clearTimeout(statusTimer);
  document.getElementById('status').textContent = '';
  document.getElementById('status').className = '';
  document.getElementById('manager').hidden = true;
  document.getElementById('pairPanel').hidden = false;
  document.getElementById('pairMessage').textContent = 'Session ended. Start a new session on the TV.';
}
async function remote(method, path, body) {
  if (!activeCode || !browserToken) throw new Error('Connect to your TV first.');
  var requestToken = browserToken;
  var reply = await api('request', {
    code: activeCode, method: method, path: path, body: body || ''
  }, browserToken);
  if (requestToken !== browserToken) throw new Error('Session ended.');
  if (reply.status < 200 || reply.status >= 300) throw new Error('TV action failed (' + reply.status + ').');
  return JSON.parse(reply.body);
}
async function getJson(path) { return remote('GET', path, ''); }
async function post(path, bodyObj) { return remote('POST', path, JSON.stringify(bodyObj)); }
function setupPairing() {
  document.getElementById('pairForm').addEventListener('submit', async function(event) {
    event.preventDefault();
    var code = document.getElementById('pairCode').value.toUpperCase().replace(/[ -]/g, '');
    var message = document.getElementById('pairMessage');
    if (!/^[23456789ABCDEFGHJKLMNPQRSTUVWXYZ]{12}$/.test(code)) {
      message.textContent = 'Enter all 12 characters shown on your TV.'; return;
    }
    // Reuse only for an explicit retry of this attempt, after a potentially lost response.
    if (pairAttemptCode !== code) {
      pairAttemptCode = code;
      pairAttemptToken = Array.from(crypto.getRandomValues(new Uint8Array(32)),
        function(b) { return b.toString(16).padStart(2, '0'); }).join('');
    }
    var button = document.getElementById('pairSubmit'); button.disabled = true;
    message.textContent = 'Connecting…';
    try {
      var result = await api('pair', { code: code, browserToken: pairAttemptToken });
      activeCode = code; browserToken = pairAttemptToken;
      document.getElementById('pairCode').value = '';
      document.getElementById('pairPanel').hidden = true;
      document.getElementById('manager').hidden = false;
      sessionTimer = setTimeout(disconnect, Math.max(0, result.expires - Date.now()));
      await loadMeta(); await loadRecording();
    } catch (e) { message.textContent = e.message; }
    finally { button.disabled = false; }
  });
  document.getElementById('disconnect').addEventListener('click', async function() {
    try { await api('end', { code: activeCode }, browserToken); }
    catch (e) { /* TV expiry remains authoritative if the network is unavailable. */ }
    disconnect();
  });
}
