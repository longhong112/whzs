<?php
declare(strict_types=1);

require __DIR__ . '/lib.php';

$config = lh_config();
$self = (string)($_SERVER['SCRIPT_NAME'] ?? '/whzs/admin.php');
$dir = rtrim(str_replace('\\', '/', dirname($self)), '/');

session_set_cookie_params([
    'lifetime' => 0,
    'path' => $dir . '/',
    'httponly' => true,
    'samesite' => 'Lax',
]);
session_name('lh_admin');
session_start();

if (empty($_SESSION['csrf'])) {
    $_SESSION['csrf'] = bin2hex(random_bytes(16));
}
$csrf = (string)$_SESSION['csrf'];

function h($value): string
{
    return htmlspecialchars((string)$value, ENT_QUOTES, 'UTF-8');
}

function lh_admin_check(string $user, string $pass): bool
{
    $c = lh_config();
    if (!hash_equals((string)$c['admin_user'], $user)) {
        return false;
    }
    $saved = (string)$c['admin_password'];
    if (preg_match('/^\$2[aby]\$/', $saved)) {
        return password_verify($pass, $saved);
    }
    return hash_equals($saved, $pass);
}

function lh_redirect(string $target): void
{
    header('Location: ' . $target);
    exit;
}

$logged = !empty($_SESSION['lh_admin']);
$loginError = '';

// ---------------------------------------------------------------- 登录
if (!$logged && isset($_POST['login'])) {
    if (!hash_equals($csrf, (string)($_POST['csrf'] ?? ''))) {
        $loginError = '页面已过期，请重试';
    } elseif ((int)($_SESSION['lock_until'] ?? 0) > time()) {
        $loginError = '尝试次数过多，请稍后再试';
    } else {
        $user = trim((string)($_POST['user'] ?? ''));
        $pass = (string)($_POST['pass'] ?? '');
        if (lh_admin_check($user, $pass)) {
            $_SESSION['lh_admin'] = $user;
            $_SESSION['fails'] = 0;
            unset($_SESSION['lock_until']);
            lh_redirect($self);
        }
        $_SESSION['fails'] = (int)($_SESSION['fails'] ?? 0) + 1;
        if ($_SESSION['fails'] >= 5) {
            $_SESSION['lock_until'] = time() + 600;
            $_SESSION['fails'] = 0;
        }
        $loginError = '账号或密码不正确';
    }
}

if (!$logged) {
    ?>
<!doctype html>
<html lang="zh-CN">
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>登录 · <?= h($config['site_name']) ?></title>
<style>
*{box-sizing:border-box}
body{margin:0;min-height:100vh;display:flex;align-items:center;justify-content:center;background:linear-gradient(150deg,#eef4fe,#dbe8fb);font:15px/1.6 -apple-system,"Microsoft YaHei",sans-serif;color:#10263f}
.card{width:min(380px,92vw);background:#fff;border:1px solid #e4edf9;border-radius:20px;padding:30px 26px;box-shadow:0 18px 40px #2359c61a}
h1{font-size:21px;margin:0 0 6px}
p.hint{color:#5c7695;font-size:13px;margin:0 0 20px}
label{display:block;font-size:13px;color:#5c7695;margin:14px 0 6px}
input{width:100%;padding:12px 13px;border:1px solid #dbe6f5;border-radius:12px;font-size:15px;background:#f7faff;outline:none}
input:focus{border-color:#5c97ff;background:#fff}
button{width:100%;margin-top:20px;padding:13px;border:0;border-radius:12px;background:linear-gradient(90deg,#5c97ff,#2f6fe4);color:#fff;font-size:15px;font-weight:600;cursor:pointer}
.err{margin-top:14px;padding:10px 12px;border-radius:10px;background:#fbe4e4;color:#b03a3a;font-size:13px}
</style>
<form class="card" method="post">
    <h1><?= h($config['site_name']) ?></h1>
    <p class="hint">一机一码 · 授权管理入口</p>
    <input type="hidden" name="csrf" value="<?= h($csrf) ?>">
    <input type="hidden" name="login" value="1">
    <label>管理员账号</label>
    <input name="user" autocomplete="username" required>
    <label>密码</label>
    <input name="pass" type="password" autocomplete="current-password" required>
    <button type="submit">登录后台</button>
    <?php if (!empty($loginError)): ?><div class="err"><?= h($loginError) ?></div><?php endif; ?>
</form>
</body>
</html>
    <?php
    exit;
}

$pdo = lh_db();

// ---------------------------------------------------------------- 退出
if (isset($_GET['logout'])) {
    $_SESSION = [];
    session_destroy();
    lh_redirect($self);
}

// ---------------------------------------------------------------- 操作
if ($_SERVER['REQUEST_METHOD'] === 'POST' && isset($_POST['action'])) {
    if (!hash_equals($csrf, (string)($_POST['csrf'] ?? ''))) {
        $_SESSION['flash'] = ['error', '页面已过期，请重试'];
        lh_redirect($self);
    }
    $action = (string)$_POST['action'];
    $id = (int)($_POST['id'] ?? 0);
    try {
        if ($action === 'generate') {
            $count = min(200, max(1, (int)($_POST['count'] ?? 10)));
            $days = min(3650, max(1, (int)($_POST['days'] ?? 30)));
            $note = trim((string)($_POST['note'] ?? ''));
            $prefix = (string)$config['code_prefix'];
            $batch = date('Ymd-His');
            $now = lh_now();
            $insert = $pdo->prepare('INSERT INTO lh_codes (code, batch, days, status, note, created_at, updated_at) VALUES (?,?,?,1,?,?,?)');
            $created = 0;
            $pdo->beginTransaction();
            while ($created < $count) {
                $code = lh_random_code($prefix);
                try {
                    $insert->execute([$code, $batch, $days, $note, $now, $now]);
                    $created++;
                } catch (PDOException $duplicate) {
                    if ((string)$duplicate->getCode() !== '23000') {
                        throw $duplicate;
                    }
                }
            }
            $pdo->commit();
            $_SESSION['flash'] = ['ok', "已生成 $created 个激活码，有效期 $days 天"];
        } elseif ($action === 'toggle' && $id > 0) {
            $pdo->prepare('UPDATE lh_codes SET status = 1 - status, updated_at = ? WHERE id = ?')->execute([lh_now(), $id]);
            $_SESSION['flash'] = ['ok', '状态已切换'];
        } elseif ($action === 'unbind' && $id > 0) {
            $pdo->prepare("UPDATE lh_codes SET device_id = '', device_fp = '', activated_at = NULL, expire_at = NULL, updated_at = ? WHERE id = ?")
                ->execute([lh_now(), $id]);
            $_SESSION['flash'] = ['ok', '已解绑设备，可重新激活'];
        } elseif ($action === 'extend' && $id > 0) {
            $extra = min(3650, max(1, (int)($_POST['days'] ?? 30)));
            $stmt = $pdo->prepare('SELECT expire_at FROM lh_codes WHERE id = ?');
            $stmt->execute([$id]);
            $current = (string)($stmt->fetchColumn() ?: '');
            $base = $current !== '' ? max(time(), (int)strtotime($current)) : time();
            $expireAt = date('Y-m-d H:i:s', $base + $extra * 86400);
            $pdo->prepare('UPDATE lh_codes SET expire_at = ?, updated_at = ? WHERE id = ?')
                ->execute([$expireAt, lh_now(), $id]);
            $_SESSION['flash'] = ['ok', "已延长 $extra 天"];
        } elseif ($action === 'delete' && $id > 0) {
            $pdo->prepare('DELETE FROM lh_codes WHERE id = ?')->execute([$id]);
            $_SESSION['flash'] = ['ok', '记录已删除'];
        }
    } catch (Throwable $e) {
        if ($pdo->inTransaction()) {
            $pdo->rollBack();
        }
        $_SESSION['flash'] = ['error', '操作失败：' . $e->getMessage()];
    }
    lh_redirect($self . (isset($_POST['back']) ? '?' . (string)$_POST['back'] : ''));
}

// ---------------------------------------------------------------- 查询
$q = trim((string)($_GET['q'] ?? ''));
$state = (string)($_GET['state'] ?? '');
$page = max(1, (int)($_GET['page'] ?? 1));
$perPage = 20;
$where = [];
$params = [];
if ($q !== '') {
    $where[] = '(code LIKE ? OR device_id LIKE ? OR note LIKE ?)';
    $params[] = "%$q%";
    $params[] = "%$q%";
    $params[] = "%$q%";
}
$now = lh_now();
switch ($state) {
    case 'unused':
        $where[] = "device_id = ''";
        break;
    case 'bound':
        $where[] = "device_id <> '' AND status = 1 AND expire_at > ?";
        $params[] = $now;
        break;
    case 'expired':
        $where[] = 'expire_at IS NOT NULL AND expire_at <= ?';
        $params[] = $now;
        break;
    case 'disabled':
        $where[] = 'status = 0';
        break;
}
$whereSql = $where ? ' WHERE ' . implode(' AND ', $where) : '';

$stat = $pdo->query("SELECT COUNT(*) AS total,
    SUM(device_id <> '') AS bound,
    SUM(device_id = '') AS unused,
    SUM(status = 0) AS disabled FROM lh_codes")->fetch() ?: [];
$expiredStmt = $pdo->prepare('SELECT COUNT(*) FROM lh_codes WHERE expire_at IS NOT NULL AND expire_at <= ?');
$expiredStmt->execute([$now]);
$expired = (int)$expiredStmt->fetchColumn();

$countStmt = $pdo->prepare('SELECT COUNT(*) FROM lh_codes' . $whereSql);
$countStmt->execute($params);
$filtered = (int)$countStmt->fetchColumn();
$pages = max(1, (int)ceil($filtered / $perPage));
$page = min($page, $pages);
$offset = ($page - 1) * $perPage;
$listStmt = $pdo->prepare('SELECT * FROM lh_codes' . $whereSql . ' ORDER BY id DESC LIMIT ' . $perPage . ' OFFSET ' . $offset);
$listStmt->execute($params);
$rows = $listStmt->fetchAll();

// ---------------------------------------------------------------- 导出
if (isset($_GET['export'])) {
    $exportStmt = $pdo->prepare('SELECT * FROM lh_codes' . $whereSql . ' ORDER BY id DESC LIMIT 20000');
    $exportStmt->execute($params);
    header('Content-Type: text/csv; charset=utf-8');
    header('Content-Disposition: attachment; filename="lh-codes-' . date('Ymd-His') . '.csv"');
    echo "\xEF\xBB\xBF";
    $out = fopen('php://output', 'w');
    fputcsv($out, ['激活码', '天数', '状态', '设备编号', '激活时间', '到期时间', '备注', '创建时间']);
    foreach ($exportStmt->fetchAll() as $row) {
        fputcsv($out, [
            $row['code'], $row['days'],
            (int)$row['status'] === 0 ? '禁用' : ((string)$row['device_id'] === '' ? '未使用' : '已绑定'),
            $row['device_id'], $row['activated_at'], $row['expire_at'], $row['note'], $row['created_at'],
        ]);
    }
    fclose($out);
    exit;
}

$flash = $_SESSION['flash'] ?? null;
unset($_SESSION['flash']);
$backQuery = http_build_query(array_filter(['q' => $q, 'state' => $state, 'page' => $page > 1 ? $page : null]));
?>
<!doctype html>
<html lang="zh-CN">
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title><?= h($config['site_name']) ?></title>
<style>
*{box-sizing:border-box}
body{margin:0;background:#f7faff;color:#10263f;font:14px/1.6 -apple-system,"Microsoft YaHei",sans-serif}
header{background:linear-gradient(120deg,#2f6fe4,#5c97ff);color:#fff;padding:20px 24px;display:flex;align-items:center;gap:14px;flex-wrap:wrap}
header h1{font-size:19px;margin:0;font-weight:600}
header .sub{opacity:.85;font-size:12px}
header nav{margin-left:auto;display:flex;gap:10px;flex-wrap:wrap}
header a{color:#fff;text-decoration:none;font-size:13px;padding:7px 13px;border:1px solid #ffffff55;border-radius:9px}
main{max-width:1180px;margin:0 auto;padding:22px 16px 60px}
.stats{display:grid;grid-template-columns:repeat(auto-fit,minmax(140px,1fr));gap:12px;margin-bottom:18px}
.stat{background:#fff;border:1px solid #e4edf9;border-radius:14px;padding:14px 16px}
.stat b{display:block;font-size:24px;line-height:1.2}
.stat span{color:#5c7695;font-size:12px}
.panel{background:#fff;border:1px solid #e4edf9;border-radius:16px;padding:18px;margin-bottom:18px}
.panel h2{font-size:15px;margin:0 0 12px}
form.inline{display:flex;gap:10px;flex-wrap:wrap;align-items:flex-end}
form.inline label{display:flex;flex-direction:column;font-size:12px;color:#5c7695;gap:5px}
input,select{padding:9px 11px;border:1px solid #dbe6f5;border-radius:9px;background:#f9fbff;font-size:13px;color:#10263f;outline:none}
input:focus,select:focus{border-color:#5c97ff;background:#fff}
button{padding:9px 16px;border:0;border-radius:9px;background:#2f6fe4;color:#fff;font-size:13px;cursor:pointer}
button.ghost{background:#eef4fe;color:#2f6fe4}
button.danger{background:#fbe4e4;color:#b03a3a}
table{width:100%;border-collapse:collapse;font-size:13px}
th,td{padding:10px 8px;text-align:left;border-bottom:1px solid #eef4fe;vertical-align:top}
th{color:#5c7695;font-weight:600;font-size:12px;white-space:nowrap}
code{background:#eef4fe;padding:2px 6px;border-radius:6px;font-size:12px}
.tag{display:inline-block;padding:2px 9px;border-radius:20px;font-size:12px}
.tag.ok{background:#ddf1e7;color:#1b7f5a}
.tag.off{background:#fbe4e4;color:#b03a3a}
.tag.wait{background:#e4eefb;color:#4a617f}
.tag.warn{background:#fbeed3;color:#9a6300}
.actions{display:flex;gap:6px;flex-wrap:wrap}
.actions button{padding:5px 9px;font-size:12px}
.muted{color:#5c7695;font-size:12px}
.flash{padding:11px 14px;border-radius:11px;margin-bottom:16px;font-size:13px}
.flash.ok{background:#ddf1e7;color:#1b7f5a}
.flash.error{background:#fbe4e4;color:#b03a3a}
.pager{display:flex;gap:8px;align-items:center;justify-content:flex-end;margin-top:14px;font-size:13px}
.pager a{padding:6px 12px;border:1px solid #dbe6f5;border-radius:8px;color:#2f6fe4;text-decoration:none}
.empty{text-align:center;color:#5c7695;padding:34px 0}
@media(max-width:720px){th:nth-child(4),td:nth-child(4),th:nth-child(8),td:nth-child(8){display:none}}
</style>
<header>
    <div>
        <h1><?= h($config['site_name']) ?></h1>
        <div class="sub">一机一码 · 按天授权 · 加密传输</div>
    </div>
    <nav>
        <a href="?export=1&amp;<?= h($backQuery) ?>">导出 CSV</a>
        <a href="?logout=1">退出</a>
    </nav>
</header>
<main>
<?php if ($flash): ?>
    <div class="flash <?= h($flash[0]) ?>"><?= h($flash[1]) ?></div>
<?php endif; ?>

<div class="stats">
    <div class="stat"><b><?= (int)($stat['total'] ?? 0) ?></b><span>激活码总数</span></div>
    <div class="stat"><b><?= (int)($stat['bound'] ?? 0) ?></b><span>已绑定设备</span></div>
    <div class="stat"><b><?= (int)($stat['unused'] ?? 0) ?></b><span>未使用</span></div>
    <div class="stat"><b><?= $expired ?></b><span>已过期</span></div>
    <div class="stat"><b><?= (int)($stat['disabled'] ?? 0) ?></b><span>已禁用</span></div>
</div>

<div class="panel">
    <h2>批量生成激活码</h2>
    <form class="inline" method="post">
        <input type="hidden" name="csrf" value="<?= h($csrf) ?>">
        <input type="hidden" name="action" value="generate">
        <input type="hidden" name="back" value="<?= h($backQuery) ?>">
        <label>数量（1-200）<input name="count" type="number" min="1" max="200" value="10" required></label>
        <label>有效天数<input name="days" type="number" min="1" max="3650" value="30" required></label>
        <label>备注<input name="note" maxlength="80" placeholder="例如：渠道A / 老用户续费"></label>
        <button type="submit">生成</button>
    </form>
    <p class="muted">激活码格式为 <?= h((string)$config['code_prefix']) ?>-XXXX-XXXX-XXXX，生成后自动去重，有效期从首次激活时开始计算。</p>
</div>

<div class="panel">
    <form class="inline" method="get">
        <label>搜索<input name="q" value="<?= h($q) ?>" placeholder="激活码 / 设备号 / 备注"></label>
        <label>状态
            <select name="state">
                <option value="" <?= $state === '' ? 'selected' : '' ?>>全部</option>
                <option value="unused" <?= $state === 'unused' ? 'selected' : '' ?>>未使用</option>
                <option value="bound" <?= $state === 'bound' ? 'selected' : '' ?>>已绑定</option>
                <option value="expired" <?= $state === 'expired' ? 'selected' : '' ?>>已过期</option>
                <option value="disabled" <?= $state === 'disabled' ? 'selected' : '' ?>>已禁用</option>
            </select>
        </label>
        <button type="submit">筛选</button>
        <?php if ($q !== '' || $state !== ''): ?><a class="muted" href="<?= h($self) ?>">清空</a><?php endif; ?>
    </form>
</div>

<div class="panel">
    <h2>激活码列表（<?= $filtered ?>）</h2>
    <?php if (!$rows): ?>
        <div class="empty">没有符合条件的记录</div>
    <?php else: ?>
    <table>
        <thead><tr>
            <th>激活码</th><th>状态</th><th>设备</th><th>到期时间</th><th>备注</th><th>操作</th>
        </tr></thead>
        <tbody>
        <?php foreach ($rows as $row):
            $isDisabled = (int)$row['status'] === 0;
            $isBound = (string)$row['device_id'] !== '';
            $isExpired = $isBound && $row['expire_at'] !== null && strtotime((string)$row['expire_at']) <= time();
            [$tagClass, $tagText] = $isDisabled ? ['off', '已禁用']
                : (!$isBound ? ['wait', '未使用'] : ($isExpired ? ['warn', '已过期'] : ['ok', '已绑定']));
            ?>
            <tr>
                <td><code><?= h($row['code']) ?></code><div class="muted"><?= (int)$row['days'] ?> 天 · <?= h($row['batch']) ?></div></td>
                <td><span class="tag <?= $tagClass ?>"><?= $tagText ?></span></td>
                <td class="muted"><?= $isBound ? h(substr((string)$row['device_id'], 0, 16)) . '…' : '—' ?></td>
                <td><?= $row['expire_at'] ? h((string)$row['expire_at']) : '<span class="muted">未激活</span>' ?></td>
                <td class="muted"><?= h((string)$row['note']) ?></td>
                <td>
                    <div class="actions">
                        <form method="post" onsubmit="return confirm('确定执行该操作？')">
                            <input type="hidden" name="csrf" value="<?= h($csrf) ?>">
                            <input type="hidden" name="back" value="<?= h($backQuery) ?>">
                            <input type="hidden" name="id" value="<?= (int)$row['id'] ?>">
                            <input type="hidden" name="action" value="toggle">
                            <button class="ghost" type="submit"><?= $isDisabled ? '启用' : '禁用' ?></button>
                        </form>
                        <?php if ($isBound): ?>
                        <form method="post" onsubmit="return confirm('解绑后该设备将失去授权，确定解绑？')">
                            <input type="hidden" name="csrf" value="<?= h($csrf) ?>">
                            <input type="hidden" name="back" value="<?= h($backQuery) ?>">
                            <input type="hidden" name="id" value="<?= (int)$row['id'] ?>">
                            <input type="hidden" name="action" value="unbind">
                            <button class="ghost" type="submit">解绑</button>
                        </form>
                        <?php endif; ?>
                        <?php if ($isBound): ?>
                        <form method="post">
                            <input type="hidden" name="csrf" value="<?= h($csrf) ?>">
                            <input type="hidden" name="back" value="<?= h($backQuery) ?>">
                            <input type="hidden" name="id" value="<?= (int)$row['id'] ?>">
                            <input type="hidden" name="action" value="extend">
                            <input type="hidden" name="days" value="30">
                            <button class="ghost" type="submit">+30 天</button>
                        </form>
                        <?php endif; ?>
                        <form method="post" onsubmit="return confirm('删除后不可恢复，确定删除？')">
                            <input type="hidden" name="csrf" value="<?= h($csrf) ?>">
                            <input type="hidden" name="back" value="<?= h($backQuery) ?>">
                            <input type="hidden" name="id" value="<?= (int)$row['id'] ?>">
                            <input type="hidden" name="action" value="delete">
                            <button class="danger" type="submit">删除</button>
                        </form>
                    </div>
                </td>
            </tr>
        <?php endforeach; ?>
        </tbody>
    </table>
    <?php if ($pages > 1): ?>
    <div class="pager">
        <?php $link = function (int $target) use ($self, $q, $state): string {
            return $self . '?' . http_build_query(array_filter(['q' => $q, 'state' => $state, 'page' => $target > 1 ? $target : null]));
        }; ?>
        <?php if ($page > 1): ?><a href="<?= h($link($page - 1)) ?>">上一页</a><?php endif; ?>
        <span class="muted">第 <?= $page ?> / <?= $pages ?> 页</span>
        <?php if ($page < $pages): ?><a href="<?= h($link($page + 1)) ?>">下一页</a><?php endif; ?>
    </div>
    <?php endif; ?>
    <?php endif; ?>
</div>

<div class="panel">
    <h2>最近激活记录</h2>
    <?php
    $events = $pdo->query('SELECT * FROM lh_events ORDER BY id DESC LIMIT 12')->fetchAll();
    if (!$events) {
        echo '<div class="empty">暂无记录</div>';
    } else {
        echo '<table><thead><tr><th>时间</th><th>动作</th><th>激活码</th><th>设备</th><th>说明</th></tr></thead><tbody>';
        foreach ($events as $event) {
            echo '<tr><td class="muted">' . h($event['created_at']) . '</td><td>' . h($event['action'])
                . '</td><td><code>' . h($event['code']) . '</code></td><td class="muted">'
                . h(substr((string)$event['device_id'], 0, 16)) . '</td><td class="muted">' . h($event['detail']) . '</td></tr>';
        }
        echo '</tbody></table>';
    }
    ?>
</div>
</main>
</html>
