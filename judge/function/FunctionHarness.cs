// 제출 파일: public static class Solution { public static bool Sol(int[][] matrix, int[] target) { ... } }
var values = Console.In.ReadToEnd().Split((char[]?)null, StringSplitOptions.RemoveEmptyEntries)
    .Select(int.Parse).ToArray();
if (values.Length != 227) throw new ArgumentException("Invalid FUNCTION input");
var matrix = Enumerable.Range(0, 15).Select(r => values.Skip(r * 15).Take(15).ToArray()).ToArray();
Console.WriteLine(Solution.Sol(matrix, values.Skip(225).Take(2).ToArray()) ? "true" : "false");
