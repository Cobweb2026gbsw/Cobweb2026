"""Lv.3-5: 그리디·이진 탐색·격자 상태 탐색의 고정 저작 명세."""
from collections import deque
from functools import lru_cache
import heapq
import itertools
import random


def selected_meetings(meetings):
    best = None
    for mask in range(1 << len(meetings)):
        ordered = sorted((end, start, i) for i, (start, end) in enumerate(meetings) if mask >> i & 1)
        if any(ordered[i][1] < ordered[i - 1][0] for i in range(1, len(ordered))):
            continue
        key = (-len(ordered), ordered)
        if best is None or key < best:
            best = key
    return [[-best[0]], [item[2] for item in best[1]]]


def jump_path(numbers):
    @lru_cache(None)
    def paths(position):
        if position == len(numbers) - 1:
            return (position,)
        candidates = []
        for nxt in range(position + 1, min(len(numbers), position + numbers[position] + 1)):
            tail = paths(nxt)
            if tail:
                candidates.append((position,) + tail)
        return min(candidates, key=lambda p: (len(p), p)) if candidates else ()
    return list(paths(0))


def covering(intervals, left, right):
    for size in range(1, len(intervals) + 1):
        for chosen in itertools.combinations(intervals, size):
            reach = left
            for start, end in sorted(chosen):
                if start > reach:
                    break
                reach = max(reach, end)
            if reach >= right:
                return size
    return -1


def merged_containing(intervals, point):
    # 정렬 스캔 대신 교차하는 구간들의 연결 요소를 구성한다.
    seen = set()
    for i in range(len(intervals)):
        if i in seen:
            continue
        stack, group = [i], []
        seen.add(i)
        while stack:
            u = stack.pop()
            group.append(u)
            for v in range(len(intervals)):
                if v not in seen and max(intervals[u][0], intervals[v][0]) <= min(intervals[u][1], intervals[v][1]):
                    seen.add(v)
                    stack.append(v)
        left = min(intervals[v][0] for v in group)
        right = max(intervals[v][1] for v in group)
        if left <= point <= right:
            return [left, right]
    return []


def minimum_trips(weights, limit):
    @lru_cache(None)
    def search(mask):
        if not mask:
            return 0
        first = (mask & -mask).bit_length() - 1
        rest = mask ^ (1 << first)
        best = 1 + search(rest)
        for j in range(first + 1, len(weights)):
            if rest >> j & 1 and weights[first] + weights[j] <= limit:
                best = min(best, 1 + search(rest ^ (1 << j)))
        return best
    return search((1 << len(weights)) - 1)


def cable_length(lengths, count):
    if len(lengths) == 1:
        return lengths[0] // count
    return max((length for length in range(1, max(lengths) + 1) if sum(value // length for value in lengths) >= count), default=0)


def wood_height(heights, need):
    if len(heights) == 1:
        return heights[0] - need if heights[0] >= need else -1
    return max((height for height in range(max(heights) + 1) if sum(max(0, tree - height) for tree in heights) >= need), default=-1)


def router_distance(houses, count):
    return max(min(b - a for a, b in zip(chosen, chosen[1:])) for chosen in itertools.combinations(sorted(houses), count))


def merge_cost(cards):
    @lru_cache(None)
    def choose(state):
        if len(state) <= 1:
            return 0
        best = None
        for i in range(len(state)):
            for j in range(i + 1, len(state)):
                cost = state[i] + state[j]
                rest = tuple(sorted([state[k] for k in range(len(state)) if k not in (i, j)] + [cost]))
                value = cost + choose(rest)
                best = value if best is None else min(best, value)
        return best
    return choose(tuple(sorted(cards)))


def average_completion(jobs):
    if not jobs:
        return 0.0
    def total(order):
        return sum(sum(order[:i + 1]) for i in range(len(order)))
    return min(total(order) for order in itertools.permutations(jobs)) / len(jobs)


STEPS = ((-1, 0), (0, 1), (1, 0), (0, -1))


def robot_distance(grid, start, target):
    # 전체 상태 그래프를 먼저 만들고 Dijkstra로 계산한다(기준 풀이는 암시적 BFS).
    graph = {}
    for row in range(len(grid)):
        for col in range(len(grid[0])):
            if grid[row][col]:
                continue
            for direction, (dr, dc) in enumerate(STEPS):
                neighbors = [(row, col, (direction - 1) % 4), (row, col, (direction + 1) % 4)]
                for distance in range(1, 4):
                    nr, nc = row + dr * distance, col + dc * distance
                    if not (0 <= nr < len(grid) and 0 <= nc < len(grid[0])) or grid[nr][nc]:
                        break
                    neighbors.append((nr, nc, direction))
                graph[row, col, direction] = neighbors
    source, goal = tuple(start), tuple(target)
    costs, queue = {source: 0}, [(0, source)]
    while queue:
        cost, node = heapq.heappop(queue)
        if node == goal:
            return cost
        if costs[node] != cost:
            continue
        for nxt in graph[node]:
            if cost + 1 < costs.get(nxt, 10**9):
                costs[nxt] = cost + 1
                heapq.heappush(queue, (cost + 1, nxt))
    return -1


def broken_maze(grid, start, target):
    # 벽을 제거하지 않은 경우와 제거할 벽을 각각 정한 경우의 일반 BFS를 비교한다.
    walls = [None] + [(r, c) for r, row in enumerate(grid) for c, value in enumerate(row) if value]
    best = None
    for removed in walls:
        queue, seen = deque([(tuple(start), 0)]), {tuple(start)}
        while queue:
            (row, col), distance = queue.popleft()
            if (row, col) == tuple(target):
                best = distance if best is None else min(best, distance)
                break
            for dr, dc in STEPS:
                nr, nc = row + dr, col + dc
                if 0 <= nr < len(grid) and 0 <= nc < len(grid[0]) and (nr, nc) not in seen and (grid[nr][nc] == 0 or removed == (nr, nc)):
                    seen.add((nr, nc))
                    queue.append(((nr, nc), distance + 1))
    return -1 if best is None else best


def safe_area(grid):
    empty = [(r, c) for r, row in enumerate(grid) for c, value in enumerate(row) if value == 0]
    virus = {(r, c) for r, row in enumerate(grid) for c, value in enumerate(row) if value == 2}
    best = 0
    for walls in itertools.combinations(empty, 3):
        free = set(empty) - set(walls)
        reached = set(virus)
        while True:
            added = {(r + dr, c + dc) for r, c in reached for dr, dc in STEPS if (r + dr, c + dc) in free} - reached
            if not added:
                break
            reached |= added
        best = max(best, len(free - reached))
    return best


def populate(add):
    rng = random.Random(2026102805)
    meetings = [[], [[0, 1]], [[0, 1], [1, 2]], [[0, 3], [1, 2], [2, 3]], [[0, 2], [0, 2]], [[3, 4], [0, 1], [1, 3]], [[0, 10], [1, 9], [2, 8]]]
    for _ in range(10):
        meetings.append([[start, start + rng.randint(1, 5)] for start in [rng.randrange(12) for _ in range(rng.randrange(2, 8))]])
    add("최대 회의 개수 1", [("meetings", "int[][]")], "int[][]",
        "각 회의는 `[시작 시각, 종료 시각]`이고 회의 번호는 입력 인덱스입니다. 하나의 회의실에서 겹치지 않게 참석할 최대 회의 수와 선택 번호를 `[[개수], [회의 번호들]]`로 반환하세요. 종료 시각과 다음 시작 시각이 같으면 이어서 참석할 수 있습니다. 결과를 하나로 정하기 위해 종료 시각, 시작 시각, 번호 순으로 오름차순 정렬한 뒤, 직전 선택 회의가 끝난 이후 시작하는 회의를 차례로 선택합니다. 이 탐욕 선택의 참석 순서를 반환하세요. 빈 입력은 `[[0], []]`입니다.",
        "- 0 ≤ 회의 수 ≤ 100000\n- 0 ≤ 시작 시각 < 종료 시각 ≤ 1000000000\n- 동일 시각의 서로 다른 회의도 각각 별도 회의입니다.",
        """ordered = sorted((end, start, i) for i, (start, end) in enumerate(meetings))
chosen = []
finished = -1
for end, start, index in ordered:
    if start >= finished:
        chosen.append(index)
        finished = end
return [[len(chosen)], chosen]""", [[m] for m in meetings], selected_meetings)
    add("회의실 배정", [("meetings", "int[][]")], "int",
        "모든 회의를 진행하는 데 필요한 최소 회의실 수를 반환하세요. 각 회의는 `[시작, 종료]`이며 끝난 시각에 시작하는 회의는 같은 방을 바로 사용할 수 있습니다. 각 회의는 하나의 방을 처음부터 끝까지 사용합니다. 회의가 없으면 0입니다.",
        "- 0 ≤ 회의 수 ≤ 100000\n- 0 ≤ 시작 < 종료 ≤ 1000000000",
        """import heapq
rooms = []
best = 0
for start, end in sorted(meetings):
    while rooms and rooms[0] <= start:
        heapq.heappop(rooms)
    heapq.heappush(rooms, end)
    best = max(best, len(rooms))
return best""", [[m] for m in meetings], lambda m: max((sum(a <= t < b for a, b in m) for t, _ in m), default=0))
    jumps = [[0], [1], [1, 0], [0, 1], [2, 3, 1, 1, 4], [3, 2, 1, 0, 4], [2, 2, 1, 0], [3, 1, 3, 1, 1, 1], [1, 1, 1, 1]]
    jumps += [[rng.randrange(5) for _ in range(rng.randint(2, 9))] for _ in range(12)]
    add("최소 점프 횟수", [("numbers", "int[]")], "int",
        "인덱스 0에서 시작합니다. 인덱스 i에서는 오른쪽으로 1칸 이상 `numbers[i]`칸 이하만큼 점프할 수 있습니다. 마지막 인덱스에 도달하는 최소 점프 횟수를 반환하세요. 도달할 수 없으면 -1이고, 길이가 1이면 0입니다.",
        "- 1 ≤ 배열 길이 ≤ 100000\n- 0 ≤ numbers[i] ≤ 100000",
        """if len(numbers) == 1:
    return 0
end = farthest = count = 0
for i in range(len(numbers) - 1):
    farthest = max(farthest, i + numbers[i])
    if i == end:
        if farthest <= end:
            return -1
        count += 1
        end = farthest
        if end >= len(numbers) - 1:
            return count
return -1""", [[a] for a in jumps], lambda a: len(jump_path(a)) - 1 if jump_path(a) else -1)
    add("최소 점프 횟수 1", [("numbers", "int[]")], "int[][]",
        "인덱스 0에서 오른쪽으로 1~`numbers[i]`칸 점프합니다. 마지막 칸까지의 도달 여부와 최소 점프 경로를 `[[도달 여부], [방문 인덱스들]]`로 반환하세요. 도달 여부는 가능하면 1, 불가능하면 0입니다. 경로에는 시작 0과 마지막 인덱스가 포함됩니다. 최소 점프 경로가 여럿이면 인덱스 배열이 사전순으로 가장 작은 경로를 선택하세요. 불가능하면 `[[0], []]`, 길이가 1이면 `[[1], [0]]`입니다.",
        "- 1 ≤ 배열 길이 ≤ 100000\n- 0 ≤ numbers[i] ≤ 100000\n- 배열의 사전순은 처음 다른 인덱스 값으로 비교합니다.",
        """from collections import deque
n = len(numbers)
parent = [-1] * n
queue = deque([0])
farthest = 0
while queue:
    i = queue.popleft()
    if i == n - 1:
        path = []
        while i != -1:
            path.append(i)
            i = parent[i]
        return [[1], path[::-1]]
    limit = min(n - 1, i + numbers[i])
    for nxt in range(farthest + 1, limit + 1):
        parent[nxt] = i
        queue.append(nxt)
    farthest = max(farthest, limit)
return [[0], []]""", [[a] for a in jumps], lambda a: [[1], p] if (p := jump_path(a)) else [[0], []])
    intervals = [[], [[0, 5]], [[0, 2], [2, 5]], [[0, 2], [3, 5]], [[-2, 1], [1, 3], [2, 6]], [[0, 4], [0, 2], [3, 5]], [[1, 7]], [[0, 1], [1, 2], [2, 3], [3, 5]]]
    intervals += [[[start, start + rng.randint(1, 5)] for start in [rng.randrange(-2, 7) for _ in range(rng.randint(1, 8))]] for _ in range(10)]
    cover_cases = [[a, 0, 5] for a in intervals] + [[[[0, 2]], 0, 2], [[[0, 2]], -1, 2]]
    add("구간 덮기", [("intervals", "int[][]"), ("left", "int"), ("right", "int")], "int",
        "닫힌 구간 `[시작, 끝]` 중 일부를 선택해 목표 닫힌 구간 `[left, right]`의 모든 실수 위치를 덮으세요. 필요한 최소 선택 개수를 반환하고 불가능하면 -1을 반환하세요. 끝점에서 맞닿은 구간은 이어 붙일 수 있지만, 정수점만 덮고 사이의 실수 구간이 비면 실패입니다.",
        "- 0 ≤ 구간 수 ≤ 100000\n- -1000000000 ≤ 각 시작 < 각 끝 ≤ 1000000000\n- -1000000000 ≤ left < right ≤ 1000000000",
        """ordered = sorted(intervals)
i = count = 0
reached = left
while reached < right:
    next_reach = reached
    while i < len(ordered) and ordered[i][0] <= reached:
        next_reach = max(next_reach, ordered[i][1])
        i += 1
    if next_reach == reached:
        return -1
    reached = next_reach
    count += 1
return count""", cover_cases, covering)
    merge_cases = [[a, p] for a, p in zip(intervals, [0, 0, 2, 2, 6, 5, 0, 4] + [rng.randrange(-3, 10) for _ in range(10)])]
    merge_cases += [[[[2, 2], [3, 4]], 2], [[[0, 1], [2, 3]], 1]]
    add("구간 병합 1", [("intervals", "int[][]"), ("point", "int")], "int[]",
        "주어진 닫힌 구간들을 겹치거나 끝점이 같으면 병합하세요. 병합 결과 중 `point`를 포함하는 구간을 `[시작, 끝]`으로 반환하고, 없으면 빈 배열을 반환하세요. 구간 `[2,2]`처럼 한 점으로 된 구간도 허용합니다. 1과 2는 서로 다른 끝점이므로 `[0,1]`과 `[2,3]`은 병합하지 않습니다.",
        "- 0 ≤ 구간 수 ≤ 100000\n- -1000000000 ≤ 시작 ≤ 끝 ≤ 1000000000\n- -1000000000 ≤ point ≤ 1000000000",
        """merged = []
for start, end in sorted(intervals):
    if merged and start <= merged[-1][1]:
        merged[-1][1] = max(merged[-1][1], end)
    else:
        merged.append([start, end])
for start, end in merged:
    if start <= point <= end:
        return [start, end]
return []""", merge_cases, merged_containing)
    boxes = [[[], 10], [[10], 10], [[5, 5], 10], [[6, 5], 10], [[7, 3, 5, 2], 10], [[1, 1, 1], 2], [[10, 10, 10], 10]]
    boxes += [[[rng.randint(1, 10) for _ in range(rng.randrange(2, 10))], 10] for _ in range(12)]
    add("물류 상자 배치", [("weights", "int[]"), ("limit", "int")], "int",
        "한 번에 최대 두 상자를 운반할 수 있고 두 상자의 합계 무게는 `limit` 이하여야 합니다. 모든 상자를 운반하는 최소 운반 횟수를 반환하세요. 상자를 나눌 수 없고 빈 입력은 0입니다. 각 상자는 단독으로 운반 가능합니다.",
        "- 0 ≤ 상자 수 ≤ 100000\n- 1 ≤ weights[i] ≤ limit ≤ 1000000000",
        """ordered = sorted(weights)
left, right, trips = 0, len(ordered) - 1, 0
while left <= right:
    if left < right and ordered[left] + ordered[right] <= limit:
        left += 1
    right -= 1
    trips += 1
return trips""", boxes, minimum_trips)
    cables = [[[1], 1], [[1], 2], [[10], 3], [[802, 743, 457, 539], 11], [[10, 10], 2], [[10, 10], 3], [[1, 2, 3], 20], [[1000000000], 1], [[1000000000], 999999999]]
    cables += [[[rng.randint(1, 100) for _ in range(rng.randint(2, 7))], rng.randint(1, 100)] for _ in range(10)]
    add("랜선 자르기", [("lengths", "int[]"), ("k", "int")], "int",
        "각 랜선을 양의 정수 길이 L인 조각들로 잘라 총 `k`개 이상 만들려고 합니다. 남는 짧은 조각은 버려도 되고 다른 랜선끼리 이어 붙일 수 없습니다. 가능한 최대 L을 반환하고, 길이 1인 조각도 k개 만들 수 없으면 0을 반환하세요.",
        "- 1 ≤ 랜선 수 ≤ 100000\n- 1 ≤ lengths[i], k ≤ 1000000000\n- 조각 개수의 중간 합은 64비트로 계산하세요.",
        """left, right, answer = 1, max(lengths), 0
while left <= right:
    mid = (left + right) // 2
    if sum(value // mid for value in lengths) >= k:
        answer, left = mid, mid + 1
    else:
        right = mid - 1
return answer""", cables, cable_length)
    wood = [[[0], 0], [[0], 1], [[10], 0], [[10], 10], [[10], 11], [[20, 15, 10, 17], 7], [[1, 1, 1], 2], [[1000000000], 1], [[1000000000], 1000000000]]
    wood += [[[rng.randrange(31) for _ in range(rng.randint(2, 7))], rng.randrange(90)] for _ in range(10)]
    add("나무 자르기", [("heights", "int[]"), ("required", "int")], "int",
        "모든 나무를 동일한 정수 높이 H에서 자릅니다. 높이가 H보다 큰 나무에서 `높이-H`만큼 얻고, 나머지 나무에서는 얻지 못합니다. 총 `required` 이상을 얻는 최대 H를 0부터 가장 높은 나무 높이 사이에서 찾아 반환하세요. 불가능하면 -1입니다. required=0이면 가장 높은 나무의 높이입니다.",
        "- 1 ≤ 나무 수 ≤ 100000\n- 0 ≤ heights[i], required ≤ 1000000000\n- 얻은 길이의 합은 64비트로 계산하세요.",
        """left, right, answer = 0, max(heights), -1
while left <= right:
    mid = (left + right) // 2
    if sum(max(0, value - mid) for value in heights) >= required:
        answer, left = mid, mid + 1
    else:
        right = mid - 1
return answer""", wood, wood_height)
    houses = [[[1, 2, 8, 4, 9], 3], [[0, 1], 2], [[0, 100], 2], [[2, 4, 6], 3], [[9, 0, 5], 2], [[0, 1, 2, 3], 3]]
    houses += [[rng.sample(range(50), n), rng.randint(2, n)] for n in [2, 3, 4, 5, 6, 7, 8, 5, 6, 7, 8]]
    add("공유기 설치", [("houses", "int[]"), ("c", "int")], "int",
        "직선 위의 서로 다른 집 좌표 중 c개에 공유기를 하나씩 설치합니다. 설치한 공유기 두 개 사이 거리의 최솟값을 최대화하여 그 거리를 반환하세요. 입력 좌표는 정렬되어 있지 않을 수 있습니다.",
        "- 2 ≤ c ≤ 집 수 ≤ 100000\n- 0 ≤ houses[i] ≤ 1000000000\n- 모든 좌표는 서로 다릅니다.",
        """ordered = sorted(houses)
left, right, answer = 0, ordered[-1] - ordered[0], 0
while left <= right:
    distance = (left + right) // 2
    installed, last = 1, ordered[0]
    for value in ordered[1:]:
        if value - last >= distance:
            installed += 1
            last = value
    if installed >= c:
        answer, left = distance, distance + 1
    else:
        right = distance - 1
return answer""", houses, router_distance)
    budgets = [[[120, 110, 140, 150], 485], [[0], 0], [[10], 0], [[10], 10], [[10], 100], [[10, 10], 19], [[0, 10, 20], 15]]
    budgets += [[[rng.randrange(50) for _ in range(rng.randint(1, 8))], rng.randrange(200)] for _ in range(12)]
    add("예산 배분", [("requests", "int[]"), ("budget", "int")], "int",
        "정수 상한액 H를 정하면 각 요청에는 `min(요청액,H)`를 배정합니다. 총 배정액이 budget을 넘지 않는 최대 H를 반환하세요. H의 범위는 0부터 최대 요청액까지이며, 모든 요청을 충족할 수 있으면 최대 요청액을 반환합니다. 모든 요청액이 0이면 0입니다.",
        "- 1 ≤ 요청 수 ≤ 100000\n- 0 ≤ requests[i] ≤ 1000000000\n- 0 ≤ budget ≤ 2147483647\n- 배정액의 합은 64비트로 계산하세요.",
        """left, right, answer = 0, max(requests), 0
while left <= right:
    cap = (left + right) // 2
    if sum(min(value, cap) for value in requests) <= budget:
        answer, left = cap, cap + 1
    else:
        right = cap - 1
return answer""", budgets, lambda a, b: max(cap for cap in range(max(a) + 1) if sum(min(v, cap) for v in a) <= b))
    cards = [[], [0], [1], [10, 20, 40], [1, 1, 1, 1], [0, 0, 5], [1, 2, 3, 4, 5]]
    cards += [[rng.randrange(11) for _ in range(rng.randint(2, 7))] for _ in range(10)]
    add("숫자 카드 합치기", [("cards", "int[]")], "int",
        "각 원소는 카드 묶음의 크기입니다. 두 묶음을 합칠 때 두 크기의 합만큼 비용이 들고 그 합 크기의 새 묶음이 생깁니다. 묶음이 하나가 될 때까지 합치는 최소 총 비용을 반환하세요. 빈 입력이나 한 묶음의 비용은 0이고 크기가 0인 묶음도 별도 묶음입니다.",
        "- 0 ≤ 묶음 수 ≤ 100000\n- 0 ≤ cards[i] ≤ 1000",
        """import heapq
queue = cards[:]
heapq.heapify(queue)
total = 0
while len(queue) >= 2:
    merged = heapq.heappop(queue) + heapq.heappop(queue)
    total += merged
    heapq.heappush(queue, merged)
return total""", [[a] for a in cards], merge_cost)
    jobs = [[], [0], [1], [3, 1, 2], [5, 5], [0, 0, 5], [7, 2, 4, 1]]
    jobs += [[rng.randrange(11) for _ in range(rng.randint(2, 7))] for _ in range(10)]
    add("작업 스케줄러", [("durations", "int[]")], "float",
        "모든 작업은 시각 0에 도착하고 단일 처리기는 한 번에 하나만, 중간 중단 없이 처리합니다. 작업 순서를 자유롭게 정해 평균 완료 시간을 최소화한 값을 실수로 반환하세요. 한 작업의 완료 시간은 그 작업과 그 앞에 처리한 작업들의 소요 시간 합입니다. 여기서 완료 대기 시간에는 자기 처리 시간도 포함합니다. 작업이 없으면 0.0입니다.",
        "- 0 ≤ 작업 수 ≤ 100000\n- 0 ≤ durations[i] ≤ 10000\n- 완료 시간 총합은 64비트로 계산하고 평균에서 실수로 나누세요.",
        """elapsed = total = 0
for duration in sorted(durations):
    elapsed += duration
    total += elapsed
return total / len(durations) if durations else 0.0""", [[a] for a in jobs], average_completion)
    grids = [[[0]], [[0, 0, 0, 0]], [[0, 1, 0]], [[0], [0], [0], [0]], [[0, 0], [0, 0]], [[0, 1], [1, 0]], [[0, 0, 0], [0, 1, 0], [0, 0, 0]]]
    grids += [[[int(rng.random() < .3) for _ in range(4)] for _ in range(3)] for _ in range(10)]
    robot_cases, maze_cases = [], []
    for i, original in enumerate(grids):
        grid = [row[:] for row in original]
        grid[0][0] = grid[-1][-1] = 0
        robot_cases.append([grid, [0, 0, i % 4], [len(grid) - 1, len(grid[0]) - 1, (i + 1) % 4]])
        maze_cases.append([grid, [0, 0], [len(grid) - 1, len(grid[0]) - 1]])
    robot_cases += [[[[0]], [0, 0, 0], [0, 0, 0]], [[[0, 0, 0, 0]], [0, 0, 1], [0, 3, 1]]]
    add("로봇 이동 4", [("grid", "int[][]"), ("start", "int[]"), ("target", "int[]")], "int",
        "0이 빈칸이고 1이 장애물인 직사각형 격자입니다. start와 target은 `[행,열,방향]`이며 방향은 0:북, 1:동, 2:남, 3:서입니다. 한 명령은 제자리 좌우 90도 회전 또는 바라보는 방향으로 1~3칸 전진입니다. 전진의 중간 칸도 모두 격자 내부의 빈칸이어야 하고 장애물을 뛰어넘을 수 없습니다. 목표의 위치와 방향을 모두 맞추는 최소 명령 수를 반환하고 불가능하면 -1입니다. 시작 상태와 목표가 같으면 0입니다.",
        "- 1 ≤ 행·열 수 ≤ 100\n- start/target의 길이는 3이고 위치는 빈칸, 방향은 0~3",
        """from collections import deque
steps = [(-1, 0), (0, 1), (1, 0), (0, -1)]
source, goal = tuple(start), tuple(target)
queue = deque([(source, 0)])
seen = {source}
while queue:
    state, count = queue.popleft()
    if state == goal:
        return count
    row, col, direction = state
    candidates = [(row, col, (direction + 1) % 4), (row, col, (direction + 3) % 4)]
    dr, dc = steps[direction]
    for distance in range(1, 4):
        nr, nc = row + dr * distance, col + dc * distance
        if not (0 <= nr < len(grid) and 0 <= nc < len(grid[0])) or grid[nr][nc] == 1:
            break
        candidates.append((nr, nc, direction))
    for nxt in candidates:
        if nxt not in seen:
            seen.add(nxt)
            queue.append((nxt, count + 1))
return -1""", robot_cases, robot_distance)
    add("미로 최단 경로 2", [("grid", "int[][]"), ("start", "int[]"), ("target", "int[]")], "int",
        "0이 통로, 1이 벽인 직사각형 격자에서 start부터 target까지 상하좌우 한 칸씩 이동합니다. 이동 중 벽을 최대 한 개 부수고 그 칸으로 들어갈 수 있습니다. 시작점과 목표는 통로입니다. 최소 이동 횟수를 반환하고 불가능하면 -1입니다. 시작 칸은 이동 횟수에 세지 않으며 start=target이면 0입니다.",
        "- 1 ≤ 행·열 수 ≤ 300\n- start/target은 유효한 [행,열] 통로 좌표",
        """from collections import deque
queue = deque([(start[0], start[1], 0, 0)])
seen = {(start[0], start[1], 0)}
while queue:
    row, col, broken, distance = queue.popleft()
    if [row, col] == target:
        return distance
    for dr, dc in [(-1, 0), (0, 1), (1, 0), (0, -1)]:
        nr, nc = row + dr, col + dc
        if 0 <= nr < len(grid) and 0 <= nc < len(grid[0]):
            used = broken + grid[nr][nc]
            state = (nr, nc, used)
            if used <= 1 and state not in seen:
                seen.add(state)
                queue.append((nr, nc, used, distance + 1))
return -1""", maze_cases, broken_maze)
    labs = [[[0, 0, 0]], [[2, 0, 0, 0]], [[0, 0], [0, 0]], [[2, 0, 0], [0, 1, 0], [0, 0, 0]], [[2, 0, 1], [0, 0, 0]], [[2, 1, 0, 0], [0, 1, 0, 0]], [[0, 0, 0], [0, 2, 0], [0, 0, 0]]]
    while len(labs) < 19:
        grid = [[rng.choices([0, 1, 2], [7, 2, 1])[0] for _ in range(4)] for _ in range(3)]
        if sum(row.count(0) for row in grid) >= 3:
            labs.append(grid)
    add("연구소 안전 구역", [("grid", "int[][]")], "int",
        "0은 빈칸, 1은 기존 벽, 2는 바이러스인 직사각형 연구소입니다. 서로 다른 빈칸 정확히 3개를 골라 새 벽을 만드세요. 그 후 바이러스는 상하좌우로 연결된 빈칸에 더 이상 퍼질 곳이 없을 때까지 전파됩니다. 끝까지 감염되지 않고 벽도 아닌 빈칸 수의 최댓값을 반환하세요. 바이러스가 하나도 없어도 벽 3개를 반드시 추가합니다.",
        "- 1 ≤ 행·열 수 ≤ 8\n- 빈칸은 최소 3개이고 바이러스 수는 0 이상입니다.\n- 새 벽·기존 벽·바이러스 칸은 안전 구역에 포함하지 않습니다.",
        """from collections import deque
from itertools import combinations
empty = [(r, c) for r, row in enumerate(grid) for c, value in enumerate(row) if value == 0]
virus = [(r, c) for r, row in enumerate(grid) for c, value in enumerate(row) if value == 2]
if not virus:
    return len(empty) - 3
best = 0
for walls in combinations(empty, 3):
    blocked = set(walls)
    seen = set(virus)
    queue = deque(virus)
    infected = 0
    while queue:
        row, col = queue.popleft()
        for dr, dc in [(-1, 0), (0, 1), (1, 0), (0, -1)]:
            nr, nc = row + dr, col + dc
            if 0 <= nr < len(grid) and 0 <= nc < len(grid[0]) and grid[nr][nc] == 0 and (nr, nc) not in blocked and (nr, nc) not in seen:
                seen.add((nr, nc))
                queue.append((nr, nc))
                infected += 1
    best = max(best, len(empty) - 3 - infected)
return best""", [[grid] for grid in labs], safe_area)
