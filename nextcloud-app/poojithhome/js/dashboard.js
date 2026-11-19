/* Poojith Home - "My Cloud" dashboard widget (buildless). */
(function () {
	'use strict'
	var ID = 'poojithhome-widget'

	function gb(b) { var g = b / 1e9; return (g >= 100 ? g.toFixed(0) : g >= 1 ? g.toFixed(1) : g.toFixed(2)) + ' GB' }
	function el(tag, cls, text) { var e = document.createElement(tag); if (cls) e.className = cls; if (text != null) e.textContent = text; return e }
	function token() { return (window.OC && OC.requestToken) || (document.head.dataset && document.head.dataset.requesttoken) || '' }
	function davBase(uid) { return OC.linkToRemote('dav') + '/files/' + encodeURIComponent(uid) }
	function encPath(p) { return p.split('/').filter(Boolean).map(encodeURIComponent).join('/') }
	function davErr(status, what) {
		var m = { 401: 'not signed in', 403: 'permission denied', 404: 'folder not found', 405: 'already exists', 409: 'parent folder missing', 412: 'already exists', 507: 'not enough storage space' }
		return what + ' failed: ' + (m[status] || ('HTTP ' + status))
	}

	function render(root) {
		root.innerHTML = ''
		var wrap = el('div', 'ph-wrap'); root.appendChild(wrap)
		var bar = el('div', 'ph-bar'), used = el('div', 'ph-used'), free = el('div', 'ph-free')
		bar.appendChild(used); bar.appendChild(free)
		var text = el('div', 'ph-text', 'Loading storage…')
		var btns = el('div', 'ph-buttons')
		var bNew = el('button', 'ph-btn', '📁 New folder'), bUp = el('button', 'ph-btn', '⬆️ Upload files')
		btns.appendChild(bNew); btns.appendChild(bUp)
		var targetLabel = el('label', null, 'Upload / create in: ')
		var target = el('select', 'ph-target'); target.appendChild(new Option('Home', '/'))
		targetLabel.appendChild(target)
		var file = el('input'); file.type = 'file'; file.multiple = true; file.style.display = 'none'
		var prog = el('div', 'ph-progress'), progText = el('div'), progBar = el('progress'); progBar.max = 100
		prog.appendChild(progText); prog.appendChild(progBar)
		var msg = el('div', 'ph-msg')
		var list = el('ul', 'ph-list')
		;[bar, text, btns, targetLabel, file, prog, msg, el('strong', null, 'Folders'), list].forEach(function (n) { wrap.appendChild(n) })

		// ---- System card (host stats from collector) ----
		var banner = el('div', 'ph-banner'); banner.style.display = 'none'
		var sys = el('div', 'ph-sys')
		wrap.insertBefore(banner, wrap.firstChild)
		wrap.appendChild(sys)
		var state = { uid: OC.getCurrentUser().uid }
		function fmtB(b) { if (b == null || b < 0) return '?'; var u = ['B', 'KB', 'MB', 'GB', 'TB'], i = 0; while (b >= 1000 && i < 4) { b /= 1000; i++ } return (i ? b.toFixed(b >= 100 ? 0 : 1) : b) + ' ' + u[i] }
		function ago(s) { if (s == null) return '?'; if (s < 60) return s + ' s ago'; if (s < 3600) return Math.round(s / 60) + ' min ago'; if (s < 86400) return Math.round(s / 3600) + ' h ago'; return Math.round(s / 86400) + ' days ago' }
		function dur(s) { var d = Math.floor(s / 86400), h = Math.floor(s % 86400 / 3600), m = Math.floor(s % 3600 / 60); return (d ? d + 'd ' : '') + h + 'h ' + m + 'm' }
		function row(k, v) { var r = el('div', 'ph-row'); r.appendChild(el('span', null, k)); r.appendChild(el('b', null, v)); return r }
		var globalBanner = null
		function topBanner(text) {
			var host = document.querySelector('#app-dashboard') || document.querySelector('#content')
			if (!host) return
			if (!globalBanner) { globalBanner = el('div', 'ph-banner ph-global'); host.insertBefore(globalBanner, host.firstChild) }
			globalBanner.style.display = text ? 'block' : 'none'; globalBanner.textContent = text || ''
		}
		function loadStats() {
			var xhr = new XMLHttpRequest()
			xhr.open('GET', OC.generateUrl('/apps/poojithhome/stats'))
			xhr.setRequestHeader('requesttoken', token()); xhr.setRequestHeader('Accept', 'application/json')
			xhr.onload = function () {
				var d; try { d = JSON.parse(xhr.responseText) } catch (e) { d = {} }
				sys.innerHTML = ''
				sys.appendChild(el('strong', 'ph-h', 'System'))
				if (xhr.status !== 200) { sys.appendChild(el('div', 'ph-msg err', d.error || ('Could not load system stats (HTTP ' + xhr.status + ')'))); banner.style.display = 'none'; topBanner(null); return }
				var t = d.cpu && d.cpu.temp_c, th = d.throttle || {}, warn = []
				if (t != null && t >= 75) warn.push('🔥 Overheating! Pi CPU is at ' + t.toFixed(1) + ' °C')
				if (th.active) warn.push('⚠️ Pi is throttling to cool down' + (th.under_voltage ? ' (under-voltage: check the power supply)' : ''))
				banner.textContent = warn.join(' — '); banner.style.display = warn.length ? 'block' : 'none'
				topBanner(warn.join(' — '))
				// temperature bar
				var tl = el('div', 'ph-row'); tl.appendChild(el('span', null, '🌡️ CPU temperature')); tl.appendChild(el('b', null, t == null ? 'n/a' : t.toFixed(1) + ' °C'))
				sys.appendChild(tl)
				var tb = el('div', 'ph-bar ph-tbar'), tf = el('div', 'ph-tfill')
				tf.style.width = (t == null ? 0 : Math.max(3, Math.min(100, t / 90 * 100))) + '%'
				tf.style.background = t == null ? '#999' : t >= 75 ? '#E53935' : t >= 60 ? '#FB8C00' : '#43A047'
				tb.appendChild(tf); sys.appendChild(tb)
				if (th.raw == null) sys.appendChild(el('div', 'ph-small', 'Throttling status unavailable (vcgencmd not found or user not in "video" group).'))
				else if (!th.active) sys.appendChild(el('div', 'ph-small', '✅ Not throttling'))
				var c = d.cpu || {}, n = d.network || {}, p = d.power || {}, nd = d.nextcloud_disk || {}
				sys.appendChild(row('CPU', (c.util_pct != null ? c.util_pct + '%' : '?') + ' · load ' + [c.load1, c.load5, c.load15].join(' / ')))
				sys.appendChild(row('Uptime', d.uptime_s != null ? dur(d.uptime_s) : '?'))
				sys.appendChild(el('strong', 'ph-h2', 'Network'))
				function nr(lbl, o) { return row(lbl, o ? '⬇ ' + fmtB(o.rx) + '  ⬆ ' + fmtB(o.tx) : '?') }
				sys.appendChild(nr('Today', n.today)); sys.appendChild(nr('This month', n.month)); sys.appendChild(nr('Since boot', n.since_boot))
				sys.appendChild(el('strong', 'ph-h2', 'Power (Estimated)'))
				sys.appendChild(row('Now (est.)', p.watts != null ? p.watts + ' W' : '?'))
				sys.appendChild(row('Today (est.)', p.today_kwh != null ? p.today_kwh.toFixed(3) + ' kWh' : '?'))
				sys.appendChild(row('This month (est.)', p.month_kwh != null ? p.month_kwh.toFixed(2) + ' kWh' : '?'))
				sys.appendChild(el('div', 'ph-small', 'Estimated from a simple model (' + (p.model || '') + '), not measured.'))
				sys.appendChild(el('strong', 'ph-h2', 'Nextcloud disk usage'))
				;[['App code', nd.app_code], ['Database', nd.database], ['App data', nd.appdata], ['Previews', nd.previews], ['Trash', nd.trash], ['Versions', nd.versions], ['Docker images', nd.docker_images]]
					.forEach(function (x) { sys.appendChild(row(x[0], fmtB(x[1]))) })
				if (nd.updated) sys.appendChild(el('div', 'ph-small', 'Disk usage measured ' + ago(Math.max(0, Math.round(Date.now() / 1000) - nd.updated)) + ' (hourly)'))
				var age = d.age_s, stale = age != null && age > 300
				sys.appendChild(el('div', 'ph-small' + (stale ? ' err' : ''), 'Last updated ' + ago(age) + (stale ? ' — the collector may have stopped' : '')))
			}
			xhr.onerror = function () { sys.innerHTML = ''; sys.appendChild(el('div', 'ph-msg err', 'Could not reach the server for system stats.')) }
			xhr.send()
		}
		loadStats()
		var timer = setInterval(function () { if (!document.body.contains(wrap)) { clearInterval(timer); return } if (!document.hidden) loadStats() }, 60000)

		function say(t, err) { msg.textContent = t || ''; msg.className = 'ph-msg' + (err ? ' err' : '') }

		function load() {
			var xhr = new XMLHttpRequest()
			xhr.open('GET', OC.generateUrl('/apps/poojithhome/storage'))
			xhr.setRequestHeader('requesttoken', token()); xhr.setRequestHeader('Accept', 'application/json')
			xhr.onload = function () {
				var d; try { d = JSON.parse(xhr.responseText) } catch (e) { d = {} }
				if (xhr.status !== 200) { text.textContent = d.error || ('Could not load storage (HTTP ' + xhr.status + ')'); return }
				state.uid = d.uid || state.uid
				var pct = d.total > 0 ? Math.min(100, d.used / d.total * 100) : 0
				used.style.width = pct + '%'
				text.textContent = gb(d.used) + ' used of ' + gb(d.total) + ' (' + gb(d.free) + ' free)'
				var sel = target.value
				target.innerHTML = ''; target.appendChild(new Option('Home', '/'))
				list.innerHTML = ''
				;(d.folders || []).forEach(function (name) {
					target.appendChild(new Option(name, '/' + name))
					var li = el('li'), a = el('a', null, '📁 ' + name)
					a.href = OC.generateUrl('/apps/files/') + '?dir=' + encodeURIComponent('/' + name)
					li.appendChild(a); list.appendChild(li)
				})
				if (!d.folders || !d.folders.length) list.appendChild(el('li', null, 'No folders yet.'))
				target.value = Array.prototype.some.call(target.options, function (o) { return o.value === sel }) ? sel : '/'
			}
			xhr.onerror = function () { text.textContent = 'Could not reach the server.' }
			xhr.send()
		}

		bNew.onclick = function () {
			var name = window.prompt('Name for the new folder:')
			if (name == null) return
			name = name.trim()
			if (!name || /[\/\\]/.test(name) || name === '.' || name === '..') { say('Please enter a valid name (no / or \\).', true); return }
			var path = encPath(target.value) ; path = (path ? path + '/' : '') + encodeURIComponent(name)
			var xhr = new XMLHttpRequest()
			xhr.open('MKCOL', davBase(state.uid) + '/' + path)
			xhr.setRequestHeader('requesttoken', token())
			xhr.onload = function () {
				if (xhr.status >= 200 && xhr.status < 300) { say('Folder "' + name + '" created.'); load() } else say(davErr(xhr.status, 'Creating folder'), true)
			}
			xhr.onerror = function () { say('Could not reach the server.', true) }
			xhr.send()
		}

		bUp.onclick = function () { file.value = ''; file.click() }
		file.onchange = function () {
			var files = Array.prototype.slice.call(file.files || []); if (!files.length) return
			var dir = encPath(target.value), i = 0, failed = 0
			bNew.disabled = bUp.disabled = true; prog.style.display = 'block'; say('')
			function next() {
				if (i >= files.length) {
					prog.style.display = 'none'; bNew.disabled = bUp.disabled = false
					say(failed ? (failed + ' of ' + files.length + ' uploads failed.') : ('Uploaded ' + files.length + ' file(s).'), failed > 0)
					load(); return
				}
				var f = files[i++]
				progText.textContent = 'Uploading ' + i + '/' + files.length + ': ' + f.name; progBar.value = 0
				var xhr = new XMLHttpRequest()
				xhr.open('PUT', davBase(state.uid) + '/' + (dir ? dir + '/' : '') + encodeURIComponent(f.name))
				xhr.setRequestHeader('requesttoken', token())
				if (f.type) xhr.setRequestHeader('Content-Type', f.type)
				xhr.upload.onprogress = function (e) { if (e.lengthComputable) progBar.value = e.loaded / e.total * 100 }
				xhr.onload = function () { if (xhr.status < 200 || xhr.status >= 300) { failed++; say(davErr(xhr.status, 'Uploading ' + f.name), true) } next() }
				xhr.onerror = function () { failed++; next() }
				xhr.send(f)
			}
			next()
		}
		load()
	}

	function register() {
		if (window.OCA && OCA.Dashboard && OCA.Dashboard.register) {
			OCA.Dashboard.register(ID, function (elm) { render(elm) })
			return true
		}
		return false
	}
	if (!register()) document.addEventListener('DOMContentLoaded', register)
})()
