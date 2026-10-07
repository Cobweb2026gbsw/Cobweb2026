import java.util.Scanner

// 제출 파일: fun sol(matrix: Array<IntArray>, target: Pair<Int, Int>): Boolean
fun main() {
    val input = Scanner(System.`in`)
    val matrix = Array(15) { IntArray(15) { input.nextInt() } }
    val target = Pair(input.nextInt(), input.nextInt())
    println(sol(matrix, target))
}
