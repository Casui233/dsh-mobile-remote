(function () {
  function ensure() {
    try {
      if (!window.DSHMobile || typeof window.DSHMobile.scanPair !== 'function') return;
      if (location.pathname === '/pair' || location.pathname.indexOf('/files') === 0) return;

      var remote = !!(window.DSHMobile.isRemote && window.DSHMobile.isRemote());
      var existing = document.getElementById('dsh-fab-master');
      if (existing && existing.getAttribute('data-remote') === String(remote)) return;
      if (existing) existing.remove();
      var oldPanel = document.getElementById('dsh-fab-panel');
      if (oldPanel) oldPanel.remove();

      var gridIcon = '<svg width="20" height="20" viewBox="0 0 24 24"><path fill="#fff" d="M5 5h5v5H5zM14 5h5v5h-5zM5 14h5v5H5zM14 14h5v5h-5z"/></svg>';
      var closeIcon = '<svg width="20" height="20" viewBox="0 0 24 24"><path fill="#fff" d="M6 6l12 12M18 6L6 18" stroke="#fff" stroke-width="2" stroke-linecap="round"/></svg>';
      var folderIcon = '<svg width="20" height="20" viewBox="0 0 24 24"><path fill="#8ab4f8" d="M3 6.5A2.5 2.5 0 0 1 5.5 4H10l2 2h6.5A2.5 2.5 0 0 1 21 8.5v9A2.5 2.5 0 0 1 18.5 20h-13A2.5 2.5 0 0 1 3 17.5z"/></svg>';
      var scanIcon = '<svg width="20" height="20" viewBox="0 0 24 24"><path fill="#8ab4f8" d="M4,4h6v2H6v4H4V4zM14,4h6v6h-2V6h-4V4zM4,14h2v4h4v2H4v-6zM18,14h2v6h-6v-2h4v-4zM4,11h16v2H4z"/></svg>';
      var switchIcon = '<svg width="20" height="20" viewBox="0 0 24 24"><path fill="#8ab4f8" d="M4 5h13v10H4zM6 7v6h9V7zM17 8h3v10H7v-2h10V8zM9 17h4v2H9z"/></svg>';
      var clipIcon = '<svg width="20" height="20" viewBox="0 0 24 24"><path fill="#8ab4f8" d="M8 4h8v2h3v14H5V6h3zM7 8v10h10V8zM9 10h6v2H9zM9 14h6v2H9z"/></svg>';

      var W = function () { return window.innerWidth || 360 };
      var H = function () { return window.innerHeight || 640 };
      function clampY(y) { return Math.max(80, Math.min(H() - 70, y)); }

      var state = null;
      try { state = JSON.parse(localStorage.getItem('dshFabState') || 'null'); } catch (e) {}
      if (!state || (state.side !== 'left' && state.side !== 'right') || typeof state.y !== 'number') {
        state = { side: 'right', y: H() - 220 };
      }
      state.y = clampY(state.y);

      var master = document.createElement('button');
      master.id = 'dsh-fab-master';
      master.type = 'button';
      master.setAttribute('data-remote', String(remote));
      master.title = 'DSH 悬浮菜单';
      master.setAttribute('aria-label', 'DSH 悬浮菜单');
      master.innerHTML = gridIcon;
      master.style.cssText = 'position:fixed;z-index:2147483647;width:46px;height:46px;border-radius:50%;' +
        'background:rgba(23,26,33,.94);border:1px solid rgba(255,255,255,.2);display:flex;align-items:center;' +
        'justify-content:center;padding:0;box-shadow:0 4px 16px rgba(0,0,0,.45);cursor:pointer;' +
        'touch-action:none;user-select:none;transition:left .18s,top .18s,opacity .2s;';
      document.body.appendChild(master);

      var panel = document.createElement('div');
      panel.id = 'dsh-fab-panel';
      panel.style.cssText = 'position:fixed;z-index:2147483646;width:148px;padding:6px;border-radius:14px;' +
        'background:rgba(20,24,31,.97);border:1px solid rgba(255,255,255,.14);' +
        'box-shadow:0 10px 30px rgba(0,0,0,.5);display:none;';
      document.body.appendChild(panel);

      var actions = [];
      if (remote) {
        actions.push({ label: '文件传输', icon: folderIcon, run: function () { location.href = '/files'; } });
        actions.push({ label: '剪贴板', icon: clipIcon, run: function () { try { window.DSHMobile.openClipboard(); } catch (e) {} } });
        actions.push({ label: '切换电脑', icon: switchIcon, run: function () { try { window.DSHMobile.openComputerSwitcher(); } catch (e) {} } });
      }
      actions.push({ label: '扫码', icon: scanIcon, run: function () { try { window.DSHMobile.scanPair(); } catch (e) {} } });

      actions.forEach(function (action) {
        var row = document.createElement('button');
        row.type = 'button';
        row.style.cssText = 'display:flex;align-items:center;gap:9px;width:100%;padding:9px 10px;margin:2px 0;' +
          'border:0;border-radius:9px;background:transparent;color:#e8eaed;' +
          'font:13px/1.2 -apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif;text-align:left;cursor:pointer;';
        var icon = document.createElement('span');
        icon.style.cssText = 'display:flex;width:20px;height:20px;align-items:center;justify-content:center;';
        icon.innerHTML = action.icon;
        var label = document.createElement('span');
        label.textContent = action.label;
        row.appendChild(icon);
        row.appendChild(label);
        row.addEventListener('click', function (event) {
          event.preventDefault();
          event.stopPropagation();
          collapse();
          setTimeout(function () { try { action.run(); } catch (e) {} }, 80);
        });
        panel.appendChild(row);
      });

      var expanded = false;
      var dragging = false;
      var moved = false;
      var wasExpanded = false;
      var sx = 0, sy = 0, ox = 0, oy = 0, pid = null;

      function expandedX() { return state.side === 'right' ? W() - 58 : 12; }
      function collapsedX() { return state.side === 'right' ? W() - 30 : -16; }

      function place() {
        master.style.left = (expanded ? expandedX() : collapsedX()) + 'px';
        master.style.top = clampY(state.y) + 'px';
        master.style.opacity = expanded ? '1' : '.55';
        if (expanded) {
          var panelHeight = actions.length * 46 + 12;
          var top = clampY(state.y) - panelHeight - 8;
          if (top < 70) top = clampY(state.y) + 54;
          top = Math.max(8, Math.min(H() - panelHeight - 8, top));
          var left = state.side === 'right' ? expandedX() - 154 : expandedX() + 54;
          left = Math.max(8, Math.min(W() - 156, left));
          panel.style.left = left + 'px';
          panel.style.top = top + 'px';
          panel.style.display = 'block';
        } else {
          panel.style.display = 'none';
        }
      }

      function setExpanded(value) {
        expanded = value;
        master.innerHTML = expanded ? closeIcon : gridIcon;
        place();
      }

      function collapse() {
        if (expanded) setExpanded(false);
      }
      window.__dshFabCollapse = collapse;

      function save() {
        try { localStorage.setItem('dshFabState', JSON.stringify(state)); } catch (e) {}
      }

      function down(event) {
        if (event.button !== undefined && event.button !== 0) return;
        dragging = true;
        moved = false;
        wasExpanded = expanded;
        pid = event.pointerId;
        sx = event.clientX;
        sy = event.clientY;
        ox = parseFloat(master.style.left) || 0;
        oy = parseFloat(master.style.top) || 0;
        master.style.transition = 'none';
        try { master.setPointerCapture(pid); } catch (e) {}
        event.preventDefault();
        event.stopPropagation();
      }

      function move(event) {
        if (!dragging) return;
        if (pid !== null && event.pointerId !== pid) return;
        var dx = event.clientX - sx;
        var dy = event.clientY - sy;
        if (Math.abs(dx) + Math.abs(dy) > 6) {
          moved = true;
          if (expanded) {
            expanded = false;
            panel.style.display = 'none';
            master.innerHTML = gridIcon;
          }
        }
        master.style.left = (ox + dx) + 'px';
        master.style.top = clampY(oy + dy) + 'px';
        event.preventDefault();
        event.stopPropagation();
      }

      function up(event) {
        if (!dragging) return;
        dragging = false;
        try { master.releasePointerCapture(pid); } catch (e) {}
        master.style.transition = 'left .18s,top .18s,opacity .2s';
        if (moved) {
          state.side = (parseFloat(master.style.left) + 23 < W() / 2) ? 'left' : 'right';
          state.y = clampY(parseFloat(master.style.top));
          expanded = false;
          master.innerHTML = gridIcon;
          place();
          save();
        } else {
          setExpanded(!wasExpanded);
        }
        event.preventDefault();
        event.stopPropagation();
      }

      function cancel(event) {
        if (!dragging) return;
        dragging = false;
        master.style.transition = 'left .18s,top .18s,opacity .2s';
        place();
        event.preventDefault();
        event.stopPropagation();
      }

      master.addEventListener('pointerdown', down, { passive: false });
      master.addEventListener('pointermove', move, { passive: false });
      master.addEventListener('pointerup', up, { passive: false });
      master.addEventListener('pointercancel', cancel, { passive: false });

      window.__dshFabReposition = place;
      place();
    } catch (e) {}
  }

  ensure();
  if (!window.__dshFabDocListener) {
    window.__dshFabDocListener = true;
    document.addEventListener('pointerdown', function (event) {
      try {
        var panel = document.getElementById('dsh-fab-panel');
        var master = document.getElementById('dsh-fab-master');
        if (panel && panel.style.display === 'block' && event.target !== master &&
            !panel.contains(event.target) && window.__dshFabCollapse) {
          window.__dshFabCollapse();
        }
      } catch (e) {}
    }, true);
  }
  window.addEventListener('resize', function () {
    if (window.__dshFabReposition) window.__dshFabReposition();
  });
  setInterval(ensure, 3000);
})();
