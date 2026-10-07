#include <stdbool.h>
#include <stdio.h>

/* 제출 파일: bool sol(const int matrix[15][15], int target_row, int target_col); */
extern bool sol(const int matrix[15][15], int target_row, int target_col);

int main(void) {
    int matrix[15][15], row, col;
    for (int r = 0; r < 15; r++)
        for (int c = 0; c < 15; c++)
            if (scanf("%d", &matrix[r][c]) != 1) return 1;
    if (scanf("%d %d", &row, &col) != 2) return 1;
    puts(sol(matrix, row, col) ? "true" : "false");
    return 0;
}
