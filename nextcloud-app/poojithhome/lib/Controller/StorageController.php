<?php
declare(strict_types=1);
namespace OCA\PoojithHome\Controller;

use OCP\AppFramework\Controller;
use OCP\AppFramework\Http;
use OCP\AppFramework\Http\Attribute\NoAdminRequired;
use OCP\AppFramework\Http\Attribute\NoCSRFRequired;
use OCP\AppFramework\Http\JSONResponse;
use OCP\Files\FileInfo;
use OCP\Files\Folder;
use OCP\Files\IRootFolder;
use OCP\IRequest;
use OCP\IUserSession;

class StorageController extends Controller {
	public function __construct(string $appName, IRequest $request, private IRootFolder $root, private IUserSession $session) {
		parent::__construct($appName, $request);
	}

	#[NoAdminRequired]
	#[NoCSRFRequired]
	public function info(): JSONResponse {
		$user = $this->session->getUser();
		if ($user === null) return new JSONResponse(['error' => 'Not logged in'], Http::STATUS_UNAUTHORIZED);
		try {
			$folder = $this->root->getUserFolder($user->getUID());
			$used = max(0, (int)$folder->getSize(false));
			$free = $folder->getStorage()->free_space('');
			$free = ($free === false || $free < 0) ? -1 : (int)$free; // -1/-2/-3 = unknown/unlimited
			$quotaStr = $user->getQuota(); // 'none', 'default' resolved, or e.g. '5 GB'
			$quota = ($quotaStr === 'none' || $quotaStr === '' ) ? -3 : \OCP\Util::computerFileSize($quotaStr);
			if ($quota === false || $quota === null) $quota = -3;
			if ($quota >= 0) {
				$total = (int)$quota;
				$quotaFree = max(0, $total - $used);
				$free = $free >= 0 ? min($free, $quotaFree) : $quotaFree;
			} else {
				if ($free < 0) $free = 0;
				$total = $used + $free; // unlimited quota: show disk free space
			}
			$folders = [];
			foreach ($folder->getDirectoryListing() as $node) {
				if ($node->getType() === FileInfo::TYPE_FOLDER) $folders[] = $node->getName();
			}
			natcasesort($folders);
			return new JSONResponse([
				'uid' => $user->getUID(), 'used' => $used, 'free' => $free, 'total' => $total,
				'unlimited' => $quota < 0, 'folders' => array_values($folders),
			]);
		} catch (\Throwable $e) {
			return new JSONResponse(['error' => 'Could not read storage: ' . $e->getMessage()], Http::STATUS_INTERNAL_SERVER_ERROR);
		}
	}
}
