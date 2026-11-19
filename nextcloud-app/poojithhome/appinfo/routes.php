<?php
return ['routes' => [
  ['name' => 'page#index', 'url' => '/', 'verb' => 'GET'],
  ['name' => 'page#manifest', 'url' => '/manifest.webmanifest', 'verb' => 'GET'],
  ['name' => 'storage#info', 'url' => '/storage', 'verb' => 'GET'],
  ['name' => 'stats#stats', 'url' => '/stats', 'verb' => 'GET'],
]];
