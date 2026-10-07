#include <iostream>
#include <utility>
#include <vector>

// 제출 파일: bool sol(const std::vector<std::vector<int>>&, std::pair<int,int>);
extern bool sol(const std::vector<std::vector<int>>& matrix, std::pair<int,int> target);

int main() {
    std::vector<std::vector<int>> matrix(15, std::vector<int>(15));
    int row, col;
    for (auto& line : matrix)
        for (int& value : line)
            if (!(std::cin >> value)) return 1;
    if (!(std::cin >> row >> col)) return 1;
    std::cout << (sol(matrix, {row, col}) ? "true" : "false") << '\n';
}
