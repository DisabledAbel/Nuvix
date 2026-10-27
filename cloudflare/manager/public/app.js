var meta = { sources: [], categories: [] };
  var currentSource = null;   // selected source filter, null = all
  var currentCat = null;      // the category object being viewed
  var channels = [];          // rows currently shown, in display order

  var el = function(id){ return document.getElementById(id); };
  var statusTimer = null;
  function setStatus(msg, kind){
    var s = el('status');
    s.textContent = msg;
    s.className = 'show ' + (kind || '');
    if (statusTimer) clearTimeout(statusTimer);
    statusTimer = setTimeout(function(){ s.className = kind || ''; }, 2600);
  }

  // Included in generated app.js. Tokens stay in memory, never in URLs or browser storage.
var activeCode = '';
var browserToken = '';
var pairAttemptCode = '';
var pairAttemptToken = '';
var sessionTimer = null;
async function api(action, payload, token) {
  var headers = { 'Content-Type': 'application/json' };
  if (token) headers.Authorization = 'Bearer ' + token;
  var r = await fetch('/api/' + action, {
    method: 'POST', headers: headers, body: JSON.stringify(payload),
    cache: 'no-store', credentials: 'omit', redirect: 'error',
    signal: AbortSignal.timeout(35000)
  });
  var value = await r.json();
  if (!r.ok) {
    if (r.status === 401 || r.status === 404) disconnect();
    throw new Error(value.error || 'The TV could not complete this action.');
  }
  return value;
}
function disconnect() {
  activeCode = ''; browserToken = ''; pairAttemptCode = ''; pairAttemptToken = '';
  clearTimeout(sessionTimer);
  document.getElementById('manager').hidden = true;
  document.getElementById('pairPanel').hidden = false;
  document.getElementById('pairMessage').textContent = 'Session ended. Start a new session on the TV.';
  document.querySelectorAll('input[type="password"]').forEach(function(input) { input.value = ''; });
}
async function remote(method, path, body) {
  if (!activeCode || !browserToken) throw new Error('Connect to your TV first.');
  var reply = await api('request', {
    code: activeCode, method: method, path: path, body: body || ''
  }, browserToken);
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

var UNREACH = "Could not reach the TV. Keep its manager open. A change may have applied; refresh the list before retrying.";

  // ---- Categories --------------------------------------------------------------------
  async function loadMeta(){
    try {
      meta = await getJson('/meta');
      renderSources();
      renderCategories();
    } catch (e) {
      el('catList').innerHTML = '';
      var d = el('catEmpty'); d.hidden = false; d.textContent = UNREACH;
    }
  }

  function renderSources(){
    var bar = el('srcBar');
    if (!meta.sources || meta.sources.length < 2) { bar.hidden = true; return; }
    bar.hidden = false;
    bar.innerHTML = '';
    var mk = function(label, id){
      var c = document.createElement('div');
      c.className = 'chip' + (currentSource === id ? ' on' : '');
      c.textContent = label;
      c.onclick = function(){ currentSource = id; renderSources(); renderCategories(); };
      bar.appendChild(c);
    };
    mk('All sources', null);
    meta.sources.forEach(function(s){ mk(s.name, s.id); });
  }

  function renderCategories(){
    var q = el('catSearch').value.trim().toLowerCase();
    var list = el('catList');
    list.innerHTML = '';
    var cats = (meta.categories || []).filter(function(c){
      if (currentSource !== null && c.sourceId !== currentSource) return false;
      if (q && c.label.toLowerCase().indexOf(q) < 0) return false;
      return true;
    });
    var empty = el('catEmpty');
    if (cats.length === 0) {
      empty.hidden = false;
      empty.textContent = (meta.categories && meta.categories.length) ? 'No categories match that.' : 'No channels yet — add a provider above to get started.';
      return;
    }
    empty.hidden = true;
    cats.forEach(function(c){
      var row = document.createElement('div');
      row.className = 'cat';
      var label = document.createElement('span'); label.className = 'label'; label.textContent = c.label;
      var count = document.createElement('span'); count.className = 'count'; count.textContent = c.count;
      var chev = document.createElement('span'); chev.className = 'chev'; chev.innerHTML = '&#8250;';
      row.appendChild(label); row.appendChild(count); row.appendChild(chev);
      row.onclick = function(){ openCategory(c); };
      list.appendChild(row);
    });
  }
  el('catSearch').addEventListener('input', renderCategories);

  // ---- Channels ----------------------------------------------------------------------
  async function openCategory(cat){
    currentCat = cat;
    el('catView').hidden = true;
    el('chanView').hidden = false;
    el('chanHead').textContent = cat.label;
    window.scrollTo(0, 0);
    var listEl = el('chanList'); listEl.innerHTML = '';
    var empty = el('chanEmpty'); empty.hidden = true;
    try {
      channels = await getJson('/channels?cat=' + encodeURIComponent(cat.id) + '&source=' + cat.sourceId);
      renderChannels();
    } catch (e) {
      empty.hidden = false; empty.textContent = UNREACH;
    }
  }

  el('back').onclick = function(){
    el('chanView').hidden = true;
    el('catView').hidden = false;
    channels = [];
  };

  function renderChannels(){
    var listEl = el('chanList');
    listEl.innerHTML = '';
    var empty = el('chanEmpty');
    if (!channels.length) { empty.hidden = false; empty.textContent = 'No channels in this category.'; return; }
    empty.hidden = true;
    channels.forEach(function(c, i){ listEl.appendChild(buildRow(c, i)); });
  }

  function buildRow(c, index){
    var row = document.createElement('div');
    row.className = 'row' + (c.hidden ? ' hidden' : '');

    var logo = document.createElement('img');
    logo.className = 'logo'; logo.alt = '';
    if (c.logo) { logo.src = c.logo; } else { logo.style.visibility = 'hidden'; }
    logo.onerror = function(){ logo.style.visibility = 'hidden'; };

    var mid = document.createElement('div'); mid.className = 'rowmeta';
    var name = document.createElement('span'); name.className = 'name'; name.textContent = c.name;
    name.onclick = function(){ startRename(name, c); };
    var sub = document.createElement('div'); sub.className = 'sub';
    mid.appendChild(name); mid.appendChild(sub);
    function refreshSub(){
      sub.innerHTML = '';
      if (c.number !== null && c.number !== undefined) {
        sub.appendChild(document.createTextNode('No. ' + c.number));
      }
      if (c.hidden) {
        if (sub.childNodes.length) sub.appendChild(document.createTextNode('  ·  '));
        var b = document.createElement('span'); b.className = 'badge'; b.textContent = 'Hidden';
        sub.appendChild(b);
      }
      if (!sub.childNodes.length) sub.textContent = c.original !== c.name ? c.original : ' ';
    }
    refreshSub();

    var ctrls = document.createElement('div'); ctrls.className = 'ctrls';

    var star = document.createElement('div');
    star.className = 'btn star' + (c.favourite ? ' on' : '');
    star.innerHTML = c.favourite ? '&#9733;' : '&#9734;';
    star.title = 'Favourite';
    star.onclick = function(){
      var target = !c.favourite;
      post('/channel', { id: c.id, favourite: target })
        .then(function(){ c.favourite = target; star.className = 'btn star' + (target ? ' on' : ''); star.innerHTML = target ? '&#9733;' : '&#9734;'; setStatus(target ? 'Favourited' : 'Unfavourited', 'ok'); })
        .catch(function(){ setStatus(UNREACH, 'err'); });
    };

    var eye = document.createElement('div');
    eye.className = 'btn eye' + (c.hidden ? '' : ' on');
    eye.innerHTML = c.hidden ? '&#128584;' : '&#128065;';
    eye.title = c.hidden ? 'Hidden — tap to show' : 'Showing — tap to hide';
    eye.onclick = function(){
      var target = !c.hidden;
      post('/channel', { id: c.id, hidden: target })
        .then(function(){
          c.hidden = target;
          row.className = 'row' + (target ? ' hidden' : '');
          eye.className = 'btn eye' + (target ? '' : ' on');
          eye.innerHTML = target ? '&#128584;' : '&#128065;';
          eye.title = target ? 'Hidden — tap to show' : 'Showing — tap to hide';
          refreshSub();
          setStatus(target ? 'Hidden from the guide' : 'Showing in the guide', 'ok');
        })
        .catch(function(){ setStatus(UNREACH, 'err'); });
    };

    var up = document.createElement('div');
    up.className = 'btn mv'; up.innerHTML = '&#9650;'; up.title = 'Move up';
    if (index === 0) up.setAttribute('disabled', '');
    up.onclick = function(){ move(index, index - 1); };

    var down = document.createElement('div');
    down.className = 'btn mv'; down.innerHTML = '&#9660;'; down.title = 'Move down';
    if (index === channels.length - 1) down.setAttribute('disabled', '');
    down.onclick = function(){ move(index, index + 1); };

    ctrls.appendChild(star); ctrls.appendChild(eye); ctrls.appendChild(up); ctrls.appendChild(down);
    row.appendChild(logo); row.appendChild(mid); row.appendChild(ctrls);
    return row;
  }

  function startRename(span, c){
    var input = document.createElement('input');
    input.type = 'text'; input.className = 'rename'; input.value = c.name;
    span.replaceWith(input); input.focus(); input.select();
    var closed = false;
    function finish(save){
      if (closed) return; closed = true;
      var val = input.value.trim();
      if (save) {
        c.name = val || c.original;
        post('/channel', { id: c.id, name: val })
          .then(function(){ setStatus('Renamed', 'ok'); })
          .catch(function(){ setStatus("Couldn't save the name.", 'err'); });
      }
      var fresh = document.createElement('span');
      fresh.className = 'name'; fresh.textContent = c.name;
      fresh.onclick = function(){ startRename(fresh, c); };
      input.replaceWith(fresh);
    }
    input.addEventListener('keydown', function(e){
      if (e.key === 'Enter') { e.preventDefault(); finish(true); }
      else if (e.key === 'Escape') { finish(false); }
    });
    input.addEventListener('blur', function(){ finish(true); });
  }

  function move(from, to){
    if (to < 0 || to >= channels.length) return;
    var item = channels.splice(from, 1)[0];
    channels.splice(to, 0, item);
    renderChannels();
    post('/reorder', { ids: channels.map(function(c){ return c.id; }) })
      .then(function(){ setStatus('Order saved', 'ok'); })
      .catch(function(){ setStatus(UNREACH, 'err'); });
  }

  // ---- Recording / NAS settings ------------------------------------------------------
  async function loadRecording(){
    try {
      var r = await getJson('/recording');
      el('recHost').value = r.host || '';
      el('recShare').value = r.share || '';
      el('recFolder').value = r.folder || '';
      el('recUser').value = r.user || '';
      el('recPass').placeholder = r.hasPassword ? '•••• (unchanged)' : '';
      if (r.target === 'SMB') el('recCard').open = true;
    } catch (e) { /* leave the form blank if the TV can't be reached yet */ }
  }
  function smbBody(){
    return {
      host: el('recHost').value.trim(),
      share: el('recShare').value.trim(),
      folder: el('recFolder').value.trim(),
      user: el('recUser').value.trim(),
      password: el('recPass').value
    };
  }
  function showTest(r){
    if (r && r.ok) setStatus('Connected to the NAS — recordings will save there. ✓', 'ok');
    else setStatus("Couldn't reach the NAS: " + ((r && r.error) || 'check the details') , 'err');
  }
  el('recSave').onclick = function(){
    var body = smbBody();
    if (!body.host || !body.share) { setStatus('Server and share are required.', 'err'); return; }
    setStatus('Saving & testing…');
    post('/recording', body)
      .then(function(){
        el('recPass').value = '';
        el('recPass').placeholder = '•••• (unchanged)';
        return post('/recording/test', {});   // test the settings we just stored
      })
      .then(showTest)
      .catch(function(){ setStatus(UNREACH, 'err'); });
  };
  el('recTest').onclick = function(){
    setStatus('Testing…');
    post('/recording/test', smbBody())
      .then(showTest)
      .catch(function(){ setStatus(UNREACH, 'err'); });
  };

  // ---- Add a provider ----------------------------------------------------------------
  function provType(){ return el('provKind').value; }
  function syncProvFields(){
    var k = provType();
    el('provUserField').hidden = (k !== 'xtream');
    el('provPassField').hidden = (k !== 'xtream');
    el('provMacField').hidden = (k !== 'stalker');
    el('provUrlLabel').textContent = (k === 'm3u') ? 'Playlist URL' : (k === 'stalker' ? 'Portal URL' : 'Server address');
    el('provUrl').placeholder = (k === 'm3u') ? 'http://example.com/playlist.m3u'
      : (k === 'stalker' ? 'http://portal.example.com:80/c' : 'http://example.com:8080');
  }
  el('provKind').addEventListener('change', syncProvFields);
  syncProvFields();

  function provBody(){
    return { kind: provType(), name: el('provName').value.trim(), url: el('provUrl').value.trim(),
             username: el('provUser').value.trim(), password: el('provPass').value, mac: el('provMac').value.trim() };
  }
  function provInvalid(b){
    if (!b.url) return 'Enter the URL.';
    if (b.kind === 'xtream' && (!b.username || !b.password)) return 'Username and password are required.';
    if (b.kind === 'stalker' && !b.mac) return 'A MAC address is required.';
    return null;
  }
  el('provTest').onclick = function(){
    var b = provBody(); var bad = provInvalid(b); if (bad){ setStatus(bad, 'err'); return; }
    setStatus('Testing…');
    post('/source/test', b)
      .then(function(r){ if (r && r.ok) setStatus(r.message || 'Looks good.', 'ok'); else setStatus((r && r.error) || 'Test failed', 'err'); })
      .catch(function(){ setStatus(UNREACH, 'err'); });
  };
  el('provSave').onclick = function(){
    var b = provBody(); var bad = provInvalid(b); if (bad){ setStatus(bad, 'err'); return; }
    setStatus('Adding & loading channels…');
    post('/source', b)
      .then(function(r){
        if (r && r.ok){
          setStatus('Added — ' + (r.channels || 0) + ' channels loaded.', 'ok');
          el('provUrl').value = ''; el('provUser').value = ''; el('provPass').value = '';
          el('provMac').value = ''; el('provName').value = '';
          loadMeta();
        } else setStatus((r && r.error) || 'Could not add the provider', 'err');
      })
      .catch(function(){ setStatus(UNREACH, 'err'); });
  };

  setupPairing();
