<?php
declare(strict_types=1);

require __DIR__ . '/lib.php';

header('Content-Type: application/json; charset=utf-8');

$raw = file_get_contents('php://input');
$envelope = json_decode((string)$raw, true);
if (!is_array($envelope)) {
    http_response_code(400);
    echo json_encode(['ok' => false, 'message' => '请求格式错误'], JSON_UNESCAPED_UNICODE);
    exit;
}

try {
    [$payload, $key] = lh_decrypt_request($envelope);
} catch (Throwable $e) {
    http_response_code(400);
    echo json_encode(['ok' => false, 'message' => $e->getMessage()], JSON_UNESCAPED_UNICODE);
    exit;
}

try {
    $pdo = lh_db();
    lh_check_replay($pdo, $payload);

    $action = (string)($payload['action'] ?? '');
    $device = strtolower((string)($payload['device'] ?? ''));
    $fp = strtolower((string)($payload['fp'] ?? ''));
    if (!preg_match('/^[a-f0-9]{64}$/', $device)) {
        lh_api_error($key, '设备标识无效');
    }
    if ($fp !== '' && !preg_match('/^[a-f0-9]{64}$/', $fp)) {
        lh_api_error($key, '设备特征无效');
    }

    if ($action === 'activate') {
        lh_activate($pdo, $payload, $device, $fp, $key);
    } elseif ($action === 'status') {
        lh_status($pdo, $device, $fp, $key);
    } else {
        lh_api_error($key, '未知操作');
    }
} catch (RuntimeException $e) {
    lh_api_error($key, $e->getMessage());
} catch (Throwable $e) {
    lh_api_error($key, '服务器繁忙，请稍后重试', 500);
}

function lh_activate(PDO $pdo, array $payload, string $device, string $fp, string $key): void
{
    $config = lh_config();
    $code = lh_normalize_code((string)($payload['code'] ?? ''), (string)$config['code_prefix']);
    if ($code === '') {
        lh_api_error($key, '激活码格式不正确');
    }
    if (lh_fail_count($pdo, $device) >= 10) {
        lh_api_error($key, '尝试次数过多，请 10 分钟后再试', 429);
    }

    $pdo->beginTransaction();
    try {
        $stmt = $pdo->prepare('SELECT * FROM lh_codes WHERE code = ? FOR UPDATE');
        $stmt->execute([$code]);
        $row = $stmt->fetch();
        if (!$row) {
            $pdo->rollBack();
            lh_event($pdo, $code, $device, 'activate_fail', 'not found');
            lh_api_error($key, '激活码不存在，请检查后重试');
        }
        if ((int)$row['status'] !== 1) {
            $pdo->rollBack();
            lh_event($pdo, $code, $device, 'activate_fail', 'disabled');
            lh_api_error($key, '该激活码已被禁用，请联系客服');
        }

        $now = time();
        $expire = $row['expire_at'] ? strtotime((string)$row['expire_at']) : 0;
        if ((string)$row['device_id'] === '') {
            $days = max(1, (int)$row['days']);
            $expire = $now + $days * 86400;
            $update = $pdo->prepare("UPDATE lh_codes SET device_id = ?, device_fp = ?, activated_at = ?, expire_at = ?, updated_at = ? WHERE id = ? AND device_id = ''");
            $update->execute([$device, $fp, lh_now(), date('Y-m-d H:i:s', $expire), lh_now(), $row['id']]);
            if ($update->rowCount() !== 1) {
                $pdo->rollBack();
                lh_api_error($key, '激活码刚刚被其他设备使用');
            }
        } elseif ((string)$row['device_id'] !== $device) {
            $pdo->rollBack();
            lh_event($pdo, $code, $device, 'activate_fail', 'bound to another device');
            lh_api_error($key, '该激活码已绑定其他设备，无法重复使用');
        } elseif ($expire <= $now) {
            $pdo->rollBack();
            lh_event($pdo, $code, $device, 'activate_fail', 'expired');
            lh_api_error($key, '该激活码已过期，请购买新的激活码');
        } else {
            // 同一设备重复输入：同步最新的设备特征，方便系统升级后继续使用。
            $update = $pdo->prepare('UPDATE lh_codes SET device_fp = ?, updated_at = ? WHERE id = ?');
            $update->execute([$fp, lh_now(), $row['id']]);
        }
        $pdo->commit();
    } catch (Throwable $e) {
        if ($pdo->inTransaction()) {
            $pdo->rollBack();
        }
        throw $e;
    }

    $license = lh_issue_license($code, $device, $fp, $expire);
    lh_event($pdo, $code, $device, 'activate_ok', 'expire ' . date('Y-m-d H:i:s', $expire));
    lh_encrypt_response(['ok' => true, 'license' => $license, 'expire' => $expire], $key);
}

function lh_status(PDO $pdo, string $device, string $fp, string $key): void
{
    $stmt = $pdo->prepare('SELECT * FROM lh_codes WHERE device_id = ? AND status = 1 AND expire_at > ? ORDER BY expire_at DESC LIMIT 1');
    $stmt->execute([$device, lh_now()]);
    $row = $stmt->fetch();
    if (!$row) {
        lh_encrypt_response(['ok' => true, 'licensed' => false], $key);
    }
    $expire = strtotime((string)$row['expire_at']);
    $license = lh_issue_license((string)$row['code'], $device, $fp, $expire);
    lh_event($pdo, (string)$row['code'], $device, 'status_ok');
    lh_encrypt_response([
        'ok' => true,
        'licensed' => true,
        'license' => $license,
        'expire' => $expire,
    ], $key);
}
