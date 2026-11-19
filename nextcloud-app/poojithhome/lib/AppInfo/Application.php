<?php
declare(strict_types=1);
namespace OCA\PoojithHome\AppInfo;

use OCA\PoojithHome\Dashboard\MyCloudWidget;
use OCP\AppFramework\App;
use OCP\AppFramework\Bootstrap\IBootContext;
use OCP\AppFramework\Bootstrap\IBootstrap;
use OCP\AppFramework\Bootstrap\IRegistrationContext;

class Application extends App implements IBootstrap {
	public const APP_ID = 'poojithhome';
	public function __construct(array $params = []) { parent::__construct(self::APP_ID, $params); }
	public function register(IRegistrationContext $context): void { $context->registerDashboardWidget(MyCloudWidget::class); }
	public function boot(IBootContext $context): void {}
}
