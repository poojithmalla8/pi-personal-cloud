<?php
declare(strict_types=1);
namespace OCA\PoojithHome\Controller;

use OCP\AppFramework\Controller;
use OCP\AppFramework\Http;
use OCP\AppFramework\Http\Attribute\NoAdminRequired;
use OCP\AppFramework\Http\Attribute\NoCSRFRequired;
use OCP\AppFramework\Http\JSONResponse;
use OCP\IConfig;
use OCP\IRequest;

/** Serves the host stats written by cloud-stats/collector.sh into <datadirectory>/stats.json. */
class StatsController extends Controller {
	public function __construct(string $appName, IRequest $request, private IConfig $config) {
		parent::__construct($appName, $request);
	}

	/**
	 * @NoAdminRequired
	 * @NoCSRFRequired
	 */
	#[NoAdminRequired]
	#[NoCSRFRequired]
	public function stats(): JSONResponse {
		$dir = rtrim($this->config->getSystemValueString('datadirectory', \OC::$SERVERROOT . '/data'), '/');
		$file = $dir . '/stats.json';
		if (!is_readable($file)) {
			return new JSONResponse(['error' => 'No stats yet. Is the collector cron job running on the Pi?'], Http::STATUS_NOT_FOUND);
		}
		$data = json_decode((string)file_get_contents($file), true);
		if (!is_array($data)) {
			return new JSONResponse(['error' => 'Stats file is damaged; it will be rewritten on the next collector run.'], Http::STATUS_SERVICE_UNAVAILABLE);
		}
		$data['age_s'] = isset($data['updated']) ? max(0, time() - (int)$data['updated']) : null;
		$resp = new JSONResponse($data);
		$resp->addHeader('Cache-Control', 'no-store');
		return $resp;
	}
}
