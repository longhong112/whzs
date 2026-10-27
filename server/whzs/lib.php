<?php
declare(strict_types=1);

/** 微话助手授权服务公共库。 */

function lh_config(): array
{
    static $config = null;
    if ($config === null) {
        $config = require __DIR__ . '/config.php';
        date_default_timezone_set($config['timezone'] ?? 'Asia/Shanghai');
        ini_set('display_errors', '0');
        header_remove('X-Powered-By');
    }
    return $config;
}

function lh_db(): PDO
{
    static $pdo = null;
    if ($pdo instanceof PDO) {
        return $pdo;
    }
    $c = lh_config()['db'];
    $dsn = sprintf('mysql:host=%s;port=%d;dbname=%s;charset=%s',
        $c['host'], $c['port'] ?? 3306, $c['name'], $c['charset'] ?? 'utf8mb4');
    $pdo = new PDO($dsn, $c['user'], $c['pass'], [
        PDO::ATTR_ERRMODE => PDO::ERRMODE_EXCEPTION,
        PDO::ATTR_DEFAULT_FETCH_MODE => PDO::FETCH_ASSOC,
        PDO::ATTR_EMULATE_PREPARES => false,
    ]);
    lh_schema($pdo);
    return $pdo;
}

function lh_schema(PDO $pdo): void
{
    $pdo->exec("CREATE TABLE IF NOT EXISTS lh_codes (
        id INT UNSIGNED NOT NULL AUTO_INCREMENT,
        code VARCHAR(32) NOT NULL,
        batch VARCHAR(32) NOT NULL DEFAULT '',
        days INT UNSIGNED NOT NULL DEFAULT 30,
        status TINYINT UNSIGNED NOT NULL DEFAULT 1,
        device_id CHAR(64) NOT NULL DEFAULT '',
        device_fp CHAR(64) NOT NULL DEFAULT '',
        activated_at DATETIME NULL,
        expire_at DATETIME NULL,
        note VARCHAR(255) NOT NULL DEFAULT '',
        created_at DATETIME NOT NULL,
        updated_at DATETIME NOT NULL,
        PRIMARY KEY (id),
        UNIQUE KEY uk_code (code),
        KEY idx_device (device_id),
        KEY idx_expire (expire_at)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
    $pdo->exec("CREATE TABLE IF NOT EXISTS lh_nonces (
        nonce CHAR(32) NOT NULL,
        created_at DATETIME NOT NULL,
        PRIMARY KEY (nonce),
        KEY idx_created (created_at)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
    $pdo->exec("CREATE TABLE IF NOT EXISTS lh_events (
        id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
        code VARCHAR(32) NOT NULL DEFAULT '',
        device_id CHAR(64) NOT NULL DEFAULT '',
        action VARCHAR(24) NOT NULL,
        ip VARCHAR(45) NOT NULL DEFAULT '',
        detail VARCHAR(255) NOT NULL DEFAULT '',
        created_at DATETIME NOT NULL,
        PRIMARY KEY (id),
        KEY idx_created (created_at),
        KEY idx_device (device_id)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
    if (random_int(1, 20) === 1) {
        $pdo->exec('DELETE FROM lh_nonces WHERE created_at < DATE_SUB(NOW(), INTERVAL 2 DAY) LIMIT 500');
    }
}

function lh_now(): string
{
    return date('Y-m-d H:i:s');
}

function lh_event(PDO $pdo, string $code, string $device, string $action, string $detail = ''): void
{
    if (!(lh_config()['api_log'] ?? true)) {
        return;
    }
    try {
        $stmt = $pdo->prepare('INSERT INTO lh_events (code, device_id, action, ip, detail, created_at) VALUES (?,?,?,?,?,?)');
        $stmt->execute([$code, $device, $action, lh_client_ip(), substr($detail, 0, 255), lh_now()]);
    } catch (Throwable $ignored) {
    }
}

function lh_client_ip(): string
{
    return substr((string)($_SERVER['REMOTE_ADDR'] ?? ''), 0, 45);
}

function lh_private_key()
{
    static $key = null;
    if ($key !== null) {
        return $key;
    }
    $path = __DIR__ . '/keys/private.pem';
    if (!is_file($path)) {
        throw new RuntimeException('服务器密钥缺失，请上传 keys/private.pem');
    }
    $key = @openssl_pkey_get_private((string)file_get_contents($path));
    if ($key === false) {
        throw new RuntimeException('服务器密钥格式错误');
    }
    return $key;
}

/**
 * 解密 App 请求：RSA-OAEP 包裹 32 字节会话密钥，AES-256-GCM 加密报文体。
 * @return array{0: array, 1: string} [payload, aesKey]
 */
function lh_decrypt_request(array $envelope): array
{
    $wrapped = base64_decode((string)($envelope['k'] ?? ''), true);
    $iv = base64_decode((string)($envelope['iv'] ?? ''), true);
    $data = base64_decode((string)($envelope['d'] ?? ''), true);
    if ($wrapped === false || $iv === false || $data === false || strlen($iv) !== 12 || strlen($data) < 17) {
        throw new RuntimeException('请求格式错误');
    }
    $key = '';
    if (!@openssl_private_decrypt($wrapped, $key, lh_private_key(), OPENSSL_PKCS1_OAEP_PADDING) || strlen($key) !== 32) {
        throw new RuntimeException('请求密钥无效');
    }
    $tag = substr($data, -16);
    $cipher = substr($data, 0, -16);
    $plain = @openssl_decrypt($cipher, 'aes-256-gcm', $key, OPENSSL_RAW_DATA, $iv, $tag);
    if ($plain === false) {
        throw new RuntimeException('请求解密失败');
    }
    $payload = json_decode($plain, true);
    if (!is_array($payload)) {
        throw new RuntimeException('请求内容错误');
    }
    return [$payload, $key];
}

function lh_encrypt_response(array $data, string $key): void
{
    $iv = random_bytes(12);
    $tag = '';
    $plain = json_encode($data, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES);
    $cipher = @openssl_encrypt($plain, 'aes-256-gcm', $key, OPENSSL_RAW_DATA, $iv, $tag);
    if ($cipher === false) {
        http_response_code(500);
        echo '{"ok":false,"message":"server encryption failed"}';
        exit;
    }
    header('Content-Type: application/json; charset=utf-8');
    header('Cache-Control: no-store');
    echo json_encode([
        'iv' => base64_encode($iv),
        'd' => base64_encode($cipher . $tag),
    ], JSON_UNESCAPED_UNICODE);
    exit;
}

function lh_api_error(string $key, string $message, int $status = 400): void
{
    http_response_code($status);
    lh_encrypt_response(['ok' => false, 'message' => $message], $key);
}

function lh_check_replay(PDO $pdo, array $payload): void
{
    $ts = (int)($payload['ts'] ?? 0);
    if ($ts <= 0 || abs(time() - $ts) > 300) {
        throw new RuntimeException('请求已过期，请校准手机时间');
    }
    $nonce = strtolower((string)($payload['nonce'] ?? ''));
    if (!preg_match('/^[a-f0-9]{32}$/', $nonce)) {
        throw new RuntimeException('请求标识无效');
    }
    $stmt = $pdo->prepare('INSERT IGNORE INTO lh_nonces (nonce, created_at) VALUES (?, ?)');
    $stmt->execute([$nonce, lh_now()]);
    if ($stmt->rowCount() !== 1) {
        throw new RuntimeException('重复请求');
    }
}

function lh_normalize_code(string $raw, string $prefix): string
{
    $value = strtoupper(preg_replace('/[^A-Za-z0-9]/', '', $raw));
    $prefix = strtoupper($prefix);
    if (strpos($value, $prefix) === 0) {
        $value = substr($value, strlen($prefix));
    }
    if (strlen($value) !== 12) {
        return '';
    }
    return $prefix . '-' . substr($value, 0, 4) . '-' . substr($value, 4, 4) . '-' . substr($value, 8, 4);
}

function lh_random_code(string $prefix): string
{
    $alphabet = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';
    $raw = '';
    for ($i = 0; $i < 12; $i++) {
        $raw .= $alphabet[random_int(0, strlen($alphabet) - 1)];
    }
    return strtoupper($prefix) . '-' . substr($raw, 0, 4) . '-' . substr($raw, 4, 4) . '-' . substr($raw, 8, 4);
}

/** 用私有密钥签发授权串；App 内置公钥，可离线验证真伪。 */
function lh_issue_license(string $code, string $device, string $fp, int $expire): string
{
    $payload = json_encode([
        'code' => $code,
        'device' => $device,
        'fp' => $fp,
        'issued' => time(),
        'expire' => $expire,
    ], JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES);
    $body = base64_encode($payload);
    $signature = '';
    if (!openssl_sign($body, $signature, lh_private_key(), OPENSSL_ALGO_SHA256)) {
        throw new RuntimeException('授权签发失败');
    }
    return $body . '.' . base64_encode($signature);
}

function lh_fail_count(PDO $pdo, string $device): int
{
    $stmt = $pdo->prepare("SELECT COUNT(*) FROM lh_events WHERE action='activate_fail' AND (device_id=? OR ip=?) AND created_at > ?");
    $stmt->execute([$device, lh_client_ip(), date('Y-m-d H:i:s', time() - 600)]);
    return (int)$stmt->fetchColumn();
}
