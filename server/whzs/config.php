<?php
/**
 * 微话助手授权后台配置。
 * 部署后请立即修改数据库信息和管理员密码。
 */
return [
    'db' => [
        'host' => '127.0.0.1',
        'port' => 3306,
        'name' => 'lh_license',
        'user' => 'lh_license',
        'pass' => 'change-this-db-password',
        'charset' => 'utf8mb4',
    ],
    'admin_user' => 'admin',
    // 明文或 bcrypt 哈希均可；上线前务必修改。
    'admin_password' => 'lh-admin-2026',
    'site_name' => '微话助手 · 授权管理',
    'timezone' => 'Asia/Shanghai',
    'code_prefix' => 'LH',
    // 授权串附加指纹校验，留空则只校验设备号。
    'api_log' => true,
];
