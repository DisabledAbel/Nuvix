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
    if ((r.status === 401 || r.status === 404) && token && token === browserToken) disconnect('Session expired or revoked. Start a new session on the TV.');
    var error = new Error(value.error || 'The TV could not complete this action.'); error.status = r.status; throw error;
  }
  return value;
}
function disconnect(reason) {
  if (typeof resetChannelState === 'function') resetChannelState();
  activeCode = ''; browserToken = ''; pairAttemptCode = ''; pairAttemptToken = '';
  clearTimeout(sessionTimer);
  meta = { sources: [], categories: [] };
  channels = []; currentCat = null; currentSource = null;
  document.getElementById('catList').replaceChildren();
  document.getElementById('chanList').replaceChildren();
  document.getElementById('srcBar').replaceChildren();
  document.getElementById('channelSource').replaceChildren();
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
  document.getElementById('pairMessage').textContent = typeof reason === 'string' ? reason : 'Session ended. Start a new session on the TV.';
}
async function remote(method, path, body) {
  if (!activeCode || !browserToken) throw new Error('Connect to your TV first.');
  var requestToken = browserToken;
  var reply = await api('request', {
    code: activeCode, method: method, path: path, body: body || ''
  }, browserToken);
  if (requestToken !== browserToken) throw new Error('Session ended.');
  var value = JSON.parse(reply.body);
  if (reply.status < 200 || reply.status >= 300) {
    var error = new Error(value.error || (reply.status === 426 ? 'Update the TV APK, Worker and browser for paginated channels.' : 'TV action failed (' + reply.status + ').'));
    error.status = reply.status; throw error;
  }
  return value;
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
      if (result.managerVersion !== 2) throw new Error('Update the Worker before the TV APK, then reload this browser. Manager protocol 2 is required.');
      activeCode = code; browserToken = pairAttemptToken;
      document.getElementById('pairCode').value = '';
      document.getElementById('pairPanel').hidden = true;
      document.getElementById('manager').hidden = false;
      sessionTimer = setTimeout(function(){ disconnect('Session expired. Start a new session on the TV.'); }, Math.max(0, result.expires - Date.now()));
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
    var epoch = channelEpoch;
    try {
      var loaded = await getJson('/meta');
      if (epoch !== channelEpoch) return;
      meta = loaded;
      compatibility = meta.managerVersion !== 2;
      if (compatibility) throw new Error('Update the TV APK and Worker, then reload this browser. Paginated channels require manager protocol 2.');
      var providerSelect = el('channelSource'), previous = providerSelect.value;
      providerSelect.replaceChildren();
      meta.sources.forEach(function(source){
        var option = document.createElement('option'); option.value = source.id; option.textContent = source.name;
        providerSelect.appendChild(option);
      });
      if (meta.sources.some(function(source){ return String(source.id) === previous; })) providerSelect.value = previous;
      if (selectionSource !== null && !meta.sources.some(function(source){ return source.id === selectionSource; })) resetChannelState();
      el('browseProvider').disabled = !meta.sources.length;
      renderSources();
      renderCategories();
    } catch (e) {
      if (epoch !== channelEpoch) return;
      el('catList').innerHTML = '';
      var d = el('catEmpty'); d.hidden = false; d.textContent = e.message || UNREACH;
    }
  }

  el('refreshProviders').onclick = loadMeta;
  function renderSources(){
    var bar = el('srcBar');
    if (!meta.sources || meta.sources.length < 2) { bar.hidden = true; return; }
    bar.hidden = false;
    bar.innerHTML = '';
    var mk = function(label, id){
      var c = document.createElement('button'); c.type = 'button';
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
      var row = document.createElement('button'); row.type = 'button';
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

  // ---- Bounded channel browsing and explicit selection -------------------------------
  var pageOffset = 0, pageSize = 50, pageTotal = 0, catalogRevision = null;
  var browseGeneration = 0, readRunning = false, readWanted = false, searchTimer;
  var pendingReconcile = new Set();
  var selected = new Map(), selectionSource = null, selectionRevision = null;
  var mutationBusy = false, needsReconcile = false, compatibility = false;
  var pageReady = false, reviewAction = null, channelEpoch = 0, stopBulkRequested = false;
  function announce(message){ el('channelStatus').textContent = message; }
  function resetChannelState(){
    channelEpoch++; browseGeneration++; readWanted = false; clearTimeout(searchTimer);
    channels = []; selected.clear(); pendingReconcile.clear(); catalogRevision = null;
    selectionSource = null; selectionRevision = null; pageReady = false; pageOffset = 0; pageTotal = 0;
    el('pageInfo').textContent = ''; el('channelStatus').textContent = ''; el('bulkSummary').textContent = '';
    needsReconcile = false; reviewAction = null; mutationBusy = false;
    el('bulkReview').hidden = true; el('stopBulk').hidden = true; stopBulkRequested = true; updateSelection();
  }
  function browseSource(){ return currentCat ? currentCat.sourceId : Number(el('channelSource').value); }
  function setBusy(busy){
    mutationBusy = busy;
    document.querySelectorAll('#chanView button, #chanView select, #chanView input').forEach(function(n){ n.disabled = busy; });
    el('categoryOnly').disabled = busy || !currentCat;
    el('cancelBulk').disabled = false;
    renderChannels(); updateSelection();
  }
  function updateSelection(){
    document.querySelectorAll('#chanList button, #chanList input').forEach(function(n){ n.disabled = mutationBusy || !pageReady || needsReconcile; });
    if (reviewAction) { reviewAction = null; el('bulkReview').hidden = true; }
    var onPage = channels.filter(function(c){ return selected.has(c.id); }).length;
    el('selectedCount').textContent = selected.size + ' selected (' + onPage + ' on this page, ' + (selected.size - onPage) + ' on other pages or filters). Maximum 500.';
    el('clearSelection').disabled = mutationBusy || needsReconcile || !selected.size;
    el('selectPage').disabled = mutationBusy || !pageReady || needsReconcile;
    el('reviewBulk').disabled = mutationBusy || !selected.size || needsReconcile || !pageReady;
    el('prevPage').disabled = mutationBusy || !pageReady || pageOffset === 0;
    el('nextPage').disabled = mutationBusy || !pageReady || pageOffset + pageSize >= pageTotal || pageOffset + pageSize > 1000000;
    el('selectionReview').replaceChildren();
    selected.forEach(function(c){
      var line = document.createElement('li');
      line.textContent = c.name + ' — ' + (c.favourite ? 'Favorite' : 'Not favorite') + ', ' + (c.hidden ? 'Hidden' : 'Visible');
      el('selectionReview').appendChild(line);
    });
  }
  function showChannelView(cat){
    if (mutationBusy) return;
    reviewAction = null; el('bulkReview').hidden = true;
    var source = cat ? cat.sourceId : Number(el('channelSource').value);
    if (selectionSource !== null && selectionSource !== source) resetChannelState();
    currentCat = cat;
    el('catView').hidden = true; el('chanView').hidden = false;
    el('chanHead').textContent = cat ? cat.label : 'Search provider channels';
    el('categoryOnly').disabled = !cat;
    if (!cat) el('categoryOnly').checked = false;
    pageOffset = 0; catalogRevision = selectionSource === source ? selectionRevision : null;
    loadPage();
  }
  function openCategory(cat){ el('channelSource').value = cat.sourceId; el('categoryOnly').checked = true; showChannelView(cat); }
  el('browseProvider').onclick = function(){ showChannelView(null); };
  el('back').onclick = function(){
    if (mutationBusy) return;
    browseGeneration++; readWanted = false; pageReady = false;
    el('chanView').hidden = true; el('catView').hidden = false;
  };
  async function loadPage(){
    browseGeneration++; readWanted = true; pageReady = false;
    reviewAction = null; el('bulkReview').hidden = true;
    updateSelection();
    if (readRunning || mutationBusy || compatibility) return;
    readRunning = true;
    // One read in flight. Rapid input cancels its result and coalesces to the latest query.
    while (readWanted && !mutationBusy && !el('chanView').hidden) {
      readWanted = false;
      var generation = browseGeneration;
      var source = browseSource();
      var query = el('channelSearch').value.trim();
      var category = currentCat && el('categoryOnly').checked ? currentCat.id : null;
      var path = '/channels/page?source=' + source + '&limit=' + pageSize + '&offset=' + pageOffset + '&q=' + encodeURIComponent(query);
      if (category !== null) path += '&cat=' + encodeURIComponent(category);
      if (catalogRevision !== null) path += '&revision=' + catalogRevision;
      announce('Loading channels…'); el('chanList').setAttribute('aria-busy', 'true');
      try {
        var page = await getJson(path);
        if (generation !== browseGeneration) continue;
        if (!page || !Array.isArray(page.rows) || page.rows.length > pageSize || typeof page.revision !== 'string') throw new Error('Update the TV APK, Worker and browser together for paginated channels.');
        catalogRevision = page.revision; pageTotal = page.total;
        if (selectionSource === source && selectionRevision !== catalogRevision) {
          selected.clear(); pendingReconcile.clear(); needsReconcile = false;
        }
        selectionSource = source; selectionRevision = catalogRevision;
        channels = page.rows; pageReady = true;
        channels.forEach(function(c){ if (selected.has(c.id)) selected.set(c.id, c); });
        renderChannels(); updateSelection();
        el('pageInfo').textContent = pageTotal ? (pageOffset + 1) + '–' + (pageOffset + channels.length) + ' of ' + pageTotal : '0 results';
        if (pageOffset + pageSize > 1000000 && pageOffset + pageSize < pageTotal) el('pageInfo').textContent += ' — narrow your search to continue';
        announce(needsReconcile ? 'A reply was lost. Refresh TV state to reconcile all selected channels before another edit.' : 'Channels loaded.');
      } catch (e) {
        if (generation !== browseGeneration) continue;
        channels = []; pageReady = false; renderChannels();
        if (e.status === 409) {
          selected.clear(); pendingReconcile.clear(); catalogRevision = null; selectionRevision = null;
          needsReconcile = false; pageOffset = 0;
        }
        announce(e.message || UNREACH); updateSelection();
      } finally { el('chanList').setAttribute('aria-busy', 'false'); }
    }
    readRunning = false;
  }
  function changeSearch(){
    browseGeneration++; pageReady = false; pageOffset = 0; updateSelection();
    clearTimeout(searchTimer); searchTimer = setTimeout(loadPage, 300);
  }
  el('channelSearch').addEventListener('input', changeSearch);
  el('categoryOnly').addEventListener('change', changeSearch);
  el('prevPage').onclick = function(){ pageOffset = Math.max(0, pageOffset - pageSize); loadPage(); };
  el('nextPage').onclick = function(){ pageOffset += pageSize; loadPage(); };
  el('selectPage').onclick = function(){
    var additional = channels.filter(function(c){ return !selected.has(c.id); }).length;
    if (selected.size + additional > 500) { announce('Selecting this page would exceed 500 channels. Apply or clear the current selection first.'); return; }
    channels.forEach(function(c){ selected.set(c.id, c); });
    renderChannels(); updateSelection();
  };
  el('clearSelection').onclick = function(){
    if (mutationBusy || needsReconcile) return;
    selected.clear(); renderChannels(); updateSelection();
  };
  el('refreshChannels').onclick = refreshState;
  async function refreshState(){
    if (mutationBusy) return;
    setBusy(true); announce('Refreshing actual TV state…');
    var ids = Array.from(new Set(Array.from(selected.keys()).concat(Array.from(pendingReconcile)))), epoch = channelEpoch;
    try {
      for (var i = 0; i < ids.length; i += 100) {
        var batch = ids.slice(i, i + 100);
        var rows = await post('/channels/state', { source: selectionSource, revision: selectionRevision, ids: batch });
        if (epoch !== channelEpoch) return;
        var found = new Set(rows.map(function(c){ return c.id; }));
        batch.forEach(function(id){ if (!found.has(id)) selected.delete(id); });
        rows.forEach(function(c){ if (selected.has(c.id)) selected.set(c.id, c); });
        batch.forEach(function(id){ pendingReconcile.delete(id); });
      }
      needsReconcile = pendingReconcile.size !== 0;
      catalogRevision = null;
    } catch (e) {
      if (epoch !== channelEpoch) return;
      if (e.status === 409) { selected.clear(); pendingReconcile.clear(); catalogRevision = null; selectionRevision = null; needsReconcile = false; pageOffset = 0; }
      else { announce(e.message || UNREACH); setBusy(false); return; }
    }
    setBusy(false); await loadPage();
  }
  el('reviewBulk').onclick = function(){
    reviewAction = el('bulkAction').value;
    el('bulkSummary').textContent = el('bulkAction').selectedOptions[0].textContent + ' for exactly ' + selected.size + ' selected channels, including selections on other pages or filters. No other channels will be selected.';
    el('bulkReview').hidden = false; el('confirmBulk').focus();
  };
  el('cancelBulk').onclick = function(){ el('bulkReview').hidden = true; reviewAction = null; };
  el('stopBulk').onclick = function(){ stopBulkRequested = true; announce('Stopping after the current batch. Already accepted TV changes may finish.'); };
  el('confirmBulk').onclick = async function(){
    if (!reviewAction || mutationBusy || needsReconcile) return;
    var action = reviewAction, ids = Array.from(selected.keys()), source = selectionSource, revision = selectionRevision, epoch = channelEpoch;
    el('bulkReview').hidden = true; reviewAction = null;
    setBusy(true); browseGeneration++; stopBulkRequested = false;
    el('stopBulk').hidden = false; el('stopBulk').disabled = false;
    var success = 0, failed = 0, skipped = 0, unknown = 0, processed = 0;
    try {
      for (var i = 0; i < ids.length; i += 100) {
        if (stopBulkRequested) break;
        var batch = ids.slice(i, i + 100);
        announce('Applying ' + action + ': ' + processed + ' of ' + ids.length + ' reviewed channels…');
        var result;
        try { result = await post('/channels/bulk', { source: source, revision: revision, ids: batch, action: action }); }
        catch (e) {
          if (epoch !== channelEpoch) return;
          // Never replay: the TV may have committed the batch before its reply was lost.
          if ([400, 409, 426].includes(e.status)) { failed += batch.length; processed += batch.length; }
          else unknown = batch.length;
          batch.forEach(function(id){ pendingReconcile.add(id); });
          needsReconcile = true; break;
        }
        if (epoch !== channelEpoch) return;
        if (!result || !Array.isArray(result.results) || result.results.length !== batch.length ||
            new Set(result.results.map(function(r){ return r.id; })).size !== batch.length ||
            result.results.some(function(r){ return !batch.includes(r.id) || !['success', 'failed', 'skipped'].includes(r.status); })) {
          unknown = batch.length;
          batch.forEach(function(id){ pendingReconcile.add(id); });
          needsReconcile = true; break;
        }
        result.results.forEach(function(r){
          if (r.status === 'success') { success++; selected.delete(r.id); }
          else if (r.status === 'skipped') { skipped++; selected.delete(r.id); }
          else failed++;
        });
        processed += batch.length;
      }
    } finally {
      if (epoch !== channelEpoch) return;
      el('stopBulk').hidden = true; setBusy(false); updateSelection();
      announce(success + ' successful, ' + failed + ' failed, ' + skipped + ' skipped, ' + unknown + ' unknown, ' + (ids.length - processed - unknown) + ' not attempted. ' +
        (needsReconcile ? 'A batch reply was lost or rejected. Refresh TV state before another edit.' : 'Failed selections remain for review. Refresh TV state to view current values.'));
      // Keep outcome visible until the user explicitly refreshes.
      pageReady = false; updateSelection();
    }
  };
  async function editChannel(c, fields, path){
    if (mutationBusy || needsReconcile || !pageReady) return;
    setBusy(true); browseGeneration++; var epoch = channelEpoch;
    try {
      await post(path || '/channel', Object.assign({ id: c.id, source: browseSource(), revision: catalogRevision }, fields));
      if (epoch !== channelEpoch) return;
      catalogRevision = null;
      // Rename/reorder changes the revision, so old selections must be reviewed again.
      if (fields.name !== undefined || path) { selected.clear(); selectionRevision = null; }
      setBusy(false); await loadPage();
    } catch (e) {
      if (epoch !== channelEpoch) return;
      pendingReconcile.add(c.id);
      if (selected.has(c.id) || selected.size < 500) selected.set(c.id, c);
      needsReconcile = true; pageReady = false; setBusy(false);
      announce((e.message || UNREACH) + ' Refresh TV state before another edit.');
    }
  }
  function renderChannels(){
    el('chanList').replaceChildren();
    el('chanEmpty').hidden = channels.length !== 0;
    el('chanEmpty').textContent = 'No channels match this provider, category and search.';
    channels.forEach(function(c){ el('chanList').appendChild(buildRow(c)); });
  }
  function button(label, action){
    var b = document.createElement('button'); b.type = 'button'; b.className = 'back';
    b.textContent = label; b.onclick = action; b.disabled = mutationBusy || needsReconcile || !pageReady; return b;
  }
  function buildRow(c){
    var row = document.createElement('div'); row.className = 'row' + (c.hidden ? ' hidden' : '');
    var check = document.createElement('input'); check.type = 'checkbox'; check.className = 'selection';
    check.setAttribute('aria-label', 'Select ' + c.name); check.checked = selected.has(c.id);
    check.disabled = mutationBusy || needsReconcile || !pageReady;
    check.onchange = function(){
      if (check.checked && selected.size >= 500) { check.checked = false; announce('Selection limit is 500 channels. Apply or clear this selection first.'); return; }
      if (check.checked) selected.set(c.id, c); else selected.delete(c.id);
      updateSelection();
    };
    var mid = document.createElement('div'); mid.className = 'rowmeta';
    var name = button(c.name, function(){ startRename(name, c); }); name.className = 'name';
    name.setAttribute('aria-label', 'Rename ' + c.name);
    var sub = document.createElement('div'); sub.className = 'sub';
    sub.textContent = (c.number !== null && c.number !== undefined ? 'No. ' + c.number + ' · ' : '') + (c.hidden ? 'Hidden' : 'Visible') + (c.favourite ? ' · Favorite' : '') + ' · ' + c.original;
    mid.appendChild(name); mid.appendChild(sub);
    var controls = document.createElement('div'); controls.className = 'ctrls';
    controls.appendChild(button(c.favourite ? 'Unfavorite' : 'Favorite', function(){ editChannel(c, { favourite: !c.favourite }); }));
    controls.appendChild(button(c.hidden ? 'Unhide' : 'Hide', function(){ editChannel(c, { hidden: !c.hidden }); }));
    // Move operates on the channel's original category, including across page boundaries.
    controls.appendChild(button('Move up', function(){ editChannel(c, { direction: -1 }, '/channels/move'); }));
    controls.appendChild(button('Move down', function(){ editChannel(c, { direction: 1 }, '/channels/move'); }));
    row.appendChild(check); row.appendChild(mid); row.appendChild(controls); return row;
  }
  function startRename(span, c){
    var input = document.createElement('input'); input.type = 'text'; input.className = 'rename';
    input.value = c.name; input.maxLength = 512; input.setAttribute('aria-label', 'New name for ' + c.name);
    span.replaceWith(input); input.focus(); input.select(); var closed = false;
    function finish(save){
      if (closed) return; closed = true;
      input.replaceWith(span);
      if (save) editChannel(c, { name: input.value.trim() });
    }
    input.addEventListener('keydown', function(e){
      if (e.key === 'Enter') { e.preventDefault(); finish(true); }
      if (e.key === 'Escape') finish(false);
    });
    input.addEventListener('blur', function(){ finish(true); });
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
