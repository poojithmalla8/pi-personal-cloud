/* Poojith Home - mobile web app view (buildless, no service worker: site is plain http) */
(function () {
	'use strict'
	function $(id) { return document.getElementById(id) }
	function el(tag, cls, text) { var e = document.createElement(tag); if (cls) e.className = cls; if (text != null) e.textContent = text; return e }
	function gb(b) { var g = b / 1e9; return (g >= 100 ? g.toFixed(0) : g >= 1 ? g.toFixed(1) : g.toFixed(2)) + ' GB' }
	function fmtB(b) { if (b == null || b < 0) return '?'; var u = ['B', 'KB', 'MB', 'GB', 'TB'], i = 0; while (b >= 1000 && i < 4) { b /= 1000; i++ } return (i ? b.toFixed(b >= 100 ? 0 : 1) : b) + ' ' + u[i] }
	function ago(s) { if (s == null) return '?'; if (s < 60) return s + ' s ago'; if (s < 3600) return Math.round(s / 60) + ' min ago'; if (s < 86400) return Math.round(s / 3600) + ' h ago'; return Math.round(s / 86400) + ' days ago' }

	function start() {
		var app = $('ph-app'); if (!app) return
		var uid = app.dataset.uid
		function token() { return (window.OC && OC.requestToken) || app.dataset.requesttoken || (document.head.dataset && document.head.dataset.requesttoken) || '' }
		var webroot = (window.OC && OC.webroot) || ''
		function appUrl(p) { return (window.OC && OC.generateUrl) ? OC.generateUrl('/apps/poojithhome' + p) : webroot + '/index.php/apps/poojithhome' + p }
		function davUrl(parts) { return webroot + '/remote.php/dav/files/' + encodeURIComponent(uid) + '/' + parts.map(encodeURIComponent).join('/') }
		var path = []

		var toastT
		function toast(t) { var e = $('m-toast'); e.textContent = t; e.hidden = false; clearTimeout(toastT); toastT = setTimeout(function () { e.hidden = true }, 3500) }
		function davErr(s, what) {
			var m = { 0: "can't reach your cloud (is Tailscale connected?)", 401: 'signed out, reload the page', 403: 'permission denied', 404: 'not found', 405: 'already exists', 409: 'parent folder missing', 412: 'an item with that name already exists', 507: 'not enough storage space' }
			return what + ' failed: ' + (m[s] || ('HTTP ' + s))
		}
		function req(method, url, opts, cb) {
			opts = opts || {}
			var x = new XMLHttpRequest(); x.open(method, url)
			x.setRequestHeader('requesttoken', token())
			Object.keys(opts.headers || {}).forEach(function (k) { x.setRequestHeader(k, opts.headers[k]) })
			if (opts.progress) x.upload.onprogress = opts.progress
			x.onload = function () { if (x.status === 401 && !opts.no401) { location.reload(); return } cb(x) }
			x.onerror = function () { cb({ status: 0, responseText: '' }) }
			x.send(opts.body == null ? null : opts.body)
		}
		function getJSON(url, cb) { req('GET', url, { headers: { Accept: 'application/json' } }, function (x) { var d; try { d = JSON.parse(x.responseText) } catch (e) { d = {} } cb(x.status, d) }) }

		// ---------- storage ----------
		function loadStorage() {
			getJSON(appUrl('/storage'), function (s, d) {
				if (s !== 200) { $('m-quota').textContent = s === 0 ? "Can't reach your cloud. Make sure Tailscale is connected." : (d.error || 'Could not load storage (HTTP ' + s + ')'); $('m-quota').className = 'm-sub err'; return }
				if (d.uid) uid = d.uid
				$('m-used').style.width = (d.total > 0 ? Math.min(100, d.used / d.total * 100) : 0) + '%'
				$('m-quota').className = 'm-sub'
				$('m-quota').textContent = gb(d.used) + ' used of ' + gb(d.total) + ' (' + gb(d.free) + ' free)'
			})
		}

		// ---------- system ----------
		function row(k, v) { var r = el('div', 'm-row'); r.appendChild(el('span', null, k)); r.appendChild(el('b', null, v)); return r }
		function loadStats() {
			getJSON(appUrl('/stats'), function (s, d) {
				var box = $('m-sys'); box.innerHTML = ''; box.appendChild(el('h2', null, 'System'))
				var banner = $('m-banner')
				if (s !== 200) { box.appendChild(el('div', 'm-sub err', s === 0 ? "Can't reach your cloud." : (d.error || 'Could not load system stats (HTTP ' + s + ')'))); banner.hidden = true; return }
				var c = d.cpu || {}, th = d.throttle || {}, n = d.network || {}, p = d.power || {}, nd = d.nextcloud_disk || {}
				var t = c.temp_c, warn = []
				if (t != null && t >= 75) warn.push('🔥 Overheating! CPU at ' + t.toFixed(1) + ' °C')
				if (th.active) warn.push('⚠️ Pi is throttling to cool down' + (th.under_voltage ? ' (under-voltage: check power supply)' : ''))
				banner.textContent = warn.join('\n'); banner.hidden = !warn.length; banner.style.whiteSpace = 'pre-line'
				var color = t == null ? '#9E9E9E' : t >= 75 ? '#E53935' : t >= 60 ? '#FB8C00' : '#43A047'
				var tr = row('🌡️ CPU temperature', t == null ? 'n/a' : t.toFixed(1) + ' °C'); tr.lastChild.style.color = color; box.appendChild(tr)
				var tb = el('div', 'm-tbar'), tf = el('div', 'm-tfill'); tb.appendChild(tf); box.appendChild(tb)
				tf.style.background = color; tf.style.width = (t == null ? 0 : Math.max(3, Math.min(100, t / 90 * 100))) + '%'
				box.appendChild(el('div', 'm-sub', th.raw == null ? 'Throttling status unavailable' : th.active ? '⚠️ Throttling' : '✅ Not throttling'))
				box.appendChild(row('CPU', (c.util_pct != null ? c.util_pct + '%' : '?') + ' · load ' + [c.load1, c.load5, c.load15].join(' / ')))
				if (d.uptime_s != null) { var u = d.uptime_s; box.appendChild(row('Uptime', Math.floor(u / 86400) + 'd ' + Math.floor(u % 86400 / 3600) + 'h ' + Math.floor(u % 3600 / 60) + 'm')) }
				box.appendChild(el('div', 'm-h3', 'Network'))
				function nl(o) { return o ? '⬇ ' + fmtB(o.rx) + '  ⬆ ' + fmtB(o.tx) : '?' }
				box.appendChild(row('Today', nl(n.today))); box.appendChild(row('This month', nl(n.month)))
				box.appendChild(el('div', 'm-h3', 'Power (Estimated)'))
				box.appendChild(row('Now', p.watts != null ? '~' + p.watts + ' W' : '?'))
				box.appendChild(row('Today', p.today_kwh != null ? '~' + p.today_kwh.toFixed(3) + ' kWh' : '?'))
				box.appendChild(row('This month', p.month_kwh != null ? '~' + p.month_kwh.toFixed(2) + ' kWh' : '?'))
				box.appendChild(el('div', 'm-sub', 'Estimated from a simple model, not measured.'))
				box.appendChild(el('div', 'm-h3', 'Nextcloud disk usage'))
				;[['App code', nd.app_code], ['Database', nd.database], ['App data', nd.appdata], ['Previews', nd.previews], ['Trash', nd.trash], ['Versions', nd.versions], ['Docker images', nd.docker_images]]
					.forEach(function (x) { box.appendChild(row(x[0], fmtB(x[1]))) })
				var stale = d.age_s != null && d.age_s > 300
				box.appendChild(el('div', 'm-sub' + (stale ? ' err' : ''), 'Updated ' + ago(d.age_s) + (stale ? ' (collector may have stopped)' : '')))
			})
		}

		// ---------- files ----------
		var PROPFIND = '<?xml version="1.0"?><d:propfind xmlns:d="DAV:"><d:prop><d:resourcetype/><d:getcontentlength/><d:getcontenttype/><d:getlastmodified/></d:prop></d:propfind>'
		function icon(it) {
			if (it.dir) return '📁'
			var m = it.mime || ''
			return m.indexOf('image/') === 0 ? '🖼️' : m.indexOf('video/') === 0 ? '🎬' : m.indexOf('audio/') === 0 ? '🎵' : m.indexOf('pdf') >= 0 ? '📕' : /zip|compressed|tar/.test(m) ? '🗜️' : /^text\/|document|word/.test(m) ? '📝' : '📄'
		}
		function crumbs() {
			var c = $('m-crumbs'); c.innerHTML = ''
			var home = el('a', null, '🏠 Home'); home.href = '#'; home.onclick = function (e) { e.preventDefault(); path = []; loadFiles() }; c.appendChild(home)
			path.forEach(function (seg, i) {
				c.appendChild(document.createTextNode(' / '))
				var a = el('a', null, seg); a.href = '#'; a.onclick = function (e) { e.preventDefault(); path = path.slice(0, i + 1); loadFiles() }; c.appendChild(a)
			})
		}
		function loadFiles() {
			crumbs()
			var want = path.slice(), list = $('m-list')
			req('PROPFIND', davUrl(want), { headers: { Depth: '1', 'Content-Type': 'application/xml' }, body: PROPFIND }, function (x) {
				if (want.join('/') !== path.join('/')) return
				list.innerHTML = ''
				if (x.status !== 207) { list.appendChild(el('li', 'm-empty', davErr(x.status, 'Loading folder'))); return }
				var doc = new DOMParser().parseFromString(x.responseText, 'application/xml'), items = []
				var self = decodeURIComponent(new URL(davUrl(want), location.href).pathname).replace(/\/$/, '')
				Array.prototype.forEach.call(doc.getElementsByTagNameNS('DAV:', 'response'), function (r) {
					var g = function (t) { var e = r.getElementsByTagNameNS('DAV:', t)[0]; return e ? e.textContent : '' }
					var href = decodeURIComponent(g('href')).replace(/\/$/, '')
					if (href === self) return
					items.push({ name: href.substring(href.lastIndexOf('/') + 1), dir: r.getElementsByTagNameNS('DAV:', 'collection').length > 0,
						size: parseInt(g('getcontentlength') || '0', 10), mime: g('getcontenttype'), mod: g('getlastmodified') ? new Date(g('getlastmodified')) : null })
				})
				items.sort(function (a, b) { return a.dir !== b.dir ? (a.dir ? -1 : 1) : a.name.localeCompare(b.name, undefined, { sensitivity: 'base', numeric: true }) })
				if (!items.length) list.appendChild(el('li', 'm-empty', 'This folder is empty. Tap Upload to add files.'))
				items.forEach(function (it) { list.appendChild(itemRow(it)) })
			})
		}
		function itemRow(it) {
			var li = el('li', 'm-item'), a = el('a', 'm-item-main')
			a.href = it.dir ? '#' : davUrl(path.concat(it.name))
			if (!it.dir) a.target = '_blank'
			a.appendChild(el('span', 'm-ico', icon(it)))
			var txt = el('div'); txt.style.minWidth = '0'
			txt.appendChild(el('div', 'm-name', it.name))
			txt.appendChild(el('div', 'm-meta', (it.dir ? 'Folder' : fmtB(it.size)) + (it.mod ? ' · ' + it.mod.toLocaleDateString() : '')))
			a.appendChild(txt)
			var pressT, longPressed = false
			a.addEventListener('touchstart', function () { longPressed = false; pressT = setTimeout(function () { longPressed = true; menu(it) }, 550) }, { passive: true })
			;['touchend', 'touchmove', 'touchcancel'].forEach(function (ev) { a.addEventListener(ev, function () { clearTimeout(pressT) }, { passive: true }) })
			a.addEventListener('contextmenu', function (e) { e.preventDefault(); if (!longPressed) menu(it) })
			a.onclick = function (e) {
				if (longPressed) { e.preventDefault(); longPressed = false; return }
				if (it.dir) { e.preventDefault(); path.push(it.name); loadFiles() }
			}
			var more = el('button', 'm-more', '⋯'); more.setAttribute('aria-label', 'More'); more.onclick = function () { menu(it) }
			li.appendChild(a); li.appendChild(more)
			return li
		}
		function sheet(title, buttons) {
			var s = $('m-sheet'), b = $('m-sheet-btns'); $('m-sheet-title').textContent = title; b.innerHTML = ''
			buttons.concat([{ label: 'Cancel' }]).forEach(function (x) {
				var btn = el('button', x.danger ? 'danger' : null, x.label)
				btn.onclick = function () { s.hidden = true; if (x.fn) x.fn() }; b.appendChild(btn)
			})
			s.onclick = function (e) { if (e.target === s) s.hidden = true }
			s.hidden = false
		}
		function validName(n) { return n && !/[\/\\]/.test(n) && n !== '.' && n !== '..' }
		function menu(it) {
			var opts = []
			if (it.dir) opts.push({ label: 'Open', fn: function () { path.push(it.name); loadFiles() } })
			else {
				opts.push({ label: 'Open', fn: function () { window.open(davUrl(path.concat(it.name)), '_blank') } })
				opts.push({ label: 'Download', fn: function () { var a = el('a'); a.href = davUrl(path.concat(it.name)); a.download = it.name; document.body.appendChild(a); a.click(); a.remove() } })
			}
			opts.push({ label: 'Rename', fn: function () {
				var n = window.prompt('New name:', it.name); if (n == null) return; n = n.trim()
				if (n === it.name) return
				if (!validName(n)) { toast('Please enter a valid name (no / or \\)'); return }
				if (!window.confirm('Rename "' + it.name + '" to "' + n + '"?')) return
				req('MOVE', davUrl(path.concat(it.name)), { headers: { Destination: new URL(davUrl(path.concat(n)), location.href).href, Overwrite: 'F' } }, function (x) {
					if (x.status >= 200 && x.status < 300) { toast('Renamed'); loadFiles() } else toast(davErr(x.status, 'Renaming'))
				})
			} })
			opts.push({ label: 'Delete', danger: true, fn: function () {
				if (!window.confirm('Delete "' + it.name + '"' + (it.dir ? ' and everything inside it' : '') + '? It goes to the Nextcloud trash.')) return
				req('DELETE', davUrl(path.concat(it.name)), {}, function (x) {
					if (x.status >= 200 && x.status < 300) { toast('Deleted'); loadFiles(); loadStorage() } else toast(davErr(x.status, 'Deleting'))
				})
			} })
			sheet(it.name, opts)
		}
		$('m-new').onclick = function () {
			var n = window.prompt('Name for the new folder:'); if (n == null) return; n = n.trim()
			if (!validName(n)) { toast('Please enter a valid name (no / or \\)'); return }
			req('MKCOL', davUrl(path.concat(n)), {}, function (x) { if (x.status >= 200 && x.status < 300) { toast('Folder created'); loadFiles() } else toast(davErr(x.status, 'Creating folder')) })
		}
		$('m-up').onclick = function () { $('m-file').value = ''; $('m-file').click() }
		$('m-file').onchange = function () {
			var files = Array.prototype.slice.call(this.files || []); if (!files.length) return
			var dir = path.slice(), i = 0, failed = 0, prog = $('m-prog'), bar = $('m-prog-bar')
			$('m-new').disabled = $('m-up').disabled = true; prog.hidden = false
			function next() {
				if (i >= files.length) {
					prog.hidden = true; $('m-new').disabled = $('m-up').disabled = false
					toast(failed ? failed + ' of ' + files.length + ' uploads failed' : 'Uploaded ' + files.length + ' file' + (files.length > 1 ? 's' : ''))
					if (dir.join('/') === path.join('/')) loadFiles(); loadStorage(); return
				}
				var f = files[i++]; $('m-prog-text').textContent = 'Uploading ' + i + '/' + files.length + ': ' + f.name; bar.value = 0
				req('PUT', davUrl(dir.concat(f.name)), { headers: f.type ? { 'Content-Type': f.type } : {}, body: f,
					progress: function (e) { if (e.lengthComputable) bar.value = e.loaded / e.total * 100 } }, function (x) {
					if (x.status < 200 || x.status >= 300) { failed++; toast(davErr(x.status, 'Uploading ' + f.name)) }
					next()
				})
			}
			next()
		}
		$('m-refresh').onclick = function () { loadStorage(); loadStats(); loadFiles() }
		document.addEventListener('visibilitychange', function () { if (!document.hidden) { loadStats(); loadStorage() } })
		setInterval(function () { if (!document.hidden) loadStats() }, 60000)
		loadStorage(); loadStats(); loadFiles()
	}
	if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', start); else start()
})()
