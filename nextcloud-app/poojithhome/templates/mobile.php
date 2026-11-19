<?php /** @var array $_ */ ?>
<div id="ph-app" data-uid="<?php p($_['uid']); ?>" data-requesttoken="<?php p($_['requesttoken']); ?>" data-files="<?php p($_['filesUrl']); ?>">
	<header class="m-top">
		<div class="m-title">☁️ My Cloud</div>
		<button class="m-icon-btn" id="m-refresh" aria-label="Refresh">⟳</button>
	</header>
	<div id="m-banner" class="m-banner" hidden></div>
	<main class="m-main">
		<section class="m-card">
			<h2>Storage</h2>
			<div class="m-bar"><div id="m-used" class="m-used"></div><div class="m-free"></div></div>
			<div id="m-quota" class="m-sub">Loading…</div>
		</section>
		<section class="m-card" id="m-sys"><h2>System</h2><div class="m-sub">Loading…</div></section>
		<section class="m-card">
			<div class="m-actions">
				<button class="m-btn" id="m-new">📁 New folder</button>
				<button class="m-btn" id="m-up">⬆️ Upload</button>
				<input type="file" id="m-file" multiple hidden>
			</div>
			<div id="m-prog" class="m-prog" hidden><div id="m-prog-text" class="m-sub"></div><progress id="m-prog-bar" max="100" value="0"></progress></div>
			<nav id="m-crumbs" class="m-crumbs"></nav>
			<ul id="m-list" class="m-list"></ul>
		</section>
		<a class="m-btn m-outline" id="m-full" href="<?php p($_['filesUrl']); ?>">Open full Nextcloud</a>
	</main>
	<div id="m-toast" class="m-toast" hidden></div>
	<div id="m-sheet" class="m-sheet" hidden><div class="m-sheet-box"><div id="m-sheet-title" class="m-sheet-title"></div><div id="m-sheet-btns"></div></div></div>
</div>
