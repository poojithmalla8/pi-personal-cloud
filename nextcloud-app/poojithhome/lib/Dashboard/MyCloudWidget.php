<?php
declare(strict_types=1);
namespace OCA\PoojithHome\Dashboard;

use OCA\PoojithHome\AppInfo\Application;
use OCP\Dashboard\IWidget;
use OCP\IURLGenerator;
use OCP\Util;

class MyCloudWidget implements IWidget {
	public function __construct(private IURLGenerator $url) {}
	public function getId(): string { return 'poojithhome-widget'; }
	public function getTitle(): string { return 'My Cloud'; }
	public function getOrder(): int { return 1; }
	public function getIconClass(): string { return 'icon-poojithhome'; }
	public function getUrl(): ?string { return $this->url->linkToRoute('files.view.index'); }
	public function load(): void {
		Util::addScript(Application::APP_ID, 'dashboard');
		Util::addStyle(Application::APP_ID, 'dashboard');
	}
}
