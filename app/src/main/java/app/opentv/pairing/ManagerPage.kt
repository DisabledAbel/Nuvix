/* Nuvix modifications Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.pairing

/**
 * The page a phone or laptop sees when managing channels.
 *
 * Like [PairingPage], one self-contained document with no external requests of any kind — no
 * fonts, no frameworks, no CDN. The browser is talking to a television on the local network, which
 * may have no route to the internet at all, so a page that needed a CDN to render would just fail.
 *
 * Unlike the pairing page this is opened on a phone OR a laptop, so it is responsive and
 * touch-friendly rather than TV-styled. All channel data arrives as JSON and is rendered with
 * `textContent`/DOM APIs (never string-built HTML), so a channel name full of `<`, `"`, `|` or
 * emoji can never break out into markup.
 */
object ManagerPage {

    /** Wraps the local web manager content in its complete HTML page. */
    fun page(token: String): String = """
        <!DOCTYPE html>
        <html lang="en">
        <head>
        <meta charset="utf-8">
        <meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
        <meta name="robots" content="noindex, nofollow">
        <title>OpenTV — manage channels</title>
        <style>
          :root { --bg:#07080c; --surface:#12141d; --surface2:#181b26; --line:#2a2f3d;
                  --text:#e8eaf0; --muted:#a8aec0; --accent:#7c93ff; --accent2:#3a447a;
                  --gold:#ffd166; --err:#ff6b6b; --ok:#6ee7a0; }
          * { box-sizing:border-box; -webkit-tap-highlight-color:transparent; }
          body { margin:0; background:var(--bg); color:var(--text);
                 font:16px/1.5 -apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,sans-serif; }
          .wrap { max-width:760px; margin:0 auto; padding:20px 16px 80px; }
          header { position:sticky; top:0; z-index:5; background:linear-gradient(var(--bg),var(--bg) 78%,rgba(7,8,12,0));
                   padding-top:8px; }
          h1 { font-size:1.35rem; margin:0; letter-spacing:-.02em; }
          .hint { color:var(--muted); font-size:.85rem; margin:4px 0 14px; }
          .search { width:100%; padding:13px 14px; font-size:17px; margin-bottom:14px;
                    background:var(--surface); color:var(--text); border:1px solid var(--line); border-radius:11px; }
          .search:focus { outline:none; border-color:var(--accent); }
          .chips { display:flex; flex-wrap:wrap; gap:8px; margin-bottom:14px; }
          .chip { padding:9px 14px; font-size:.9rem; border-radius:999px; cursor:pointer;
                  background:var(--surface); border:1px solid var(--line); color:var(--muted); }
          .chip.on { background:var(--accent2); border-color:var(--accent); color:var(--text); }
          .list { display:flex; flex-direction:column; gap:8px; }
          .cat { display:flex; align-items:center; gap:12px; padding:15px 16px; cursor:pointer;
                 background:var(--surface); border:1px solid var(--line); border-radius:12px; }
          .cat:active { background:var(--surface2); }
          .cat .label { flex:1; font-weight:600; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
          .cat .count { color:var(--muted); font-size:.85rem; font-variant-numeric:tabular-nums; }
          .cat .chev { color:var(--muted); }
          .row { display:flex; align-items:center; gap:11px; padding:10px 12px;
                 background:var(--surface); border:1px solid var(--line); border-radius:12px; }
          .row.hidden { opacity:.55; }
          .logo { width:44px; height:44px; border-radius:8px; background:#000; object-fit:contain; flex:none; }
          .rowmeta { flex:1; min-width:0; }
          .name { font-weight:600; cursor:text; display:inline-block; max-width:100%;
                  overflow:hidden; text-overflow:ellipsis; white-space:nowrap; vertical-align:bottom; }
          .name:hover { text-decoration:underline dotted; }
          .rename { width:100%; padding:6px 8px; font-size:16px; font-weight:600;
                    background:var(--bg); color:var(--text); border:1px solid var(--accent); border-radius:8px; }
          .sub { color:var(--muted); font-size:.78rem; margin-top:2px; }
          .sub .badge { color:var(--err); font-weight:600; }
          .ctrls { display:flex; align-items:center; gap:6px; flex:none; }
          .btn { width:40px; height:40px; display:flex; align-items:center; justify-content:center;
                 font-size:18px; border-radius:9px; cursor:pointer; user-select:none;
                 background:var(--surface2); border:1px solid var(--line); color:var(--muted); }
          .btn:active { transform:scale(.92); }
          .btn.star.on { color:var(--gold); border-color:var(--gold); }
          .btn.eye.on { color:var(--accent); border-color:var(--accent); }
          .btn.mv { font-size:14px; }
          .btn[disabled] { opacity:.3; pointer-events:none; }
          .back { display:inline-flex; align-items:center; gap:6px; padding:9px 14px; margin-bottom:12px;
                  background:var(--surface); border:1px solid var(--line); border-radius:10px;
                  color:var(--text); font-weight:600; cursor:pointer; }
          .chanhead { font-size:1.1rem; font-weight:700; margin:0 0 4px; }
          .empty { color:var(--muted); text-align:center; padding:40px 10px; }
          #status { position:fixed; left:50%; bottom:18px; transform:translateX(-50%) translateY(120%);
                    max-width:90%; padding:11px 18px; border-radius:11px; font-size:.9rem; font-weight:600;
                    background:var(--surface2); border:1px solid var(--line); color:var(--text);
                    transition:transform .25s ease; z-index:20; box-shadow:0 8px 24px rgba(0,0,0,.4); }
          #status.show { transform:translateX(-50%) translateY(0); }
          #status.ok { border-color:var(--ok); color:var(--ok); }
          #status.err { border-color:var(--err); color:#ffc9c9; }
          .privacy { color:var(--muted); font-size:.8rem; margin-top:28px; line-height:1.5; }
          details.rec { background:var(--surface); border:1px solid var(--line); border-radius:12px; margin-bottom:16px; }
          details.rec summary { padding:14px 16px; font-weight:600; cursor:pointer; list-style:none; }
          details.rec summary::-webkit-details-marker { display:none; }
          details.rec summary::before { content:'\25B8'; color:var(--muted); margin-right:8px; }
          details.rec[open] summary::before { content:'\25BE'; }
          details.rec .body { padding:0 16px 16px; }
          .field { display:block; margin:10px 0; }
          .field span { display:block; color:var(--muted); font-size:.8rem; margin-bottom:4px; }
          .field input { width:100%; padding:11px 12px; font-size:16px; background:var(--bg); color:var(--text);
                         border:1px solid var(--line); border-radius:9px; }
          .field input:focus { outline:none; border-color:var(--accent); }
          .save { margin-top:6px; padding:12px 18px; font-size:15px; font-weight:600;
                  background:var(--accent2); color:var(--text); border:1px solid var(--accent); border-radius:10px; cursor:pointer; }
          .save:active { transform:scale(.97); }
          .field select { width:100%; padding:11px 12px; font-size:16px; background:var(--bg); color:var(--text);
                          border:1px solid var(--line); border-radius:9px; }
          .field select:focus { outline:none; border-color:var(--accent); }
          button, input, select { font:inherit; }
          select { min-height:44px; padding:8px; background:var(--surface2); color:var(--text); border:1px solid var(--line); border-radius:8px; }
          button { min-height:44px; color:var(--text); background:var(--surface2); border:1px solid var(--line); border-radius:8px; }
          button:focus-visible, input.search:focus-visible, .field input:focus-visible, .field select:focus-visible, :focus-visible { outline:3px solid var(--accent); outline-offset:3px; }
          .toolbar { display:flex; gap:8px; flex-wrap:wrap; align-items:center; margin:12px 0; }
          .selection { width:44px; height:44px; flex:none; }
          .row { flex-wrap:wrap; }
          .rowmeta { min-width:160px; overflow-wrap:anywhere; }
          .row .name { background:transparent; text-align:left; white-space:normal; }
          .ctrls { flex-wrap:wrap; }
          .ctrls .back { margin:0; font-size:.85rem; }
          #channelStatus { min-height:48px; overflow-wrap:anywhere; }
          #bulkReview { border:2px solid var(--accent); padding:16px; border-radius:12px; }
          [hidden] { display:none !important; }
          @media (max-width:600px) { .ctrls { width:100%; } .ctrls button { flex:1; } }
          /* `.field { display:block }` above would otherwise override the [hidden] attribute, leaving
             a type's irrelevant fields (e.g. username/password on a Stalker portal) on screen. */
          .field[hidden] { display:none; }
        </style>
        </head>
        <body>
          <div class="wrap">
            <header>
              <h1>OpenTV — manage channels</h1>
              <p class="hint">Changes apply to your TV the moment you make them.</p>
            </header>

            <details class="rec" id="provCard">
              <summary>Add a provider</summary>
              <div class="body">
                <p class="hint">Add an Xtream, M3U or Stalker portal from here — a real keyboard beats the TV remote. Your login stays on the TV.</p>
                <label class="field"><span>Type</span>
                  <select id="provKind">
                    <option value="xtream">Xtream Codes login</option>
                    <option value="m3u">M3U playlist URL</option>
                    <option value="stalker">Stalker portal</option>
                  </select></label>
                <label class="field"><span>Name (optional)</span>
                  <input id="provName" placeholder="My provider" autocapitalize="off" autocorrect="off" spellcheck="false"></label>
                <label class="field"><span id="provUrlLabel">Server address</span>
                  <input id="provUrl" placeholder="http://example.com:8080" autocapitalize="off" autocorrect="off" spellcheck="false"></label>
                <label class="field" id="provUserField"><span>Username</span>
                  <input id="provUser" autocapitalize="off" autocorrect="off" spellcheck="false"></label>
                <label class="field" id="provPassField"><span>Password</span>
                  <input id="provPass" type="password" autocomplete="off"></label>
                <label class="field" id="provMacField"><span>MAC address</span>
                  <input id="provMac" placeholder="00:1A:79:xx:xx:xx" autocapitalize="off" autocorrect="off" spellcheck="false"></label>
                <button class="save" id="provSave">Add &amp; load channels</button>
                <button class="save" id="provTest" style="background:var(--surface2);border-color:var(--line);margin-left:8px;">Test</button>
              </div>
            </details>

            <details class="rec" id="recCard">
              <summary>Recording to NAS (SMB)</summary>
              <div class="body">
                <p class="hint">Point recordings at a network drive. Saved straight to your TV — the password stays on the TV.</p>
                <label class="field"><span>Server (IP address)</span>
                  <input id="recHost" placeholder="192.168.0.214" autocapitalize="off" autocorrect="off" spellcheck="false"></label>
                <label class="field"><span>Share</span>
                  <input id="recShare" placeholder="Nuvix" autocapitalize="off" autocorrect="off" spellcheck="false"></label>
                <label class="field"><span>Folder (optional)</span>
                  <input id="recFolder" placeholder="(leave blank)" autocapitalize="off" autocorrect="off" spellcheck="false"></label>
                <label class="field"><span>Username</span>
                  <input id="recUser" placeholder="record" autocapitalize="off" autocorrect="off" spellcheck="false"></label>
                <label class="field"><span>Password</span>
                  <input id="recPass" type="password" autocomplete="off"></label>
                <button class="save" id="recSave">Save &amp; use NAS</button>
                <button class="save" id="recTest" style="background:var(--surface2);border-color:var(--line);margin-left:8px;">Test connection</button>
              </div>
            </details>

            <section id="catView">
              <div id="srcBar" class="chips" hidden></div>
              <label class="field"><span>Provider for channel search</span><select id="channelSource"></select></label>
              <button class="back" id="browseProvider" type="button">Browse or search provider channels</button>
              <button class="back" id="refreshProviders" type="button">Refresh providers</button>
              <label for="catSearch">Filter categories</label>
              <input id="catSearch" class="search" type="text" placeholder="Filter categories…"
                     autocapitalize="off" autocorrect="off" spellcheck="false">
              <div id="catList" class="list"></div>
              <div id="catEmpty" class="empty" hidden></div>
            </section>

            <section id="chanView" hidden>
              <button type="button" id="back" class="back">&#8249;&nbsp;Categories</button>
              <h2 id="chanHead" class="chanhead"></h2>
              <p class="hint" id="chanHint">Tap a name to rename. Moves apply within the channel’s original category. Selection includes only channels you check, up to 500 across pages and filters.</p>
              <label for="channelSearch">Search original and custom channel names</label>
              <input id="channelSearch" class="search" type="search" maxlength="120" autocomplete="off">
              <label class="toolbar"><input id="categoryOnly" type="checkbox" class="selection">Only this category</label>
              <div class="toolbar">
                <button type="button" id="prevPage">Previous page</button><span id="pageInfo"></span>
                <button type="button" id="nextPage">Next page</button>
                <button type="button" id="refreshChannels">Refresh TV state</button>
              </div>
              <div class="toolbar">
                <button type="button" id="selectPage">Select All on This Page</button>
                <button type="button" id="clearSelection">Clear Selection</button>
              </div>
              <p id="selectedCount" role="status" aria-live="polite"></p>
              <details><summary>Review selected channels and last known state</summary><ul id="selectionReview"></ul></details>
              <div class="toolbar">
                <label for="bulkAction">Bulk action</label>
                <select id="bulkAction"><option value="favorite">Favorite</option><option value="unfavorite">Unfavorite</option><option value="hide">Hide</option><option value="unhide">Unhide</option></select>
                <button type="button" id="reviewBulk">Review action</button>
              </div>
              <div id="bulkReview" role="region" aria-label="Confirm bulk action" hidden>
                <p id="bulkSummary"></p>
                <button type="button" id="confirmBulk" aria-describedby="bulkSummary">Apply reviewed action</button>
                <button type="button" id="cancelBulk">Cancel</button>
              </div>
              <button type="button" id="stopBulk" hidden>Stop after current batch</button>
              <p id="channelStatus" role="status" aria-live="polite" aria-atomic="true"></p>
              <div id="chanList" class="list"></div>
              <div id="chanEmpty" class="empty" hidden></div>
            </section>

            <p class="privacy">
              This page is served by your television, over your own network — there is no OpenTV
              account and no OpenTV server. Nothing you change here leaves your home.
            </p>
          </div>

          <div id="status"></div>

        <script>
          var EMBEDDED_TOKEN = "$token";
          var token = new URLSearchParams(location.search).get('t') || EMBEDDED_TOKEN;

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

          function withToken(path){ return path + (path.indexOf('?') >= 0 ? '&' : '?') + 't=' + encodeURIComponent(token); }
          async function getJson(path){
            var r = await fetch(withToken(path));
            if (!r.ok) { var error = new Error((await r.json().catch(function(){ return {}; })).error || 'HTTP ' + r.status); error.status = r.status; throw error; }
            return r.json();
          }
          async function post(path, bodyObj){
            var r = await fetch(withToken(path), {
              method: 'POST', headers: { 'Content-Type': 'application/json' },
              body: JSON.stringify(bodyObj)
            });
            if (!r.ok) { var error = new Error((await r.json().catch(function(){ return {}; })).error || 'HTTP ' + r.status); error.status = r.status; throw error; }
            return r.json();
          }
          var UNREACH = "Couldn't reach the TV — still on the same wifi?";

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
          var selected = new Map(), selectionSource = null, selectionRevision = null;
          var mutationBusy = false, needsReconcile = false, compatibility = false;
          var pageReady = false, reviewAction = null, channelEpoch = 0, stopBulkRequested = false;
          function announce(message){ el('channelStatus').textContent = message; }
          function resetChannelState(){
            channelEpoch++; browseGeneration++; readWanted = false; clearTimeout(searchTimer);
            channels = []; selected.clear(); catalogRevision = null;
            selectionSource = null; selectionRevision = null; pageReady = false; pageOffset = 0; pageTotal = 0;
            el('pageInfo').textContent = ''; el('channelStatus').textContent = ''; el('bulkSummary').textContent = '';
            needsReconcile = false; reviewAction = null; mutationBusy = false;
            el('bulkReview').hidden = true; el('stopBulk').hidden = true; stopBulkRequested = true; updateSelection();
          }
          function browseSource(){ return currentCat ? currentCat.sourceId : Number(el('channelSource').value); }
          function setBusy(busy){
            mutationBusy = busy;
            document.querySelectorAll('#chanView button, #chanView select, #chanView input').forEach(function(n){ n.disabled = busy; });
            el('cancelBulk').disabled = false;
            renderChannels(); updateSelection();
          }
          function updateSelection(){
            document.querySelectorAll('#chanList button, #chanList input').forEach(function(n){ n.disabled = mutationBusy || !pageReady || needsReconcile; });
            if (reviewAction) { reviewAction = null; el('bulkReview').hidden = true; }
            var onPage = channels.filter(function(c){ return selected.has(c.id); }).length;
            el('selectedCount').textContent = selected.size + ' selected (' + onPage + ' on this page, ' + (selected.size - onPage) + ' on other pages or filters). Maximum 500.';
            el('clearSelection').disabled = mutationBusy || !selected.size;
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
                  selected.clear(); needsReconcile = false;
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
                  selected.clear(); catalogRevision = null; selectionRevision = null;
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
          el('clearSelection').onclick = function(){ selected.clear(); renderChannels(); updateSelection(); };
          el('refreshChannels').onclick = refreshState;
          async function refreshState(){
            if (mutationBusy) return;
            setBusy(true); announce('Refreshing actual TV state…');
            var ids = Array.from(selected.keys()), epoch = channelEpoch;
            try {
              for (var i = 0; i < ids.length; i += 100) {
                var batch = ids.slice(i, i + 100);
                var rows = await post('/channels/state', { source: selectionSource, revision: selectionRevision, ids: batch });
                if (epoch !== channelEpoch) return;
                var found = new Set(rows.map(function(c){ return c.id; }));
                batch.forEach(function(id){ if (!found.has(id)) selected.delete(id); });
                rows.forEach(function(c){ selected.set(c.id, c); });
              }
              needsReconcile = false;
              catalogRevision = null;
            } catch (e) {
              if (epoch !== channelEpoch) return;
              if (e.status === 409) { selected.clear(); catalogRevision = null; selectionRevision = null; needsReconcile = false; pageOffset = 0; }
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
                  needsReconcile = true; break;
                }
                if (epoch !== channelEpoch) return;
                if (!result || !Array.isArray(result.results) || result.results.length !== batch.length ||
                    new Set(result.results.map(function(r){ return r.id; })).size !== batch.length ||
                    result.results.some(function(r){ return !batch.includes(r.id) || !['success', 'failed', 'skipped'].includes(r.status); })) {
                  unknown = batch.length; needsReconcile = true; break;
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
              selected.set(c.id, c);
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

          loadMeta();
          loadRecording();
        </script>
        </body>
        </html>
    """.trimIndent()
}
