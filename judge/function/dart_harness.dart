// 제출 main.dart: bool sol(List<List<int>> matrix, List<int> target) { ... }
import 'dart:io';
import 'file:///source/main.dart' as submission;

void main() {
  final values = stdin.readLineSync()!.trim().split(RegExp(r'\s+')).map(int.parse).toList();
  if (values.length != 227) {
    throw FormatException('Invalid FUNCTION input');
  }
  final matrix = List.generate(15, (row) => values.sublist(row * 15, row * 15 + 15));
  final target = values.sublist(225);
  final bool result = submission.sol(matrix, target);
  stdout.writeln(result);
}
