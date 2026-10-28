/* Nuvix Contributors. GPL-3.0-or-later. No analytics or persistent browser storage. */
(() => {
  const navigation = document.getElementById('navigation');
  const mobile = window.matchMedia('(max-width: 650px)');
  const adapt = () => { navigation.open = !mobile.matches; };
  adapt();
  if (mobile.addEventListener) mobile.addEventListener('change', adapt);
  else mobile.addListener(adapt);
  const input = document.getElementById('docs-search');
  const panel = document.getElementById('search-panel');
  const results = document.getElementById('search-results');
  const status = document.getElementById('search-status');
  let index, generation = 0, timer;
  input.addEventListener('input', () => {
    const current = ++generation;
    clearTimeout(timer);
    const query = input.value.trim().toLocaleLowerCase();
    results.replaceChildren(); panel.hidden = !query;
    if (!query) return;
    status.textContent = 'Searching documentation…';
    timer = setTimeout(async () => {
      try {
        index ||= fetch('search-index.json').then(r => { if (!r.ok) throw new Error(); return r.json(); });
        const pages = await index;
        if (current !== generation) return;
        const terms = query.split(/\s+/);
        const matches = pages.filter(p => terms.every(term => (p.title + ' ' + p.text).toLocaleLowerCase().includes(term)))
          .sort((a, b) => Number(b.title.toLocaleLowerCase().includes(query)) - Number(a.title.toLocaleLowerCase().includes(query)) || a.title.localeCompare(b.title));
        status.textContent = matches.length ? `${matches.length} guides found. Showing up to 8.` : 'No matching guides. Try a shorter search or browse the navigation.';
        for (const page of matches.slice(0, 8)) {
          const link = document.createElement('a'); link.href = page.url;
          const title = document.createElement('strong'); title.textContent = page.title;
          const snippet = document.createElement('small');
          const position = Math.max(0, page.text.toLocaleLowerCase().indexOf(terms[0]) - 35);
          snippet.textContent = (position ? '…' : '') + page.text.slice(position, position + 130) + '…';
          link.append(title, snippet); results.append(link);
        }
      } catch {
        index = undefined;
        if (current === generation) status.textContent = 'Search is unavailable. Use the navigation to browse guides.';
      }
    }, 120);
  });
  input.addEventListener('keydown', event => {
    if (event.key === 'Escape') { generation++; clearTimeout(timer); input.value = ''; panel.hidden = true; results.replaceChildren(); }
  });
  document.addEventListener('click', event => { if (!event.target.closest('.search-wrap')) panel.hidden = true; });
  input.addEventListener('focus', () => { if (input.value.trim()) panel.hidden = false; });
})();
