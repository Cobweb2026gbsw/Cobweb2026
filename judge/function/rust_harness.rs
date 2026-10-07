// 제출 파일: fn sol(matrix: Vec<Vec<i32>>, target: (usize, usize)) -> bool
include!("/source/main.rs");
fn main() {
    use std::io::Read;
    let mut input = String::new();
    std::io::stdin().read_to_string(&mut input).unwrap();
    let mut values = input.split_whitespace().map(|v| v.parse::<i32>().unwrap());
    let matrix: Vec<Vec<i32>> = (0..15).map(|_| (0..15).map(|_| values.next().unwrap()).collect()).collect();
    let target = (values.next().unwrap() as usize, values.next().unwrap() as usize);
    println!("{}", sol(matrix, target));
}
