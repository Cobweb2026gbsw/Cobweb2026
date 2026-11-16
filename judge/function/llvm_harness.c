#include <stdbool.h>
#include <stdio.h>

/* 제출 IR: define i1 @sol(ptr %matrix, i32 %target_row, i32 %target_col) */
extern bool sol(const int *matrix, int target_row, int target_col);

int main(void) {
    int matrix[225], row, col;
    for (int i = 0; i < 225; i++) if (scanf("%d", &matrix[i]) != 1) return 1;
    if (scanf("%d %d", &row, &col) != 2) return 1;
    puts(sol(matrix, row, col) ? "true" : "false");
    return 0;
}
