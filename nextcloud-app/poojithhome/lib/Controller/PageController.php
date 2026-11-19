<?php
declare(strict_types=1);
namespace OCA\PoojithHome\Controller;

use OCP\AppFramework\Controller;
use OCP\AppFramework\Http\Attribute\NoAdminRequired;
use OCP\AppFramework\Http\Attribute\NoCSRFRequired;
use OCP\AppFramework\Http\Attribute\PublicPage;
use OCP\AppFramework\Http\DataDisplayResponse;
use OCP\AppFramework\Http\TemplateResponse;
use OCP\IRequest;
use OCP\IURLGenerator;
use OCP\IUserSession;
use OCP\Util;

class PageController extends Controller {
	public function __construct(string $appName, IRequest $request, private IURLGenerator $url, private IUserSession $session) {
		parent::__construct($appName, $request);
	}

	/**
	 * @NoAdminRequired
	 * @NoCSRFRequired
	 */
	#[NoAdminRequired]
	#[NoCSRFRequired]
	public function index(): TemplateResponse {
		$img = fn (string $f) => $this->url->imagePath('poojithhome', $f);
		Util::addHeader('meta', ['name' => 'viewport', 'content' => 'width=device-width, initial-scale=1, viewport-fit=cover']);
		Util::addHeader('meta', ['name' => 'apple-mobile-web-app-capable', 'content' => 'yes']);
		Util::addHeader('meta', ['name' => 'mobile-web-app-capable', 'content' => 'yes']);
		Util::addHeader('meta', ['name' => 'apple-mobile-web-app-title', 'content' => 'My Cloud']);
		Util::addHeader('meta', ['name' => 'apple-mobile-web-app-status-bar-style', 'content' => 'black-translucent']);
		Util::addHeader('meta', ['name' => 'theme-color', 'content' => '#0082C9']);
		Util::addHeader('link', ['rel' => 'apple-touch-icon', 'sizes' => '180x180', 'href' => $img('apple-touch-icon.png')]);
		Util::addHeader('link', ['rel' => 'manifest', 'href' => $this->url->linkToRoute('poojithhome.page.manifest')]);
		Util::addScript('poojithhome', 'mobile');
		Util::addStyle('poojithhome', 'mobile');
		$user = $this->session->getUser();
		return new TemplateResponse('poojithhome', 'mobile', [
			'uid' => $user ? $user->getUID() : '',
			'requesttoken' => Util::callRegister(),
			'filesUrl' => $this->url->linkToRoute('files.view.index'),
		], TemplateResponse::RENDER_AS_BLANK);
	}

	/**
	 * Public so iOS/Chrome can fetch it without cookies; contains no user data.
	 * @PublicPage
	 * @NoCSRFRequired
	 */
	#[PublicPage]
	#[NoCSRFRequired]
	public function manifest(): DataDisplayResponse {
		$img = fn (string $f) => $this->url->imagePath('poojithhome', $f);
		$start = $this->url->linkToRoute('poojithhome.page.index');
		$m = [
			'name' => 'My Cloud', 'short_name' => 'My Cloud', 'start_url' => $start, 'scope' => $start,
			'display' => 'standalone', 'background_color' => '#F4F8FB', 'theme_color' => '#0082C9',
			'icons' => [
				['src' => $img('icon-192.png'), 'sizes' => '192x192', 'type' => 'image/png'],
				['src' => $img('icon-512.png'), 'sizes' => '512x512', 'type' => 'image/png', 'purpose' => 'any maskable'],
				['src' => $img('apple-touch-icon.png'), 'sizes' => '180x180', 'type' => 'image/png'],
			],
		];
		return new DataDisplayResponse(json_encode($m, JSON_UNESCAPED_SLASHES), 200, ['Content-Type' => 'application/manifest+json']);
	}
}
