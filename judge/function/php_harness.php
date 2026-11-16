<?php
// 제출 main.php: <?php function sol(array $matrix, array $target): bool { ... }
declare(strict_types=1);

$values = preg_split('/\s+/', trim(stream_get_contents(STDIN)));
if (count($values) !== 227) {
    throw new RuntimeException('Invalid FUNCTION input');
}
$values = array_map('intval', $values);
$matrix = array_chunk(array_slice($values, 0, 225), 15);
$target = array_slice($values, 225, 2);
require '/source/main.php';
$result = sol($matrix, $target);
if (!is_bool($result)) {
    throw new RuntimeException('sol must return bool');
}
echo $result ? "true\n" : "false\n";
