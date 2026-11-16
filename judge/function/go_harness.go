package main

import "fmt"

// 제출 파일: func sol(matrix [][]int, target [2]int) bool
func main() {
    matrix := make([][]int, 15)
    for r := range matrix {
        matrix[r] = make([]int, 15)
        for c := range matrix[r] {
            if _, err := fmt.Scan(&matrix[r][c]); err != nil { panic(err) }
        }
    }
    var target [2]int
    if _, err := fmt.Scan(&target[0], &target[1]); err != nil { panic(err) }
    fmt.Println(sol(matrix, target))
}
